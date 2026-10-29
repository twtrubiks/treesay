package com.twtrubiks.treesay

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

/** 照搬 test_api.py 的 TestWeeklyReport。2026-07-24 是週五，那週從 7/20 開始，上一週是 7/13～7/19。 */
class WeeklyReportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t by lazy { TestDiary(tmp.root) }

    @After
    fun close() = t.close()

    private val lastWeek = LocalDate.of(2026, 7, 13)

    private fun weeklyReply(memories: Any? = null): ObjectNode = ObjectMapper().valueToTree<ObjectNode>(
        buildMap {
            put("good_things", listOf("整理了陽台", 3))
            put("bad_things", listOf("有點累"))
            put("keywords", listOf("陽台", "明信片"))
            put("advice", " 下週也慢慢來。 ")
            if (memories != null) put("memories", memories)
        },
    )

    @Test
    fun generateThenCacheHit() {
        t.plantedDay(LocalDate.of(2026, 7, 13), "陽台")
        t.ai.reply = { weeklyReply() }
        val first = t.report(lastWeek) as WeeklyResult.Ready
        assertEquals(WeeklyReport(listOf("整理了陽台", "3"), listOf("有點累"), listOf("陽台", "明信片"), "下週也慢慢來。"), first.report)
        assertEquals(t.now, first.generatedAt)
        assertEquals(Purpose.WEEKLY, t.ai.calls.last().purpose)

        val calls = t.ai.calls.size
        t.now = t.now.plusDays(3)
        assertEquals(first, t.report(lastWeek))
        assertEquals(calls, t.ai.calls.size)
    }

    @Test
    fun promptCarriesOnlyThisWeeksPlantedDiaries() {
        t.plantedDay(LocalDate.of(2026, 7, 12), "前一週", reply = plantReply(diary = "前一週的日記"))
        t.plantedDay(LocalDate.of(2026, 7, 13), "週一", reply = plantReply(diary = "週一的日記", emotion = "tired"))
        t.plantedDay(LocalDate.of(2026, 7, 19), "週日", reply = plantReply(diary = "週日的日記"))
        t.plantedDay(LocalDate.of(2026, 7, 20), "下一週", reply = plantReply(diary = "下一週的日記"))
        t.ai.reply = { weeklyReply() }
        t.report(lastWeek)
        val prompt = t.ai.lastPrompt
        assertTrue(prompt.contains("【2026-07-13（tired）】\n週一的日記\n\n【2026-07-19（happy）】\n週日的日記"))
        assertFalse(prompt.contains("前一週的日記"))
        assertFalse(prompt.contains("下一週的日記"))
    }

    @Test
    fun currentWeekIsGrowing() {
        t.plantedDay(LocalDate.of(2026, 7, 20), "這週")
        assertEquals(WeeklyResult.Growing, t.report(LocalDate.of(2026, 7, 20)))
    }

    /** 週日要整天過完（凌晨 4:00 前也還算週日）——週報永久快取，早一步生成，那天就永遠不在週報裡。 */
    @Test
    fun growingUntilItsSundayIsOver() {
        t.now = taipei(2026, 7, 20, 3, 59)
        assertEquals(WeeklyResult.Growing, t.report(lastWeek))
        t.now = taipei(2026, 7, 20, 4, 0)
        assertEquals(WeeklyResult.Empty, t.report(lastWeek))
    }

    @Test
    fun emptyPastWeekDoesNotCallAi() {
        t.send("沒種的那天")
        t.now = t.now.plusDays(7)
        assertEquals(WeeklyResult.Empty, t.report(LocalDate.of(2026, 7, 20)))
        assertTrue(t.ai.calls.isEmpty())
    }

    @Test
    fun weekStartMustBeMonday() {
        assertRefused("週報從週一開始") { t.report(LocalDate.of(2026, 7, 14)) }
    }

    @Test
    fun aiFailureIsNotCached() {
        t.plantedDay(lastWeek, "陽台")
        t.ai.reply = { throw AiError("壞掉了") }
        assertRefused(Diary.TREE_ASLEEP) { t.report(lastWeek) }
        assertTrue(t.rows("weekly_reports").isEmpty())
        t.ai.reply = { weeklyReply() }
        assertTrue(t.report(lastWeek) is WeeklyResult.Ready)
    }

    /** 記憶只在週報時瘦身：合併重複、放下過時的。合併出的新條目記在整理當天。 */
    @Test
    fun weeklyCuratesMemories() {
        t.plantedDay(lastWeek, "搬家", reply = plantReply(memory = listOf("在準備搬家", "在打包", "養了一隻貓")))
        t.ai.reply = { weeklyReply(memories = listOf("養了一隻貓", "正在搬家，打包中")) }
        t.report(lastWeek)

        assertTrue(t.ai.calls.last().schema["properties"].has("memories"))
        assertTrue(t.ai.lastPrompt.contains("樹一路記著這些關於使用者的事：\n\n- 在準備搬家\n- 在打包\n- 養了一隻貓"))
        val memories = t.diary.memories()
        assertEquals(listOf("正在搬家，打包中", "養了一隻貓"), memories.map { it.content })
        assertEquals(t.today, memories.first().sourceDate)
        assertEquals(2, t.rows("memories", "deleted_at IS NOT NULL AND content = ''").size)
    }

    /** AI 回空清單視為可疑：樹不該在一次週報裡忘掉所有事。 */
    @Test
    fun emptyCurationKeepsMemories() {
        t.plantedDay(lastWeek, "搬家", reply = plantReply(memory = listOf("在準備搬家")))
        t.ai.reply = { weeklyReply(memories = emptyList<String>()) }
        t.report(lastWeek)
        assertEquals(listOf("在準備搬家"), t.diary.memories().map { it.content })
    }

    @Test
    fun withoutMemoriesSkipsCuration() {
        t.plantedDay(lastWeek, "陽台")
        t.ai.reply = { weeklyReply() }
        t.report(lastWeek)
        assertFalse(t.ai.calls.last().schema["properties"].has("memories"))
        assertFalse(t.ai.lastPrompt.contains("樹一路記著"))
    }
}
