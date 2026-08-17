package com.wordtaker.keyboard.wordtaker.polish

import android.util.Log
import com.wordtaker.keyboard.wordtaker.backend.PolishOutcome

/**
 * Payload-free events for the top-level polish chain. Events intentionally cannot
 * carry transcript text, authorization values, login codes, or backend messages.
 */
enum class PolishEvent {
    OFFLINE_BYPASS,
    TOP_LEVEL_ATTEMPT,
    BACKEND_SUCCESS,
    BACKEND_BLANK_RESPONSE,
    BACKEND_NETWORK_FAILURE,
    BACKEND_TIMEOUT,
    BACKEND_HTTP_FAILURE,
    BACKEND_BUSINESS_FAILURE,
    BACKEND_QUOTA_FAILURE,
    BACKEND_AUTH_FAILURE,
}

fun interface PolishDiagnostics {
    fun record(event: PolishEvent)
}

/** Production sink logs only the fixed enum name, never request or response payloads. */
object AndroidPolishDiagnostics : PolishDiagnostics {
    override fun record(event: PolishEvent) {
        when (event) {
            PolishEvent.OFFLINE_BYPASS,
            PolishEvent.TOP_LEVEL_ATTEMPT,
            PolishEvent.BACKEND_SUCCESS,
            -> Log.i(TAG, "polish_event=${event.name}")

            else -> Log.w(TAG, "polish_event=${event.name}")
        }
    }

    private const val TAG = "PolishChain"
}

/** Injectable backend call used by [RealPolisher] contract tests. */
fun interface PolishBackend {
    fun polish(
        text: String,
        mode: String,
        wordMap: List<Pair<String, String>>?,
    ): PolishOutcome
}
