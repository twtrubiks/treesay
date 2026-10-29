package com.twtrubiks.treesay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/** 照搬 test_api.py 的 TestDayCutover、TestMessages、TestForest、TestBackfillInvite、TestQuestionIsAFact。 */
class DiaryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t by lazy { TestDiary(tmp.root) }

    @After
    fun close() = t.close()

    // ---------- 切日 ----------

    @Test
    fun before4amBelongsToPreviousDay() {
        t.now = taipei(2026, 7, 25, 3, 59)
        t.send("熬夜寫完了")
        assertEquals(LocalDate.of(2026, 7, 24), t.diary.today().day.date)
        assertEquals(1, t.diary.day(LocalDate.of(2026, 7, 24))!!.messages.size)
    }

    @Test
    fun at4amBelongsToSameDay() {
        t.now = taipei(2026, 7, 25, 4, 0)
        t.send("早起")
        assertNotNull(t.diary.day(LocalDate.of(2026, 7, 25)))
        assertNull(t.diary.day(LocalDate.of(2026, 7, 24)))
    }

    // ---------- 訊息 ----------

    @Test
    fun quietStartHasTodaysQuestionButNoRow() {
        val today = t.diary.today()
        assertEquals(DayStatus.COLLECTING, today.day.status)
        assertEquals(Questions.questionFor(t.today), today.day.question)
        assertTrue(today.day.messages.isEmpty())
        assertFalse(today.day.canPlant)
        assertNull(today.backfillCandidate)
        assertTrue(t.rows("days").isEmpty())
    }

    @Test
    fun sendTrimsAndKeepsOrder() {
        t.send("  第一句  ")
        t.send("第二句")
        val day = t.diary.today().day
        assertEquals(listOf("第一句", "第二句"), day.messages.map { it.content })
        assertTrue(day.canPlant)
    }

    @Test
    fun emptyMessageRejected() {
        assertRefused("想說什麼都可以，但訊息是空的喔") { t.send("   ") }
        assertTrue(t.rows("days").isEmpty())
    }

    @Test
    fun photoOnlyMessageAccepted() {
        val m = t.send("", NewPhoto(JPEG, ".jpg"))
        assertEquals("", m.content)
        val photo = requireNotNull(m.photo)
        assertTrue(photo.startsWith("2026-07-24/") && photo.endsWith(".jpg"))
        assertTrue(File(t.photos, photo).readBytes().contentEquals(JPEG))
    }

    @Test
    fun sendRefusedOncePlantedOrWhilePlanting() {
        t.send("今天")
        t.sql.execute("UPDATE days SET status = 'planting'")
        assertRefused("樹正在生長中，等它一下 🌱") { t.send("再一句") }
        t.sql.execute("UPDATE days SET status = 'planted'")
        assertRefused("今天的樹已經種下了，新的想法留到明天吧 🌱") { t.send("再一句") }
    }

    /** 收回：畫面上不見、照片刪掉，資料庫留一筆沒有內容的墓碑（日後同步才不會被同步回來）。 */
    @Test
    fun retractLeavesAnEmptyTombstoneAndRemovesThePhoto() {
        val m = t.send("說錯了", NewPhoto(JPEG, ".jpg"))
        val file = File(t.photos, m.photo!!)
        t.diary.retract(m.id)
        assertTrue(t.diary.today().day.messages.isEmpty())
        assertFalse(file.exists())
        val row = t.rows("messages", "id = ?", m.id).single()
        assertNotNull(row.stringOrNull("deleted_at"))
        assertEquals("", row.string("content"))
        assertNull(row.stringOrNull("photo_path"))
        // 收回過的不能再改、再收回
        assertRefused("找不到這則訊息") { t.diary.retract(m.id) }
        assertRefused("找不到這則訊息") { t.diary.edit(m.id, "改") }
    }

    @Test
    fun retractOnlyWhileCollecting() {
        val m = t.send("今天")
        t.plant()
        assertRefused("只有今天還沒種樹前的訊息可以收回") { t.diary.retract(m.id) }
    }

    /** 改字不改時間：時間留在說出口的那一刻，也不記錄改過的痕跡。 */
    @Test
    fun editKeepsTheOriginalTime() {
        val m = t.send("今天好累")
        t.now = t.now.plusHours(2)
        val edited = t.diary.edit(m.id, "  今天好累，但很充實  ")
        assertEquals("今天好累，但很充實", edited.content)
        val stored = t.diary.today().day.messages.single()
        assertEquals("今天好累，但很充實", stored.content)
        assertEquals(m.createdAt, stored.createdAt)
    }

    @Test
    fun editRejectedAfterPlanting() {
        val m = t.send("今天")
        t.plant()
        assertRefused("只有今天還沒種樹前的訊息可以修改") { t.diary.edit(m.id, "改") }
    }

    @Test
    fun editRejectedOnPastDay() {
        val m = t.send("昨天的話")
        t.now = t.now.plusDays(1)
        assertRefused("只有今天還沒種樹前的訊息可以修改") { t.diary.edit(m.id, "改") }
    }

    @Test
    fun editToEmptyRejectedUnlessThereIsAPhoto() {
        val text = t.send("整句不要了")
        assertRefused("整句不想留的話，用收回就好") { t.diary.edit(text.id, "  ") }
        val photo = t.send("配圖", NewPhoto(JPEG, ".jpg"))
        assertEquals("", t.diary.edit(photo.id, "").content)
    }

    @Test
    fun editMissingMessage() {
        val e = assertRefused("找不到這則訊息") { t.diary.edit("no-such-id", "改") }
        assertEquals(TreeSayException.Kind.NOT_FOUND, e.kind)
    }

    /** 主鍵是 UUID：兩台裝置各自編號，日後同步才不會撞號。 */
    @Test
    fun idsAreUuids() {
        val m = t.send("今天")
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        assertTrue(uuid.matches(m.id))
        assertTrue(uuid.matches(t.rows("days").single().string("id")))
    }

    // ---------- 今日問題落盤 ----------

    /** 題庫加題會讓所有日期的題目位移；問過什麼是那一天的事實，Day 誕生時就蓋章存下。 */
    @Test
    fun stampedQuestionIsKeptNotRecomputed() {
        t.send("今天")
        assertEquals(Questions.questionFor(t.today), t.rows("days").single().string("question"))
        t.sql.execute("UPDATE days SET question = '那天真正問過的題目'")
        assertEquals("那天真正問過的題目", t.diary.today().day.question)
        assertEquals("那天真正問過的題目", t.diary.day(t.today)!!.question)
    }

    // ---------- 森林 ----------

    @Test
    fun plantedAndUnfinishedDaysBothListed() {
        t.plantedDay(LocalDate.of(2026, 7, 20), "種下的那天", reply = plantReply(emotion = "tired"))
        t.send("還沒收尾的今天")
        assertEquals(
            listOf(
                ForestDay(LocalDate.of(2026, 7, 20), Emotion.TIRED, DayStatus.PLANTED),
                ForestDay(LocalDate.of(2026, 7, 24), null, DayStatus.COLLECTING),
            ),
            t.diary.month(YearMonth.of(2026, 7)),
        )
    }

    /** 全部收回的那天，跟從沒打開過 App 一樣。 */
    @Test
    fun untouchedDaysStayOut() {
        t.diary.retract(t.send("說錯了").id)
        assertTrue(t.diary.month(YearMonth.of(2026, 7)).isEmpty())
    }

    @Test
    fun monthListsOnlyItsOwnDays() {
        t.plantedDay(LocalDate.of(2026, 6, 30), "六月")
        t.plantedDay(LocalDate.of(2026, 7, 1), "七月")
        t.plantedDay(LocalDate.of(2026, 8, 1), "八月")
        assertEquals(listOf(LocalDate.of(2026, 7, 1)), t.diary.month(YearMonth.of(2026, 7)).map { it.date })
    }

    @Test
    fun dayDetailOfASilentDayIsNull() {
        assertNull(t.diary.day(LocalDate.of(2026, 7, 1)))
    }

    // ---------- 補種邀請 ----------

    @Test
    fun todayItselfIsNotACandidate() {
        t.send("今天")
        assertNull(t.diary.today().backfillCandidate)
    }

    @Test
    fun yesterdayWithMessagesIsOffered() {
        t.send("昨天說的")
        t.now = t.now.plusDays(1)
        assertEquals(LocalDate.of(2026, 7, 24), t.diary.today().backfillCandidate)
    }

    /** 一次只遞一天，挑最早的：它最先過期。 */
    @Test
    fun oldestFirstSoNothingExpiresUnseen() {
        t.send("前天")
        t.now = t.now.plusDays(1)
        t.send("昨天")
        t.now = t.now.plusDays(1)
        assertEquals(LocalDate.of(2026, 7, 24), t.diary.today().backfillCandidate)
        t.now = t.now.plusDays(1)
        assertEquals(LocalDate.of(2026, 7, 25), t.diary.today().backfillCandidate)
    }

    @Test
    fun plantedOrRetractedDaysAreNotOffered() {
        t.plantedDay(LocalDate.of(2026, 7, 23), "種下了")
        t.diary.retract(t.send("收回了").id)
        t.now = t.now.plusDays(1)
        assertNull(t.diary.today().backfillCandidate)
    }

    // ---------- 記憶 ----------

    @Test
    fun memoriesNewestFirstAndLettingGoLeavesAnEmptyTombstone() {
        t.plantedDay(LocalDate.of(2026, 7, 22), "搬家", reply = plantReply(memory = listOf("在準備搬家")))
        t.plantedDay(LocalDate.of(2026, 7, 23), "貓", reply = plantReply(memory = listOf("養了一隻貓叫麻糬")))
        val memories = t.diary.memories()
        assertEquals(listOf("養了一隻貓叫麻糬", "在準備搬家"), memories.map { it.content })
        assertEquals(LocalDate.of(2026, 7, 23), memories.first().sourceDate)

        t.diary.letGo(memories.first().id)
        assertEquals(listOf("在準備搬家"), t.diary.memories().map { it.content })
        val tomb = t.rows("memories", "id = ?", memories.first().id).single()
        assertNotNull(tomb.stringOrNull("deleted_at"))
        assertEquals("", tomb.string("content"))

        val e = assertRefused("樹已經放下這件事了") { t.diary.letGo(memories.first().id) }
        assertEquals(TreeSayException.Kind.NOT_FOUND, e.kind)
    }

    // ---------- AI 費用 ----------

    @Test
    fun usageEmpty() {
        assertEquals(UsageSummary(emptyList(), 0.0), t.diary.usage())
    }

    /** 只按月加總、最近的在前；不在價目表上的模型只說有幾次，不偷偷當成 0 元。 */
    @Test
    fun usageGroupedByMonthNewestFirst() {
        t.now = taipei(2026, 6, 30, 23)
        t.diary.recordUsage(AiUsage(Purpose.PLANT, "claude-opus-5-5", 1_000, 1_000))
        t.now = taipei(2026, 7, 2)
        t.diary.recordUsage(AiUsage(Purpose.PLANT, "claude-opus-5-5", 1_000, 1_000))
        t.diary.recordUsage(AiUsage(Purpose.WEEKLY, "claude-haiku-5-5", 1_000, 1_000))
        t.diary.recordUsage(AiUsage(Purpose.PLANT, "claude-opus-9", 1_000, 1_000))

        val usage = t.diary.usage()
        assertEquals(listOf(YearMonth.of(2026, 7), YearMonth.of(2026, 6)), usage.months.map { it.month })
        val july = usage.months.first()
        assertEquals(0.0246, july.totalUsd, 1e-9)
        assertEquals(0.024, july.plantUsd, 1e-9)
        assertEquals(0.0006, july.weeklyUsd, 1e-9)
        assertEquals(1, july.unpricedCalls)
        assertEquals(0.0486, usage.totalUsd, 1e-9)
        // 記帳不連到哪一天的日記
        assertFalse(t.rows("api_usage").first().let { row -> runCatching { row.string("day_id") }.isSuccess })
    }

    // ---------- 時區 ----------

    /**
     * 在東京晚上 11 點說的話，回到台北看也是晚上 11 點：時間存當地時間＋時差。
     * 切日照人在的地方：到了台北，台北的凌晨 4:00 才換日。
     */
    @Test
    fun timesStayLocalToWhereTheyWereSaid() {
        val tokyo = ZoneOffset.ofHours(9)
        t.now = OffsetDateTime.of(2026, 7, 24, 23, 30, 0, 0, tokyo)
        t.send("東京的夜景")
        // 隔天早上 3:30（台北），等於東京 4:30——在台北還是 7/24
        t.now = taipei(2026, 7, 25, 3, 30)
        val day = t.diary.today().day
        assertEquals(LocalDate.of(2026, 7, 24), day.date)
        val said = day.messages.single().createdAt
        assertEquals(23, said.hour)
        assertEquals(tokyo, said.offset)
        assertEquals("2026-07-24T23:30:00+09:00", t.rows("messages").single().string("created_at"))
    }
}
