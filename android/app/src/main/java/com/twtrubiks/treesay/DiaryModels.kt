package com.twtrubiks.treesay

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

/** 給使用者看的拒絕或失敗，[message] 直接顯示。[kind] 讓畫面決定要不要帶人去輸入 key。 */
class TreeSayException(message: String, val kind: Kind = Kind.REFUSED) : Exception(message) {
    enum class Kind { REFUSED, NOT_FOUND, KEY_MISSING, KEY_INVALID, ASLEEP }
}

/**
 * 一則訊息。[createdAt] 帶著說出口那一刻的時差：在東京晚上 11 點說的話，回到台北看也是晚上 11 點。
 * [photo] 是照片在 photos 資料夾裡的相對路徑（`日期/檔名`），跟網頁版的 `photo_path` 同一種寫法。
 */
data class MessageView(val id: String, val content: String, val photo: String?, val createdAt: OffsetDateTime)

/** 一天。[question] 是那天樹問過的題目（Day 誕生時蓋章存下，不隨題庫位移）。 */
data class DayView(
    val date: LocalDate,
    val status: DayStatus,
    val question: String,
    val diary: String?,
    val emotion: Emotion?,
    val treeReply: String?,
    // 回望的線索，先存不顯示；永遠不拿它做次數統計或標籤雲
    val keywords: List<String>,
    val plantedAt: OffsetDateTime?,
    val messages: List<MessageView>,
    // 那一天現在還能不能種（今天隨時可以，過去的只在補種窗口內）
    val canPlant: Boolean,
)

/** 首頁要的東西。[backfillCandidate] 是窗口內留了話卻沒收尾、最早的那一天，沒有就是 null。 */
data class TodayView(val day: DayView, val backfillCandidate: LocalDate?)

/** 森林月曆的一格。未種下的日子沒有情緒，只有那天留下的話。 */
data class ForestDay(val date: LocalDate, val emotion: Emotion?, val status: DayStatus)

/** 樹記得的一件事。[sourceDate] 是樹記下它的那天。 */
data class MemoryView(val id: String, val content: String, val sourceDate: LocalDate)

data class WeeklyReport(
    val goodThings: List<String>,
    val badThings: List<String>,
    val keywords: List<String>,
    val advice: String,
)

/** 週報：還沒過完的週在生長中、沒種樹的週是安靜的土壤，其餘生成一次後永久快取。 */
sealed interface WeeklyResult {
    data object Growing : WeeklyResult

    data object Empty : WeeklyResult

    data class Ready(val report: WeeklyReport, val generatedAt: OffsetDateTime) : WeeklyResult
}

/** AI 費用只按月加總，不拆到天——每天花多少跟那天說了多少話直接相關，拆到天就是另一種「今天的數字」。 */
data class UsageMonth(
    val month: YearMonth,
    val totalUsd: Double,
    val plantUsd: Double,
    val weeklyUsd: Double,
    // 模型不在價目表上的呼叫，算不出錢，只能說有這幾次
    val unpricedCalls: Int,
)

data class UsageSummary(val months: List<UsageMonth>, val totalUsd: Double)

/** 要跟訊息一起存的照片。[extension] 含點，例如 `.jpg`。 */
class NewPhoto(val bytes: ByteArray, val extension: String)

/** 匯入的結果，給使用者看加進了多少、略過了多少。 */
data class ImportResult(
    val backup: String?,
    val daysAdded: Int,
    val daysSkipped: Int,
    val reportsAdded: Int,
    val reportsSkipped: Int,
    val memoriesAdded: Int,
    val memoriesSkipped: Int,
    val photosMissing: Int,
)
