package com.twtrubiks.treesay

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.InterruptedIOException
import java.time.Duration
import java.util.Base64

/** 要送給樹看的一張照片，已經縮好、轉正（見 [Photos.prepare]）。 */
class AiImage(val bytes: ByteArray, val mediaType: String)

/** 一個 API 回應的用量。拒答、截斷而要重試的回應一樣計費，拿到回應就記一筆。 */
data class AiUsage(val purpose: Purpose, val model: String, val inputTokens: Long, val outputTokens: Long)

/** AI 呼叫失敗（API 錯誤或輸出不可用）。給使用者看的話由呼叫端決定，不裸露技術錯誤。 */
open class AiError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 逾時：不重試，否則會把 planting 拖過逾期時間。 */
class AiTimeout(cause: Throwable) : AiError("Claude API 逾時", cause)

/** 連不上網路。跟網頁版的連線錯誤一樣重試一次，但給使用者看的話不同：手機沒網路是常有的事。 */
class AiUnreachable(cause: Throwable) : AiError("連不上 Claude API", cause)

/** key 的問題——重按幾次也不會好，要讓使用者知道該去修什麼。不重試。 */
open class AiKeyError(message: String, cause: Throwable? = null) : AiError(message, cause)

class AiKeyMissing : AiKeyError("還沒有 API key")

class AiKeyInvalid(cause: Throwable) : AiKeyError("API key 被拒絕", cause)

/** 種樹與週報呼叫 Claude 的入口。測試換成假的，就不用真的打 API。 */
fun interface TreeAi {
    /** 回傳符合 [schema] 的 JSON 物件；失敗丟 [AiError]。照片放在 prompt 前面，各自帶「照片 N」編號。 */
    suspend fun ask(prompt: String, schema: ObjectNode, images: List<AiImage>, purpose: Purpose): ObjectNode
}

/**
 * Claude API 封裝，照搬網頁版 `ai.py`：Messages API ＋ structured outputs ＋ 重試。
 *
 * 輸出由 structured outputs 把關（`output_config.format`），模型只能產出符合 schema 的 JSON。
 * 例外只有拒答與 max_tokens 截斷，兩者都當失敗重試 1 次；逾時與 key 錯誤不重試。
 * SDK 自己的重試關掉（它連逾時也重試，會把 planting 拖過逾期時間），次數只由這裡決定。
 *
 * 沒有工具：照片直接以 image block 放進訊息，請求裡不存在任何工具。使用者寫的日記原樣進 prompt、
 * 等同不可信輸入，但裡面就算夾帶指令，能影響的也只有輸出的文字。
 */
class Claude(
    private val apiKey: () -> String?,
    private val profile: (Purpose) -> AiProfile,
    private val recordUsage: (AiUsage) -> Unit,
    private val baseUrl: String? = null,
    private val timeout: Duration = TIMEOUT,
) : TreeAi {

    override suspend fun ask(prompt: String, schema: ObjectNode, images: List<AiImage>, purpose: Purpose): ObjectNode {
        var lastError = AiError("AI 呼叫失敗")
        repeat(2) {
            try {
                return parse(create(prompt, schema, images, purpose))
            } catch (e: AiTimeout) {
                throw e
            } catch (e: AiKeyError) {
                throw e
            } catch (e: AiError) {
                lastError = e
            }
        }
        throw lastError
    }

    private suspend fun create(prompt: String, schema: ObjectNode, images: List<AiImage>, purpose: Purpose): Message {
        val key = apiKey() ?: throw AiKeyMissing()
        val (model, effort) = profile(purpose)
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .addUserMessageOfContentBlockParams(content(prompt, images))
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(effort))
                    .format(
                        JsonOutputFormat.builder()
                            .schema(
                                JsonOutputFormat.Schema.builder()
                                    .putAllAdditionalProperties(
                                        schema.properties().associate { (k, v) -> k to JsonValue.fromJsonNode(v) },
                                    )
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
        // key 明確傳入，不讓 SDK 自己去翻環境變數
        val client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .apply { if (baseUrl != null) baseUrl(baseUrl) }
            .maxRetries(0)
            .timeout(timeout)
            .build()
        val response = try {
            runInterruptible(Dispatchers.IO) { client.messages().create(params) }
        } catch (e: UnauthorizedException) {
            throw AiKeyInvalid(e)
        } catch (e: AnthropicIoException) {
            throw if (e.isTimeout()) AiTimeout(e) else AiUnreachable(e)
        } catch (e: AnthropicException) {
            // 真正的原因（額度用完、模型名打錯……）留在例外裡，畫面上只會看到療癒文案
            throw AiError("Claude API 失敗：${e.message}", e)
        } finally {
            client.close()
        }
        record(purpose, model, response)
        return response
    }

    /** 記帳失敗只吞掉：錢已經花了、回覆也拿到了，不能因為記帳出錯讓這次種樹作廢。 */
    private fun record(purpose: Purpose, model: String, response: Message) {
        try {
            val usage = response.usage()
            recordUsage(AiUsage(purpose, model, usage.inputTokens(), usage.outputTokens()))
        } catch (e: Exception) {
            // 跟網頁版一樣只留紀錄，不往上丟
        }
    }

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(240)

        /** 思考與輸出共用這個上限。日記與週報都是幾百字的短文，16k 是留給 adaptive thinking 的餘裕。 */
        const val MAX_TOKENS = 16_000L

        private val mapper = ObjectMapper()

        /** 照片放前面、各自帶編號標籤，prompt 裡用「附照片 N」對上。 */
        fun content(prompt: String, images: List<AiImage>): List<ContentBlockParam> = buildList {
            images.forEachIndexed { i, image ->
                add(ContentBlockParam.ofText("照片 ${i + 1}："))
                add(
                    ContentBlockParam.ofImage(
                        Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.of(image.mediaType))
                            .data(Base64.getEncoder().encodeToString(image.bytes))
                            .build(),
                    ),
                )
            }
            add(ContentBlockParam.ofText(prompt))
        }

        /**
         * 取出 structured output 的 JSON 物件，失敗丟 [AiError]。schema 保證正常結束的輸出能解析；
         * 拒答與 max_tokens 截斷時文字不保證符合 schema，一律當失敗交給重試。
         */
        fun parse(response: Message): ObjectNode {
            val stop = response.stopReason().orElse(null)
            if (stop != StopReason.END_TURN) throw AiError("Claude 沒有正常結束（stop_reason=$stop）")
            val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            val data: JsonNode = try {
                mapper.readTree(text) ?: throw AiError("輸出是空的")
            } catch (e: JsonProcessingException) {
                throw AiError("輸出不是合法 JSON：${e.message}", e)
            }
            return data as? ObjectNode ?: throw AiError("輸出 JSON 不是物件（得到 ${data.nodeType}）")
        }

        private fun Throwable.isTimeout(): Boolean =
            generateSequence(this) { it.cause }.any { it is InterruptedIOException }
    }
}
