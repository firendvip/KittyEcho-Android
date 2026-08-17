package com.wordtaker.keyboard.wordtaker.polish

import com.wordtaker.keyboard.wordtaker.network.InternetConnection

/**
 * Gates the complete cloud-polish stack before the billing backend is called.
 *
 * Offline input returns unchanged before the delegate is invoked. Online calls remain
 * transparent so the existing VoiceViewModel fallback handles unexpected failures.
 */
class OnlineOnlyPolisher(
    private val internetConnection: InternetConnection,
    private val onlineDelegate: Polisher,
    private val diagnostics: PolishDiagnostics = AndroidPolishDiagnostics,
) : Polisher {

    override fun isAvailable(): Boolean = internetConnection.isAvailable()

    override suspend fun polish(raw: String, role: String): String =
        polishResult(raw, role).text

    override suspend fun polishResult(raw: String, role: String): PolishResult {
        if (!isAvailable()) {
            diagnostics.record(PolishEvent.OFFLINE_BYPASS)
            return PolishResult(raw, PolishOutcomeKind.OfflineDirect)
        }
        diagnostics.record(PolishEvent.TOP_LEVEL_ATTEMPT)
        return onlineDelegate.polishResult(raw, role)
    }
}
