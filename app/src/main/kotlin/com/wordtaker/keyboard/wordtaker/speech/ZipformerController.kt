package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** One decode step's outcome: the latest partial transcript + endpoint flag. */
data class StreamingPartial(val text: String, val isEndpoint: Boolean)

/**
 * Streaming on-device ASR using sherpa-onnx + Zipformer transducer
 * (sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12, int8). The ~72MB model
 * set is bundled in the APK (installed by [ModelAssetInstaller] into
 * `filesDir/zipformer-zh/`; [ModelDownloader] is the network fallback).
 * OnlineRecognizer is built from absolute FILE PATHS (no AssetManager -> native
 * `newFromFile`), so the model is read straight from internal storage.
 *
 * One recognition SESSION (an [OnlineStream]) is active at a time, owned by this
 * controller so the stream's native lifetime is guarded by the same [lock] as the
 * recognizer. Session flow:
 *   startSession() -> feed(samples)* -> finishSession()   (or cancelSession())
 *
 * feed() decodes incrementally and returns the current partial text plus whether
 * the endpoint detector fired (trailing silence => user finished speaking).
 *
 * prepare() builds the recognizer on a background thread (model init is slow);
 * feed()/finishSession() run synchronously on the caller's worker thread.
 * Everything is exception-safe: failures log and degrade, never crash the IME.
 *
 * Thread-safety: `loading` is an AtomicBoolean so concurrent prepare() calls never
 * build two recognizers. `recognizer`/`stream` are only touched under `lock`, so a
 * feed in flight can never race destroy()'s native release (see SenseVoice-era
 * BUG #12 notes: destroy() uses a bounded tryLock and leaks rather than freezes).
 */
class ZipformerController(private val context: Context) {

    @Volatile
    var isReady = false
        private set

    private val loading = AtomicBoolean(false)

    // Guards the native lifecycle of recognizer AND the active session stream.
    // A ReentrantLock (not `synchronized`) so destroy() can tryLock WITH A TIMEOUT and
    // never wedge the caller behind a hung native decode.
    private val lock = ReentrantLock()
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun prepare(onReady: () -> Unit = {}, onError: (String) -> Unit = {}) {
        if (isReady) { onReady(); return }
        // Model must be present in internal storage first.
        if (!ModelDownloader.isDownloaded(context)) {
            Log.w(TAG, "prepare aborted: model not installed")
            main.post { onError("模型未安装") }
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
                    // Cap threads to available cores (min 4) to stay responsive.
                    val threads = minOf(4, Runtime.getRuntime().availableProcessors())
                    val featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)
                    val transducer = OnlineTransducerModelConfig(
                        encoder = ModelDownloader.encoderFile(context).absolutePath,
                        decoder = ModelDownloader.decoderFile(context).absolutePath,
                        joiner = ModelDownloader.joinerFile(context).absolutePath,
                    )
                    val modelConfig = OnlineModelConfig(
                        transducer = transducer,
                        tokens = ModelDownloader.tokensFile(context).absolutePath,
                        numThreads = threads,
                        debug = false,
                        provider = "cpu",
                        // IMPORTANT: leave modelType empty so the native side auto-detects
                        // from the ONNX metadata. This model is a zipformer2; forcing
                        // "zipformer" makes the v1 loader exit() the whole process.
                        modelType = "",
                    )
                    val config = OnlineRecognizerConfig(
                        featConfig = featConfig,
                        modelConfig = modelConfig,
                        endpointConfig = EndpointConfig(
                            // rule1: long trailing silence with nothing decoded yet.
                            rule1 = EndpointRule(false, RULE1_TRAILING_SILENCE_S, 0f),
                            // rule2: short trailing silence AFTER speech => user finished.
                            rule2 = EndpointRule(true, RULE2_TRAILING_SILENCE_S, 0f),
                            // rule3: hard utterance-length cap.
                            rule3 = EndpointRule(false, 0f, RULE3_MAX_UTTERANCE_S),
                        ),
                        enableEndpoint = true,
                        decodingMethod = "greedy_search",
                    )
                    // No AssetManager -> native newFromFile loads from absolute paths.
                    val built = OnlineRecognizer(config = config)
                    lock.lock()
                    try {
                        recognizer = built
                        isReady = true
                    } finally {
                        lock.unlock()
                    }
                    loading.set(false)
                    main.post { onReady() }
                } catch (t: Throwable) {
                    loading.set(false)
                    Log.e(TAG, "Zipformer init failed", t)
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

    /**
     * Opens a new recognition session. Returns false when the engine isn't ready or a
     * session is already active. Safe from any thread.
     */
    fun startSession(): Boolean {
        return try {
            lock.lock()
            try {
                val r = recognizer ?: return false
                if (stream != null) return false // one session at a time
                stream = r.createStream("")
                true
            } finally {
                lock.unlock()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startSession failed", t)
            false
        }
    }

    /**
     * Feeds 16kHz mono float samples into the active session and decodes whatever is
     * ready. Returns the latest partial text and whether the endpoint detector fired.
     *
     * MUST NOT be called on the main thread — callers use a dedicated worker. Returns
     * an empty non-endpoint result when no session is active or on any failure.
     */
    fun feed(samples: FloatArray): StreamingPartial {
        return try {
            lock.lock()
            try {
                val r = recognizer ?: return EMPTY_PARTIAL
                val s = stream ?: return EMPTY_PARTIAL
                s.acceptWaveform(samples, SAMPLE_RATE)
                while (r.isReady(s)) r.decode(s)
                StreamingPartial(r.getResult(s).text, r.isEndpoint(s))
            } finally {
                lock.unlock()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "feed failed", t)
            EMPTY_PARTIAL
        }
    }

    /**
     * Finalizes the active session: flushes remaining audio (inputFinished + drain
     * decode), returns the final text and releases the stream. Returns "" when no
     * session is active or on failure. Worker thread only.
     */
    fun finishSession(): String {
        return try {
            lock.lock()
            try {
                val r = recognizer ?: return ""
                val s = stream ?: return ""
                stream = null
                try {
                    s.inputFinished()
                    while (r.isReady(s)) r.decode(s)
                    r.getResult(s).text
                } finally {
                    s.release()
                }
            } finally {
                lock.unlock()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "finishSession failed", t)
            ""
        }
    }

    /** Discards the active session without decoding. Never throws. */
    fun cancelSession() {
        try {
            lock.lock()
            try {
                val s = stream ?: return
                stream = null
                s.release()
            } finally {
                lock.unlock()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "cancelSession failed", t)
        }
    }

    /**
     * Tears down the recognizer WITHOUT ever blocking the caller: the whole release
     * runs on a throwaway background thread, and it acquires [lock] with a bounded
     * tryLock. If an in-flight decode is hung on native code, we do NOT wait forever —
     * we detach the references, drop readiness, and shut down the executor so the IME
     * can recover instead of freezing. The stale native objects are then leaked
     * (unavoidable if their thread is wedged) — far better than a frozen keyboard.
     */
    fun destroy() {
        Thread {
            var toReleaseRecognizer: OnlineRecognizer? = null
            var toReleaseStream: OnlineStream? = null
            val acquired = try {
                lock.tryLock(DESTROY_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                false
            }
            try {
                // Only read/write the native refs while actually holding the lock,
                // otherwise this races feed()'s locked access (use-after-free).
                if (acquired) {
                    toReleaseStream = stream
                    toReleaseRecognizer = recognizer
                    stream = null
                    recognizer = null
                }
                isReady = false
            } finally {
                if (acquired) lock.unlock()
            }
            if (acquired) {
                try {
                    toReleaseStream?.release()
                    toReleaseRecognizer?.release()
                } catch (t: Throwable) {
                    Log.e(TAG, "release failed", t)
                }
            } else {
                // A decode is wedged on the native side; releasing now would race a
                // use-after-free. Leak it deliberately and let the process reclaim it.
                Log.w(TAG, "destroy: decode still in flight, detaching without native release")
            }
            try {
                io.shutdownNow()
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true; name = "ZipformerDestroy" }.start()
    }

    private companion object {
        private const val TAG = "Zipformer"
        private const val SAMPLE_RATE = 16000
        private val EMPTY_PARTIAL = StreamingPartial("", false)
        // Endpoint rules (sherpa-onnx defaults): silence-only cutoff, post-speech
        // pause cutoff, and a hard utterance cap.
        private const val RULE1_TRAILING_SILENCE_S = 2.4f
        private const val RULE2_TRAILING_SILENCE_S = 1.2f
        private const val RULE3_MAX_UTTERANCE_S = 60f
        // Polling cadence/cap for a caller waiting on an in-flight prepare().
        private const val AWAIT_POLL_MS = 100L
        private const val AWAIT_TIMEOUT_MS = 30_000L
        // How long destroy() waits for an in-flight decode before detaching without
        // a native release.
        private const val DESTROY_LOCK_TIMEOUT_MS = 2_000L
    }
}
