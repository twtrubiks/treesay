package com.twtrubiks.treesay

/** 情緒固定 7 種，順序跟網頁版 `prompts.EMOTION_CHOICES` 一樣。資料庫與 AI 之間用 [key] 溝通，[label] 給人看。 */
enum class Emotion(val key: String, val label: String) {
    HAPPY("happy", "開心"),
    CALM("calm", "平靜"),
    EXCITED("excited", "期待"),
    TIRED("tired", "疲憊"),
    SAD("sad", "難過"),
    ANXIOUS("anxious", "不安"),
    ANGRY("angry", "生氣");

    companion object {
        val DEFAULT = CALM

        /** 枚舉外的值（包括大小寫不同、不是字串）一律 fallback 為 calm。 */
        fun normalize(value: Any?): Emotion = entries.firstOrNull { it.key == value } ?: DEFAULT
    }
}
