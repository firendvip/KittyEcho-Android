package com.wordtaker.keyboard.wordtaker.voice

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wordtaker.keyboard.wordtaker.audio.ToneController
import com.wordtaker.keyboard.wordtaker.history.HistoryRepository
import com.wordtaker.keyboard.wordtaker.polish.Polisher
import com.wordtaker.keyboard.wordtaker.settings.SettingsRepository
import com.wordtaker.keyboard.wordtaker.settings.SettingsState
import com.wordtaker.keyboard.wordtaker.speech.MicPermissionRequiredException
import com.wordtaker.keyboard.wordtaker.speech.ModelNotReadyException
import com.wordtaker.keyboard.wordtaker.speech.SpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

enum class VoicePhase { Idle, Recording, Recognizing, Polishing, Success }

/** One-shot events the UI must act on (launch permission / model-download flows). */
enum class VoiceEvent { PermissionRequired, ModelRequired }

/** Immutable UI state for the voice panel. */
data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.Idle,
    val recording: Boolean = false,
    val level: Float = 0f,
    val busy: Boolean = false,
) {
    val isProcessing: Boolean get() = busy
}

class VoiceViewModel(
    private val speechEngine: SpeechEngine,
    private val polisher: Polisher,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val toneController: ToneController,
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // Emits the final (polished) text when a recognition completes successfully.
    // The IME host collects this to commit the text into the focused input field.
    private val _committed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val committed: SharedFlow<String> = _committed.asSharedFlow()

    private val _event = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<VoiceEvent> = _event.asSharedFlow()

    private val settings: StateFlow<SettingsState> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    private var levelJob: Job? = null

    fun onTap() {
        val current = _state.value
        if (current.isProcessing) return
        if (current.phase == VoicePhase.Idle) {
            startRecording()
        } else if (current.phase == VoicePhase.Recording) {
            stopAndProcess()
        }
    }

    /**
     * External entry point (toolbar voice icon / long-press space / CAT_VOICE key):
     * starts recording when idle, but does NOT stop an in-progress recording the way
     * [onTap] does — these triggers should only ever begin a recording, never end one.
     */
    fun startFromExternal() {
        val current = _state.value
        if (current.isProcessing) return
        if (current.phase == VoicePhase.Idle) {
            startRecording()
        }
    }

    /**
     * Stops an active recording and plays the end tone, then discards the audio.
     * Called when the user taps the Keyboard icon to switch away from the cat panel
     * mid-recording. The end tone always plays so the start/end pair is symmetrical.
     *
     * Safety: only acts when phase == Recording. Does NOT abort an in-progress
     * Recognizing/Polishing/Success coroutine — those must run to completion so the
     * text is committed and history is written.
     */
    fun stopRecordingAndEndTone() {
        val current = _state.value
        if (current.phase != VoicePhase.Recording) return   // no-op during processing
        levelJob?.cancel()
        levelJob = null
        val toneOn = settings.value.tone
        toneController.endBeep(toneOn, settings.value.toneStyle)
        runCatching { speechEngine.cancel() }
        _state.value = VoiceUiState()
        Log.d(TAG, "stopRecordingAndEndTone: discarded recording, reset to Idle")
    }

    private fun startRecording() {
        val toneOn = settings.value.tone
        toneController.startBeep(toneOn, settings.value.toneStyle)
        speechEngine.start()
        _state.value = VoiceUiState(
            phase = VoicePhase.Recording,
            recording = true,
            level = INITIAL_LEVEL,
            busy = false,
        )
        Log.d(TAG, "startRecording: phase=Recording")
        levelJob?.cancel()
        levelJob = viewModelScope.launch {
            while (isActive) {
                delay(LEVEL_TICK_MS)
                _state.value = _state.value.copy(
                    level = BASE_LEVEL + Random.nextFloat() * LEVEL_SWING,
                )
            }
        }
    }

    private fun stopAndProcess() {
        levelJob?.cancel()
        levelJob = null
        // Sample tone on/off and style together so they can't drift mid-flight.
        val toneOn = settings.value.tone
        val toneStyle = settings.value.toneStyle
        _state.value = _state.value.copy(
            phase = VoicePhase.Recognizing,
            recording = false,
            level = 0f,
            busy = true,
        )
        Log.d(TAG, "stopAndProcess: phase=Recognizing")
        viewModelScope.launch {
            // Terminal-state guarantee: finally block always resets to Idle if we somehow
            // escape via an unhandled path. All normal paths set phase explicitly before
            // reaching the end of this block, so the finally only fires on true exceptions.
            var reachedTerminalState = false
            try {
                val raw: String = try {
                    speechEngine.stop()
                } catch (e: CancellationException) {
                    throw e  // propagate scope cancellation unchanged
                } catch (e: MicPermissionRequiredException) {
                    Log.i(TAG, "ASR: MicPermissionRequired")
                    _state.value = VoiceUiState()
                    _event.tryEmit(VoiceEvent.PermissionRequired)
                    _toast.value = "需要麦克风权限"
                    reachedTerminalState = true
                    return@launch
                } catch (e: ModelNotReadyException) {
                    Log.i(TAG, "ASR: ModelNotReady")
                    _state.value = VoiceUiState()
                    _event.tryEmit(VoiceEvent.ModelRequired)
                    _toast.value = "语音模型正在准备，请稍候"
                    reachedTerminalState = true
                    return@launch
                } catch (e: Exception) {
                    Log.i(TAG, "ASR: exception ignored, treating as empty: ${e.message}")
                    ""
                }

                Log.d(TAG, "ASR result: ${raw.length} chars")

                if (raw.isBlank()) {
                    _state.value = VoiceUiState()
                    _toast.value = "未识别到语音"
                    reachedTerminalState = true
                    Log.d(TAG, "ASR: blank result -> Idle, showed hint")
                    return@launch
                }

                // Polishing phase
                _state.value = _state.value.copy(phase = VoicePhase.Polishing)
                Log.d(TAG, "phase=Polishing")
                val role = settings.value.role
                val polished: String = try {
                    withTimeout(POLISH_TIMEOUT_MS) { polisher.polish(raw, role) }
                        .also { Log.i(TAG, "polish: success") }
                } catch (e: CancellationException) {
                    // withTimeout throws TimeoutCancellationException (subclass of CancellationException);
                    // catch it here to fall back to raw, then rethrow only if it's a true scope cancel.
                    if (e is kotlinx.coroutines.TimeoutCancellationException) {
                        Log.i(TAG, "polish: timeout after ${POLISH_TIMEOUT_MS}ms, falling back to raw")
                        raw
                    } else {
                        throw e  // true scope cancellation — propagate
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "polish: failure (${e.message}), falling back to raw")
                    raw
                }

                // Commit FIRST (non-suspending tryEmit) so text is emitted even if the
                // viewModelScope is cancelled before historyRepository.add completes.
                _committed.tryEmit(polished)
                Log.i(TAG, "commit emitted: ${polished.length} chars")

                // Persist history — failure must not break the terminal-state guarantee.
                runCatching { historyRepository.add(raw, polished) }
                    .onSuccess { Log.d(TAG, "history: written") }
                    .onFailure { Log.i(TAG, "history: write failed: ${it.message}") }

                toneController.endBeep(toneOn, toneStyle)

                // busy true -> false triggers the cat sparkle animation.
                _state.value = _state.value.copy(phase = VoicePhase.Success, busy = false)
                Log.d(TAG, "phase=Success")
                if (!settings.value.minimal) {
                    _toast.value = "已写入历史"
                }
                reachedTerminalState = true
                delay(SUCCESS_HOLD_MS)
                _state.value = VoiceUiState()
                Log.d(TAG, "phase=Idle (after Success)")

            } catch (e: CancellationException) {
                // Scope was cancelled (e.g. ViewModel cleared mid-flight).
                // _committed.tryEmit was already called before any suspending history write,
                // so text is not lost. Let cancellation propagate normally.
                Log.d(TAG, "processing coroutine cancelled (scope teardown)")
                throw e
            } catch (e: Exception) {
                // Catch-all safety net — no exception path may leave the UI stuck on 处理中.
                Log.i(TAG, "stopAndProcess: unexpected exception -> reset to Idle: ${e.message}")
            } finally {
                // Guarantee: if we exit without having set a terminal state ourselves,
                // reset to Idle so the UI is never stuck on 处理中.
                if (!reachedTerminalState && _state.value.phase != VoicePhase.Idle) {
                    _state.value = VoiceUiState()
                    Log.d(TAG, "finally: forced reset to Idle")
                }
            }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    override fun onCleared() {
        levelJob?.cancel()
        // stopRecordingAndEndTone is a no-op if phase != Recording, so it will NOT
        // abort an in-progress Recognizing/Polishing coroutine. The viewModelScope
        // cancellation below will cancel that coroutine, but _committed.tryEmit is
        // called before any suspending call, so committed text is already in the buffer.
        stopRecordingAndEndTone()
        toneController.release()
        super.onCleared()
    }

    class Factory(
        private val speechEngine: SpeechEngine,
        private val polisher: Polisher,
        private val historyRepository: HistoryRepository,
        private val settingsRepository: SettingsRepository,
        private val toneController: ToneController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VoiceViewModel(
                speechEngine, polisher, historyRepository, settingsRepository, toneController,
            ) as T
        }
    }

    private companion object {
        const val TAG = "VoiceVM"
        const val LEVEL_TICK_MS = 140L
        const val INITIAL_LEVEL = 0.6f
        const val BASE_LEVEL = 0.3f
        const val LEVEL_SWING = 0.6f
        const val SUCCESS_HOLD_MS = 1200L
        const val POLISH_TIMEOUT_MS = 10_000L
    }
}
