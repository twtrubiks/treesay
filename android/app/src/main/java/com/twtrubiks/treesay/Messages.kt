package com.twtrubiks.treesay

import java.time.LocalDate

/** 訊息的界線，跟網頁版 `main.py` 的 create_message、editable_message、update_message 同一套。 */
object Messages {
    /** 新訊息送不送得出去：可以送回 null，不行就回給使用者看的話。內容存之前要先 trim。 */
    fun sendRefusal(content: String, hasPhoto: Boolean, todayStatus: DayStatus?): String? = when {
        content.isBlank() && !hasPhoto -> "想說什麼都可以，但訊息是空的喔"
        todayStatus == DayStatus.PLANTED -> "今天的樹已經種下了，新的想法留到明天吧 🌱"
        todayStatus == DayStatus.PLANTING -> "樹正在生長中，等它一下 🌱"
        else -> null
    }

    /**
     * 能不能修改或收回。兩者共用同一條界線：今天、樹還沒收下——種下之後日記是從這些話長出來的，
     * 事後改素材會讓日記對不上，錯字也是那天的一部分。不記錄改過的痕跡，時間留在說出口的那一刻。
     */
    fun isEditable(messageDate: LocalDate, dayStatus: DayStatus, today: LocalDate): Boolean =
        messageDate == today && dayStatus == DayStatus.COLLECTING

    /** 改完的內容能不能存：整句清空又沒照片的話，請使用者改用收回。 */
    fun editRefusal(newContent: String, hasPhoto: Boolean): String? =
        if (newContent.isBlank() && !hasPhoto) "整句不想留的話，用收回就好" else null
}
