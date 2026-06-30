package com.wordtaker.keyboard.wordtaker.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide one-shot channel that lets外部 entry points (the toolbar voice icon,
 * long-press space, the IME_UI_MODE_CAT_VOICE key) start voice recording on the
 * unified [CatKeyboardLayout] without owning its [VoiceViewModel].
 *
 * The merged单界面 means there is no longer a separate CAT_VOICE "屏" to switch to —
 * those entry points simply request recording to begin. [CatKeyboardLayout] collects
 * [requests] and forwards each one to [VoiceViewModel.startFromExternal].
 */
object VoiceTrigger {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    /** Ask the unified panel to start a voice recording (no-op if already recording). */
    fun requestStart() {
        _requests.tryEmit(Unit)
    }
}
