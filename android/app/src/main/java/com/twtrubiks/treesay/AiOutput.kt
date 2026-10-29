package com.twtrubiks.treesay

/**
 * 把 AI 回的字串清單欄位整理乾淨，跟網頁版 `ai.py` 的 normalize_* 同一套規則：寬進嚴出。
 * 不是清單就當沒有，非字串、空白、過長、重複的條目一律略過——欄位壞掉不能拖垮種樹。
 * 條數與字數上限不能寫進 JSON schema（structured outputs 不支援），只能在落盤前清洗。
 */
object AiOutput {
    /** 單條記憶的長度上限。超過的多半是整段日記而非「一件事」，存進去會灌爆之後每次種樹的 prompt。 */
    const val MEMORY_ITEM_MAX_CHARS = 120

    /** 單個關鍵詞的長度上限。超過的是句子不是詞。 */
    const val KEYWORD_ITEM_MAX_CHARS = 20

    fun normalizeMemories(value: Any?, limit: Int): List<String> = cleanStrList(value, limit, MEMORY_ITEM_MAX_CHARS)

    fun normalizeKeywords(value: Any?, limit: Int): List<String> = cleanStrList(value, limit, KEYWORD_ITEM_MAX_CHARS)

    private fun cleanStrList(value: Any?, limit: Int, maxChars: Int): List<String> {
        if (value !is List<*>) return emptyList()
        val out = mutableListOf<String>()
        for (item in value) {
            if (item !is String) continue
            val text = item.trim()
            // 字數照 Python 的 len 算 code point：emoji 是一個字，不是 String.length 的兩個
            if (text.isEmpty() || text.codePointCount(0, text.length) > maxChars || text in out) continue
            out += text
            if (out.size >= limit) break
        }
        return out
    }
}
