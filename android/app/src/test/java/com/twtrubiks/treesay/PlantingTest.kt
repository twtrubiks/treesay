package com.twtrubiks.treesay

import com.twtrubiks.treesay.PlantVerdict.AlreadyPlanted
import com.twtrubiks.treesay.PlantVerdict.Expired
import com.twtrubiks.treesay.PlantVerdict.Go
import com.twtrubiks.treesay.PlantVerdict.Growing
import com.twtrubiks.treesay.PlantVerdict.NotYet
import com.twtrubiks.treesay.PlantVerdict.NothingSaid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val NOW: OffsetDateTime = taipei(2026, 7, 24, 12)
private val TODAY: LocalDate = NOW.toLocalDate()
private val SAID = DayState(DayStatus.COLLECTING, hasMessages = true)

/** 種樹前的檢查。補種：當天忘了按種樹，那些話不該就這樣沉掉，但也不能永遠補得回來。 */
class PlantCheckTest {

    @Test
    fun todayCanBePlanted() {
        assertEquals(Go(daysAgo = 0), Planting.check(TODAY, SAID, NOW))
    }

    @Test
    fun yesterdayAndTheDayBeforeCanBeBackfilled() {
        // daysAgo 交給 prompt：隔了一天還說「今天辛苦了」會很假，樹得知道時間差
        assertEquals(Go(daysAgo = 1), Planting.check(TODAY.minusDays(1), SAID, NOW))
        assertEquals(Go(daysAgo = 2), Planting.check(TODAY.minusDays(2), SAID, NOW))
    }

    @Test
    fun expiredDayIsLeftAlone() {
        assertEquals(Expired, Planting.check(TODAY.minusDays(3), SAID, NOW))
        assertTrue(Expired.message.contains("走遠"))
    }

    @Test
    fun cutoverDecidesWhichDayIsToday() {
        // 凌晨 3:59 還是昨天：前天仍在窗口內
        val lateNight = taipei(2026, 7, 27, 3, 59)
        assertEquals(Go(daysAgo = 2), Planting.check(LocalDate.of(2026, 7, 24), SAID, lateNight))
        assertEquals(Expired, Planting.check(LocalDate.of(2026, 7, 24), SAID, lateNight.plusMinutes(1)))
    }

    @Test
    fun futureDayRejected() {
        assertEquals(NotYet, Planting.check(TODAY.plusDays(1), null, NOW))
    }

    @Test
    fun dayWithoutMessagesIsNotPlantable() {
        assertEquals(NothingSaid(isToday = true), Planting.check(TODAY, null, NOW))
        assertEquals(NothingSaid(isToday = false), Planting.check(TODAY.minusDays(1), null, NOW))
        // 全部收回的那天也一樣
        val retracted = DayState(DayStatus.COLLECTING, hasMessages = false)
        assertEquals(NothingSaid(isToday = true), Planting.check(TODAY, retracted, NOW))
    }

    @Test
    fun alreadyPlantedDayRejected() {
        val planted = DayState(DayStatus.PLANTED, hasMessages = true)
        assertEquals(AlreadyPlanted(isToday = true), Planting.check(TODAY, planted, NOW))
        assertEquals(AlreadyPlanted(isToday = false), Planting.check(TODAY.minusDays(1), planted, NOW))
    }

    @Test
    fun duplicatePlantBlockedWhilePlanting() {
        val planting = DayState(DayStatus.PLANTING, hasMessages = true, plantingStartedAt = NOW.minusSeconds(299))
        assertEquals(Growing, Planting.check(TODAY, planting, NOW))
    }

    /** 卡在 planting 超過 5 分鐘視為失敗，允許重按，否則那天會永遠種不下去。 */
    @Test
    fun stalePlantingCanReplant() {
        for (seconds in listOf(300L, 320L)) {
            val stuck = DayState(DayStatus.PLANTING, hasMessages = true, plantingStartedAt = NOW.minusSeconds(seconds))
            assertEquals("卡了 $seconds 秒", Go(daysAgo = 0), Planting.check(TODAY, stuck, NOW))
        }
        val noStartTime = DayState(DayStatus.PLANTING, hasMessages = true)
        assertEquals(Go(daysAgo = 0), Planting.check(TODAY, noStartTime, NOW))
    }

    /**
     * 東京按下種樹、飛回台北：牆上時鐘倒退一小時，實際只過了 6 分鐘。
     * 照牆上時鐘算會變成「還沒開始」，那天就卡在 planting 一個多小時。
     */
    @Test
    fun stalenessFollowsRealElapsedTimeAcrossTimeZones() {
        val tokyo = ZoneOffset.ofHours(9)
        val startedInTokyo = OffsetDateTime.of(2026, 7, 24, 12, 0, 0, 0, tokyo)
        val stuck = DayState(DayStatus.PLANTING, hasMessages = true, plantingStartedAt = startedInTokyo)
        val inTaipei = taipei(2026, 7, 24, 11, 6)
        assertEquals(Go(daysAgo = 0), Planting.check(TODAY, stuck, inTaipei))
        assertEquals(Growing, Planting.check(TODAY, stuck, inTaipei.minusMinutes(2)))
    }

    /**
     * 週報只生成已結束的週且永久快取。窗口一旦跨週，就會出現
     * 「森林上有那棵樹、週報裡沒有那一天」的不一致。
     */
    @Test
    fun windowStaysShorterThanAWeek() {
        assertTrue(Planting.BACKFILL_WINDOW_DAYS < 7)
    }
}

/** 詳情頁的「現在還能不能種」。 */
class CanPlantTest {

    @Test
    fun markedOnlyInsideTheWindow() {
        assertTrue(Planting.canPlant(TODAY, SAID, TODAY))
        assertTrue(Planting.canPlant(TODAY, SAID, TODAY.plusDays(2)))
        assertFalse(Planting.canPlant(TODAY, SAID, TODAY.plusDays(3)))
    }

    @Test
    fun plantedOrSilentDayIsNotOffered() {
        assertFalse(Planting.canPlant(TODAY, DayState(DayStatus.PLANTED, hasMessages = true), TODAY))
        assertFalse(Planting.canPlant(TODAY, DayState(DayStatus.COLLECTING, hasMessages = false), TODAY))
        assertFalse(Planting.canPlant(TODAY, null, TODAY))
    }
}

/** 首頁的補種邀請：前一兩天留了話沒收尾，遞一個安靜的入口，不自動種。 */
class BackfillCandidateTest {

    @Test
    fun noCandidateOnAQuietStart() {
        assertNull(Planting.backfillCandidate(TODAY, emptyMap()))
    }

    @Test
    fun todayItselfIsNotACandidate() {
        assertNull(Planting.backfillCandidate(TODAY, mapOf(TODAY to SAID)))
    }

    @Test
    fun yesterdayWithMessagesIsOffered() {
        val yesterday = TODAY.minusDays(1)
        assertEquals(yesterday, Planting.backfillCandidate(TODAY, mapOf(yesterday to SAID)))
    }

    /** 一次只遞一天（多天並列就成了待辦清單），先遞快過期的那天，種完之後下一天自然浮上來。 */
    @Test
    fun oldestFirstSoNothingExpiresUnseen() {
        val days = mapOf(TODAY.minusDays(1) to SAID, TODAY.minusDays(2) to SAID)
        assertEquals(TODAY.minusDays(2), Planting.backfillCandidate(TODAY, days))
    }

    /** 走遠的日子連邀請都不遞——邀請一個補不回來的天只是折磨。 */
    @Test
    fun expiredDayIsNotOffered() {
        assertNull(Planting.backfillCandidate(TODAY, mapOf(TODAY.minusDays(3) to SAID)))
    }

    @Test
    fun plantedDayIsNotOffered() {
        val planted = DayState(DayStatus.PLANTED, hasMessages = true)
        assertNull(Planting.backfillCandidate(TODAY, mapOf(TODAY.minusDays(1) to planted)))
    }

    @Test
    fun dayWithOnlyRetractedMessagesIsNotOffered() {
        val retracted = DayState(DayStatus.COLLECTING, hasMessages = false)
        assertNull(Planting.backfillCandidate(TODAY, mapOf(TODAY.minusDays(1) to retracted)))
    }
}

/** 種樹時帶進 prompt 的近期日記。 */
class RecentDiariesTest {

    private fun diary(daysBefore: Long, text: String = "${TODAY.minusDays(daysBefore)} 的日記") =
        PlantedDiary(TODAY.minusDays(daysBefore), Emotion.CALM, text)

    /** 「近期」要對得起這兩個字——超過窗口的日記不注入。 */
    @Test
    fun onlyTheLastSevenDays() {
        assertEquals(listOf(diary(7)), Planting.recentDiaries(TODAY, listOf(diary(8), diary(7))))
    }

    @Test
    fun latestThreeOldestFirst() {
        val planted = listOf(diary(1), diary(5), diary(2), diary(6), diary(4))
        assertEquals(listOf(diary(4), diary(2), diary(1)), Planting.recentDiaries(TODAY, planted))
    }

    /** 補種以「那一天」為基準往回看，樹不能引用未來的日記。 */
    @Test
    fun backfillDoesNotSeeFutureDiaries() {
        val target = TODAY.minusDays(1)
        assertEquals(emptyList<PlantedDiary>(), Planting.recentDiaries(target, listOf(diary(0), diary(1))))
    }

    @Test
    fun longDiaryCutAtCharLimit() {
        val long = "🌳".repeat(Planting.RECENT_DIARY_CHARS + 5)
        val cut = Planting.recentDiaries(TODAY, listOf(diary(1, long))).single().diary
        assertEquals("🌳".repeat(Planting.RECENT_DIARY_CHARS), cut)
    }
}
