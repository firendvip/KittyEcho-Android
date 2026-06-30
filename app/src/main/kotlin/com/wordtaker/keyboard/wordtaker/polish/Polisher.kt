package com.wordtaker.keyboard.wordtaker.polish

import kotlinx.coroutines.delay

/**
 * Text-polishing boundary. Real implementations (e.g. RelayClient) can be swapped
 * in later without touching the UI layer.
 */
interface Polisher {
    /** Polish [raw] recognized text according to the AI [role]. */
    suspend fun polish(raw: String, role: String): String
}

/** Mock polisher returning role-specific canned rewrites after a fake delay. */
class MockPolisher : Polisher {

    override suspend fun polish(raw: String, role: String): String {
        delay(POLISH_DELAY_MS)
        return when (role) {
            ROLE_GAOEQ -> POLISHED_GAOEQ
            else -> POLISHED_DEFAULT
        }
    }

    private companion object {
        const val POLISH_DELAY_MS = 1200L
        const val ROLE_GAOEQ = "gaoeq"
        const val POLISHED_GAOEQ =
            "这个方案整体方向很不错，如果细节上我们能再一起打磨打磨，就更完善了。"
        const val POLISHED_DEFAULT =
            "我想说的是，这个方案整体可行，细节方面还需要再讨论一下。"
    }
}
