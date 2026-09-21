package com.wordtaker.keyboard.wordtaker.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Production on-device ASR: full 16 kHz mono PCM capture followed by one non-streaming
 * Paraformer decode. Capture release and decode are deliberately separate so another utterance
 * can start while the process-wide single recognizer actor handles queued work in strict FIFO.
 */
class RealSpeechEngine(private val context: Context) : SpeechEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorder = PcmRecorder()
    private val decoder = SherpaParaformerDecoder(ParaformerPrivateModelStore(context))
    private val actor = ParaformerRecognitionActor(
        decoder = decoder,
        scope = scope,
    )
    private val legacyModelCleaner = ParaformerLegacyModelCleaner(context)
    private val bundledInstaller = ParaformerBundledAssetInstaller(context)
    private val preparationLock = Any()

    private val _modelState = MutableStateFlow(ParaformerLifecycleState())
    internal val modelState: StateFlow<ParaformerLifecycleState> = _modelState.asStateFlow()
    @Volatile
    private var preparationFailure: AsrFailureException? = null
    private var preparationJob: Job? = null

    private val _partials = MutableStateFlow("")
    override val partials: StateFlow<String> = _partials.asStateFlow()

    init {
        ensureModelPreparationStarted()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // Permission is surfaced by stopCapture() through its dedicated typed exception. Readiness
    // here represents only whether the fail-closed local model can accept a recording.
    override fun isReady(): Boolean = actor.isReady()

    override fun readinessFailure(): AsrFailureException? =
        preparationFailure ?: actor.readinessFailure().takeIf {
            _modelState.value.phase == ParaformerModelPhase.Error
        }

    override suspend fun prepareInstalledModel(): AsrFailureException? {
        ensureModelPreparationStarted()?.join()
        return readinessFailure()
    }

    internal fun retryModelPreparation() {
        if (!actor.isReady()) ensureModelPreparationStarted()
    }

    private fun ensureModelPreparationStarted(): Job? = synchronized(preparationLock) {
        if (actor.isReady()) {
            _modelState.value = ParaformerLifecycleState(ParaformerModelPhase.Ready)
            return@synchronized null
        }
        preparationJob?.takeIf { it.isActive }?.let { return@synchronized it }
        preparationFailure = null
        _modelState.value = ParaformerLifecycleState(ParaformerModelPhase.Installing)
        scope.launch {
            try {
                bundledInstaller.ensureInstalled()
                _modelState.value = ParaformerLifecycleState(ParaformerModelPhase.Initializing)
                actor.prepareAndAwait()
                preparationFailure = null
                _modelState.value = ParaformerLifecycleState(ParaformerModelPhase.Ready)
            } catch (error: ParaformerAttemptException) {
                preparationFailure = when (error.failure) {
                    ParaformerAttemptFailure.Integrity -> AsrModelCorruptException(error)
                    ParaformerAttemptFailure.Storage -> AsrInitializationException(error)
                }
                _modelState.value = ParaformerLifecycleState(
                    phase = ParaformerModelPhase.Error,
                    failure = when (error.failure) {
                        ParaformerAttemptFailure.Integrity -> ParaformerModelFailure.Integrity
                        ParaformerAttemptFailure.Storage -> ParaformerModelFailure.Storage
                    },
                )
            } catch (error: AsrOutOfMemoryException) {
                preparationFailure = error
                _modelState.value = ParaformerLifecycleState(
                    ParaformerModelPhase.Error,
                    ParaformerModelFailure.Memory,
                )
            } catch (error: OutOfMemoryError) {
                preparationFailure = AsrOutOfMemoryException(error)
                _modelState.value = ParaformerLifecycleState(
                    ParaformerModelPhase.Error,
                    ParaformerModelFailure.Memory,
                )
            } catch (error: AsrFailureException) {
                preparationFailure = error
                _modelState.value = ParaformerLifecycleState(
                    ParaformerModelPhase.Error,
                    ParaformerModelFailure.Initialization,
                )
            } catch (error: Throwable) {
                preparationFailure = AsrInitializationException(error)
                _modelState.value = ParaformerLifecycleState(
                    ParaformerModelPhase.Error,
                    ParaformerModelFailure.Initialization,
                )
            }
        }.also { preparationJob = it }
    }

    @SuppressLint("MissingPermission")
    override fun start(): CaptureStartResult {
        if (!hasMicPermission()) {
            return CaptureStartResult.Failed(CaptureStartFailure.PermissionDenied)
        }
        if (!actor.isReady()) {
            return CaptureStartResult.Failed(
                CaptureStartFailure.ModelNotReady(readinessFailure()),
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
