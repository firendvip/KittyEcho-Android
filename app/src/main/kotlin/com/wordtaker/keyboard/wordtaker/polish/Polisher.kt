package com.wordtaker.keyboard.wordtaker.polish

/** Payload-free outcome carried with the committed text; never stores transcript or credentials. */
enum class PolishOutcomeKind {
    ShortDirect,
    OfflineDirect,
    Polished,
    FallbackQuota,
    FallbackAuthExpired,
    FallbackTimeout,
    FallbackNetwork,
    FallbackServer,
    FallbackUnknown,
}

/** Text plus its immutable processing outcome, scoped to one transcript segment. */
data class PolishResult(
    val text: String,
    val outcome: PolishOutcomeKind,
)

/**
 * Text-polishing boundary. Cloud implementations can be swapped without touching the UI layer.
 */
interface Polisher {
    /**
     * Whether a real polish attempt can be made now.
     *
     * Voice input checks this only after the transcript exceeds its local-direct threshold.
     */
    fun isAvailable(): Boolean = true

    /** Polish [raw] recognized text according to the AI [role]. */
    suspend fun polish(raw: String, role: String): String

    /**
     * Structured compatibility boundary. Production implementations override this so
     * fallbacks remain attributable; simple/test implementations retain polished semantics.
     */
    suspend fun polishResult(raw: String, role: String): PolishResult =
        PolishResult(polish(raw, role), PolishOutcomeKind.Polished)
}
