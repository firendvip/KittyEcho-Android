package com.wordtaker.keyboard.wordtaker.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Real on-device STREAMING speech engine: continuous PCM capture ([PcmRecorder])
 * decoded live by the streaming Zipformer transducer ([ZipformerController]).
 * Fully on-device, no network.
 *
 * Pipeline: the capture thread pushes float chunks into [queue]; a dedicated decode
 * worker drains it into the active Zipformer session, publishing live partials into
 * [partials]. batch3-C 攒段模式：endpoint（尾部静音）命中时，worker 把该句定稿文本
 * 追加到 [finalizedSegments] 缓冲（不对外发射、不上屏），随即 reset 解码流继续听
 * 下一句 —— 录音不停止。[stop]（用户点击结束）flushes the session and returns
 * 「攒下的所有定稿段 + 最后未定稿的尾巴」拼成的整段文本。
 *
 * Cold-start fallback: if the recognizer wasn't ready when [start] ran (first-use
 * warm-up still loading), the recorder still captures; [stop] then waits briefly for
 * readiness and batch-decodes the accumulated buffer through a one-shot session.
 *
 * Preconditions surfaced as typed exceptions so the UI can react:
 *   - [MicPermissionRequiredException] when RECORD_AUDIO is missing (IME must route
 *     the user through a permission Activity, since a service can't request it).
 *   - [ModelNotReadyException] when the model isn't installed or fails to load.
 */
class RealSpeechEngine(private val context: Context) : SpeechEngine {

    private val recorder = PcmRecorder()
    private val zip = ZipformerController(context)

    private val _partials = MutableStateFlow("")
    override val partials: StateFlow<String> = _partials.asStateFlow()

    // batch3-C 攒段缓冲：endpoint 定稿的句子只累积在这里，stop() 时与尾巴拼成整段
    // 一次性返回。worker 线程 add；stop() 在 drainWorker（join）之后读取，happens-before
    // 由 Thread.join 保证；abortSession（主线程）清空靠 synchronizedList 保护。
    private val finalizedSegments =
        java.util.Collections.synchronizedList(mutableListOf<String>())

    // Chunk hand-off between the capture thread and the decode worker.
    private val queue = LinkedBlockingQueue<FloatArray>()

    @Volatile
    private var workerRunning = false
    // Set by stop(): the worker drains whatever is left in the queue, then exits.
    @Volatile
    private var draining = false
    private var worker: Thread? = null

    // Whether the CURRENT recording opened a live streaming session at start().
    @Volatile
    private var sessionOpened = false

    // P2-205 自愈：模型安装/预热可能在进程存活期间被再次需要（模型目录被清掉等），
    // 不能只在构造时跑一次。CAS 防并发重入；跑完复位，失败后下次触发可再试。
    private val modelInstallRunning = AtomicBoolean(false)

    init {
        // Install the bundled model (assets -> filesDir) and warm up THIS instance's
        // recognizer on a background thread. ensureInstalled() copies ~72MB and must
        // never run on the main thread; prepare()'s own heavy ONNX load is already
        // off-thread. FlorisApplication.onCreate() triggers construction of this
        // singleton early (via AppGraph.speechEngine) so this priming happens well
        // before first voice use rather than lazily blocking it (BUG #12).
        ensureModelAsync()
    }

    /**
     * Kicks off (at most one concurrent) background install + warm-up of the bundled
     * model. Re-invoked whenever a voice attempt finds the model missing (P2-205), so
     * the engine heals itself within the same process instead of requiring a restart.
     */
    private fun ensureModelAsync() {
        if (!modelInstallRunning.compareAndSet(false, true)) return
        Thread {
            // D-1 fix: background priority so the (first-run) 72MB asset copy and the
            // zip.prepare() trigger below never compete with the main thread for CPU on
            // constrained devices. See ZipformerController.prepare() for the actual heavy
            // native load, which sets its own executor thread to background priority too.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                ModelAssetInstaller.ensureInstalled(context)
                if (ModelDownloader.isDownloaded(context)) zip.prepare()
            } catch (t: Throwable) {
                Log.e(TAG, "warm-up failed", t)
            } finally {
                modelInstallRunning.set(false)
            }
        }.apply { isDaemon = true; name = "AsrWarmUp" }.start()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // D-1: same two gates [start] itself checks before it will actually open the mic —
    // exposed so the UI can pre-flight instead of flipping into a fake Recording state
    // (see start()'s early-return branch below, which today opens no recorder at all).
    override fun isReady(): Boolean = hasMicPermission() && ModelDownloader.isDownloaded(context)

    @SuppressLint("MissingPermission") // guarded by hasMicPermission()
    override fun start(suppressLeadingMs: Long) {
        if (!hasMicPermission()) return
        if (!ModelDownloader.isDownloaded(context)) {
            ensureModelAsync() // P2-205: 自动重装，进程内自愈
            return
        }
        _partials.value = ""
        queue.clear()
        finalizedSegments.clear()

        // Open the live session up front when the recognizer is ready; otherwise the
        // recorder still captures and stop() batch-decodes as a fallback.
        sessionOpened = zip.isReady && zip.startSession()
        if (sessionOpened) startWorker()

        val started = recorder.start(
            onChunk = if (sessionOpened) { chunk -> queue.offer(chunk) } else null,
            // 起始提示音护栏：喵叫在开麦后立即播放，会被手机自己的麦克风录进来，
            // 解码成垃圾字且其后停顿会提前触发 endpoint —— 按调用方给的窗口把
            // 录音开头这段完全丢弃（不进队列、不进兜底缓冲）。
            skipLeadingSamples = (suppressLeadingMs * SAMPLE_RATE / 1000L).toInt(),
        )
        if (!started && sessionOpened) {
            // Mic failed to open: tear the session back down so nothing leaks.
            stopWorker()
            zip.cancelSession()
            sessionOpened = false
        }
    }

    override suspend fun stop(): String = withContext(Dispatchers.IO) {
        if (!hasMicPermission()) {
            recorder.cancel()
            abortSession()
            throw MicPermissionRequiredException()
        }
        if (!ModelDownloader.isDownloaded(context)) {
            recorder.cancel()
            abortSession()
            ensureModelAsync() // P2-205: 抛错前先触发后台重装，下次尝试即可用
            throw ModelNotReadyException()
        }
        val samples = recorder.stop()
        val text = if (sessionOpened) {
            // Live streaming path: let the worker drain the tail of the queue, then
            // flush the session for the tail; 与攒下的定稿段拼成整段返回。
            drainWorker()
            sessionOpened = false
            val tail = zip.finishSession().trim()
            // worker 已 join，此处读取无并发；copy 后立刻清空。
            val parts = ArrayList(finalizedSegments)
            finalizedSegments.clear()
            if (tail.isNotBlank()) parts.add(tail)
            parts.joinToString(SEGMENT_JOINER)
        } else {
            // Cold-start fallback: batch-decode the accumulated buffer.
            if (samples.isEmpty()) return@withContext ""
            if (!zip.isReady && !awaitReady()) throw ModelNotReadyException()
            if (!zip.startSession()) throw ModelNotReadyException()
            zip.feed(samples)
            zip.finishSession()
        }
        _partials.value = ""
        text.trim()
    }

    override fun cancel() {
        // Called from main-thread IME lifecycle callbacks: everything here must be
        // non-blocking. recorder.cancel() never blocks; session teardown (which takes
        // the native lock) is pushed to a throwaway background thread.
        recorder.cancel()
        abortSession()
    }

    /** Non-blocking teardown of the worker + any active session. */
    private fun abortSession() {
        draining = false
        workerRunning = false
        worker = null
        queue.clear()
        finalizedSegments.clear()
        _partials.value = ""
        if (sessionOpened) {
            sessionOpened = false
            Thread {
                try {
                    zip.cancelSession()
                } catch (t: Throwable) {
                    Log.e(TAG, "session cancel failed", t)
                }
            }.apply { isDaemon = true; name = "AsrSessionCancel" }.start()
        }
    }

    /** Launches the decode worker that streams queued chunks into the session. */
    private fun startWorker() {
        draining = false
        workerRunning = true
        worker = Thread {
            var lastPartial = ""
            while (workerRunning) {
                val chunk = try {
                    queue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                if (chunk == null) {
                    if (draining) break // capture ended and queue is empty -> done
                    continue
                }
                val partial = zip.feed(chunk)
                if (partial.text != lastPartial) {
                    if (lastPartial.isEmpty() && partial.text.isNotEmpty()) {
                        // QA instrumentation: timestamp of the first partial of this session
                        // (used for first-character latency measurement via logcat).
                        Log.i(TAG, "first partial: ${partial.text}")
                    }
                    lastPartial = partial.text
                    _partials.value = partial.text
                }
                if (partial.isEndpoint) {
                    // batch3-C 攒段：一句说完（尾部静音）→ 定稿文本只累积进缓冲
                    // （不发射、不上屏），reset 解码流继续听下一句。reset 与 feed 同在
                    // 本线程，二者不会交错。空文本的 endpoint（纯静音，rule1）也 reset，
                    // 保持检测器状态干净。
                    val segment = partial.text.trim()
                    zip.resetSession()
                    lastPartial = ""
                    _partials.value = ""
                    if (segment.isNotBlank()) {
                        Log.i(TAG, "segment finalized (buffered): ${segment.length} chars")
                        finalizedSegments.add(segment)
                    }
                }
            }
        }.apply { isDaemon = true; name = "AsrDecodeWorker" }
        worker?.start()
    }

    /** Stops the worker immediately, discarding anything still queued. */
    private fun stopWorker() {
        draining = false
        workerRunning = false
        worker?.interrupt()
        worker = null
        queue.clear()
    }

    /**
     * Lets the worker consume the remaining queued audio, then waits (bounded) for it
     * to exit so finishSession() sees the complete stream. Called from Dispatchers.IO.
     */
    private fun drainWorker() {
        draining = true
        try {
            worker?.join(DRAIN_JOIN_MS)
        } catch (_: InterruptedException) {
        }
        workerRunning = false
        worker = null
    }

    /**
     * Triggers a prepare and polls readiness up to ~3s. Only ever invoked from [stop],
     * which runs on Dispatchers.IO — never the main thread. The cap is short so a slow /
     * failed model load surfaces a ModelNotReadyException quickly instead of hanging the
     * voice flow (BUG #12: "after a while, completely freezes").
     */
    private suspend fun awaitReady(): Boolean {
        runCatching { zip.prepare() }
        var waited = 0L
        while (!zip.isReady && waited < READY_TIMEOUT_MS) {
            delay(READY_POLL_MS)
            waited += READY_POLL_MS
        }
        return zip.isReady
    }

    private companion object {
        const val TAG = "RealSpeechEngine"
        const val SAMPLE_RATE = 16000
        const val READY_POLL_MS = 100L
        const val READY_TIMEOUT_MS = 3_000L
        const val QUEUE_POLL_MS = 50L
        const val DRAIN_JOIN_MS = 3_000L

        // 攒段拼接分隔符：endpoint 边界≈一句话结束（Zipformer 流式输出无标点），
        // 用中文逗号给润色模型明确的句界提示；润色失败回退原文时也保持可读。
        const val SEGMENT_JOINER = "，"
    }
}
