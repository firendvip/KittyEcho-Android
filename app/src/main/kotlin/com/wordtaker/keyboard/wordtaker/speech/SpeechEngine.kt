package com.wordtaker.keyboard.wordtaker.speech

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Speech-to-text engine boundary. Production capture is stopped and released by
 * [stopCapture] before whole-utterance recognition continues asynchronously.
 */
interface SpeechEngine {
    /** Begin capturing and recognizing every PCM frame from the start of the session. */
    fun start(): CaptureStartResult

    /** Stop capturing and return the recognized text (攒下的定稿段 + 尾巴，整段). */
    suspend fun stop(): String

    /**
     * Freeze the complete current 16 kHz mono PCM utterance and release the microphone,
     * then return a handle for the queued recognition result. The default adapter keeps
     * existing test/debug engines source-compatible; production overrides it so decode never
     * delays opening the next recording.
     */
    suspend fun stopCapture(): PendingAsrResult {
        val text = stop()
        return PendingAsrResult.completed(
            AsrResult(text = text, modelId = "legacy", modelRevision = "legacy"),
        )
    }

    /** Cancel an active recording without processing. */
    fun cancel()

    /**
     * Cheap, synchronous, non-blocking readiness check used by the UI to decide whether
     * tapping the mic will actually start capturing audio right now. While the runtime model
     * is absent, downloading, or initializing, this returns false so the caller can show an
     * immediate model action instead of
     * flipping into a Recording UI that silently captures nothing (D-1: 初始化期点话筒无反馈).
     */
    fun isReady(): Boolean

    /** Stable typed reason for a fail-closed readiness state, if known. */
    fun readinessFailure(): AsrFailureException? = null

    /** Initialize a newly atomically installed model without opening the microphone. */
    suspend fun prepareInstalledModel(): AsrFailureException? = readinessFailure()

    /** Live partial transcript of the CURRENT recording ("" when idle). */
    val partials: StateFlow<String>

    /** Latest normalized microphone level for non-semantic visual feedback. */
    fun currentLevel(): Float = 0f
}

/** Thrown by a real engine when RECORD_AUDIO has not been granted. */
class MicPermissionRequiredException : Exception()

sealed interface CaptureStartResult {
    data object Started : CaptureStartResult
    data class Failed(val failure: CaptureStartFailure) : CaptureStartResult
}

sealed interface CaptureStartFailure {
    data object PermissionDenied : CaptureStartFailure
    data class ModelNotReady(val failure: AsrFailureException?) : CaptureStartFailure
    data class RecorderStartFailed(val cause: Throwable? = null) : CaptureStartFailure
}

/** Thrown when AudioRecord initialization/start itself fails unexpectedly. */
class RecorderStartException(cause: Throwable) : Exception("audio capture start failed", cause)

/** Thrown when the capture loop reports a negative read result or crashes. */
class RecorderReadException(
    val errorCode: Int? = null,
    cause: Throwable? = null,
) : Exception("audio capture read failed", cause)

/** Thrown when AudioRecord could not be stopped/released with certainty. */
class RecorderStopException(cause: Throwable) : Exception("audio capture stop failed", cause)

/** Thrown by a real engine when the offline ASR model is not installed/ready. */
open class ModelNotReadyException(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

sealed class AsrFailureException(message: String, cause: Throwable? = null) :
    ModelNotReadyException(message, cause)

class AsrModelMissingException : AsrFailureException("offline ASR model is missing")

class AsrModelCorruptException(cause: Throwable? = null) :
    AsrFailureException("offline ASR model failed integrity validation", cause)

class AsrInitializationException(cause: Throwable? = null) :
    AsrFailureException("offline ASR initialization failed", cause)

class AsrOutOfMemoryException(cause: Throwable? = null) :
    AsrFailureException("offline ASR ran out of memory", cause)

class AsrDecodeException(cause: Throwable? = null) :
    AsrFailureException("offline ASR decode failed", cause)

data class AsrResult(
    val text: String,
    val modelId: String,
    val modelRevision: String,
)

fun interface PendingAsrResult {
    suspend fun await(): AsrResult

    companion object {
        fun completed(result: AsrResult): PendingAsrResult = PendingAsrResult { result }
    }
}

/** Mock engine that fakes a recognition delay and returns a fixed transcript. */
class MockSpeechEngine : SpeechEngine {

    private val _partials = MutableStateFlow("")
    override val partials: StateFlow<String> = _partials.asStateFlow()

    override fun start(): CaptureStartResult {
        // No-op for the mock pipeline.
        return CaptureStartResult.Started
    }

    override suspend fun stop(): String {
        delay(RECOGNIZE_DELAY_MS)
        return MOCK_TRANSCRIPT
    }

    override suspend fun stopCapture(): PendingAsrResult = PendingAsrResult.completed(
        AsrResult(MOCK_TRANSCRIPT, "mock", "1"),
    )

    override fun cancel() {
        // No-op for the mock pipeline.
    }

    override fun isReady(): Boolean = true

    private companion object {
        const val RECOGNIZE_DELAY_MS = 1200L
        const val MOCK_TRANSCRIPT =
            "嗯那个我想说的就是这个方案我觉得整体上是可以的然后细节方面可能还需要再讨论一下"
    }
}
