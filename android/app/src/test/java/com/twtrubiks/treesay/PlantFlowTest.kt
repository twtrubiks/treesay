package com.twtrubiks.treesay

import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/**
 * 從丟訊息到種下樹，照搬 test_api.py 的 TestMessageToPlant、TestPlantLock、TestBackfill、
 * TestKeywords、TestMemory 與照片相關的案例。Claude 是假的，SQL 是真的。
 */
class PlantFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t by lazy { TestDiary(tmp.root) }

    @After
    fun close() = t.close()

    private fun status(date: LocalDate = t.today) = t.rows("days", "date = ?", date.toString()).single().string("status")

    @Test
    fun fullFlow() {
        t.now = taipei(2026, 7, 24, 9, 15)
        t.send("早上去河堤跑了一圈")
        t.now = taipei(2026, 7, 24, 21, 40)
        t.send("晚上看了電影")
        t.ai.reply = { plantReply(emotion = "calm", keywords = listOf("跑步", "電影")) }

        val day = t.plant()
        assertEquals(DayStatus.PLANTED, day.status)
        assertEquals("今天去河堤跑了一圈。", day.diary)
        assertEquals(Emotion.CALM, day.emotion)
        assertEquals("跑完很舒服吧，謝謝你告訴我。", day.treeReply)
        assertEquals(listOf("跑步", "電影"), day.keywords)
        assertEquals(taipei(2026, 7, 24, 21, 40), day.plantedAt)
        assertFalse(day.canPlant)

        val call = t.ai.calls.single()
        assertEquals(Purpose.PLANT, call.purpose)
        assertEquals(Prompts.PLANT_SCHEMA, call.schema)
        assertTrue(call.prompt.contains("- [09:15] 早上去河堤跑了一圈\n- [21:40] 晚上看了電影"))
        assertTrue(call.images.isEmpty())
    }

    @Test
    fun plantWithoutMessages() {
        assertRefused("今天還沒有想法丟給樹") { t.plant() }
        assertTrue(t.ai.calls.isEmpty())
    }

    @Test
    fun outOfEnumEmotionFallsBackToCalm() {
        t.send("今天")
        t.ai.reply = { plantReply(emotion = "ecstatic") }
        assertEquals(Emotion.CALM, t.plant().emotion)
    }

    /** AI 失敗：鎖解開、那天回到收集中，給療癒文案，可以再按一次。 */
    @Test
    fun aiFailureUnlocksAndTellsGently() {
        t.send("今天")
        t.ai.reply = { throw AiError("壞掉了") }
        val e = assertRefused(Diary.TREE_ASLEEP) { t.plant() }
        assertEquals(TreeSayException.Kind.ASLEEP, e.kind)
        assertEquals("collecting", status())
        assertNull(t.rows("days").single().stringOrNull("planting_started_at"))

        t.ai.reply = { plantReply() }
        assertEquals(DayStatus.PLANTED, t.plant().status)
    }

    @Test
    fun blankDiaryOrReplyUnlocks() {
        t.send("今天")
        for (reply in listOf(plantReply(diary = "  "), plantReply(treeReply = ""))) {
            t.ai.reply = { reply }
            assertRefused(Diary.TREE_ASLEEP) { t.plant() }
            assertEquals("collecting", status())
        }
    }

    /** key 的問題重按也不會好：講清楚該修什麼，畫面據此帶人去輸入 key。 */
    @Test
    fun keyProblemsAreActionable() {
        t.send("今天")
        t.ai.reply = { throw AiKeyMissing() }
        assertEquals(TreeSayException.Kind.KEY_MISSING, assertRefused(Diary.KEY_MISSING) { t.plant() }.kind)
        t.ai.reply = { throw AiKeyInvalid(RuntimeException()) }
        assertEquals(TreeSayException.Kind.KEY_INVALID, assertRefused(Diary.KEY_INVALID) { t.plant() }.kind)
        t.ai.reply = { throw AiUnreachable(RuntimeException()) }
        assertRefused(Diary.UNREACHABLE) { t.plant() }
        assertEquals("collecting", status())
    }

    /** 被取消（使用者切走、系統停掉背景工作）也要把鎖解開。 */
    @Test
    fun cancellationUnlocks() {
        t.send("今天")
        t.ai.reply = { throw CancellationException("背景工作被停掉") }
        assertThrows<CancellationException> { t.plant() }
        assertEquals("collecting", status())
    }

    // ---------- 種樹鎖 ----------

    @Test
    fun lockTakenBeforeCallingAi() {
        t.send("今天")
        var seen: String? = null
        t.ai.reply = {
            seen = status()
            plantReply()
        }
        t.plant()
        assertEquals("planting", seen)
    }

    @Test
    fun duplicatePlantBlockedWhilePlanting() {
        t.send("今天")
        t.ai.reply = {
            assertRefused("樹正在生長中 🌱") { t.plant() }
            plantReply()
        }
        assertEquals(DayStatus.PLANTED, t.plant().status)
        assertEquals(1, t.ai.calls.size)
    }

    @Test
    fun stalePlantingCanReplant() {
        t.send("今天")
        t.sql.execute("UPDATE days SET status = 'planting', planting_started_at = ?", "2026-07-24T11:54:59+08:00")
        assertEquals(DayStatus.PLANTED, t.plant().status)
    }

    // ---------- 補種 ----------

    @Test
    fun yesterdayCanBePlantedAndPromptKnowsItIsLate() {
        t.send("昨天說的")
        t.now = t.now.plusDays(1)
        val day = t.plant(LocalDate.of(2026, 7, 24))
        assertEquals(DayStatus.PLANTED, day.status)
        assertTrue(t.ai.lastPrompt.contains("使用者昨天像傳訊息一樣"))
        assertTrue(t.ai.lastPrompt.contains("是現在才回來種下這棵樹"))
    }

    @Test
    fun sameDayPromptHasNoBackfillBlock() {
        t.send("今天")
        t.plant()
        assertFalse(t.ai.lastPrompt.contains("才回來種下"))
    }

    @Test
    fun expiredFutureAndPlantedDaysAreRefused() {
        t.send("三天前")
        t.now = t.now.plusDays(3)
        assertRefused("這一天已經走遠了，就讓它安靜地留著吧 🍂") { t.plant(LocalDate.of(2026, 7, 24)) }
        assertRefused("那一天還沒到呢") { t.plant(t.today.plusDays(1)) }
        assertRefused("這一天沒有留下什麼話") { t.plant(t.today.minusDays(1)) }
        t.plantedDay(t.today.minusDays(1), "昨天")
        assertRefused("這一天的樹已經種下了 🌳") { t.plant(t.today.minusDays(1)) }
        assertTrue(t.ai.calls.size == 1)
    }

    @Test
    fun plantPromptUsesTheStampedQuestion() {
        t.send("今天")
        t.sql.execute("UPDATE days SET question = '那天真正問過的題目'")
        t.plant()
        assertTrue(t.ai.lastPrompt.contains("今天樹問過使用者：「那天真正問過的題目」"))
    }

    // ---------- 關鍵詞 ----------

    @Test
    fun garbageOrMissingKeywordsDoNotBreakPlanting() {
        t.send("今天")
        t.ai.reply = { plantReply(keywords = "不是清單") }
        assertEquals(emptyList<String>(), t.plant().keywords)
        assertEquals("[]", t.rows("days").single().string("keywords_json"))

        t.plantedDay(t.today.minusDays(1), "昨天", reply = plantReply(keywords = listOf(" 搬家 ", "搬家", 3, "", "一".repeat(21), "貓", "咖啡", "雨")))
        assertEquals(listOf("搬家", "貓", "咖啡", "雨"), t.diary.day(t.today.minusDays(1))!!.keywords)
    }

    // ---------- 記憶 ----------

    @Test
    fun plantExtractsMemoriesAndNextPlantRemembers() {
        t.plantedDay(LocalDate.of(2026, 7, 23), "在打包", reply = plantReply(memory = listOf("在準備搬家", "養了一隻貓")))
        assertFalse(t.ai.lastPrompt.contains("你一直記得"))

        t.send("今天搬完了")
        t.ai.reply = { plantReply(memory = listOf("在準備搬家", "新家在台中")) }
        t.plant()
        val prompt = t.ai.lastPrompt
        assertTrue(prompt.contains("- 在準備搬家\n- 養了一隻貓"))
        assertTrue(prompt.contains("【2026-07-23（happy）】\n今天去河堤跑了一圈。"))
        // 已經記得的不重複記
        assertEquals(listOf("新家在台中", "養了一隻貓", "在準備搬家"), t.diary.memories().map { it.content })
        assertEquals(LocalDate.of(2026, 7, 24), t.diary.memories().first().sourceDate)
    }

    @Test
    fun garbageMemoryFieldDoesNotBreakPlanting() {
        t.send("今天")
        t.ai.reply = { plantReply(memory = mapOf("不是" to "清單")) }
        assertEquals(DayStatus.PLANTED, t.plant().status)
        assertTrue(t.diary.memories().isEmpty())
    }

    /** 放下的記憶不會再進 prompt。 */
    @Test
    fun deletedMemoryStaysForgotten() {
        t.plantedDay(LocalDate.of(2026, 7, 23), "難過的事", reply = plantReply(memory = listOf("跟家人吵架了")))
        t.diary.letGo(t.diary.memories().single().id)
        t.send("今天")
        t.plant()
        assertFalse(t.ai.lastPrompt.contains("跟家人吵架了"))
    }

    @Test
    fun recentDiariesAreTheLatestThreePlantedWithinAWeek() {
        t.plantedDay(LocalDate.of(2026, 7, 16), "太久了", reply = plantReply(diary = "八天前的日記"))
        for (d in 19..22) t.plantedDay(LocalDate.of(2026, 7, d), "第 $d 天", reply = plantReply(diary = "第 $d 天的日記"))
        t.send("今天")
        t.plant()
        val prompt = t.ai.lastPrompt
        assertFalse(prompt.contains("八天前的日記"))
        assertFalse(prompt.contains("第 19 天的日記"))
        assertTrue(prompt.contains("第 20 天的日記\n\n【2026-07-21（happy）】\n第 21 天的日記\n\n【2026-07-22（happy）】\n第 22 天的日記"))
    }

    /** 補種以那一天為基準往回看，不能引用之後才種下的日記。 */
    @Test
    fun backfillDoesNotSeeFutureDiaries() {
        t.send("昨天")
        t.now = t.now.plusDays(1)
        t.plantedDay(t.today, "今天先種了", reply = plantReply(diary = "今天的日記"))
        t.plant(LocalDate.of(2026, 7, 24))
        assertFalse(t.ai.lastPrompt.contains("今天的日記"))
    }

    // ---------- 照片 ----------

    @Test
    fun photosAreSentFirstAndNumbered() {
        t.send("早餐", NewPhoto(JPEG, ".jpg"))
        t.send("沒有照片")
        t.send("", NewPhoto(JPEG, ".jpg"))
        t.plant()
        val call = t.ai.calls.single()
        assertEquals(2, call.images.size)
        assertEquals("image/jpeg", call.images.first().mediaType)
        assertTrue(call.prompt.contains("- [12:00] 早餐（附照片 1）\n- [12:00] 沒有照片\n- [12:00] （附照片 2）"))
    }

    /** 送不出去的照片（HEIC 沒轉成、檔案不見）只標記樹沒看到，種樹照常。 */
    @Test
    fun unsupportedOrMissingPhotoDoesNotBlockPlanting() {
        t.send("看不到的", NewPhoto(HEIC, ".heic"))
        val gone = t.send("不見的", NewPhoto(JPEG, ".jpg"))
        File(t.photos, gone.photo!!).delete()
        t.send("看得到的", NewPhoto(JPEG, ".jpg"))
        assertEquals(DayStatus.PLANTED, t.plant().status)
        val call = t.ai.calls.single()
        assertEquals(1, call.images.size)
        assertTrue(call.prompt.contains("看不到的（附了一張照片，但這次沒能帶給你看）"))
        assertTrue(call.prompt.contains("不見的（附了一張照片，但這次沒能帶給你看）"))
        assertTrue(call.prompt.contains("看得到的（附照片 1）"))
    }
}
