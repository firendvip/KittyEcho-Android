package com.wordtaker.keyboard.wordtaker.polish

import com.wordtaker.keyboard.wordtaker.relay.RelayClient
import com.wordtaker.keyboard.wordtaker.relay.RelayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real polisher backed by the self-hosted relay (DeepSeek server-side). On any relay
 * failure it gracefully falls back to the raw transcription so the user never loses
 * their words.
 *
 * SECURITY: the client holds NO prompt text. The [role] is mapped to a server-side
 * [RelayClient.polish] mode and the relay selects the matching secret system prompt.
 * The polished result is trusted as-is — no client-side rewriting is applied.
 */
class RealPolisher(private val relay: RelayClient) : Polisher {

    override suspend fun polish(raw: String, role: String): String =
        withContext(Dispatchers.IO) {
            when (val result = relay.polish(raw, modeFor(role))) {
                is RelayResult.Success -> result.polished
                is RelayResult.Failure -> raw
            }
        }

    /** Maps an AI role to the relay mode that selects the cloud system prompt. */
    private fun modeFor(role: String): String = when (role) {
        ROLE_GAOEQ -> MODE_GAOEQ
        ROLE_VIBECODING -> MODE_COPYWRITING
        else -> MODE_NORMAL // ROLE_NORMAL (default) and any unknown role
    }

    private companion object {
        const val ROLE_GAOEQ = "gaoeq"
        const val ROLE_VIBECODING = "vibecoding"

        const val MODE_NORMAL = "normal"
        const val MODE_GAOEQ = "gaoeq"
        const val MODE_COPYWRITING = "copywriting"
    }
}
