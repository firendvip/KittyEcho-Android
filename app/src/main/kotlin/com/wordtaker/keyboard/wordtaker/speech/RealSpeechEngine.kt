package com.wordtaker.keyboard.wordtaker.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Real on-device speech engine: continuous PCM capture ([PcmRecorder]) decoded by the
 * offline SenseVoice ASR ([SenseVoiceController]). Fully on-device, no network.
 *
 * Preconditions surfaced as typed exceptions so the UI can react:
 *   - [MicPermissionRequiredException] when RECORD_AUDIO is missing (IME must route
 *     the user through a permission Activity, since a service can't request it).
 *   - [ModelNotReadyException] when the ~228MB model isn't downloaded or fails to load.
 */
class RealSpeechEngine(private val context: Context) : SpeechEngine {

    private val recorder = PcmRecorder()
    private val sense = SenseVoiceController(context)

    init {
        // Install the bundled model (assets -> filesDir) and warm up the recognizer on a
        // background thread. ensureInstalled() copies a ~228MB file and must never run on
        // the main thread; prepare()'s own heavy ONNX load is already off-thread.
        Thread {
            ModelAssetInstaller.ensureInstalled(context)
            if (ModelDownloader.isDownloaded(context)) sense.prepare()
        }.apply { isDaemon = true }.start()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by hasMicPermission()
    override fun start() {
        if (!hasMicPermission()) return
        if (!ModelDownloader.isDownloaded(context)) return
        recorder.start()
    }

    override suspend fun stop(): String = withContext(Dispatchers.IO) {
        if (!hasMicPermission()) {
            recorder.cancel()
            throw MicPermissionRequiredException()
        }
        if (!ModelDownloader.isDownloaded(context)) {
            recorder.cancel()
            throw ModelNotReadyException()
        }
        val samples = recorder.stop()
        if (samples.isEmpty()) return@withContext ""
        if (!sense.isReady && !awaitReady()) {
            throw ModelNotReadyException()
        }
        sense.transcribe(samples).trim()
    }

    override fun cancel() {
        recorder.cancel()
    }

    /** Triggers a prepare and polls readiness up to ~10s. */
    private suspend fun awaitReady(): Boolean {
        sense.prepare()
        var waited = 0L
        while (!sense.isReady && waited < READY_TIMEOUT_MS) {
            delay(READY_POLL_MS)
            waited += READY_POLL_MS
        }
        return sense.isReady
    }

    private companion object {
        const val READY_POLL_MS = 100L
        const val READY_TIMEOUT_MS = 10_000L
    }
}
