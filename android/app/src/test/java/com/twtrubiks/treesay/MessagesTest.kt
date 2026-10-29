package com.twtrubiks.treesay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MessagesTest {
    private val today = LocalDate.of(2026, 7, 24)

    @Test
    fun emptyMessageRejected() {
        assertNotNull(Messages.sendRefusal("  ", hasPhoto = false, todayStatus = null))
        assertNotNull(Messages.sendRefusal("　", hasPhoto = false, todayStatus = null))
    }

    /** 拍了照不想打字也是一則訊息。 */
    @Test
    fun photoOnlyMessageAccepted() {
        assertNull(Messages.sendRefusal("", hasPhoto = true, todayStatus = DayStatus.COLLECTING))
    }

    @Test
    fun nothingNewAfterTodaysTreeIsPlanted() {
        assertEquals(
            "今天的樹已經種下了，新的想法留到明天吧 🌱",
            Messages.sendRefusal("還有一件事", hasPhoto = false, todayStatus = DayStatus.PLANTED),
        )
        assertEquals(
            "樹正在生長中，等它一下 🌱",
            Messages.sendRefusal("還有一件事", hasPhoto = false, todayStatus = DayStatus.PLANTING),
        )
    }

    /** 收回與修改共用同一條界線：今天、樹還沒收下。 */
    @Test
    fun editableOnlyTodayBeforePlanting() {
        assertTrue(Messages.isEditable(today, DayStatus.COLLECTING, today))
        // 種下之後日記就是從這些話長出來的，改素材會讓日記對不上
        assertFalse(Messages.isEditable(today, DayStatus.PLANTING, today))
        assertFalse(Messages.isEditable(today, DayStatus.PLANTED, today))
        // 昨天的話只能補種，不能再改
        assertFalse(Messages.isEditable(today.minusDays(1), DayStatus.COLLECTING, today))
    }

    /** 整句不想留的話用收回，不要留一則空泡泡在那裡。 */
    @Test
    fun editToEmptyRejected() {
        assertEquals("整句不想留的話，用收回就好", Messages.editRefusal("  ", hasPhoto = false))
        assertNull(Messages.editRefusal("今天好累", hasPhoto = false))
        // 照片還在，字可以清空
        assertNull(Messages.editRefusal("", hasPhoto = true))
    }
}
