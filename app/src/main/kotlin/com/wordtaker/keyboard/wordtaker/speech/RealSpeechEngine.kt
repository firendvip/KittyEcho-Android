package com.wordtaker.keyboard.wordtaker.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Production on-device ASR: full 16 kHz mono PCM capture followed by one non-streaming
 * Paraformer decode. Capture release and decode are deliberately separate so another utterance
 * can start while the process-wide single recognizer actor handles queued work in strict FIFO.
 */
class RealSpeechEngine(private val context: Context) : SpeechEngine {
    private val recorder = PcmRecorder()
    private val decoder = SherpaParaformerDecoder(ParaformerPrivateModelStore(context))
    private val actor = ParaformerRecognitionActor(
        decoder = decoder,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
    private val legacyModelCleaner = ParaformerLegacyModelCleaner(context)

    private val _partials = MutableStateFlow("")
    override val partials: StateFlow<String> = _partials.asStateFlow()

    init {
        // Hash and initialize off-main. Runtime download/install is owned by the shared model
        // manager; a missing or corrupt private artifact leaves capture fail-closed.
        actor.prepareAsync()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // Permission is surfaced by stopCapture() through its dedicated typed exception. Readiness
    // here represents only whether the fail-closed local model can accept a recording.
    override fun isReady(): Boolean = actor.isReady()

    override fun readinessFailure(): AsrFailureException? = actor.readinessFailure()

    override suspend fun prepareInstalledModel(): AsrFailureException? = withContext(Dispatchers.IO) {
        try {
            actor.prepareAndAwait()
            actor.readinessFailure()
        } catch (error: AsrFailureException) {
            error
        } catch (error: OutOfMemoryError) {
            AsrOutOfMemoryException(error)
        } catch (error: Throwable) {
            AsrInitializationException(error)
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(): CaptureStartResult {
        if (!hasMicPermission()) {
            return CaptureStartResult.Failed(CaptureStartFailure.PermissionDenied)
        }
        if (!actor.isReady()) {
            actor.prepareAsync()
            return CaptureStartResult.Failed(
                CaptureStartFailure.ModelNotReady(actor.readinessFailure()),
            )
        }
        _partials.value = ""
        return try {
            if (recorder.start()) {
                CaptureStartResult.Started
            } else {
                CaptureStartResult.Failed(CaptureStartFailure.RecorderStartFailed())
            }
        } catch (error: RecorderStartException) {
            CaptureStartResult.Failed(CaptureStartFailure.RecorderStartFailed(error))
        } catch (error: Throwable) {
            CaptureStartResult.Failed(CaptureStartFailure.RecorderStartFailed(error))
        }
    }

    override suspend fun stopCapture(): PendingAsrResult = withContext(Dispatchers.IO) {
        if (!hasMicPermission()) {
            recorder.cancel()
            throw MicPermissionRequiredException()
        }
        val samples = recorder.stop()
        _partials.value = ""
        val pending = actor.submit(samples)
        PendingAsrResult {
            pending.await().also(legacyModelCleaner::afterSuccessfulRecognition)
        }
    }

    override suspend fun stop(): String = stopCapture().await().text

    override fun cancel() {
        recorder.cancel()
        _partials.value = ""
    }

    override fun currentLevel(): Float = recorder.currentLevel()
}
