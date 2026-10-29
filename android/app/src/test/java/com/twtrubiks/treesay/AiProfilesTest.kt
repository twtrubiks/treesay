package com.twtrubiks.treesay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每次呼叫都按 token 計費，不同用途不該一律走最貴的檔次。照搬 test_ai.py 的 TestProfiles。 */
class AiProfilesTest {

    /** 預設值跟網頁版 `ai.DEFAULT_PROFILES` 一樣。 */
    @Test
    fun plantAndWeeklyUseDifferentDefaults() {
        assertEquals(AiProfile("claude-opus-5-5", "medium"), AiProfiles.default(Purpose.PLANT))
        assertEquals(AiProfile("claude-sonnet-5-5", "medium"), AiProfiles.default(Purpose.WEEKLY))
        assertNotEquals(AiProfiles.default(Purpose.PLANT), AiProfiles.default(Purpose.WEEKLY))
    }

    /** xhigh／max 寫死在日記生成上只是燒錢。 */
    @Test
    fun noDefaultPinnedToTopEffort() {
        for (purpose in Purpose.entries) {
            assertTrue(AiProfiles.default(purpose).effort !in listOf("xhigh", "max"))
        }
        assertTrue("max" !in AiProfiles.EFFORTS)
    }

    /** 預設的模型一定要在可選清單（有價目的模型）上，否則出廠就記不到錢。 */
    @Test
    fun defaultModelsAreSelectable() {
        for (purpose in Purpose.entries) {
            assertTrue(AiProfiles.MODELS.any { it.id == AiProfiles.default(purpose).model })
        }
    }

    @Test
    fun storedChoiceWins() {
        assertEquals(
            AiProfile("claude-haiku-5-5", "low"),
            AiProfiles.resolve(Purpose.PLANT, "claude-haiku-5-5", "low"),
        )
    }

    @Test
    fun nothingStoredMeansDefault() {
        assertEquals(AiProfiles.default(Purpose.WEEKLY), AiProfiles.resolve(Purpose.WEEKLY, null, null))
    }

    /** typo 或舊版留下的值原樣傳下去，只會變成使用者查不出原因的「樹睡著了」。 */
    @Test
    fun unknownValuesFallBackToDefault() {
        val default = AiProfiles.default(Purpose.PLANT)
        assertEquals(default, AiProfiles.resolve(Purpose.PLANT, "claude-opus-9", "hgih"))
        assertEquals(AiProfile(default.model, "high"), AiProfiles.resolve(Purpose.PLANT, "claude-opus-9", "high"))
        assertEquals(AiProfile("claude-haiku-5-5", default.effort), AiProfiles.resolve(Purpose.PLANT, "claude-haiku-5-5", "max"))
    }

    /** 可選的模型都要有價目，「AI 費用」頁才不會有算不到錢的呼叫。 */
    @Test
    fun everySelectableModelIsPriced() {
        for (model in AiProfiles.MODELS) {
            assertTrue(model.id, model.id in AiProfiles.PRICES)
        }
    }

    /** 照搬 test_ai.py 的 test_cost_follows_price_table：每百萬 token 的價格。 */
    @Test
    fun costFollowsPriceTable() {
        assertEquals(0.024, AiProfiles.estimateCost("claude-opus-5-5", 1_000, 1_000)!!, 1e-12)
        assertEquals(0.0006, AiProfiles.estimateCost("claude-haiku-5-5", 1_000, 1_000)!!, 1e-12)
        assertEquals(null, AiProfiles.estimateCost("claude-opus-9", 1_000, 1_000))
    }
}
