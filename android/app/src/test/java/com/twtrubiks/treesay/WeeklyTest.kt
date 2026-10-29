package com.twtrubiks.treesay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WeeklyTest {
    private val lastMonday = LocalDate.of(2026, 7, 13)
    private val thisMonday = LocalDate.of(2026, 7, 20)
    private val now = LocalDateTime.of(2026, 7, 24, 12, 0)

    /** 週一到週日都算這週；隔壁週的日子不混進來。 */
    @Test
    fun weekRunsMondayThroughSunday() {
        val week = Weekly.dates(lastMonday)
        assertTrue(LocalDate.of(2026, 7, 13) in week)
        assertTrue(LocalDate.of(2026, 7, 19) in week)
        assertFalse(LocalDate.of(2026, 7, 12) in week)
        assertFalse(LocalDate.of(2026, 7, 20) in week)
    }

    @Test
    fun weekStartMustBeMonday() {
        assertTrue(Weekly.isWeekStart(lastMonday))
        assertFalse(Weekly.isWeekStart(lastMonday.plusDays(1)))
        assertThrows(IllegalArgumentException::class.java) { Weekly.isOver(lastMonday.plusDays(1), now) }
    }

    @Test
    fun pastWeekIsOver() {
        assertTrue(Weekly.isOver(lastMonday, now))
    }

    @Test
    fun currentWeekIsGrowing() {
        assertFalse(Weekly.isOver(thisMonday, now))
    }

    /** 週日還沒過完（凌晨 4:00 前也還算週日）。週報永久快取，早一步生成，那天就永遠不在週報裡。 */
    @Test
    fun growingUntilItsSundayIsOver() {
        assertFalse(Weekly.isOver(thisMonday, LocalDateTime.of(2026, 7, 26, 23, 0)))
        assertFalse(Weekly.isOver(thisMonday, LocalDateTime.of(2026, 7, 27, 3, 59)))
        assertTrue(Weekly.isOver(thisMonday, LocalDateTime.of(2026, 7, 27, 4, 0)))
    }
}

/** 週報是記憶唯一的瘦身時機：合併重複、放下過時，保留的不動聲色。 */
class CurateMemoriesTest {

    @Test
    fun keepsMergesAndLetsGo() {
        val existing = listOf("在做樹說", "在做 treesay", "一次性的事")
        val curation = Weekly.curateMemories(existing, listOf("在做樹說", "新合併的事"))
        assertEquals(listOf("新合併的事"), curation.added)
        assertEquals(listOf("在做 treesay", "一次性的事"), curation.removed)
    }

    /** 樹不該在一次週報裡忘掉所有事——回空清單視為可疑，不套用。 */
    @Test
    fun emptyCurationKeepsMemories() {
        assertEquals(MemoryCuration.NONE, Weekly.curateMemories(listOf("重要的事"), emptyList<String>()))
    }

    @Test
    fun garbageCurationKeepsMemories() {
        assertEquals(MemoryCuration.NONE, Weekly.curateMemories(listOf("重要的事"), "不是清單"))
        assertEquals(MemoryCuration.NONE, Weekly.curateMemories(listOf("重要的事"), listOf("", 3)))
    }

    @Test
    fun curatedListIsCleanedAndCapped() {
        val many = (1..40).map { "第 $it 件事" }
        val curation = Weekly.curateMemories(emptyList(), many + "  第 1 件事  ")
        assertEquals(many.take(Weekly.MEMORY_KEEP_LIMIT), curation.added)
    }
}
