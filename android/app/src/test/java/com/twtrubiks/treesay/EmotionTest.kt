package com.twtrubiks.treesay

import org.junit.Assert.assertEquals
import org.junit.Test

class EmotionTest {

    /** 順序跟網頁版 `prompts.EMOTION_CHOICES` 一樣，JSON schema 的 enum 照這個順序列。 */
    @Test
    fun sevenEmotionsInWebOrder() {
        assertEquals(
            listOf("happy", "calm", "excited", "tired", "sad", "anxious", "angry"),
            Emotion.entries.map { it.key },
        )
    }

    @Test
    fun validEmotionsKept() {
        for (emotion in Emotion.entries) {
            assertEquals(emotion, Emotion.normalize(emotion.key))
        }
    }

    @Test
    fun outOfEnumFallsBackToCalm() {
        for (bad in listOf("joyful", "", null, 123, "HAPPY")) {
            assertEquals("「$bad」", Emotion.CALM, Emotion.normalize(bad))
        }
    }
}
