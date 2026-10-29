package com.twtrubiks.treesay

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 手機上的日記本：網頁版 `main.py` 每一支 API 背後的規則，搬到這裡變成一個個方法。
 *
 * 跟網頁版不同、為了日後同步現在就要守的事（見 android/CLAUDE.md）：
 * - 主鍵用 UUID，兩台裝置各自編號才不會撞
 * - 收回訊息、放下記憶都只蓋 `deleted_at`，留一筆墓碑——同步時另一邊的那筆才不會被同步回來。
 *   墓碑不留內容：使用者收回、放下的話，就不該還留在手機裡
 * - 時間存當地時間＋時差（`2026-10-28T21:05:00+08:00`）。今天是哪天照人在的地方的凌晨 4:00 切；
 *   訊息顯示說出口那一刻當地的時間；種樹逾期照實際經過的時間算
 *
 * [prepareImage] 把一張照片縮好、轉正，送不出去回 null（手機上是 [PhotoPrep.prepare]）。
 * [now] 每次都重新問系統時區，人換了時區就跟著換。
 */
class Diary(
    private val sql: Sql,
    private val photosDir: File,
    private val ai: TreeAi,
    private val prepareImage: (ByteArray) -> AiImage?,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    init {
        migrate()
    }

    // ---------- 時間 ----------

    private fun clock(): OffsetDateTime = now().truncatedTo(ChronoUnit.SECONDS)

    private fun today(at: OffsetDateTime): LocalDate = TreeDay.effectiveDate(at.toLocalDateTime())

    private fun stamp(time: OffsetDateTime): String = time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun parseTime(text: String): OffsetDateTime = OffsetDateTime.parse(text)

    // ---------- 今日 ----------

    fun today(): TodayView {
        val date = today(clock())
        return TodayView(day(date) ?: emptyDay(date), backfillCandidate(date))
    }

    /** 丟一則訊息給樹。內容存之前先 trim；空白又沒照片、或今天已經種下／正在長，都送不出去。 */
    fun send(content: String, photo: NewPhoto? = null): MessageView {
        val text = content.trim()
        Messages.sendRefusal(text, photo != null, null)?.let { throw TreeSayException(it) }
        val time = clock()
        val date = today(time)
        return sql.transaction {
            val day = getOrCreateDay(date)
            Messages.sendRefusal(text, photo != null, status(day))?.let { throw TreeSayException(it) }
            val photoPath = photo?.let { savePhoto(date, it) }
            val id = newId()
            sql.execute(
                "INSERT INTO messages (id, day_id, content, photo_path, created_at) VALUES (?, ?, ?, ?, ?)",
                id, day.string("id"), text, photoPath, stamp(time),
            )
            MessageView(id, text, photoPath, time)
        }
    }

    /** 改一則說錯的話。只動文字，照片要換請收回重傳。不記錄改過的痕跡，時間留在說出口的那一刻。 */
    fun edit(id: String, content: String): MessageView = sql.transaction {
        val message = editableMessage(id, "修改")
        val text = content.trim()
        Messages.editRefusal(text, message.photo != null)?.let { throw TreeSayException(it) }
        sql.execute("UPDATE messages SET content = ? WHERE id = ?", text, id)
        message.copy(content = text)
    }

    /** 收回一則話：蓋上 deleted_at 留作墓碑，內容與照片都不留。 */
    fun retract(id: String) {
        sql.transaction {
            val message = editableMessage(id, "收回")
            sql.execute(
                "UPDATE messages SET deleted_at = ?, content = '', photo_path = NULL WHERE id = ?",
                stamp(clock()), id,
            )
            message.photo?.let { photoFile(it)?.delete() }
        }
    }

    /** 照片在手機裡的位置。路徑跑出 photos 資料夾的（例如匯入檔裡寫了 `../`）一律不認。 */
    fun photoFile(path: String): File? {
        val root = photosDir.canonicalFile
        val file = File(root, path).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    // ---------- 森林 ----------

    /** 那個月種下的樹，加上留了話卻沒收尾的日子——後者只是給一個回得去的入口，不是標記「未完成」。 */
    fun month(month: YearMonth): List<ForestDay> = sql.query(
        "SELECT date, emotion, status FROM days " +
            "WHERE date >= ? AND date < ? AND (status = 'planted' OR $HAS_LIVE_MESSAGES) ORDER BY date",
        month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString(),
    ).map {
        val status = status(it)
        ForestDay(
            LocalDate.parse(it.string("date")),
            if (status == DayStatus.PLANTED) Emotion.normalize(it.stringOrNull("emotion")) else null,
            status,
        )
    }

    /** 某一天的全貌；連一筆紀錄都沒有的日子回 null（「這一天還是安靜的土壤」）。 */
    fun day(date: LocalDate): DayView? {
        val row = dayRow(date) ?: return null
        val messages = liveMessages(row.string("id"))
        val status = status(row)
        val planted = status == DayStatus.PLANTED
        return DayView(
            date = date,
            status = status,
            question = row.stringOrNull("question") ?: Questions.questionFor(date),
            diary = row.stringOrNull("diary_text"),
            emotion = if (planted) Emotion.normalize(row.stringOrNull("emotion")) else null,
            treeReply = row.stringOrNull("tree_reply"),
            keywords = row.stringOrNull("keywords_json")?.let(::stringList).orEmpty(),
            plantedAt = row.stringOrNull("planted_at")?.let(::parseTime),
            messages = messages,
            canPlant = Planting.canPlant(date, dayState(row, messages.isNotEmpty()), today(clock())),
        )
    }

    // ---------- 種樹 ----------

    /**
     * 種下某一天的樹。是今天就是正常結算，是過去某天就是補種。會呼叫 Claude，不要在主執行緒呼叫。
     *
     * 先在一個 transaction 裡檢查並上鎖（status = planting），再呼叫 AI；失敗、被取消都把鎖解開，
     * 那天回到收集中，可以再按一次。行程被系統整個殺掉的話，鎖 5 分鐘後逾期，一樣能重按。
     */
    suspend fun plant(date: LocalDate): DayView {
        val started = clock()
        val (dayId, daysAgo) = sql.transaction {
            val row = dayRow(date)
            val state = row?.let { dayState(it, hasLiveMessages(it.string("id"))) }
            when (val verdict = Planting.check(date, state, started)) {
                is PlantVerdict.Refused -> throw TreeSayException(verdict.message)
                is PlantVerdict.Go -> {
                    val id = requireNotNull(row).string("id")
                    sql.execute(
                        "UPDATE days SET status = 'planting', planting_started_at = ? WHERE id = ?",
                        stamp(started), id,
                    )
                    id to verdict.daysAgo
                }
            }
        }
        try {
            val row = requireNotNull(dayRow(date))
            val messages = liveMessages(dayId)

            // 照片直接附進請求，prompt 裡只用編號指涉。送不出去的只標記樹沒看到，種樹照常
            val prepared = Photos.fitBudget(messages.mapNotNull { it.photo }.map(::loadPhoto)).iterator()
            val images = mutableListOf<AiImage>()
            val promptMessages = messages.map { m ->
                val time = m.createdAt.toLocalTime().format(HOUR_MINUTE)
                if (m.photo == null) {
                    PromptMessage(time, m.content)
                } else {
                    val image = prepared.next()
                    if (image == null) {
                        PromptMessage(time, m.content, photoUnseen = true)
                    } else {
                        images += image
                        PromptMessage(time, m.content, photo = images.size)
                    }
                }
            }

            // 樹帶著記憶傾聽。補種時近期日記以「那一天」為基準往回看，不然樹會引用未來
            val memories = liveMemoryContents()
            val prompt = Prompts.plantPrompt(
                promptMessages,
                // 用蓋章存下的那題，不重算——題庫變動後重算會對不上當天真正問過的
                question = row.stringOrNull("question") ?: Questions.questionFor(date),
                daysAgo = daysAgo,
                memories = memories.takeLast(MEMORY_PROMPT_LIMIT),
                recentDiaries = Planting.recentDiaries(date, plantedDiaries(date.minusDays(Planting.RECENT_DIARY_DAYS), date)),
            )
            val data = ai.ask(prompt, Prompts.PLANT_SCHEMA, images, Purpose.PLANT)

            val diary = data.text("diary")
            val reply = data.text("tree_reply")
            if (diary.isEmpty() || reply.isEmpty()) throw AiError("日記或樹的回覆是空的")

            sql.transaction {
                sql.execute(
                    "UPDATE days SET diary_text = ?, emotion = ?, tree_reply = ?, keywords_json = ?, " +
                        "status = 'planted', planted_at = ? WHERE id = ?",
                    diary,
                    Emotion.normalize(data["emotion"]?.textValue()).key,
                    reply,
                    // 順手留下的回望線索：欄位壞掉就當沒有，不拖垮種樹
                    json(AiOutput.normalizeKeywords(data["keywords"].plain(), PLANT_KEYWORD_LIMIT)),
                    stamp(clock()),
                    dayId,
                )
                // 順手記下值得長期記住的事（搭種樹的便車，不另外呼叫 AI）。記憶欄位壞掉就當沒有
                val existing = memories.toSet()
                for (text in AiOutput.normalizeMemories(data["memory"].plain(), PLANT_MEMORY_LIMIT)) {
                    if (text !in existing) addMemory(text, date, clock())
                }
            }
        } catch (e: Throwable) {
            sql.execute("UPDATE days SET status = 'collecting', planting_started_at = NULL WHERE id = ?", dayId)
            throw if (e is AiError) aiFailure(e) else e
        }
        return requireNotNull(day(date))
    }

    private fun loadPhoto(path: String): AiImage? = try {
        photoFile(path)?.readBytes()?.let(prepareImage)
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        // 解不開的圖丟的例外五花八門，對這裡都是同一件事：這張送不出去
        null
    }

    // ---------- 樹記得的事 ----------

    /** 樹記得的事，最新記下的在前。看不見的記憶比沒有記憶更可怕。 */
    fun memories(): List<MemoryView> =
        sql.query("SELECT id, content, source_date FROM memories WHERE deleted_at IS NULL ORDER BY rowid DESC").map {
            MemoryView(it.string("id"), it.string("content"), LocalDate.parse(it.string("source_date")))
        }

    /** 讓樹放下一件事。刪了就是刪了：之後的種樹與週報都看不到它，墓碑也不留內容。 */
    fun letGo(id: String) {
        sql.transaction {
            if (sql.query("SELECT id FROM memories WHERE id = ? AND deleted_at IS NULL", id).isEmpty()) {
                throw TreeSayException("樹已經放下這件事了", TreeSayException.Kind.NOT_FOUND)
            }
            sql.execute("UPDATE memories SET deleted_at = ?, content = '' WHERE id = ?", stamp(clock()), id)
        }
    }

    // ---------- 週報 ----------

    /**
     * 某一週的週報。週一到週日，只生成已經結束的週並永久快取；沒種樹的週不呼叫 AI。
     * 生成時順手整理記憶（合併重複、放下過時的）——記憶清單只在這裡瘦身。
     */
    suspend fun report(weekStart: LocalDate): WeeklyResult {
        if (!Weekly.isWeekStart(weekStart)) throw TreeSayException("週報從週一開始")
        if (!Weekly.isOver(weekStart, clock().toLocalDateTime())) return WeeklyResult.Growing
        cachedReport(weekStart)?.let { return it }

        val week = Weekly.dates(weekStart)
        val diaries = plantedDiaries(week.start, week.endInclusive.plusDays(1))
        if (diaries.isEmpty()) return WeeklyResult.Empty

        val memories = sql.query("SELECT content FROM memories WHERE deleted_at IS NULL ORDER BY rowid")
            .map { it.string("content") }
        val data = try {
            ai.ask(
                Prompts.weeklyPrompt(diaries, memories),
                Prompts.weeklySchema(withMemories = memories.isNotEmpty()),
                emptyList(),
                Purpose.WEEKLY,
            )
        } catch (e: AiError) {
            throw aiFailure(e)
        }
        val report = WeeklyReport(
            goodThings = data["good_things"].texts(),
            badThings = data["bad_things"].texts(),
            keywords = data["keywords"].texts(),
            advice = data.text("advice"),
        )
        val generatedAt = clock()
        return sql.transaction {
            // 兩邊同時生成同一週（例如連點）時，先寫進去的贏，後來的拿快取
            cachedReport(weekStart)?.let { return@transaction it }
            // AI 只拿得到現有清單，使用者放下的事不會復活。整理完是空的視為可疑，不套用
            if (memories.isNotEmpty()) {
                val curation = Weekly.curateMemories(memories, data["memories"].plain())
                for (text in curation.added) addMemory(text, today(generatedAt), generatedAt)
                for (text in curation.removed) {
                    sql.execute(
                        "UPDATE memories SET deleted_at = ?, content = '' WHERE content = ? AND deleted_at IS NULL",
                        stamp(generatedAt), text,
                    )
                }
            }
            sql.execute(
                "INSERT INTO weekly_reports (id, week_start, content_json, created_at) VALUES (?, ?, ?, ?)",
                newId(), weekStart.toString(), reportJson(report).toString(), stamp(generatedAt),
            )
            WeeklyResult.Ready(report, generatedAt)
        }
    }

    private fun cachedReport(weekStart: LocalDate): WeeklyResult.Ready? =
        sql.query("SELECT content_json, created_at FROM weekly_reports WHERE week_start = ?", weekStart.toString())
            .firstOrNull()
            ?.let { row ->
                val data = mapper.readTree(row.string("content_json"))
                WeeklyResult.Ready(
                    WeeklyReport(
                        data["good_things"].texts(),
                        data["bad_things"].texts(),
                        data["keywords"].texts(),
                        data.text("advice"),
                    ),
                    parseTime(row.string("created_at")),
                )
            }

    private fun reportJson(report: WeeklyReport): ObjectNode = mapper.createObjectNode().apply {
        set<JsonNode>("good_things", array(report.goodThings))
        set<JsonNode>("bad_things", array(report.badThings))
        set<JsonNode>("keywords", array(report.keywords))
        put("advice", report.advice)
    }

    // ---------- AI 費用 ----------

    /**
     * 記下一個 API 回應的用量，費用在記下的當下依價目表算好——日後調價不改寫舊帳。
     * 刻意不連到哪一天的日記。
     */
    fun recordUsage(usage: AiUsage) {
        sql.execute(
            "INSERT INTO api_usage (id, created_at, purpose, model, input_tokens, output_tokens, cost_usd) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            newId(), stamp(clock()), usage.purpose.key, usage.model, usage.inputTokens, usage.outputTokens,
            AiProfiles.estimateCost(usage.model, usage.inputTokens, usage.outputTokens),
        )
    }

    /** 按月加總，最近的月份在前。月份照記帳那一刻當地的日曆。 */
    fun usage(): UsageSummary {
        val months = sql.query("SELECT created_at, purpose, cost_usd FROM api_usage")
            .groupBy { YearMonth.from(parseTime(it.string("created_at")).toLocalDate()) }
            .toSortedMap(compareByDescending { it })
            .map { (month, rows) ->
                val priced = rows.filter { it.doubleOrNull("cost_usd") != null }
                fun sum(purpose: Purpose?) = priced
                    .filter { purpose == null || it.string("purpose") == purpose.key }
                    .sumOf { it.doubleOrNull("cost_usd")!! }
                UsageMonth(month, sum(null), sum(Purpose.PLANT), sum(Purpose.WEEKLY), rows.size - priced.size)
            }
        return UsageSummary(months, months.sumOf { it.totalUsd })
    }

    // ---------- 匯出、匯入 ----------

    /**
     * 完整匯出成網頁版 `/api/export` 的格式：手機匯出、電腦匯入（只加不蓋），就是同步做出來前的手動同步。
     * 照片不在這份 JSON 裡，只記檔名。收回的訊息、放下的記憶不匯出。
     */
    fun export(): String {
        val root = mapper.createObjectNode()
        root.put("exported_at", stamp(clock()))
        root.put("data_dir", "Android 版樹說（App 的私有儲存空間）")
        val days = root.putArray("days")
        for (row in sql.query("SELECT * FROM days ORDER BY date")) {
            days.addObject().apply {
                put("date", row.string("date"))
                put("status", row.string("status"))
                put("question", row.stringOrNull("question"))
                put("diary", row.stringOrNull("diary_text"))
                put("emotion", row.stringOrNull("emotion"))
                put("tree_reply", row.stringOrNull("tree_reply"))
                set<JsonNode>("keywords", array(row.stringOrNull("keywords_json")?.let(::stringList).orEmpty()))
                put("planted_at", row.stringOrNull("planted_at"))
                val messages = putArray("messages")
                for (m in liveMessages(row.string("id"))) {
                    messages.addObject().put("content", m.content).put("photo", m.photo).put("created_at", stamp(m.createdAt))
                }
            }
        }
        val reports = root.putArray("weekly_reports")
        for (row in sql.query("SELECT week_start, content_json, created_at FROM weekly_reports ORDER BY week_start")) {
            reports.addObject().apply {
                put("week_start", row.string("week_start"))
                set<JsonNode>("report", mapper.readTree(row.string("content_json")))
                put("created_at", row.string("created_at"))
            }
        }
        val memories = root.putArray("memories")
        for (row in sql.query(
            "SELECT id, content, source_date, created_at FROM memories WHERE deleted_at IS NULL ORDER BY rowid",
        )) {
            memories.addObject()
                .put("id", row.string("id"))
                .put("content", row.string("content"))
                .put("source_date", row.string("source_date"))
                .put("created_at", row.string("created_at"))
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
    }

    /**
     * 把匯出的 JSON（手機或網頁版的都行）匯回來。只加不蓋：手機上已經有的那一天、那一週、同一條記憶
     * 都跳過，日記不該因為一次匯入默默少掉。寫入前先把整個資料庫備份一份放在旁邊。
     * 照片不在 JSON 裡，檔名照存，只回報缺幾張。
     */
    fun import(json: String): ImportResult {
        val payload = try {
            ImportPayload.parse(mapper.readTree(json), now().offset)
        } catch (e: Exception) {
            throw TreeSayException(IMPORT_UNREADABLE)
        }
        val backup = sql.backup(".bak-" + clock().format(BACKUP_STAMP))
        val time = clock()
        return sql.transaction {
            val dates = sql.query("SELECT date FROM days").map { it.string("date") }.toMutableSet()
            val weeks = sql.query("SELECT week_start FROM weekly_reports").map { it.string("week_start") }.toMutableSet()
            val contents = liveMemoryContents().toMutableSet()
            var daysAdded = 0
            var reportsAdded = 0
            var memoriesAdded = 0
            var photosMissing = 0

            for (d in payload.days) {
                if (!dates.add(d.date.toString())) continue
                // 匯出當下卡在 planting 的那天，進來當作還在收集；種好了才算種好
                val planted = d.status == "planted" && !d.diary.isNullOrEmpty() && !d.treeReply.isNullOrEmpty()
                val dayId = newId()
                sql.execute(
                    "INSERT INTO days (id, date, status, question, diary_text, emotion, tree_reply, keywords_json, planted_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    dayId,
                    d.date.toString(),
                    if (planted) "planted" else "collecting",
                    // 匯出沒帶問題的舊資料，照 Day 誕生時的規則蓋章
                    d.question ?: Questions.questionFor(d.date),
                    if (planted) d.diary else null,
                    if (planted) Emotion.normalize(d.emotion).key else null,
                    if (planted) d.treeReply else null,
                    if (planted) json(AiOutput.normalizeKeywords(d.keywords, PLANT_KEYWORD_LIMIT)) else null,
                    if (planted) d.plantedAt?.let(::stamp) else null,
                )
                for (m in d.messages) {
                    // 檔名照存不丟（丟了就永遠接不回來），但跑出 photos 資料夾的路徑不認
                    val photo = m.photo?.takeIf { photoFile(it) != null }
                    if (m.photo != null && photo?.let(::photoFile)?.isFile != true) photosMissing++
                    sql.execute(
                        "INSERT INTO messages (id, day_id, content, photo_path, created_at) VALUES (?, ?, ?, ?, ?)",
                        newId(), dayId, m.content, photo, stamp(m.createdAt),
                    )
                }
                daysAdded++
            }
            for (r in payload.reports) {
                if (!weeks.add(r.weekStart.toString())) continue
                sql.execute(
                    "INSERT INTO weekly_reports (id, week_start, content_json, created_at) VALUES (?, ?, ?, ?)",
                    newId(), r.weekStart.toString(), r.report.toString(), stamp(r.createdAt ?: time),
                )
                reportsAdded++
            }
            for (m in payload.memories) {
                if (!contents.add(m.content)) continue
                addMemory(m.content, m.sourceDate, m.createdAt ?: time)
                memoriesAdded++
            }
            ImportResult(
                backup = backup,
                daysAdded = daysAdded,
                daysSkipped = payload.days.size - daysAdded,
                reportsAdded = reportsAdded,
                reportsSkipped = payload.reports.size - reportsAdded,
                memoriesAdded = memoriesAdded,
                memoriesSkipped = payload.memories.size - memoriesAdded,
                photosMissing = photosMissing,
            )
        }
    }

    // ---------- 資料庫 ----------

    private fun migrate() {
        sql.transaction {
            for (statement in SCHEMA) sql.execute(statement)
        }
    }

    private fun dayRow(date: LocalDate): Row? = sql.query("SELECT * FROM days WHERE date = ?", date.toString()).firstOrNull()

    /** 這一天的那一列；還沒有的話建一列，並在誕生時把今日問題蓋章存下。 */
    private fun getOrCreateDay(date: LocalDate): Row = dayRow(date) ?: run {
        sql.execute(
            "INSERT INTO days (id, date, status, question) VALUES (?, ?, 'collecting', ?)",
            newId(), date.toString(), Questions.questionFor(date),
        )
        requireNotNull(dayRow(date))
    }

    private fun emptyDay(date: LocalDate) = DayView(
        date = date,
        status = DayStatus.COLLECTING,
        // 這一天還沒有任何紀錄，問題現算現看；第一則訊息進來、那一列誕生時才蓋章成事實
        question = Questions.questionFor(date),
        diary = null,
        emotion = null,
        treeReply = null,
        keywords = emptyList(),
        plantedAt = null,
        messages = emptyList(),
        canPlant = false,
    )

    private fun status(row: Row): DayStatus = when (row.string("status")) {
        "planted" -> DayStatus.PLANTED
        "planting" -> DayStatus.PLANTING
        else -> DayStatus.COLLECTING
    }

    private fun dayState(row: Row, hasMessages: Boolean) =
        DayState(status(row), hasMessages, row.stringOrNull("planting_started_at")?.let(::parseTime))

    private fun hasLiveMessages(dayId: String): Boolean =
        sql.query("SELECT 1 FROM messages WHERE day_id = ? AND deleted_at IS NULL LIMIT 1", dayId).isNotEmpty()

    private fun liveMessages(dayId: String): List<MessageView> = sql.query(
        "SELECT id, content, photo_path, created_at FROM messages WHERE day_id = ? AND deleted_at IS NULL ORDER BY rowid",
        dayId,
    ).map { MessageView(it.string("id"), it.string("content"), it.stringOrNull("photo_path"), parseTime(it.string("created_at"))) }

    private fun editableMessage(id: String, action: String): MessageView {
        val row = sql.query(
            "SELECT m.id, m.content, m.photo_path, m.created_at, d.date, d.status FROM messages m " +
                "JOIN days d ON d.id = m.day_id WHERE m.id = ? AND m.deleted_at IS NULL",
            id,
        ).firstOrNull() ?: throw TreeSayException("找不到這則訊息", TreeSayException.Kind.NOT_FOUND)
        if (!Messages.isEditable(LocalDate.parse(row.string("date")), status(row), today(clock()))) {
            throw TreeSayException("只有今天還沒種樹前的訊息可以$action")
        }
        return MessageView(row.string("id"), row.string("content"), row.stringOrNull("photo_path"), parseTime(row.string("created_at")))
    }

    private fun backfillCandidate(today: LocalDate): LocalDate? {
        val rows = sql.query(
            "SELECT * FROM days WHERE date >= ? AND date < ? AND status = 'collecting'",
            today.minusDays(Planting.BACKFILL_WINDOW_DAYS).toString(), today.toString(),
        )
        return Planting.backfillCandidate(
            today,
            rows.associate { LocalDate.parse(it.string("date")) to dayState(it, hasLiveMessages(it.string("id"))) },
        )
    }

    /** [from] 起（含）到 [until]（不含）之間種下的日記，由舊到新。 */
    private fun plantedDiaries(from: LocalDate, until: LocalDate): List<PlantedDiary> = sql.query(
        "SELECT date, emotion, diary_text FROM days WHERE status = 'planted' AND date >= ? AND date < ? ORDER BY date",
        from.toString(), until.toString(),
    ).map {
        PlantedDiary(LocalDate.parse(it.string("date")), Emotion.normalize(it.stringOrNull("emotion")), it.stringOrNull("diary_text").orEmpty())
    }

    private fun liveMemoryContents(): List<String> =
        sql.query("SELECT content FROM memories WHERE deleted_at IS NULL ORDER BY rowid").map { it.string("content") }

    private fun addMemory(content: String, sourceDate: LocalDate, createdAt: OffsetDateTime) {
        sql.execute(
            "INSERT INTO memories (id, content, source_date, created_at) VALUES (?, ?, ?, ?)",
            newId(), content, sourceDate.toString(), stamp(createdAt),
        )
    }

    private fun savePhoto(date: LocalDate, photo: NewPhoto): String {
        val dir = File(photosDir, date.toString()).apply { mkdirs() }
        val name = UUID.randomUUID().toString().replace("-", "") + photo.extension
        File(dir, name).writeBytes(photo.bytes)
        return "$date/$name"
    }

    // ---------- JSON ----------

    private fun json(values: List<String>): String = array(values).toString()

    private fun array(values: List<String>): ArrayNode = mapper.createArrayNode().apply { values.forEach(::add) }

    private fun stringList(text: String): List<String> = mapper.readTree(text).texts()

    companion object {
        const val TREE_ASLEEP = "樹睡著了，等等再來 🌙"
        const val KEY_MISSING = "樹還沒連上森林 🌱 給樹一把 Claude API key，它就能幫你把這一天收好"
        const val KEY_INVALID = "樹在等你開門 🌿 這把 API key 打不開門，換一把再試試"
        const val UNREACHABLE = "連不上森林，等網路順一點再試一次 🌙"
        const val IMPORT_UNREADABLE = "這份檔案樹讀不懂 🍂 請確認是樹說匯出的 JSON。"

        /** 種一次樹最多記下幾件事、注入 prompt 的記憶條數上限、最多留幾個關鍵詞，同網頁版 main.py。 */
        const val PLANT_MEMORY_LIMIT = 3
        const val MEMORY_PROMPT_LIMIT = 20
        const val PLANT_KEYWORD_LIMIT = 4

        private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")
        private val BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

        private const val HAS_LIVE_MESSAGES =
            "EXISTS (SELECT 1 FROM messages m WHERE m.day_id = days.id AND m.deleted_at IS NULL)"

        /** 只新增不刪除：某張表即使不再使用，裡面仍是使用者寫下的東西，不該被一次升級默默清掉。 */
        private val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS days (
                id TEXT PRIMARY KEY,
                date TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL DEFAULT 'collecting',
                question TEXT,
                keywords_json TEXT,
                diary_text TEXT,
                emotion TEXT,
                tree_reply TEXT,
                planted_at TEXT,
                planting_started_at TEXT
            )""",
            """CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                day_id TEXT NOT NULL REFERENCES days(id),
                content TEXT NOT NULL,
                photo_path TEXT,
                created_at TEXT NOT NULL,
                deleted_at TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS messages_day_id ON messages(day_id)",
            """CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                source_date TEXT NOT NULL,
                created_at TEXT NOT NULL,
                deleted_at TEXT
            )""",
            """CREATE TABLE IF NOT EXISTS weekly_reports (
                id TEXT PRIMARY KEY,
                week_start TEXT NOT NULL UNIQUE,
                content_json TEXT NOT NULL,
                created_at TEXT NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS api_usage (
                id TEXT PRIMARY KEY,
                created_at TEXT NOT NULL,
                purpose TEXT NOT NULL,
                model TEXT NOT NULL,
                input_tokens INTEGER NOT NULL,
                output_tokens INTEGER NOT NULL,
                cost_usd REAL
            )""",
        )

        /** 把 AI 失敗轉成使用者能行動的話：key 的問題要講清楚該修什麼，其餘維持療癒文案。 */
        fun aiFailure(e: AiError): TreeSayException = when (e) {
            is AiKeyMissing -> TreeSayException(KEY_MISSING, TreeSayException.Kind.KEY_MISSING)
            is AiKeyInvalid -> TreeSayException(KEY_INVALID, TreeSayException.Kind.KEY_INVALID)
            is AiUnreachable -> TreeSayException(UNREACHABLE, TreeSayException.Kind.ASLEEP)
            else -> TreeSayException(TREE_ASLEEP, TreeSayException.Kind.ASLEEP)
        }

        /** 跟 Python 的 `str(data.get(key) or "").strip()` 一樣寬鬆：沒有、null 都當空字串。 */
        private fun JsonNode.text(key: String): String =
            get(key)?.takeUnless { it.isNull || it.isContainerNode }?.asText()?.trim().orEmpty()

        /** `[str(x) for x in (data.get(key) or [])]`：不是陣列就當沒有。 */
        private fun JsonNode?.texts(): List<String> =
            if (this == null || !isArray) emptyList() else map { if (it.isTextual) it.asText() else it.toString() }

        /** JSON 轉成 Kotlin 的 List／String／數字，交給跟網頁版同一套的清洗規則（AiOutput）。 */
        private fun JsonNode?.plain(): Any? = when {
            this == null || isNull -> null
            isArray -> map { it.plain() }
            isTextual -> asText()
            isNumber -> numberValue()
            isBoolean -> booleanValue()
            else -> this
        }
    }
}

/** 匯入檔讀進來的樣子。欄位規則對齊網頁版 main.py 的 ImportPayload：多出來的欄位略過，缺必要欄位整份不收。 */
private class ImportPayload(
    val days: List<ImportDay>,
    val reports: List<ImportReport>,
    val memories: List<ImportMemory>,
) {
    class ImportDay(
        val date: LocalDate,
        val status: String,
        val question: String?,
        val diary: String?,
        val emotion: String?,
        val treeReply: String?,
        val keywords: List<Any?>,
        val plantedAt: OffsetDateTime?,
        val messages: List<ImportMessage>,
    )

    class ImportMessage(val content: String, val photo: String?, val createdAt: OffsetDateTime)

    class ImportReport(val weekStart: LocalDate, val report: ObjectNode, val createdAt: OffsetDateTime?)

    class ImportMemory(val content: String, val sourceDate: LocalDate, val createdAt: OffsetDateTime?)

    companion object {
        /** 讀不懂就丟例外。[offset] 給網頁版匯出的時間用：它沒帶時差，當成這支手機現在的時差。 */
        fun parse(root: JsonNode, offset: ZoneOffset): ImportPayload {
            require(root.isObject)

            fun list(node: JsonNode, key: String): List<JsonNode> {
                val value = node[key] ?: return emptyList()
                require(value.isArray)
                return value.toList()
            }

            fun required(node: JsonNode, key: String): String {
                val value = node[key]
                require(value != null && value.isTextual)
                return value.asText()
            }

            fun optional(node: JsonNode, key: String): String? {
                val value = node[key]
                if (value == null || value.isNull) return null
                require(value.isTextual)
                return value.asText()
            }

            fun time(text: String): OffsetDateTime = try {
                OffsetDateTime.parse(text)
            } catch (e: DateTimeParseException) {
                LocalDateTime.parse(text.replace(' ', 'T')).atOffset(offset)
            }

            val days = list(root, "days").map { d ->
                require(d.isObject)
                val keywords = d["keywords"]
                require(keywords == null || keywords.isArray)
                ImportDay(
                    date = LocalDate.parse(required(d, "date")),
                    status = optional(d, "status") ?: "collecting",
                    question = optional(d, "question"),
                    diary = optional(d, "diary"),
                    emotion = optional(d, "emotion"),
                    treeReply = optional(d, "tree_reply"),
                    // 非字串的條目原樣留著，交給 AiOutput 的清洗規則略過
                    keywords = keywords?.map { if (it.isTextual) it.asText() else it }.orEmpty(),
                    plantedAt = optional(d, "planted_at")?.let(::time),
                    messages = list(d, "messages").map { m ->
                        require(m.isObject)
                        ImportMessage(optional(m, "content").orEmpty(), optional(m, "photo"), time(required(m, "created_at")))
                    },
                )
            }
            val reports = list(root, "weekly_reports").map { r ->
                require(r.isObject)
                val report = r["report"]
                require(report is ObjectNode)
                ImportReport(LocalDate.parse(required(r, "week_start")), report, optional(r, "created_at")?.let(::time))
            }
            val memories = list(root, "memories").map { m ->
                require(m.isObject)
                ImportMemory(required(m, "content"), LocalDate.parse(required(m, "source_date")), optional(m, "created_at")?.let(::time))
            }
            return ImportPayload(days, reports, memories)
        }
    }
}
