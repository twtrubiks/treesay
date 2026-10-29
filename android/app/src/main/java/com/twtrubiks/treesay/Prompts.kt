package com.twtrubiks.treesay

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/** 種樹 prompt 裡的一則訊息。[photo] 是照片編號（對上請求最前面的「照片 N」）；[photoUnseen] 是有照片但送不出去。 */
data class PromptMessage(
    val time: String,
    val content: String,
    val photo: Int? = null,
    val photoUnseen: Boolean = false,
)

/**
 * 所有 prompt 模板與 JSON schema，逐字照搬網頁版 `backend/prompts.py`。
 * `PromptsTest` 拿網頁版產生的對照檔比對，改這裡之前先改網頁版、重新產生對照檔
 * （`android/scripts/prompt-fixtures.py`）。
 *
 * 語氣總原則：溫暖、具體、不說教——「今天真的辛苦了」式的接住，不給「你應該早點睡」式的建議。
 */
object Prompts {
    private val EMOTIONS_TEXT = Emotion.entries.joinToString(" / ") { it.key }

    /** 輸出格式由 schema 把關，prompt 只需要管語言與標點。 */
    private const val TEXT_STYLE = "所有文字內容一律使用繁體中文，標點符號使用全形（，、。！？）。"

    private val json = JsonNodeFactory.instance

    /** structured outputs 要求每個欄位都列進 required、且不能有多餘欄位。不支援 maxItems／maxLength，別加。 */
    private fun objectSchema(properties: Map<String, JsonNode>): ObjectNode = json.objectNode().apply {
        put("type", "object")
        set<JsonNode>("properties", json.objectNode().apply { properties.forEach { (k, v) -> set<JsonNode>(k, v) } })
        set<JsonNode>("required", json.arrayNode().apply { properties.keys.forEach(::add) })
        put("additionalProperties", false)
    }

    private fun string(): ObjectNode = json.objectNode().put("type", "string")

    private fun stringList(): ObjectNode = json.objectNode().put("type", "array").also { it.set<JsonNode>("items", string()) }

    // 情緒的順序固定：它會進 JSON schema，schema 一變 API 就得重新編譯 grammar
    val PLANT_SCHEMA: ObjectNode = objectSchema(
        linkedMapOf(
            "diary" to string(),
            "emotion" to string().also { s ->
                s.set<JsonNode>("enum", json.arrayNode().apply { Emotion.entries.forEach { add(it.key) } })
            },
            "tree_reply" to string(),
            "memory" to stringList(),
            "keywords" to stringList(),
        ),
    )

    /** 週報的輸出格式。有記憶可整理時才多一個 memories 欄位，對上 [weeklyPrompt]。 */
    fun weeklySchema(withMemories: Boolean): ObjectNode = objectSchema(
        linkedMapOf<String, JsonNode>(
            "good_things" to stringList(),
            "bad_things" to stringList(),
            "keywords" to stringList(),
            "advice" to string(),
        ).apply { if (withMemories) put("memories", stringList()) },
    )

    /** 把「幾天前」講成人話。補種時整份 prompt 都用它稱呼那一天。 */
    fun dayWord(daysAgo: Long): String = when (daysAgo) {
        0L -> "今天"
        1L -> "昨天"
        2L -> "前天"
        else -> "$daysAgo 天前"
    }

    private fun diaryEntries(diaries: List<PlantedDiary>): String =
        diaries.joinToString("\n\n") { "【${it.date}（${it.emotion.key}）】\n${it.diary}" }

    /**
     * 樹帶著記憶傾聽：長期記得的事＋最近幾天的日記。兩者皆空就回空字串。
     * 界線文字是這段的命脈——拿掉它，記憶就會從「更懂你」變成「查你的帳」。
     */
    fun memoryBlock(memories: List<String>, recentDiaries: List<PlantedDiary>, daysAgo: Long = 0): String {
        if (memories.isEmpty() && recentDiaries.isEmpty()) return ""
        val whenWord = dayWord(daysAgo)
        val parts = mutableListOf<String>()
        if (memories.isNotEmpty()) {
            val facts = memories.joinToString("\n") { "- $it" }
            parts += "你們已經認識一段時間了，你一直記得這些關於使用者的事：\n\n$facts"
        }
        if (recentDiaries.isNotEmpty()) {
            parts += "這是使用者最近種下的日記：\n\n${diaryEntries(recentDiaries)}"
        }
        val body = parts.joinToString("\n\n")
        return """
$body

這些記憶只是幫你更懂使用者的背景，有幾條界線：
- 只用來理解與關心，不追蹤進度、不對帳——他之前說想做的事後來做了沒有，不是你該問的。
- 不主動翻舊的難過；只有當${whenWord}的訊息自己先碰到相關的事，才自然接上。
- 記憶與${whenWord}的訊息矛盾時，以${whenWord}的訊息為準。
若${whenWord}的訊息與記得的事有呼應，tree_reply 可以自然流露「我記得」的連續性，不必刻意每次引用。
"""
    }

    /** 種樹：把當天碎片訊息整理成日記＋判斷情緒＋樹的回覆＋記憶＋關鍵詞。[daysAgo] 大於 0 是補種。 */
    fun plantPrompt(
        messages: List<PromptMessage>,
        question: String? = null,
        daysAgo: Long = 0,
        memories: List<String> = emptyList(),
        recentDiaries: List<PlantedDiary> = emptyList(),
    ): String {
        val whenWord = dayWord(daysAgo)
        val messagesText = messages.joinToString("\n") { m ->
            buildString {
                append("- [${m.time}] ${m.content}")
                if (m.photo != null && m.photo != 0) {
                    append("（附照片 ${m.photo}）")
                } else if (m.photoUnseen) {
                    append("（附了一張照片，但這次沒能帶給你看）")
                }
            }
        }
        val questionBlock = if (!question.isNullOrEmpty()) {
            "\n${whenWord}樹問過使用者：「$question」，若訊息中有回應這個問題，可自然融入日記。\n"
        } else {
            ""
        }
        // 補種：日記仍是那一天的第一人稱記錄，但樹的回覆得認得時間差——隔兩天還說「今天辛苦了」會很假
        val backfillBlock = if (daysAgo > 0) {
            "\n使用者${whenWord}沒有為那一天收尾，是現在才回來種下這棵樹。diary 仍以那一天當下的" +
                "第一人稱記錄，不要寫成「${whenWord}我如何」的回憶語氣；tree_reply 則要認得時間差，" +
                "別說成「今天辛苦了」，也不要責怪他沒有準時回來。\n"
        } else {
            ""
        }
        return """你是「樹」——一個不評價、全然接納的傾聽者。使用者${whenWord}像傳訊息一樣，把想法陸續丟給你。
${memoryBlock(memories, recentDiaries, daysAgo)}
以下是使用者${whenWord}丟給你的訊息（依時間排序）：

$messagesText
$questionBlock$backfillBlock
請完成五件事：

1. diary：以使用者的第一人稱「我」，把這些碎片整理成一篇通順的日記。保留使用者原本的語氣與用詞，不加油添醋、不虛構沒提到的事。訊息附的照片依編號放在最前面，請看圖，把畫面自然地編進日記裡；沒能帶給你看的照片不要猜測畫面。
2. emotion：從這七種裡選一個最貼近${whenWord}整體心情的：$EMOTIONS_TEXT。
3. tree_reply：以樹的身分寫一段溫暖的回覆（80～150 字）。要具體回應使用者${whenWord}提到的事，接住情緒就好——不說教、不給建議、不打分數。
4. memory：從${whenWord}的訊息裡，挑出值得長期記住、關於使用者本人的事（0～3 條，每條一句話、40 字以內）。只記會持續一段時間的事——進行中的事、重要的人、反覆出現的心情或期待；一次性的瑣事、當天就過去的情緒不記。已經在記得清單裡的不重複記。沒有值得記的就給空陣列。
5. keywords：從${whenWord}的訊息裡挑 2～4 個關鍵詞（用使用者自己的用詞，每個 2～8 個字），讓日後回望時想得起這一天。不是分類標籤、不是評價，沒有合適的就給空陣列。

$TEXT_STYLE"""
    }

    /**
     * 週報：像朋友幫你整理這一週。有記憶的話請 AI 順手整理（合併重複、放下過時的）——
     * 記憶清單只在這裡瘦身，種樹時只進不出。
     */
    fun weeklyPrompt(diaries: List<PlantedDiary>, memories: List<String> = emptyList()): String {
        var memorySection = ""
        var memoryItem = ""
        if (memories.isNotEmpty()) {
            val facts = memories.joinToString("\n") { "- $it" }
            memorySection = "\n樹一路記著這些關於使用者的事：\n\n$facts\n"
            memoryItem = "\n5. memories：幫樹整理上面記得的清單——合併重複的、放下已經過時或" +
                "只是一次性的，保留仍然重要的，回傳整理後的完整清單（每條一句話）。" +
                "只能整理，不能新增清單裡沒有的事。"
        }
        return """以下是使用者這一週種下的日記：

${diaryEntries(diaries)}
$memorySection
請像一個溫柔的朋友，幫使用者整理這一週：

1. good_things：這週發生的好事（2～4 條，具體、取自日記內容）。
2. bad_things：這週辛苦或不順的事（1～3 條，語氣溫柔、不放大）。
3. keywords：這週的關鍵字（3～5 個詞）。
4. advice：給下週的一句話（一句就好，像朋友的暖心叮嚀，不說教、不列清單）。$memoryItem

$TEXT_STYLE"""
    }
}
