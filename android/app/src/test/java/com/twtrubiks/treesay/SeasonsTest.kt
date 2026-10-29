package com.twtrubiks.treesay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SeasonsTest {

    @Test
    fun seasonsFollowTheNorthernCalendar() {
        val seasons = (1..12).map(Seasons::seasonFor)
        assertEquals(
            listOf(
                Season.WINTER, Season.WINTER, Season.SPRING, Season.SPRING, Season.SPRING, Season.SUMMER,
                Season.SUMMER, Season.SUMMER, Season.AUTUMN, Season.AUTUMN, Season.AUTUMN, Season.WINTER,
            ),
            seasons,
        )
    }

    /** 入夜跟問候語的「晚安」、種樹按鈕亮起是同一條線：18:00 到切日前。 */
    @Test
    fun eveningRunsFromSixUntilTheCutover() {
        assertFalse(Seasons.isEvening(LocalTime.of(17, 59)))
        assertTrue(Seasons.isEvening(LocalTime.of(18, 0)))
        assertTrue(Seasons.isEvening(LocalTime.of(3, 59)))
        assertFalse(Seasons.isEvening(LocalTime.of(4, 0)))
        for (hour in 0..23) {
            val time = LocalTime.of(hour, 30)
            assertEquals(Seasons.isEvening(time), Format.greeting(time).startsWith("晚安"))
        }
    }

    @Test
    fun visitorsComeWithTheSeasonOnly() {
        assertEquals(Visitor.PETAL, Seasons.visitorFor(Sky(Season.SPRING, night = true)))
        assertEquals(Visitor.FLUFF, Seasons.visitorFor(Sky(Season.SUMMER, night = false)))
        assertEquals(Visitor.FIREFLY, Seasons.visitorFor(Sky(Season.SUMMER, night = true)))
        assertEquals(Visitor.LEAF, Seasons.visitorFor(Sky(Season.AUTUMN, night = false)))
        assertEquals(Visitor.SNOW, Seasons.visitorFor(Sky(Season.WINTER, night = false)))
        assertEquals(Sky(Season.AUTUMN, night = true), Seasons.skyAt(LocalDateTime.of(2026, 10, 28, 21, 0)))
    }

    @Test
    fun greetingFollowsTheHour() {
        assertEquals("早安，今天想說些什麼？", Format.greeting(LocalTime.of(4, 0)))
        assertEquals("午安，今天過得怎麼樣？", Format.greeting(LocalTime.of(11, 0)))
        assertEquals("晚安，今天辛苦了嗎？", Format.greeting(LocalTime.of(18, 0)))
    }

    @Test
    fun datesAndTimesLikeTheWebVersion() {
        assertEquals("10月28日 星期三", Format.dateLabel(LocalDate.of(2026, 10, 28)))
        assertEquals("11月1日 星期日", Format.dateLabel(LocalDate.of(2026, 11, 1)))
        val offset = ZoneOffset.ofHours(9)
        assertEquals("上午 12:05", Format.time(OffsetDateTime.of(2026, 10, 28, 0, 5, 0, 0, offset)))
        assertEquals("上午 9:30", Format.time(OffsetDateTime.of(2026, 10, 28, 9, 30, 0, 0, offset)))
        assertEquals("下午 12:00", Format.time(OffsetDateTime.of(2026, 10, 28, 12, 0, 0, 0, offset)))
        assertEquals("下午 11:30", Format.time(OffsetDateTime.of(2026, 10, 28, 23, 30, 0, 0, offset)))
    }

    @Test
    fun smallAmountsStayReadable() {
        assertEquals("US$0", Format.usd(0.0))
        assertEquals("不到 US$0.001", Format.usd(0.0004))
        assertEquals("US$0.024", Format.usd(0.024))
        assertEquals("US$1.25", Format.usd(1.2512))
    }
}
