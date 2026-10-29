package com.twtrubiks.treesay

import com.twtrubiks.treesay.PlantVerdict.AlreadyPlanted
import com.twtrubiks.treesay.PlantVerdict.Expired
import com.twtrubiks.treesay.PlantVerdict.Go
import com.twtrubiks.treesay.PlantVerdict.Growing
import com.twtrubiks.treesay.PlantVerdict.NotYet
import com.twtrubiks.treesay.PlantVerdict.NothingSaid
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** 一天的狀態：白天收集訊息，按下種樹進入 planting，長成之後是 planted。 */
enum class DayStatus { COLLECTING, PLANTING, PLANTED }

/**
 * 判斷那一天能不能種樹要知道的事。[hasMessages] 只算還留著的訊息，收回的不算。
 * [plantingStartedAt] 帶著時差：按下種樹之後搭飛機換了時區，逾期照實際經過的時間算。
 */
data class DayState(
    val status: DayStatus,
    val hasMessages: Boolean,
    val plantingStartedAt: OffsetDateTime? = null,
)

/** 已經種下的一篇日記，種樹時當作近期日記帶進 prompt。 */
data class PlantedDiary(val date: LocalDate, val emotion: Emotion, val diary: String)

/** 按下種樹之後的判斷。不能種的時候，[Refused.message] 直接給使用者看。 */
sealed interface PlantVerdict {
    /** 可以種。[daysAgo] 大於 0 是補種：樹的回覆要認得時間差，但不能責怪使用者沒準時回來。 */
    data class Go(val daysAgo: Long) : PlantVerdict

    sealed class Refused(val message: String) : PlantVerdict

    data object NotYet : Refused("那一天還沒到呢")

    data object Expired : Refused("這一天已經走遠了，就讓它安靜地留著吧 🍂")

    data class NothingSaid(val isToday: Boolean) :
        Refused(if (isToday) "今天還沒有想法丟給樹" else "這一天沒有留下什麼話")

    data class AlreadyPlanted(val isToday: Boolean) :
        Refused(if (isToday) "今天的樹已經種下了 🌳" else "這一天的樹已經種下了 🌳")

    data object Growing : Refused("樹正在生長中 🌱")
}

/** 種樹的規則，跟網頁版 `main.py` 的 plant_day、can_plant_day、backfill_candidate 同一套。 */
object Planting {
    /**
     * 今天之外還能回頭補種的天數。窗口刻意留短：任何一天都補得回來的話，森林就從「發生過的紀錄」
     * 變成可以填滿的成果，空格成了待辦事項。而且必須短於一週，否則補種會晚於永久快取的週報，
     * 那天就永遠不在週報裡。
     */
    const val BACKFILL_WINDOW_DAYS = 2L

    /** 卡在 planting 超過這麼久就視為失敗、允許重按，否則那天會永遠種不下去。 */
    val STALE_AFTER: Duration = Duration.ofMinutes(5)

    /**
     * 近期日記：目標日往前這幾天內、最多這幾篇、每篇最多這幾個字。
     * 窗口刻意小——一個月前的日記突然被翻出來不是連續性，是驚嚇。
     */
    const val RECENT_DIARY_DAYS = 7L
    const val RECENT_DIARY_LIMIT = 3
    const val RECENT_DIARY_CHARS = 300

    /** 種下 [date] 那天的樹之前的檢查。今天是哪天，照 [now] 所在時區的凌晨 4:00 切。 */
    fun check(date: LocalDate, day: DayState?, now: OffsetDateTime): PlantVerdict {
        val daysAgo = ChronoUnit.DAYS.between(date, TreeDay.effectiveDate(now.toLocalDateTime()))
        val isToday = daysAgo == 0L
        return when {
            daysAgo < 0 -> NotYet
            daysAgo > BACKFILL_WINDOW_DAYS -> Expired
            day == null || !day.hasMessages -> NothingSaid(isToday)
            day.status == DayStatus.PLANTED -> AlreadyPlanted(isToday)
            day.status == DayStatus.PLANTING && day.plantingStartedAt != null &&
                Duration.between(day.plantingStartedAt, now) < STALE_AFTER -> Growing
            else -> Go(daysAgo)
        }
    }

    /** 那一天現在還能不能種——今天隨時可以，過去的只在補種窗口內可以。 */
    fun canPlant(date: LocalDate, day: DayState?, today: LocalDate): Boolean {
        if (day == null || day.status != DayStatus.COLLECTING || !day.hasMessages) return false
        return ChronoUnit.DAYS.between(date, today) in 0..BACKFILL_WINDOW_DAYS
    }

    /**
     * 窗口內留了話卻沒收尾、最早的那一天，給首頁遞補種邀請；沒有就回 null，那行字整個不出現。
     * 一次只遞一天，多天並列就成了待辦清單。挑最早的，因為它最先過期，種下之後下一天自然浮上來。
     */
    fun backfillCandidate(today: LocalDate, days: Map<LocalDate, DayState>): LocalDate? =
        days.filter { (date, day) -> date < today && canPlant(date, day, today) }.keys.minOrNull()

    /** 種 [date] 那天時帶進 prompt 的近期日記，由舊到新。補種以那一天為基準往回看，不能引用未來的日記。 */
    fun recentDiaries(date: LocalDate, planted: List<PlantedDiary>): List<PlantedDiary> =
        planted.filter { it.date < date && it.date >= date.minusDays(RECENT_DIARY_DAYS) }
            .sortedByDescending { it.date }
            .take(RECENT_DIARY_LIMIT)
            .reversed()
            .map { it.copy(diary = it.diary.takeCodePoints(RECENT_DIARY_CHARS)) }
}

/** 照 Python 字串切片的算法取前 [n] 個字：emoji 不會被切成半個。 */
private fun String.takeCodePoints(n: Int): String =
    if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))
