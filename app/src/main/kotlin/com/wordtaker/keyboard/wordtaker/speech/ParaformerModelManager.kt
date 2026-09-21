package com.wordtaker.keyboard.wordtaker.speech

import kotlinx.coroutines.flow.StateFlow

/** Shared read-only presentation facade for automatic bundled model preparation. */
internal class ParaformerModelManager(
    val state: StateFlow<ParaformerLifecycleState>,
    private val retryPreparation: () -> Unit,
) {
    fun retry() = retryPreparation()
}
