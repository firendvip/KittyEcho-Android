package com.wordtaker.keyboard.wordtaker.cat

import androidx.compose.runtime.Immutable

/**
 * Public state for [CatSkin]. Equivalent to the demo's four setters:
 *  - [recording] -> setMic("recording" / "idle")
 *  - [level]     -> setLevel(v)
 *  - [busy]      -> setBusy(b)
 *  - [error]     -> setError(e)
 *
 * Immutable value type — produce new copies with `state.copy(...)`.
 */
@Immutable
data class CatState(
    val recording: Boolean = false,
    val level: Float = 0f,
    val busy: Boolean = false,
    val error: Boolean = false,
)
