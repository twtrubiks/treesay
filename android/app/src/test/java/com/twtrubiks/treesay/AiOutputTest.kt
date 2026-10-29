package com.twtrubiks.treesay

import org.junit.Assert.assertEquals
import org.junit.Test

/** 記憶會被永久保存並注入之後每次種樹，寬進嚴出。 */
class NormalizeMemoriesTest {

    @Test
    fun nonListReturnsEmpty() {
        for (bad in listOf(null, "一句話", mapOf("a" to 1), 123)) {
            assertEquals("「$bad」", emptyList<String>(), AiOutput.normalizeMemories(bad, 3))
        }
    }

    @Test
    fun stripsAndDropsEmpty() {
        assertEquals(listOf("在做樹說"), AiOutput.normalizeMemories(listOf("  在做樹說  ", "", "   "), 3))
    }

    @Test
    fun fullWidthSpaceStrippedLikePython() {
        assertEquals(listOf("在做樹說"), AiOutput.normalizeMemories(listOf("　在做樹說　", "　"), 3))
    }

    @Test
    fun nonStringItemsSkipped() {
        assertEquals(listOf("養了一隻貓"), AiOutput.normalizeMemories(listOf(1, null, "養了一隻貓", listOf("巢狀")), 3))
    }

    @Test
    fun tooLongItemDropped() {
        val long = "很長".repeat(100)
        assertEquals(listOf("短短的"), AiOutput.normalizeMemories(listOf(long, "短短的"), 3))
    }

    @Test
    fun dedupesAndCapsAtLimit() {
        val items = listOf("一樣的", "一樣的", "第二件", "第三件", "第四件")
        assertEquals(listOf("一樣的", "第二件", "第三件"), AiOutput.normalizeMemories(items, 3))
    }
}

/** 關鍵詞欄位壞掉就當沒有，不能拖垮種樹。 */
class NormalizeKeywordsTest {

    @Test
    fun nonListReturnsEmpty() {
        for (bad in listOf(null, "一個詞", mapOf("a" to 1), 123)) {
            assertEquals("「$bad」", emptyList<String>(), AiOutput.normalizeKeywords(bad, 4))
        }
    }

    @Test
    fun cleansDedupesAndCaps() {
        val items = listOf("  種樹  ", "", 7, "種樹", "散步", "晚餐", "貓", "多的")
        assertEquals(listOf("種樹", "散步", "晚餐", "貓"), AiOutput.normalizeKeywords(items, 4))
    }

    @Test
    fun sentenceLengthItemDropped() {
        val long = "這不是一個詞而是一整句話所以應該被丟掉才對吧"
        assertEquals(listOf("散步"), AiOutput.normalizeKeywords(listOf(long, "散步"), 4))
    }

    /** 字數照網頁版 Python 的 len 算：emoji 是一個字，不是 Kotlin String.length 的兩個。 */
    @Test
    fun emojiCountsAsOneCharLikePython() {
        val atLimit = "🌳".repeat(AiOutput.KEYWORD_ITEM_MAX_CHARS)
        val overLimit = atLimit + "🌳"
        assertEquals(listOf(atLimit), AiOutput.normalizeKeywords(listOf(atLimit, overLimit), 4))
    }
}
