package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * On-device ASR using sherpa-onnx + SenseVoice. The ~228MB int8 ONNX model is NOT
 * bundled in the APK — it is downloaded on first launch (see [ModelDownloader]) into
 * `filesDir/sensevoice/`. OfflineRecognizer is built from absolute FILE PATHS using
 * its no-AssetManager constructor (which calls the native `newFromFile`), so the
 * model is read straight from internal storage.
 *
 * prepare() builds the recognizer on a background thread (model init is slow);
 * transcribe() runs synchronously on the caller's thread (caller uses Dispatchers.IO).
 * Everything is exception-safe: failures log and degrade, never crash the IME.
 *
 * Thread-safety: `loading` is an AtomicBoolean so concurrent prepare() calls (the
 * onCreate warmup vs. an on-demand awaitEngineReady) never build two recognizers.
 * `recognizer` is read under `lock` in both transcribe() and destroy() so a
 * transcribe in flight can never touch a native object that destroy() has freed.
 */
class SenseVoiceController(private val context: Context) {

    @Volatile
    var isReady = false
        private set

    private val loading = AtomicBoolean(false)

    // Guards the recognizer's native lifecycle: transcribe() holds it across the
    // whole decode, destroy() holds it across release(), so the two never overlap.
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun prepare(onReady: () -> Unit = {}, onError: (String) -> Unit = {}) {
        if (isReady) { onReady(); return }
        // Model must be downloaded to internal storage first.
        if (!ModelDownloader.isDownloaded(context)) {
            Log.w(TAG, "prepare aborted: model not downloaded")
            main.post { onError("模型未下载") }
            return
        }
        // Atomically claim the loading slot. If another caller already owns it, don't
        // start a second init — but don't silently drop THIS caller's callbacks either:
        // poll for readiness off-thread so onReady/onError still fire.
        if (!loading.compareAndSet(false, true)) {
            awaitExistingLoad(onReady, onError)
            return
        }
        try {
            io.execute {
                try {
                    val modelPath = ModelDownloader.modelFile(context).absolutePath
                    val tokensPath = ModelDownloader.tokensFile(context).absolutePath
                    // Cap threads to available cores (min 4) to stay responsive.
                    val threads = minOf(4, Runtime.getRuntime().availableProcessors())
                    val featConfig = getFeatureConfig(sampleRate = 16000, featureDim = 80)
                    val senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelPath,
                        language = "auto",
                        useInverseTextNormalization = true
                    )
                    val modelConfig = OfflineModelConfig(
                        senseVoice = senseVoice,
                        tokens = tokensPath,
                        numThreads = threads,
                        debug = false,
                        provider = "cpu",
                        modelType = "sense_voice"
                    )
                    val config = OfflineRecognizerConfig(
                        featConfig = featConfig,
                        modelConfig = modelConfig,
                        decodingMethod = "greedy_search"
                    )
                    // No AssetManager -> native newFromFile loads from absolute paths.
                    val built = OfflineRecognizer(config = config)
                    synchronized(lock) {
                        recognizer = built
                        isReady = true
                    }
                    loading.set(false)
                    main.post { onReady() }
                } catch (t: Throwable) {
                    loading.set(false)
                    Log.e(TAG, "SenseVoice init failed", t)
                    main.post { onError(t.message ?: t.javaClass.simpleName) }
                }
            }
        } catch (e: RejectedExecutionException) {
            // Executor already shut down (destroy() ran). Release the slot.
            loading.set(false)
            Log.e(TAG, "prepare rejected: executor shut down", e)
            main.post { onError("engine destroyed") }
        }
    }

    /**
     * Another prepare() is already building the recognizer. Poll readiness on a
     * throwaway thread (the `io` executor is busy with the build) so this caller's
     * callbacks still fire instead of being silently dropped.
     */
    private fun awaitExistingLoad(onReady: () -> Unit, onError: (String) -> Unit) {
        Thread {
            var waited = 0L
            while (!isReady && loading.get() && waited < AWAIT_TIMEOUT_MS) {
                try {
                    Thread.sleep(AWAIT_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                waited += AWAIT_POLL_MS
            }
            if (isReady) main.post { onReady() }
            else main.post { onError("engine still loading") }
        }.apply { isDaemon = true }.start()
    }

    /** Runs synchronously on the CALLER's thread. Returns "" on failure. */
    fun transcribe(samples: FloatArray): String {
        return try {
            synchronized(lock) {
                val r = recognizer ?: return ""
                val stream = r.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    r.decode(stream)
                    r.getResult(stream).text
                } finally {
                    stream.release()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe failed", t)
            ""
        }
    }

    fun destroy() {
        // Take the recognizer under the lock so an in-flight transcribe finishes
        // before we release the native object (no use-after-free).
        val toRelease = synchronized(lock) {
            val r = recognizer
            recognizer = null
            isReady = false
            r
        }
        try {
            toRelease?.release()
        } catch (t: Throwable) {
            Log.e(TAG, "release failed", t)
        }
        try {
            io.shutdownNow()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        private const val TAG = "SenseVoice"
        // Polling cadence/cap for a caller waiting on an in-flight prepare().
        private const val AWAIT_POLL_MS = 100L
        private const val AWAIT_TIMEOUT_MS = 30_000L
    }
}
