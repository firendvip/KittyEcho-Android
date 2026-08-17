package com.wordtaker.keyboard.wordtaker.polish

import com.wordtaker.keyboard.wordtaker.backend.BackendClient
import com.wordtaker.keyboard.wordtaker.backend.BackendException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真实润色器：只走计费后端 POST /polish（带 x-device-id / x-platform / Bearer，
 * 匿名走设备额度）。
 *
 * 任何失败都保留原文。客户端绝不直连 relay，避免绕过服务端额度、提示词和
 * 凭据边界；401 / NOT_LOGGED_IN 还会清除过期登录态。
 *
 * SECURITY: 客户端不持有任何提示词；role 映射为服务端 mode，由后端选择系统提示词。
 */
class RealPolisher(
    private val backend: PolishBackend,
    private val onAuthExpired: () -> Unit = {},
    private val diagnostics: PolishDiagnostics = AndroidPolishDiagnostics,
) : Polisher {

    constructor(
        backend: BackendClient,
        onAuthExpired: () -> Unit = {},
        diagnostics: PolishDiagnostics = AndroidPolishDiagnostics,
    ) : this(
        backend = PolishBackend { text, mode, wordMap ->
            backend.polish(text, mode, wordMap)
        },
        onAuthExpired = onAuthExpired,
        diagnostics = diagnostics,
    )

    override suspend fun polish(raw: String, role: String): String =
        polishResult(raw, role).text

    override suspend fun polishResult(raw: String, role: String): PolishResult =
        withContext(Dispatchers.IO) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return@withContext PolishResult(raw, PolishOutcomeKind.ShortDirect)
            }
            val capped = trimmed.take(MAX_INPUT_CHARS)
            val mode = modeFor(role)

            try {
                val out = backend.polish(capped, mode, null)
                if (out.text.isNotBlank()) {
                    diagnostics.record(PolishEvent.BACKEND_SUCCESS)
                    return@withContext PolishResult(out.text, PolishOutcomeKind.Polished)
                }
                diagnostics.record(PolishEvent.BACKEND_BLANK_RESPONSE)
                PolishResult(raw, PolishOutcomeKind.FallbackServer)
            } catch (e: BackendException) {
                diagnostics.record(e.toPolishEvent())
                when {
                    e.isQuotaError -> PolishResult(raw, PolishOutcomeKind.FallbackQuota)
                    e.isAuthExpired -> {
                        runCatching { onAuthExpired() }
                        PolishResult(raw, PolishOutcomeKind.FallbackAuthExpired)
                    }
                    else -> PolishResult(raw, e.toOutcomeKind())
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

    private fun BackendException.toPolishEvent(): PolishEvent = when {
        isQuotaError -> PolishEvent.BACKEND_QUOTA_FAILURE
        isAuthExpired -> PolishEvent.BACKEND_AUTH_FAILURE
        kind == BackendException.Kind.TIMEOUT -> PolishEvent.BACKEND_TIMEOUT
        kind == BackendException.Kind.NETWORK -> PolishEvent.BACKEND_NETWORK_FAILURE
        !code.isNullOrBlank() -> PolishEvent.BACKEND_BUSINESS_FAILURE
        else -> PolishEvent.BACKEND_HTTP_FAILURE
    }

    private fun BackendException.toOutcomeKind(): PolishOutcomeKind = when (kind) {
        BackendException.Kind.TIMEOUT -> PolishOutcomeKind.FallbackTimeout
        BackendException.Kind.NETWORK -> PolishOutcomeKind.FallbackNetwork
        BackendException.Kind.HTTP -> PolishOutcomeKind.FallbackServer
    }

    private companion object {
        const val MAX_INPUT_CHARS = 4000

        const val ROLE_NORMAL = "normal"
        const val ROLE_GAOEQ = "gaoeq"

        const val MODE_NORMAL = "normal"
        const val MODE_GAOEQ = "gaoeq"
        const val MODE_COPYWRITING = "copywriting"
    }
}
