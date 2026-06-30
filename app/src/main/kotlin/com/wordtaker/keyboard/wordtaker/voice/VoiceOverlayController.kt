package com.wordtaker.keyboard.wordtaker.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide trigger for the cat voice-input overlay.
 *
 * The keyboard is the single main surface. Voice input is no longer a separate
 * full-screen mode — instead a small "walking cat" overlay is rendered ON TOP of
 * the keyboard while recording / recognising / polishing, then automatically
 * removed when the flow reaches a terminal state.
 *
 * Two entry points request the overlay:
 *  - the toolbar "voice" icon ([ImeToolbar])
 *  - long-pressing the space bar (mapped via [SwipeAction] in KeyboardManager)
 *
 * The overlay composable observes [visible] and dismisses itself by calling
 * [hide] when the voice flow finishes (or the user taps to end + the result is
 * committed). Keeping this as a plain singleton avoids threading a controller
 * through the whole IME tree.
 */
object VoiceOverlayController {
    private val _visible = MutableStateFlow(false)

    /** True while the cat voice overlay should be shown over the keyboard. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Show the overlay and (implicitly) begin recording. Idempotent. */
    fun show() {
        _visible.value = true
    }

    /** Remove the overlay and return to the bare keyboard. Idempotent. */
    fun hide() {
        _visible.value = false
    }

    /** Toggle — used by the toolbar icon so a second tap can dismiss it. */
    fun toggle() {
        _visible.value = !_visible.value
    }
}
