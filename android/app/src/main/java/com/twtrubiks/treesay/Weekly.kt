package com.twtrubiks.treesay

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** 週報整理記憶的結果：[added] 是合併出的新條目（source date 記整理當天），[removed] 是放下的。 */
data class MemoryCuration(val added: List<String>, val removed: List<String>) {
    companion object {
        val NONE = MemoryCuration(emptyList(), emptyList())
    }
}

/** 週報的規則，跟網頁版 `main.py` 的 get_report 同一套：週一到週日，只生成已經結束的週並永久快取。 */
object Weekly {
    /** 週報整理後最多留幾條記憶。記憶清單長到讀不完，「樹記得你」就退化成「樹存了一堆你的資料」。 */
    const val MEMORY_KEEP_LIMIT = 30

    fun isWeekStart(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.MONDAY

    fun dates(weekStart: LocalDate): ClosedRange<LocalDate> = weekStart..weekStart.plusDays(6)

    /**
     * 那一週過完了沒；沒過完就是「這週還在生長中」。週日要整天過完（凌晨 4:00 前也還算週日）——
     * 週報永久快取，早一步生成，那天就永遠不在週報裡。
     */
    fun isOver(weekStart: LocalDate, now: LocalDateTime): Boolean {
        require(isWeekStart(weekStart)) { "週報的週從週一開始：$weekStart" }
        return dates(weekStart).endInclusive < TreeDay.effectiveDate(now)
    }

    /**
     * 套用 AI 整理過的記憶清單。AI 只拿得到現有清單，所以使用者刪掉的事不會復活。
     * 整理完是空的（或欄位壞掉）視為可疑，整個不套用——樹不該在一次週報裡忘掉所有事。
     */
    fun curateMemories(existing: List<String>, aiMemories: Any?): MemoryCuration {
        val curated = AiOutput.normalizeMemories(aiMemories, MEMORY_KEEP_LIMIT)
        if (curated.isEmpty()) return MemoryCuration.NONE
        return MemoryCuration(
            added = curated.filter { it !in existing },
            removed = existing.filter { it !in curated },
        )
    }
}
