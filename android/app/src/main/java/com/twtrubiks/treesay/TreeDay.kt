package com.twtrubiks.treesay

import java.time.LocalDate
import java.time.LocalDateTime

/** 切日：凌晨 4:00 之前的訊息歸前一天，跟網頁版 `backend/models.py` 的 `effective_date` 同一條規則。 */
object TreeDay {
    const val CUTOVER_HOUR = 4L

    /** [time] 是當地時間；人在哪個時區，就照那裡的凌晨 4:00 切。 */
    fun effectiveDate(time: LocalDateTime): LocalDate = time.minusHours(CUTOVER_HOUR).toLocalDate()
}
