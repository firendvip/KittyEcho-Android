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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

// Recognizing/Polishing/Success 已不在主流程使用（batch3-C：点击结束即回待机，
// 处理在后台并行进行）；保留枚举值仅为兼容旧引用（CatVoiceOverlay 等）。
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

/**
 * batch3-C 录音重做（对齐 PC 端）：
 *  - 录音中：endpoint 不再定稿出字、不显示实时字幕 —— 引擎在后台流式解码并攒段，
 *    界面只有小猫动画 + 「正在倾听…点击结束」。
 *  - 点击结束：当前段整段定稿（引擎本地 flush，攒段+尾巴），交给后台「润色→上屏」
 *    任务；界面立刻回到待机，可马上开新一段 —— 多段并行处理。
 *  - 顺序保证：每段的润色在入队时即并发启动（async），单 worker 按 FIFO await，
 *    上屏顺序与录音开始顺序严格一致。
 *  - 取消 = 丢弃当前录音段（不影响已在队列中的段）；切App/收键盘 = 停当前录音
 *    （队列中的段继续完成上屏）。
 */
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

    // Emits the final (polished) text when a segment completes successfully.
    // The IME host collects this to commit the text into the focused input field.
    // 容量 16：多段并行时可能连续快速完成，收集方在主线程，防 tryEmit 丢文本。
    private val _committed = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val committed: SharedFlow<String> = _committed.asSharedFlow()

    private val _event = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<VoiceEvent> = _event.asSharedFlow()

    // 「有 N 段在处理」：从点击结束（开始收尾 flush）起计入，润色上屏完成后减一。
    // UI 用它画多猫/角标视觉反馈。
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val settings: StateFlow<SettingsState> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    private var levelJob: Job? = null

    // start/stop 串行化：引擎是单实例（单 recorder + 单解码 session），新段开麦必须等
    // 上一段的本地 flush（engine.stop()）完成；收尾也必须等本段真正开麦之后。
    // 两个 Job 都在主调度器上 launch，join 链保证 stop_i → start_{i+1} → stop_{i+1}。
    private var startJob: Job? = null
    private var stopJob: Job? = null

    // 本段是否真的开了麦（startJob 里 engine.start() 已执行）。cancel/收键盘只有在
    // 开了麦时才需要 engine.cancel()，避免打断上一段还在 flush 的 engine.stop()。
    @Volatile
    private var micOpen = false

    /** 一段录音的提交任务：raw 原文 + 已并发启动的润色结果。 */
    private class CommitJob(val raw: String, val polished: Deferred<String>)

    // FIFO 提交管线：润色各自并发跑（多只小猫并行干活），worker 按开始顺序 await
    // 逐条上屏（排队交卷），保证上屏顺序与录音开始顺序严格一致。
    private val commitQueue = Channel<CommitJob>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (job in commitQueue) {
                try {
                    val text = job.polished.await()
                    // Commit FIRST (non-suspending tryEmit) so text is emitted even if
                    // the viewModelScope is cancelled before historyRepository.add completes.
                    _committed.tryEmit(text)
                    Log.i(TAG, "commit emitted: ${text.length} chars")
                    runCatching { historyRepository.add(job.raw, text) }
                        .onSuccess { Log.d(TAG, "history: written") }
                        .onFailure { Log.i(TAG, "history: write failed: ${it.message}") }
                    if (!settings.value.minimal) {
                        _toast.value = "已写入历史"
                    }
                } finally {
                    _pending.update { (it - 1).coerceAtLeast(0) }
                }
            }
        }
    }

    fun onTap() {
        when (_state.value.phase) {
            VoicePhase.Idle -> startRecording()
            VoicePhase.Recording -> finishSegment()
            else -> Unit
        }
    }

    /**
     * User-initiated abort from the visible 取消 button：丢弃当前录音段（不上屏、
     * 不写历史）。已在队列中处理的段不受影响，会继续完成上屏。
     */
    fun cancel() {
        if (_state.value.phase != VoicePhase.Recording) return
        levelJob?.cancel()
        levelJob = null
        if (micOpen) runCatching { speechEngine.cancel() }
        micOpen = false
        val toneOn = settings.value.tone
        toneController.endBeep(toneOn, settings.value.toneStyle, toneVolume())
        _state.value = VoiceUiState()
        Log.d(TAG, "cancel: current segment discarded, reset to Idle")
    }

    /**
     * External entry point (toolbar voice icon / long-press space / CAT_VOICE key):
     * starts recording when idle; never ends one.
     */
    fun startFromExternal() {
        if (_state.value.phase == VoicePhase.Idle) {
            startRecording()
        }
    }

    /**
     * Stops an active recording (discarding it) and plays the end tone. Lifecycle
     * safety net (P2-106 幽灵录音): called from the IME service's onWindowHidden /
     * onFinishInputView (and [onCleared]) so switching apps / hiding the keyboard
     * mid-recording always releases the AudioRecord and resets the UI. 已入队的段
     * 不受影响（worker 继续跑，队列中的段照常完成上屏）。
     */
    fun stopRecordingAndEndTone() {
        if (_state.value.phase != VoicePhase.Recording) return
        levelJob?.cancel()
        levelJob = null
        val toneOn = settings.value.tone
        toneController.endBeep(toneOn, settings.value.toneStyle, toneVolume())
        if (micOpen) runCatching { speechEngine.cancel() }
        micOpen = false
        _state.value = VoiceUiState()
        Log.d(TAG, "stopRecordingAndEndTone: discarded recording, reset to Idle")
    }

    /** 语音提示音音量系数 0..1（用户滑杆，仅作用于喵叫/开始/结束音）。 */
    private fun toneVolume(): Float = settings.value.toneVolume / 100f

    /** 润色一段原文；失败/超时回退原文。 */
    private suspend fun polishOrFallback(raw: String): String {
        val role = settings.value.role
        return try {
            withTimeout(POLISH_TIMEOUT_MS) { polisher.polish(raw, role) }
                .also { Log.i(TAG, "polish: success") }
        } catch (e: CancellationException) {
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
    }

    private fun startRecording() {
        // D-1: engine.start() silently no-ops when the bundled model hasn't finished
        // copying yet; pre-flight so the tap gets an immediate, honest response.
        if (!speechEngine.isReady()) {
            _toast.value = "语音正在准备，请稍候"
            Log.d(TAG, "startRecording: engine not ready yet, showed hint")
            return
        }
        // UI 立即进入录音态（防双击重入）；真正开麦在 startJob 里等上一段 flush 完。
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
        val priorStop = stopJob
        startJob = viewModelScope.launch {
            priorStop?.join()
            // 等待期间被取消/收键盘（phase 已复位）→ 放弃开麦，避免幽灵录音。
            if (_state.value.phase != VoicePhase.Recording) {
                Log.d(TAG, "startRecording: aborted before mic open (phase reset)")
                return@launch
            }
            val toneOn = settings.value.tone
            // 顺序敏感（真机 vivo iQOO 8 实测得出，勿改回）：
            // 1) 先开麦克风再播提示音——提示音输出流启动若与录音通路建立同时发生，
            //    audioserver 会让 AudioRecord 首帧延迟数秒、句首被吞（原「前切」bug）。
            // 2) 提示音在开麦后播放会被自己的麦克风录进（解码成垃圾字、其后停顿提前触发
            //    endpoint），故让引擎丢弃录音开头的提示音窗口（TONE_GUARD_MS）。
            // R3-011：提示音关闭时句首被吞已由采集音源改为 VOICE_COMMUNICATION 修复
            // （见 PcmRecorder 音源注释），无需任何声学预热；关提示音 = 全程无声。
            speechEngine.start(if (toneOn) TONE_GUARD_MS else 0L)
            micOpen = true
            toneController.startBeep(toneOn, settings.value.toneStyle, toneVolume())
        }
    }

    /**
     * 点击结束：立即回待机（可马上开新段），当前段在后台收尾 —— engine.stop()
     * 本地 flush 出「攒段 + 尾巴」整段文本，随即并发启动润色并入 FIFO 队列。
     */
    private fun finishSegment() {
        levelJob?.cancel()
        levelJob = null
        val toneOn = settings.value.tone
        toneController.endBeep(toneOn, settings.value.toneStyle, toneVolume())
        _state.value = VoiceUiState()
        micOpen = false
        _pending.update { it + 1 }
        Log.d(TAG, "finishSegment: back to Idle, flushing segment in background")
        val priorStart = startJob
        stopJob = viewModelScope.launch {
            priorStart?.join()
            val raw: String = try {
                speechEngine.stop()
            } catch (e: CancellationException) {
                throw e  // scope teardown — propagate
            } catch (e: MicPermissionRequiredException) {
                Log.i(TAG, "ASR: MicPermissionRequired")
                _event.tryEmit(VoiceEvent.PermissionRequired)
                _toast.value = "需要麦克风权限"
                _pending.update { (it - 1).coerceAtLeast(0) }
                return@launch
            } catch (e: ModelNotReadyException) {
                Log.i(TAG, "ASR: ModelNotReady")
                _event.tryEmit(VoiceEvent.ModelRequired)
                _toast.value = "语音模型正在准备，请稍候"
                _pending.update { (it - 1).coerceAtLeast(0) }
                return@launch
            } catch (e: Exception) {
                Log.i(TAG, "ASR: exception ignored, treating as empty: ${e.message}")
                ""
            }
            val trimmed = raw.trim()
            Log.d(TAG, "ASR result: ${trimmed.length} chars")
            if (trimmed.isBlank()) {
                _toast.value = "未识别到语音"
                _pending.update { (it - 1).coerceAtLeast(0) }
                return@launch
            }
            // 润色即刻并发启动（多段并行干活）；FIFO worker 按开始顺序 await 上屏。
            val polishing = viewModelScope.async { polishOrFallback(trimmed) }
            commitQueue.trySend(CommitJob(trimmed, polishing))
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    override fun onCleared() {
        levelJob?.cancel()
        // stopRecordingAndEndTone is a no-op if phase != Recording. viewModelScope
        // cancellation aborts in-flight polish jobs; already-committed text was
        // emitted via non-suspending tryEmit so it is not lost.
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
        const val POLISH_TIMEOUT_MS = 10_000L

        /**
         * 录音开头丢弃窗口：覆盖起始喵叫（meow.mp3 ≈0.55s，开麦后立即播放，在已捕获
         * 音频的时间轴上约占 0.05–0.75s，含 ±0.1s 播放启动抖动）。用户以喵叫为
         * 「开始说话」提示（喵叫约在点击后 0.9s 听完），正常不会在此窗口内开口，
         * 丢弃不伤真实语音。真机（vivo iQOO 8）多轮标定：600ms 必漏（喵叫尾巴解码成
         * 「什么」且提前触发 endpoint 截断整句）；700ms 偶漏（抖动时尾巴解码成「嗯/所」
         * 垃圾前缀）；850ms 全覆盖。勿改小；改大会削掉抢跑说话者的句首字。
         */
        const val TONE_GUARD_MS = 850L
    }
}
