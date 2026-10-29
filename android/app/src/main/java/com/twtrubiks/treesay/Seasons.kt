package com.twtrubiks.treesay

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

/** 偶爾來訪的小東西：春天花瓣、夏天白日飛絮／夜裡螢火蟲、秋天落葉、冬天雪。[rise] 的往上飄，其餘往下落。 */
enum class Visitor(val rise: Boolean, val driftSeconds: Float) {
    PETAL(false, 4.2f),
    FLUFF(true, 6f),
    FIREFLY(true, 7f),
    LEAF(false, 4.8f),
    SNOW(false, 7f),
}

data class Sky(val season: Season, val night: Boolean)

/**
 * 樹活在同一個世界：樣貌隨真實的季節與時段走，跟網頁版 `season.ts` 同一套。
 * 只看時鐘，不看使用者的任何資料——這是環境感，不是狀態。
 * 永遠不要把訊息數、情緒、天數接進來：一接進去，樹的樣子就變成「今天的分數」。
 */
object Seasons {
    /** 北半球、以月份切：3～5 春、6～8 夏、9～11 秋、其餘冬。 */
    fun seasonFor(month: Int): Season = when (month) {
        in 3..5 -> Season.SPRING
        in 6..8 -> Season.SUMMER
        in 9..11 -> Season.AUTUMN
        else -> Season.WINTER
    }

    /** 入夜＝問候語進入「晚安」的時段，凌晨 4:00 切日前都算今晚。種樹按鈕亮起、樹入夜都是這一條線。 */
    fun isEvening(time: LocalTime): Boolean = time.hour >= 18 || time.hour < TreeDay.CUTOVER_HOUR

    fun skyAt(time: LocalDateTime): Sky = Sky(seasonFor(time.monthValue), isEvening(time.toLocalTime()))

    fun visitorFor(sky: Sky): Visitor = when (sky.season) {
        Season.SPRING -> Visitor.PETAL
        Season.SUMMER -> if (sky.night) Visitor.FIREFLY else Visitor.FLUFF
        Season.AUTUMN -> Visitor.LEAF
        Season.WINTER -> Visitor.SNOW
    }
}

/** 日期與時間的顯示格式，跟網頁版 `format.ts` 一樣。 */
object Format {
    private val WEEKDAYS = mapOf(
        DayOfWeek.SUNDAY to "日", DayOfWeek.MONDAY to "一", DayOfWeek.TUESDAY to "二", DayOfWeek.WEDNESDAY to "三",
        DayOfWeek.THURSDAY to "四", DayOfWeek.FRIDAY to "五", DayOfWeek.SATURDAY to "六",
    )

    fun weekday(date: LocalDate): String = WEEKDAYS.getValue(date.dayOfWeek)

    fun dateLabel(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日 星期${weekday(date)}"

    fun shortDate(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

    /** 說出口那一刻當地的時間，不換算成現在所在的時區。 */
    fun time(at: OffsetDateTime): String {
        val h = at.hour
        val mm = "%02d".format(at.minute)
        return if (h < 12) "上午 ${if (h == 0) 12 else h}:$mm" else "下午 ${if (h == 12) 12 else h - 12}:$mm"
    }

    fun greeting(time: LocalTime): String = when (time.hour) {
        in 4 until 11 -> "早安，今天想說些什麼？"
        in 11 until 18 -> "午安，今天過得怎麼樣？"
        else -> "晚安，今天辛苦了嗎？"
    }

    /** 一次種樹只要幾分錢，固定兩位小數會把大部分月份顯示成 US$0.00。 */
    fun usd(value: Double): String = when {
        value == 0.0 -> "US$0"
        value < 0.001 -> "不到 US$0.001"
        value < 1 -> "US$%.3f".format(value)
        else -> "US$%.2f".format(value)
    }
}
