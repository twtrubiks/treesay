package com.twtrubiks.treesay

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 走真的 SDK，只把伺服器換成假的：確認 key 的對錯、伺服器出事、網路不通各自分得出來。 */
class ApiKeyCheckTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun check() = ApiKeyCheck.check("sk-ant-test-key", baseUrl = server.url("/").toString())

    private fun error(code: Int, type: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"type":"error","error":{"type":"$type","message":"test"}}""")

    @Test
    fun workingKeyOpensTheDoor() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"data":[{"id":"claude-opus-5-5","type":"model","display_name":"Claude Opus 5.5",""" +
                        """"created_at":"2026-01-01T00:00:00Z"}],"has_more":false,""" +
                        """"first_id":"claude-opus-5-5","last_id":"claude-opus-5-5"}""",
                ),
        )
        assertEquals(KeyCheck.Ok, check())

        // 列模型不耗 token；送出的是明確交給 SDK 的那一把（SDK 3.x 放在 Authorization: Bearer）
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.requestUrl?.encodedPath)
        val sent = request.getHeader("x-api-key") ?: request.getHeader("Authorization")?.removePrefix("Bearer ")
        assertEquals("sk-ant-test-key", sent)
    }

    @Test
    fun wrongKeyIsRejected() {
        server.enqueue(error(401, "authentication_error"))
        assertEquals(KeyCheck.Rejected, check())
    }

    @Test
    fun forbiddenKeyIsRejected() {
        server.enqueue(error(403, "permission_error"))
        assertEquals(KeyCheck.Rejected, check())
    }

    /** 限流、伺服器出事：不是 key 的問題，請使用者等一下。SDK 自己不重試，一次就回。 */
    @Test
    fun serverTroubleMeansTheTreeIsAsleep() {
        for ((code, type) in listOf(429 to "rate_limit_error", 500 to "api_error", 529 to "overloaded_error")) {
            server.enqueue(error(code, type))
            val before = server.requestCount
            assertEquals("HTTP $code", KeyCheck.TreeAsleep, check())
            assertEquals("HTTP $code 只打一次", before + 1, server.requestCount)
        }
    }

    @Test
    fun noNetworkMeansUnreachable() {
        val url = server.url("/").toString()
        server.shutdown()
        assertEquals(KeyCheck.Unreachable, ApiKeyCheck.check("sk-ant-test-key", baseUrl = url))
    }
}
