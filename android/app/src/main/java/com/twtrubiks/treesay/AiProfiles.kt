package com.twtrubiks.treesay

/** 會呼叫 Claude 的用途，各自挑模型與 effort。[key] 跟網頁版 `ai.DEFAULT_PROFILES` 的 key 一樣。 */
enum class Purpose(val key: String, val label: String) {
    PLANT("plant", "種樹"),
    WEEKLY("weekly", "週報"),
}

data class AiModel(val id: String, val label: String)

data class AiProfile(val model: String, val effort: String)

/** 模型與 effort 的規則，跟網頁版 `ai.resolve_profile` 同一套。 */
object AiProfiles {
    /** 依強度排序。不開 max：日記是短文不是推理題，開到頂只是多花錢。 */
    val EFFORTS = listOf("low", "medium", "high", "xhigh")

    /** 網頁版 `ai.PRICES` 上的模型。只給選有價目的，之後的「AI 費用」頁才不會有算不到錢的呼叫。 */
    val MODELS = listOf(
        AiModel("claude-fable-5-1", "Fable 5.1"),
        AiModel("claude-opus-5-5", "Opus 5.5"),
        AiModel("claude-sonnet-5-5", "Sonnet 5.5"),
        AiModel("claude-haiku-5-5", "Haiku 5.5"),
    )

    /**
     * 每百萬 token 的美元價格（輸入, 輸出），照搬網頁版 `ai.PRICES`（官方定價頁，2026-10）。
     * 只用來估算「AI 費用」頁的數字，實際帳單以 Claude Console 為準。
     */
    val PRICES: Map<String, Pair<Double, Double>> = mapOf(
        "claude-fable-5-1" to (10.0 to 50.0),
        "claude-opus-5-5" to (4.0 to 20.0),
        "claude-sonnet-5-5" to (2.0 to 10.0),
        "claude-haiku-5-5" to (0.10 to 0.50),
    )

    /** 依價目表估算一次呼叫的美元費用；模型不在表上回 null（記成未計價，不偷偷當成 0 元）。 */
    fun estimateCost(model: String, inputTokens: Long, outputTokens: Long): Double? {
        val (input, output) = PRICES[model] ?: return null
        return (inputTokens * input + outputTokens * output) / 1_000_000
    }

    /**
     * 每一次呼叫都按 token 計費，不同用途各自挑檔次。
     * 種樹：日記是使用者每天真的會讀的東西，值得好模型；但它是一百多字的溫暖短文，effort 開到頂不會讓文字更暖。
     * 週報：把寫好的日記濃縮成幾個欄位，模型小一階就夠。
     */
    fun default(purpose: Purpose): AiProfile = when (purpose) {
        Purpose.PLANT -> AiProfile("claude-opus-5-5", "medium")
        Purpose.WEEKLY -> AiProfile("claude-sonnet-5-5", "medium")
    }

    /** 存下來的值不認得（舊版留下的、已下架的模型）就回到預設，不讓它變成看不懂的「樹睡著了」。 */
    fun resolve(purpose: Purpose, storedModel: String?, storedEffort: String?): AiProfile {
        val default = default(purpose)
        return AiProfile(
            model = storedModel?.takeIf { id -> MODELS.any { it.id == id } } ?: default.model,
            effort = storedEffort?.takeIf { it in EFFORTS } ?: default.effort,
        )
    }
}
