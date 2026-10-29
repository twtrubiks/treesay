package com.twtrubiks.treesay

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.UnauthorizedException
import java.time.Duration

/** 拿一把 key 去敲門的結果。打不開時，[Failed.message] 直接給使用者看，不裸露技術錯誤。 */
sealed interface KeyCheck {
    data object Ok : KeyCheck

    sealed class Failed(val message: String) : KeyCheck

    data object Rejected : Failed("樹在等你開門 🌿 這把 API key 打不開門，再確認一下有沒有貼完整")

    data object Unreachable : Failed("連不上森林，等網路順一點再試一次 🌙")

    data object TreeAsleep : Failed("樹睡著了，等等再來 🌙")
}

object ApiKeyCheck {
    private val TIMEOUT: Duration = Duration.ofSeconds(20)

    /**
     * 用 [apiKey] 列一次模型：不耗 token、不花錢，打得開就代表 key 能用。
     * 會連網路，不要在主執行緒呼叫。[baseUrl] 只給測試指到假的伺服器。
     */
    fun check(apiKey: String, baseUrl: String? = null): KeyCheck {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .apply { if (baseUrl != null) baseUrl(baseUrl) }
            // SDK 自己的重試關掉：key 錯了重試也不會對，網路不通就讓使用者自己再按一次
            .maxRetries(0)
            .timeout(TIMEOUT)
            .build()
        return try {
            client.models().list()
            KeyCheck.Ok
        } catch (e: UnauthorizedException) {
            KeyCheck.Rejected
        } catch (e: PermissionDeniedException) {
            KeyCheck.Rejected
        } catch (e: AnthropicIoException) {
            KeyCheck.Unreachable
        } catch (e: AnthropicException) {
            KeyCheck.TreeAsleep
        } finally {
            client.close()
        }
    }
}
