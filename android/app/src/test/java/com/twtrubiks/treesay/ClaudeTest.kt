package com.twtrubiks.treesay

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

/**
 * 走真的 SDK，只把伺服器換成假的：請求長什麼樣、什麼時候重試、用量怎麼記。
 * 照搬 test_ai.py 的 TestAskRetry、TestRequest、TestApiKey、TestUsage。
 */
class ClaudeTest {
    private val server = MockWebServer()
    private val mapper = ObjectMapper()
    private val usage = mutableListOf<AiUsage>()
    private var key: String? = "sk-ant-test-key"
    private var profile = AiProfile("claude-opus-5-5", "medium")

    private val claude get() = Claude(
        apiKey = { key },
        profile = { profile },
        recordUsage = { usage += it },
        baseUrl = server.url("/").toString(),
        timeout = Duration.ofSeconds(2),
    )

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private val plantOutput =
        """{"diary":"今天去跑步。","emotion":"happy","tree_reply":"跑完很舒服吧。","memory":[],"keywords":["跑步"]}"""

    private fun reply(
        text: String = plantOutput,
        stopReason: String = "end_turn",
        input: Int = 1000,
        output: Int = 500,
        thinking: Boolean = false,
    ) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                mapper.createObjectNode().apply {
                    put("id", "msg_test")
                    put("type", "message")
                    put("role", "assistant")
                    put("model", profile.model)
                    putArray("content").apply {
                        // effort 開著時模型會先想一下：回應裡 text 前面多一個 thinking block（內容可能是空的，只有簽章）
                        if (thinking) {
                            addObject().put("type", "thinking").put("thinking", "").put("signature", "EqQBCkYIBxgC")
                            addObject().put("type", "redacted_thinking").put("data", "EmwKAhgBEgy3va3pzix")
                        }
                        addObject().put("type", "text").put("text", text)
                    }
                    put("stop_reason", stopReason)
                    putNull("stop_sequence")
                    putObject("usage").put("input_tokens", input).put("output_tokens", output)
                }.toString(),
            )

    private fun error(code: Int, type: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"type":"error","error":{"type":"$type","message":"test"}}""")

    private fun ask(images: List<AiImage> = emptyList(), purpose: Purpose = Purpose.PLANT) =
        runBlocking { claude.ask("把今天整理成日記", Prompts.PLANT_SCHEMA, images, purpose) }

    private fun body(): JsonNode = mapper.readTree(server.takeRequest().body.readUtf8())

    @Test
    fun validOutputIsParsed() {
        server.enqueue(reply())
        val data = ask()
        assertEquals("今天去跑步。", data["diary"].asText())
        assertEquals(1, server.requestCount)
    }

    /** 真的模型回應：text 前面有 thinking 與 redacted_thinking，只取 text。 */
    @Test
    fun thinkingBlocksBeforeTheTextAreSkipped() {
        server.enqueue(reply(thinking = true))
        assertEquals("今天去跑步。", ask()["diary"].asText())
        assertEquals(1, server.requestCount)
        assertEquals(1, usage.size)
    }

    /** 請求不帶任何工具；輸出格式由 schema 把關，effort 與模型照設定。 */
    @Test
    fun requestCarriesSchemaAndProfileButNoTools() {
        profile = AiProfile("claude-haiku-5-5", "low")
        server.enqueue(reply())
        ask()
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.requestUrl?.encodedPath)
        val sent = request.getHeader("x-api-key") ?: request.getHeader("Authorization")?.removePrefix("Bearer ")
        assertEquals("sk-ant-test-key", sent)
        val body = mapper.readTree(request.body.readUtf8())
        assertFalse(body.has("tools"))
        assertEquals("claude-haiku-5-5", body["model"].asText())
        assertEquals(Claude.MAX_TOKENS, body["max_tokens"].asLong())
        assertEquals("low", body["output_config"]["effort"].asText())
        assertEquals("json_schema", body["output_config"]["format"]["type"].asText())
        assertEquals(Prompts.PLANT_SCHEMA, body["output_config"]["format"]["schema"])
    }

    @Test
    fun textOnlyPromptIsOneBlock() {
        server.enqueue(reply())
        ask()
        val content = body()["messages"][0]["content"]
        assertEquals(1, content.size())
        assertEquals("把今天整理成日記", content[0]["text"].asText())
    }

    /** 照片放前面、各自帶編號標籤，prompt 裡用「附照片 N」對上。 */
    @Test
    fun imagesComeFirstWithNumberedLabels() {
        server.enqueue(reply())
        ask(listOf(AiImage(byteArrayOf(1, 2, 3), "image/jpeg"), AiImage(byteArrayOf(4), "image/png")))
        val content = body()["messages"][0]["content"]
        assertEquals(listOf("text", "image", "text", "image", "text"), content.map { it["type"].asText() })
        assertEquals("照片 1：", content[0]["text"].asText())
        assertEquals("image/jpeg", content[1]["source"]["media_type"].asText())
        assertEquals("AQID", content[1]["source"]["data"].asText())
        assertEquals("照片 2：", content[2]["text"].asText())
        assertEquals("image/png", content[3]["source"]["media_type"].asText())
        assertEquals("把今天整理成日記", content[4]["text"].asText())
    }

    @Test
    fun refusalRetriedOnceThenSuccess() {
        server.enqueue(reply(text = "", stopReason = "refusal"))
        server.enqueue(reply())
        assertEquals("happy", ask()["emotion"].asText())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun truncatedTwiceRaises() {
        repeat(2) { server.enqueue(reply(text = """{"diary":"今天""", stopReason = "max_tokens")) }
        assertThrows<AiError> { ask() }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun badJsonAndNonObjectAreFailures() {
        server.enqueue(reply(text = "這不是 JSON"))
        server.enqueue(reply(text = "[1, 2]"))
        assertThrows<AiError> { ask() }
        assertEquals(2, server.requestCount)
    }

    /** SDK 自己的重試關掉：一次 500 只會多一次，是我們自己的那一次。 */
    @Test
    fun apiErrorRetriedOnceAndSdkRetriesDisabled() {
        server.enqueue(error(500, "api_error"))
        server.enqueue(reply())
        ask()
        assertEquals(2, server.requestCount)

        repeat(2) { server.enqueue(error(529, "overloaded_error")) }
        assertThrows<AiError> { ask() }
        assertEquals(4, server.requestCount)
    }

    /** 逾時不重試：重試會把 planting 拖過逾期時間。 */
    @Test
    fun timeoutNotRetried() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertThrows<AiTimeout> { ask() }
        assertEquals(1, server.requestCount)
    }

    /** key 錯了重按也不會好，不重試，讓使用者知道要換 key。 */
    @Test
    fun invalidKeyNotRetried() {
        server.enqueue(error(401, "authentication_error"))
        assertThrows<AiKeyInvalid> { ask() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun missingKeyNeverReachesNetwork() {
        key = null
        assertThrows<AiKeyMissing> { ask() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unreachableIsTold() {
        val offline = Claude({ key }, { profile }, { usage += it }, baseUrl = server.url("/").toString())
        server.shutdown()
        assertThrows<AiUnreachable> {
            runBlocking { offline.ask("p", Prompts.PLANT_SCHEMA, emptyList(), Purpose.PLANT) }
        }
        assertTrue(usage.isEmpty())
    }

    /** 拒答、截斷而重試的回應一樣計費，每個回應都記一筆；沒拿到回應的不記。 */
    @Test
    fun everyResponseIsRecordedWithItsPurpose() {
        server.enqueue(error(500, "api_error"))
        server.enqueue(reply(text = "", stopReason = "refusal", input = 10, output = 2))
        server.enqueue(reply(input = 1200, output = 300))
        assertThrows<AiError> { ask() }
        assertEquals(listOf(AiUsage(Purpose.PLANT, "claude-opus-5-5", 10, 2)), usage)

        ask(purpose = Purpose.WEEKLY)
        assertEquals(AiUsage(Purpose.WEEKLY, "claude-opus-5-5", 1200, 300), usage.last())
    }

    /** 記帳出錯不能讓這次種樹作廢：錢已經花了、回覆也拿到了。 */
    @Test
    fun recordingFailureDoesNotLoseReply() {
        server.enqueue(reply())
        val broken = Claude(
            apiKey = { key },
            profile = { profile },
            recordUsage = { throw IllegalStateException("資料庫鎖住了") },
            baseUrl = server.url("/").toString(),
        )
        val data = runBlocking { broken.ask("p", Prompts.PLANT_SCHEMA, emptyList(), Purpose.PLANT) }
        assertTrue(data.has("diary"))
    }
}
