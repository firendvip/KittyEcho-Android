package com.wordtaker.keyboard.wordtaker.speech

internal enum class ParaformerAttemptFailure {
    Integrity,
    Storage,
}

internal class ParaformerAttemptException(
    val failure: ParaformerAttemptFailure,
    cause: Throwable? = null,
) : RuntimeException("Paraformer bundled installation failed: $failure", cause)
