package com.wordtaker.keyboard.wordtaker.polish

import android.util.Log
import com.wordtaker.keyboard.wordtaker.backend.BackendClient
import com.wordtaker.keyboard.wordtaker.backend.BackendConfig
import com.wordtaker.keyboard.wordtaker.backend.BackendException
import com.wordtaker.keyboard.wordtaker.relay.RelayClient
import com.wordtaker.keyboard.wordtaker.relay.RelayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真实润色器：主通路走计费后端 POST /polish（带 x-device-id / x-platform / Bearer，
 * 匿名走设备额度），失败降级旧 relay 兜底 —— 对齐 Mac 端 aiService.processTextViaBackend：
 *
 *  - 额度不足 / 超日上限（INSUFFICIENT_QUOTA / DAILY_CAP_EXCEEDED）→ 直接贴原文，
 *    绝不回退 relay（避免绕过计费）。
 *  - 401 / NOT_LOGGED_IN（token 过期）→ 清 token（onAuthExpired），本句降级 relay。
 *  - 其余失败（网络/超时/后端 4xx/5xx）→ 按开关降级 relay；
 *    relay 再失败 → 贴原文。用户永远不丢字。
 *
 * SECURITY: 客户端不持有任何提示词；role 映射为服务端 mode，由后端选择系统提示词。
 */
class RealPolisher(
    private val backend: BackendClient,
    private val relay: RelayClient,
    private val onAuthExpired: () -> Unit = {},
) : Polisher {

    override suspend fun polish(raw: String, role: String): String =
        withContext(Dispatchers.IO) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@withContext raw
            val capped = trimmed.take(MAX_INPUT_CHARS)
            val mode = modeFor(role)

            try {
                val out = backend.polish(capped, mode)
                if (out.text.isNotBlank()) return@withContext out.text
                Log.w(TAG, "backend polish returned blank text, falling back to relay")
                relayFallback(capped, mode, raw)
            } catch (e: BackendException) {
                when {
                    // 额度类失败：贴原文，不回退 relay（不绕过计费）。
                    e.isQuotaError -> {
                        Log.w(TAG, "cloud quota exhausted (${e.code}), pasting raw text")
                        raw
                    }
                    e.isAuthExpired -> {
                        Log.w(TAG, "backend polish 401, clearing token and falling back")
                        runCatching { onAuthExpired() }
                        relayFallback(capped, mode, raw)
                    }
                    else -> {
                        Log.w(TAG, "backend polish failed (${e.kind}/${e.code}): ${e.message}")
                        relayFallback(capped, mode, raw)
                    }
                }
            }
        }

    /** relay 降级兜底；relay 也失败则返回原文。 */
    private fun relayFallback(text: String, mode: String, raw: String): String {
        if (!BackendConfig.FALLBACK_TO_RELAY) return raw
        return when (val result = relay.polish(text, mode)) {
            is RelayResult.Success -> result.polished
            is RelayResult.Failure -> {
                Log.w(TAG, "relay fallback failed: ${result.reason}")
                raw
            }
        }
    }

    /** Maps an AI role to the backend/relay mode that selects the cloud system prompt. */
    // 与 Mac 端 aiService.js 的 role→mode 契约一致：normal→normal，gaoeq→gaoeq，
    // 其余角色（含 vibecoding / 未知）→copywriting。
    private fun modeFor(role: String): String = when (role) {
        ROLE_NORMAL -> MODE_NORMAL
        ROLE_GAOEQ -> MODE_GAOEQ
        else -> MODE_COPYWRITING
    }

    private companion object {
        const val TAG = "RealPolisher"
        const val MAX_INPUT_CHARS = 4000

        const val ROLE_NORMAL = "normal"
        const val ROLE_GAOEQ = "gaoeq"

        const val MODE_NORMAL = "normal"
        const val MODE_GAOEQ = "gaoeq"
        const val MODE_COPYWRITING = "copywriting"
    }
}
