package com.wordtaker.keyboard.wordtaker.speech

internal enum class ParaformerModelPhase {
    Installing,
    Initializing,
    Ready,
    Error,
}

internal enum class ParaformerModelFailure {
    Storage,
    Integrity,
    Memory,
    Initialization,
}

internal data class ParaformerLifecycleState(
    val phase: ParaformerModelPhase = ParaformerModelPhase.Installing,
    val failure: ParaformerModelFailure? = null,
)

/** Allows legacy cleanup only after a verified model and a successful decode. */
internal object ParaformerLegacyCleanupPolicy {
    fun mayDelete(
        paraformerVerified: Boolean,
        initialized: Boolean,
        successfulRecognitionCount: Int,
    ): Boolean =
        paraformerVerified && initialized && successfulRecognitionCount > 0
}
