package com.wordtaker.keyboard.wordtaker.voice

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wordtaker.keyboard.wordtaker.audio.VoiceToneFeedback
import com.wordtaker.keyboard.wordtaker.history.HistoryRepository
import com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind
import com.wordtaker.keyboard.wordtaker.polish.PolishResult
import com.wordtaker.keyboard.wordtaker.polish.Polisher
import com.wordtaker.keyboard.wordtaker.settings.SettingsSource
import com.wordtaker.keyboard.wordtaker.settings.SettingsState
import com.wordtaker.keyboard.wordtaker.speech.CaptureStartFailure
import com.wordtaker.keyboard.wordtaker.speech.CaptureStartResult
import com.wordtaker.keyboard.wordtaker.speech.MicPermissionRequiredException
import com.wordtaker.keyboard.wordtaker.speech.AsrDecodeException
import com.wordtaker.keyboard.wordtaker.speech.AsrFailureException
import com.wordtaker.keyboard.wordtaker.speech.AsrInitializationException
import com.wordtaker.keyboard.wordtaker.speech.AsrModelCorruptException
import com.wordtaker.keyboard.wordtaker.speech.AsrModelMissingException
import com.wordtaker.keyboard.wordtaker.speech.AsrOutOfMemoryException
import com.wordtaker.keyboard.wordtaker.speech.AsrResult
import com.wordtaker.keyboard.wordtaker.speech.ModelNotReadyException
import com.wordtaker.keyboard.wordtaker.speech.PendingAsrResult
import com.wordtaker.keyboard.wordtaker.speech.RecorderReadException
import com.wordtaker.keyboard.wordtaker.speech.SpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// 录音结束后处理仍在后台并发；phase 只聚合当前录音、Success 与 FIFO 队首阶段，
// 不阻止用户继续键入或开始下一段录音。
enum class VoicePhase { Idle, Recording, Recognizing, Polishing, Success }

/** One-shot events the UI must act on (launch permission / model-download flows). */
enum class VoiceEvent { PermissionRequired, ModelRequired }

enum class VoiceSegmentDiscardReason { PrivacyUnavailable, EditorSessionChanged }

fun interface VoiceStartHaptic {
    fun pulse()

    companion object {
        val NONE = VoiceStartHaptic { }
    }
}

/** Immutable UI state for the voice panel. */
data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.Idle,
    val recording: Boolean = false,
    val level: Float = 0f,
    val busy: Boolean = false,
    val polishOutcome: PolishOutcomeKind? = null,
) {
    val isProcessing: Boolean get() = busy
}

data class VoiceCommit(
    val text: String,
    val editorSessionToken: Long,
) {
    fun belongsTo(editorSessionToken: Long): Boolean =
        this.editorSessionToken == editorSessionToken
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
    private val settingsRepository: SettingsSource,
    private val toneController: VoiceToneFeedback,
    private val startHaptic: VoiceStartHaptic,
    private val privacySource: VoicePrivacySource = VoicePrivacySource.STRICT,
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // Emits the final (polished) text when a segment completes successfully.
    // The IME host collects this to commit the text into the focused input field.
    // 容量 16：多段并行时可能连续快速完成，收集方在主线程，防 tryEmit 丢文本。
    private val _committed = MutableSharedFlow<VoiceCommit>(extraBufferCapacity = 16)
    val committed: SharedFlow<VoiceCommit> = _committed.asSharedFlow()

    private val _event = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<VoiceEvent> = _event.asSharedFlow()

    private val _discarded = MutableSharedFlow<VoiceSegmentDiscardReason>(extraBufferCapacity = 4)
    val discarded: SharedFlow<VoiceSegmentDiscardReason> = _discarded.asSharedFlow()

    // 「有 N 段在处理」：从点击结束（开始收尾 flush）起计入，润色上屏完成后减一。
    // UI 用它画多猫/角标视觉反馈。
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    // Until DataStore has emitted successfully, cloud processing is fail-closed. A flow failure
    // resets this snapshot to local-only instead of retaining a previously permissive value.
    private val settings = MutableStateFlow(SettingsState(localRecognitionOnly = true))
    @Volatile
    private var settingsLoaded = false

    private var levelJob: Job? = null

    private var recordingActive = false
    private var recordingLevel = 0f

    // Only capture open/release is serialized. Whole-utterance decode continues on the
    // recognizer actor and never blocks the next recording from opening its microphone.
    private var startJob: Job? = null
    private var startRequested = false
    private var captureReleaseJob: Job? = null
    private var recordingGuard = SegmentPrivacyGuard.strict()

    // 本段是否真的开了麦（startJob 里 engine.start() 已执行）。cancel/收键盘只有在
    // 开了麦时才需要 engine.cancel()，避免打断上一段还在 flush 的 engine.stop()。
    @Volatile
    private var micOpen = false

    /** 一段录音的提交任务：队列 id + raw 原文 + 已并发启动的润色/直出结果。 */
    private class CommitJob(
        val id: Long,
        val raw: String,
        val polished: Deferred<PolishResult>,
        val guard: SegmentPrivacyGuard,
    )

    private data class RecognitionJob(
        val id: Long,
        val pending: PendingAsrResult,
        val guard: SegmentPrivacyGuard,
    )

    private data class SegmentPrivacyGuard(
        val editorSessionToken: Long,
        val decision: VoicePrivacyDecision,
        val privacyUnavailable: Boolean,
        val editorSessionChanged: Boolean,
    ) {
        companion object {
            fun strict() = SegmentPrivacyGuard(
                editorSessionToken = VoicePrivacyContext.INVALID_SESSION_TOKEN,
                decision = VoicePrivacyPolicy.decide(
                    context = VoicePrivacyContext.STRICT,
                    localRecognitionOnly = true,
                ),
                privacyUnavailable = true,
                editorSessionChanged = false,
            )
        }
    }

    private data class SegmentProgress(
        val id: Long,
        var phase: VoicePhase,
    )

    // FIFO 提交管线：润色各自并发跑（多只小猫并行干活），worker 按开始顺序 await
    // 逐条上屏（排队交卷），保证上屏顺序与录音开始顺序严格一致。
    private val commitQueue = Channel<CommitJob>(Channel.UNLIMITED)
    private val recognitionQueue = Channel<RecognitionJob>(Channel.UNLIMITED)
    private val segmentProgress = ArrayDeque<SegmentProgress>()
    private var nextSegmentId = 0L
    private var successVisible = false
    private var completionOutcome: PolishOutcomeKind? = null
    private var successJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings
                .catch {
                    settingsLoaded = false
                    settings.value = SettingsState(localRecognitionOnly = true)
                    Log.i(TAG, "settings unavailable; cloud disabled")
                }
                .collect {
                    settings.value = it
                    settingsLoaded = true
                }
        }
        viewModelScope.launch {
            for (job in recognitionQueue) {
                recognizeAndQueueCommit(job)
            }
        }
        viewModelScope.launch {
            for (job in commitQueue) {
                try {
                    val result = job.polished.await()
                    val output = result.text
                    val commitGuard = refreshPrivacyGuard(job.guard)
                    val discardReason = commitGuard.discardReason()
                    if (discardReason != null) {
                        discardForPrivacy(job.id, discardReason)
                        continue
                    }
                    // Commit FIRST (non-suspending tryEmit) so text is emitted even if
                    // the viewModelScope is cancelled before historyRepository.add completes.
                    _committed.tryEmit(
                        VoiceCommit(
                            text = output,
                            editorSessionToken = commitGuard.editorSessionToken,
                        ),
                    )
                    Log.i(TAG, "commit emitted: ${output.length} chars")
                    finishProcessingWithSuccess(job.id, result.outcome)
                    val historyGuard = refreshPrivacyGuard(commitGuard)
                    if (
                        historyGuard.discardReason() == null &&
                        historyGuard.decision.saveHistory
                    ) {
                        runCatching { historyRepository.add(job.raw, output, result.outcome) }
                            .onSuccess { Log.d(TAG, "history: written") }
                            .onFailure { Log.i(TAG, "history: write failed") }
                        if (!settings.value.minimal) {
                            _toast.value = "已写入历史"
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "commit: failed")
                    discardProcessing(job.id)
                }
            }
        }
    }

    fun onTap() {
        if (recordingActive) finishSegment() else startRecording()
    }

    /**
     * User-initiated abort from the visible 取消 button：丢弃当前录音段（不上屏、
     * 不写历史）。已在队列中处理的段不受影响，会继续完成上屏。
     */
    fun cancel() {
        if (!recordingActive && !startRequested) return
        levelJob?.cancel()
        levelJob = null
        if (startRequested && !recordingActive) {
            startRequested = false
            startJob?.cancel()
            playEndTone()
            refreshUiState()
            return
        }
        val captureClosed = if (micOpen) {
            runCatching { speechEngine.cancel() }.isSuccess
        } else {
            true
        }
        micOpen = false
        if (captureClosed) playEndTone()
        recordingActive = false
        recordingLevel = 0f
        refreshUiState()
        Log.d(TAG, "cancel: current segment discarded")
    }

    /**
     * External entry point (toolbar voice icon / long-press space / CAT_VOICE key):
     * starts recording whenever no segment is currently recording; never ends one.
     */
    fun startFromExternal() {
        if (!recordingActive) {
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
        if (!recordingActive && !startRequested) return
        levelJob?.cancel()
        levelJob = null
        if (startRequested && !recordingActive) {
            startRequested = false
            startJob?.cancel()
            playEndTone()
            refreshUiState()
            return
        }
        val captureClosed = if (micOpen) {
            runCatching { speechEngine.cancel() }.isSuccess
        } else {
            true
        }
        micOpen = false
        if (captureClosed) playEndTone()
        recordingActive = false
        recordingLevel = 0f
        refreshUiState()
        Log.d(TAG, "stopRecordingAndEndTone: discarded recording")
    }

    private fun playEndTone() {
        val current = settings.value
        runCatching {
            toneController.endBeep(current.tone, current.toneStyle, toneVolume())
        }.onFailure {
            Log.i(TAG, "end tone failed")
        }
    }

    /** 语音提示音音量系数 0..1（用户滑杆，仅作用于结束喵叫）。 */
    private fun toneVolume(): Float = settings.value.toneVolume / 100f

    private fun beginProcessing(): Long {
        val id = nextSegmentId++
        segmentProgress.addLast(SegmentProgress(id, VoicePhase.Recognizing))
        _pending.value = segmentProgress.size
        refreshUiState()
        return id
    }

    private fun updateProcessingPhase(id: Long, phase: VoicePhase) {
        segmentProgress.firstOrNull { it.id == id }?.phase = phase
        refreshUiState()
    }

    private fun discardProcessing(id: Long) {
        segmentProgress.removeAll { it.id == id }
        _pending.value = segmentProgress.size
        refreshUiState()
    }

    private fun finishProcessingWithSuccess(id: Long, outcome: PolishOutcomeKind) {
        segmentProgress.removeAll { it.id == id }
        _pending.value = segmentProgress.size
        completionOutcome = outcome
        successVisible = true
        refreshUiState()

        successJob?.cancel()
        successJob = viewModelScope.launch {
            delay(SUCCESS_DURATION_MS)
            successVisible = false
            completionOutcome = null
            refreshUiState()
        }
    }

    private fun refreshUiState() {
        val phase = when {
            recordingActive -> VoicePhase.Recording
            successVisible -> VoicePhase.Success
            else -> segmentProgress.firstOrNull()?.phase ?: VoicePhase.Idle
        }
        _state.value = VoiceUiState(
            phase = phase,
            recording = recordingActive,
            level = if (recordingActive) recordingLevel else 0f,
            busy = phase == VoicePhase.Recognizing || phase == VoicePhase.Polishing,
            polishOutcome = completionOutcome.takeIf { phase == VoicePhase.Success },
        )
    }

    /** 润色一段原文；失败/超时回退原文。 */
    private suspend fun polishOrFallback(raw: String): PolishResult {
        val role = settings.value.role
        return try {
            withTimeout(POLISH_TIMEOUT_MS) { polisher.polishResult(raw, role) }
                .also { Log.i(TAG, "polish: completed outcome=${it.outcome.name}") }
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                Log.i(TAG, "polish: timeout after ${POLISH_TIMEOUT_MS}ms, falling back to raw")
                PolishResult(raw, PolishOutcomeKind.FallbackTimeout)
            } else {
                throw e  // true scope cancellation — propagate
            }
        } catch (e: Exception) {
            Log.i(TAG, "polish: unexpected failure, falling back to raw")
            PolishResult(raw, PolishOutcomeKind.FallbackUnknown)
        }
    }

    /** Consume first-layer finals in FIFO; only a successful final may enter layer two. */
    private suspend fun recognizeAndQueueCommit(job: RecognitionJob) {
        val asr: AsrResult = try {
            job.pending.await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: AsrFailureException) {
            handleAsrFailure(job.id, error)
            return
        } catch (_: Exception) {
            handleAsrFailure(job.id, AsrDecodeException())
            return
        }
        val prepared = TranscriptText.prepare(asr.text)
        Log.d(
            TAG,
            "ASR final: ${prepared.visibleGraphemeCount} graphemes model=${asr.modelId}",
        )
        if (prepared.visibleGraphemeCount == 0) {
            _toast.value = "未识别到语音"
            discardProcessing(job.id)
            return
        }

        val guard = refreshPrivacyGuard(job.guard)
        val discardReason = guard.discardReason()
        if (discardReason != null) {
            discardForPrivacy(job.id, discardReason)
            return
        }
        val exceedsLocalDirectThreshold =
            prepared.visibleGraphemeCount > LOCAL_DIRECT_MAX_GRAPHEMES
        val polishAvailable = guard.decision.allowCloudPolish && exceedsLocalDirectThreshold &&
            runCatching { polisher.isAvailable() }
                .onFailure { Log.i(TAG, "polish availability failed") }
                .getOrDefault(false)
        val result = when {
            !exceedsLocalDirectThreshold -> CompletableDeferred(
                PolishResult(prepared.text, PolishOutcomeKind.ShortDirect),
            )
            polishAvailable -> {
                updateProcessingPhase(job.id, VoicePhase.Polishing)
                viewModelScope.async { polishOrFallback(prepared.text) }
            }
            else -> CompletableDeferred(
                PolishResult(prepared.text, PolishOutcomeKind.OfflineDirect),
            )
        }
        if (
            commitQueue.trySend(
                CommitJob(job.id, prepared.text, result, guard),
            ).isFailure
        ) {
            result.cancel()
            discardProcessing(job.id)
        }
    }

    private fun handleAsrFailure(id: Long, error: AsrFailureException) {
        when (error) {
            is AsrModelMissingException,
            is AsrModelCorruptException,
            is AsrInitializationException,
            is AsrOutOfMemoryException,
            -> {
                _event.tryEmit(VoiceEvent.ModelRequired)
                _toast.value = when (error) {
                    is AsrModelMissingException -> "缺少本地语音模型"
                    is AsrModelCorruptException -> "本地语音模型校验失败"
                    is AsrOutOfMemoryException -> "设备内存不足，语音模型无法运行"
                    else -> "本地语音模型初始化失败"
                }
            }
            else -> _toast.value = "语音识别失败"
        }
        Log.i(TAG, "ASR failed: ${error::class.simpleName}")
        discardProcessing(id)
    }

    private fun effectiveLocalRecognitionOnly(): Boolean =
        !settingsLoaded || settings.value.localRecognitionOnly

    private fun readPrivacyContext(): VoicePrivacyContext =
        runCatching { privacySource.current() }
            .getOrElse {
                Log.i(TAG, "privacy source unavailable; strict policy applied")
                VoicePrivacyContext.STRICT
            }

    private fun startPrivacyGuard(): SegmentPrivacyGuard {
        val context = readPrivacyContext()
        return SegmentPrivacyGuard(
            editorSessionToken = context.editorSessionToken,
            decision = VoicePrivacyPolicy.decide(
                context = context,
                localRecognitionOnly = effectiveLocalRecognitionOnly(),
            ),
            privacyUnavailable = !context.hasVerifiedEditor,
            editorSessionChanged = false,
        )
    }

    private fun refreshPrivacyGuard(previous: SegmentPrivacyGuard): SegmentPrivacyGuard {
        val context = readPrivacyContext()
        val currentDecision = VoicePrivacyPolicy.decide(
            context = context,
            localRecognitionOnly = effectiveLocalRecognitionOnly(),
        )
        val unavailable = previous.privacyUnavailable || !context.hasVerifiedEditor
        val sessionChanged = previous.editorSessionChanged || (
            !unavailable && context.editorSessionToken != previous.editorSessionToken
        )
        return previous.copy(
            decision = previous.decision.restrictWith(currentDecision),
            privacyUnavailable = unavailable,
            editorSessionChanged = sessionChanged,
        )
    }

    private fun SegmentPrivacyGuard.discardReason(): VoiceSegmentDiscardReason? = when {
        editorSessionChanged -> VoiceSegmentDiscardReason.EditorSessionChanged
        privacyUnavailable -> VoiceSegmentDiscardReason.PrivacyUnavailable
        else -> null
    }

    private fun discardForPrivacy(id: Long, reason: VoiceSegmentDiscardReason) {
        _discarded.tryEmit(reason)
        _toast.value = when (reason) {
            VoiceSegmentDiscardReason.EditorSessionChanged -> "输入框已切换，语音内容已丢弃"
            VoiceSegmentDiscardReason.PrivacyUnavailable -> "无法确认输入框隐私状态，语音内容已丢弃"
        }
        Log.i(TAG, "segment discarded: ${reason.name}")
        discardProcessing(id)
    }

    private fun startRecording() {
        // Pre-flight before permission or AudioRecord work so an unavailable runtime model
        // produces the download/repair UI without opening or buffering microphone PCM.
        if (!speechEngine.isReady()) {
            when (val failure = speechEngine.readinessFailure()) {
                is AsrModelMissingException -> {
                    _event.tryEmit(VoiceEvent.ModelRequired)
                    _toast.value = "缺少本地语音模型"
                }
                is AsrModelCorruptException -> {
                    _event.tryEmit(VoiceEvent.ModelRequired)
                    _toast.value = "本地语音模型校验失败"
                }
                is AsrInitializationException -> {
                    _event.tryEmit(VoiceEvent.ModelRequired)
                    _toast.value = "本地语音模型初始化失败"
                }
                is AsrOutOfMemoryException -> {
                    _event.tryEmit(VoiceEvent.ModelRequired)
                    _toast.value = "设备内存不足，语音模型无法运行"
                }
                else -> _toast.value = "语音正在准备，请稍候"
            }
            Log.d(TAG, "startRecording: engine not ready")
            return
        }
        if (recordingActive || startRequested) return
        // Capture is not visible as Recording until the typed start result confirms success.
        // startRequested is the re-entry guard while a prior segment releases its microphone.
        startRequested = true
        recordingLevel = 0f
        recordingGuard = startPrivacyGuard()
        refreshUiState()
        val priorRelease = captureReleaseJob
        startJob = viewModelScope.launch {
            priorRelease?.join()
            // Waiting may be cancelled by the user/lifecycle; never open a ghost capture.
            if (!startRequested) {
                Log.d(TAG, "startRecording: aborted before mic open")
                return@launch
            }
            val result = try {
                speechEngine.start()
            } catch (error: Throwable) {
                CaptureStartResult.Failed(CaptureStartFailure.RecorderStartFailed(error))
            }
            when (result) {
                CaptureStartResult.Started -> {
                    micOpen = true
                    recordingActive = true
                    startRequested = false
                    refreshUiState()
                    Log.d(TAG, "startRecording: phase=Recording")
                    levelJob?.cancel()
                    levelJob = viewModelScope.launch {
                        while (isActive) {
                            delay(LEVEL_TICK_MS)
                            recordingLevel = speechEngine.currentLevel().coerceIn(0f, 1f)
                            refreshUiState()
                        }
                    }
                    runCatching { startHaptic.pulse() }
                        .onFailure { Log.i(TAG, "start haptic failed") }
                }
                is CaptureStartResult.Failed -> {
                    startRequested = false
                    recordingActive = false
                    micOpen = false
                    handleCaptureStartFailure(result.failure)
                    refreshUiState()
                }
            }
        }
    }

    private fun handleCaptureStartFailure(failure: CaptureStartFailure) {
        when (failure) {
            CaptureStartFailure.PermissionDenied -> {
                _event.tryEmit(VoiceEvent.PermissionRequired)
                _toast.value = "需要麦克风权限"
            }
            is CaptureStartFailure.ModelNotReady -> {
                _event.tryEmit(VoiceEvent.ModelRequired)
                _toast.value = when (failure.failure) {
                    is AsrModelMissingException -> "缺少本地语音模型"
                    is AsrModelCorruptException -> "本地语音模型校验失败"
                    is AsrOutOfMemoryException -> "设备内存不足，语音模型无法运行"
                    is AsrInitializationException -> "本地语音模型初始化失败"
                    else -> "语音模型正在准备，请稍候"
                }
            }
            is CaptureStartFailure.RecorderStartFailed -> _toast.value = "无法启动录音"
        }
        Log.i(TAG, "capture start failed: ${failure::class.simpleName}")
    }

    /**
     * 点击结束：立即释放当前录音交互（可马上开新段），当前段在后台收尾 —— engine.stop()
     * 本地 flush 出「攒段 + 尾巴」整段文本，随即并发启动润色并入 FIFO 队列。
     */
    private fun finishSegment() {
        levelJob?.cancel()
        levelJob = null
        recordingActive = false
        recordingLevel = 0f
        micOpen = false
        val segmentId = beginProcessing()
        Log.d(TAG, "finishSegment: flushing segment in background")
        val priorStart = startJob
        val guard = refreshPrivacyGuard(recordingGuard)
        captureReleaseJob = viewModelScope.launch {
            priorStart?.join()
            var captureClosed = false
            val pending: PendingAsrResult = try {
                speechEngine.stopCapture().also { captureClosed = true }
            } catch (e: CancellationException) {
                captureClosed = runCatching { speechEngine.cancel() }.isSuccess
                throw e  // scope teardown — propagate
            } catch (e: MicPermissionRequiredException) {
                captureClosed = runCatching { speechEngine.cancel() }.isSuccess
                Log.i(TAG, "ASR: MicPermissionRequired")
                _event.tryEmit(VoiceEvent.PermissionRequired)
                _toast.value = "需要麦克风权限"
                discardProcessing(segmentId)
                return@launch
            } catch (e: ModelNotReadyException) {
                captureClosed = runCatching { speechEngine.cancel() }.isSuccess
                Log.i(TAG, "ASR: ModelNotReady")
                _event.tryEmit(VoiceEvent.ModelRequired)
                _toast.value = "语音模型正在准备，请稍候"
                discardProcessing(segmentId)
                return@launch
            } catch (e: RecorderReadException) {
                captureClosed = runCatching { speechEngine.cancel() }.isSuccess
                Log.i(TAG, "ASR: capture read failed")
                _toast.value = "录音读取失败"
                discardProcessing(segmentId)
                return@launch
            } catch (e: Exception) {
                captureClosed = runCatching { speechEngine.cancel() }.isSuccess
                Log.i(TAG, "ASR: capture stop failed")
                _toast.value = "录音停止失败"
                discardProcessing(segmentId)
                return@launch
            } finally {
                // A successful stop freezes/drains PCM and closes AudioRecord. If stop fails,
                // cancel is the close fallback; never play into a capture that might remain open.
                if (captureClosed) playEndTone()
            }
            if (
                recognitionQueue.trySend(
                    RecognitionJob(
                        id = segmentId,
                        pending = pending,
                        guard = guard,
                    ),
                ).isFailure
            ) {
                discardProcessing(segmentId)
            }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    override fun onCleared() {
        levelJob?.cancel()
        // stopRecordingAndEndTone is a no-op if there is no active recording. viewModelScope
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
        private val settingsRepository: SettingsSource,
        private val toneController: VoiceToneFeedback,
        private val startHaptic: VoiceStartHaptic,
        private val privacySource: VoicePrivacySource = VoicePrivacySource.STRICT,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VoiceViewModel(
                speechEngine,
                polisher,
                historyRepository,
                settingsRepository,
                toneController,
                startHaptic,
                privacySource,
            ) as T
        }
    }

    private companion object {
        const val TAG = "VoiceVM"
        const val LEVEL_TICK_MS = 140L
        const val POLISH_TIMEOUT_MS = 10_000L
        const val LOCAL_DIRECT_MAX_GRAPHEMES = 6
        const val SUCCESS_DURATION_MS = 1_200L

    }
}
