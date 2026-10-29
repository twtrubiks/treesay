package com.twtrubiks.treesay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TreeDayTest {

    @Test
    fun beforeCutover_belongsToPreviousDay() {
        assertEquals(LocalDate.of(2026, 10, 27), TreeDay.effectiveDate(LocalDateTime.of(2026, 10, 28, 3, 59, 59)))
    }

    @Test
    fun atCutover_startsNewDay() {
        assertEquals(LocalDate.of(2026, 10, 28), TreeDay.effectiveDate(LocalDateTime.of(2026, 10, 28, 4, 0)))
    }

    @Test
    fun earlyNewYear_stillBelongsToLastYear() {
        assertEquals(LocalDate.of(2026, 12, 31), TreeDay.effectiveDate(LocalDateTime.of(2027, 1, 1, 1, 30)))
    }
}
