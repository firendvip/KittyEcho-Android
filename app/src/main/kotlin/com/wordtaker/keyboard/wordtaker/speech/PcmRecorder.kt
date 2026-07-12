package com.wordtaker.keyboard.wordtaker.speech

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Continuous 16kHz mono 16-bit PCM capture for the streaming ASR engine.
 *
 * start() begins recording on a background thread, accumulating raw shorts into a
 * synchronized list of chunks. stop() flips the flag, waits briefly for the reader
 * thread to drain, releases the AudioRecord, and flattens everything to a FloatArray
 * (short / 32768f) in [-1, 1]. cancel() discards the buffer. Total capture is capped
 * at 120s to bound memory; on overflow recording auto-stops.
 *
 * Exception-safe: every public method swallows failures and returns a safe default.
 */
class PcmRecorder {

    @Volatile
    private var recording = false
    // AtomicReference so only one caller ever obtains (and releases) the recorder:
    // getAndSet(null) is atomic, preventing a double release() race between stop()
    // (Dispatchers.IO) and cancel() (main thread, e.g. onStartInput mid-transcribe).
    private val record = AtomicReference<AudioRecord?>(null)
    @Volatile
    private var latch: CountDownLatch? = null

    // Captured PCM chunks, guarded by `chunks` itself.
    private val chunks = ArrayList<ShortArray>()

    /**
     * @param onChunk optional streaming sink: invoked on the capture thread with each
     *        fresh chunk converted to floats in [-1, 1] (for live ASR decoding). The
     *        callback must be fast and never throw (exceptions are swallowed here).
     * @param skipLeadingSamples drop this many samples from the head of the capture
     *        (both the accumulated buffer and [onChunk]). Used to keep the start tone
     *        — played right after the mic opens — out of recognition entirely.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(
        onChunk: ((FloatArray) -> Unit)? = null,
        skipLeadingSamples: Int = 0,
    ): Boolean {
        if (recording) return false
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return false
            val bufferSize = maxOf(minBuf, SAMPLE_RATE * 2)
            // 音源选择（vivo iQOO 8/OriginOS 真机逐一标定，勿随意改动）：
            // - VOICE_COMMUNICATION（当前）：通话采集通路无「类语音 VAD 慢开门」，
            //   提示音关闭、无任何声学预热时立即开口 5/5 句首完整（R3-011 修复）。
            // - MIC：前端 AGC/降噪是「检测到类语音才开门」，无提示音（喵叫）预热时
            //   吞掉每句开头 ~0.6-2.2s（静态噪声/超声怎么播都不预热，喵叫 0.1 音量即可
            //   预热——即门控看信号形态不看能量）。
            // - VOICE_RECOGNITION：前端降噪慢收敛，句首前切 ~2s（R3 轮标定）。
            // - UNPROCESSED / VOICE_PERFORMANCE：vivo 上返回全零帧（采集无声）。
            // - CAMCORDER：与 MIC 同样吞句首。
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            synchronized(chunks) { chunks.clear() }
            record.set(rec)
            val doneLatch = CountDownLatch(1)
            latch = doneLatch
            val startedAt = android.os.SystemClock.elapsedRealtime()
            rec.startRecording()
            recording = true
            thread(name = "PcmRecorder", isDaemon = true) {
                val buf = ShortArray(CHUNK_SAMPLES)
                var total = 0
                // R4-011 修复：厂商 VoIP 前端 NS 把小声/远场语音衰到 ASR 阈下但未置零
                // （vol25 落盘标定：语音残留 rms 16-81）。audiofx 禁用 NS 会致流头损坏
                // （见 companion 内标定结论注释），改为软件 AGC-lite：块 RMS 低于目标
                // 时整块提升（仅放大不衰减、增益平滑防泵效应、硬限幅防削波）；正常音量
                // （rms>=目标）增益恒 1，CER 不受影响。静音块（低于噪声底）不调增益。
                var agcGain = 1f
                try {
                    while (recording) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            // R4-001/002 修复第二段（真机 PCM 落盘标定）：VOICE_COMMUNICATION
                            // 前端 AGC（audiofx 不可禁，AGC=n/a）在采集起始 ~0.45s 内从低增益
                            // 爬坡，句首软起音被衰减 3-4 倍导致 ASR 丢首词。对开头样本乘逆向
                            // 爬坡增益（RAMP_START_GAIN→1 线性）补偿，带硬限幅防削波。
                            if (total < RAMP_SAMPLES) {
                                for (i in 0 until n) {
                                    val idx = total + i
                                    if (idx >= RAMP_SAMPLES) break
                                    val g = RAMP_START_GAIN -
                                        (RAMP_START_GAIN - 1f) * idx / RAMP_SAMPLES
                                    buf[i] = clampPcm(buf[i] * g)
                                }
                            }
                            if (total == 0) {
                                // 首帧延迟诊断：startRecording() -> 首个有效帧。该窗口内的
                                // 声音在系统层丢失（句首前切的直接观测量）。
                                Log.i(
                                    TAG,
                                    "first frame after " +
                                        "${android.os.SystemClock.elapsedRealtime() - startedAt}ms" +
                                        " (n=$n)"
                                )
                            }
                            // 护栏：丢掉开头 skipLeadingSamples 个采样（起始提示音窗口），
                            // 不进缓冲、不进流式回调——喵叫绝不进识别器。样本级精度。
                            val dropped = total // samples dropped/consumed before this read
                            val skipInBuf = (skipLeadingSamples - dropped).coerceIn(0, n)
                            // AGC-lite（见上）：在爬坡补偿之后按块调平。
                            var sum = 0.0
                            for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                            val rms = Math.sqrt(sum / n).toFloat()
                            if (rms > AGC_NOISE_FLOOR) {
                                val desired = (AGC_TARGET_RMS / rms).coerceIn(1f, AGC_MAX_GAIN)
                                agcGain += (desired - agcGain) * AGC_ADAPT
                            }
                            if (agcGain > 1.01f) {
                                for (i in 0 until n) {
                                    buf[i] = clampPcm(buf[i] * agcGain)
                                }
                            }
                            total += n
                            if (skipInBuf >= n) {
                                if (total >= MAX_SAMPLES) { recording = false; break }
                                continue
                            }
                            val copy = buf.copyOfRange(skipInBuf, n)
                            synchronized(chunks) { chunks.add(copy) }
                            if (onChunk != null) {
                                try {
                                    val floats = FloatArray(copy.size)
                                    for (i in copy.indices) floats[i] = copy[i] / 32768f
                                    onChunk(floats)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "onChunk sink failed", t)
                                }
                            }
                            if (total >= MAX_SAMPLES) {
                                recording = false
                                break
                            }
                        } else if (n < 0) {
                            // read error; stop capturing.
                            recording = false
                            break
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "capture loop failed", t)
                } finally {
                    doneLatch.countDown()
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            safeReleaseRecord()
            recording = false
            false
        }
    }

    /** Stops capture, releases the recorder, returns the captured samples as floats. */
    fun stop(): FloatArray {
        recording = false
        try {
            latch?.await(AWAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
        safeReleaseRecord()
        return flattenToFloats()
    }

    /**
     * Stops capture and discards the buffer. Never throws, never blocks.
     *
     * Called from main-thread IME lifecycle callbacks, so it must NOT await the
     * capture thread (ANR risk). safeReleaseRecord() calls AudioRecord.stop(),
     * which unblocks the capture thread's read(); record.getAndSet(null) guarantees
     * a single release; and the buffer is discarded regardless, so awaiting is
     * unnecessary here.
     */
    fun cancel() {
        recording = false
        synchronized(chunks) { chunks.clear() }
        safeReleaseRecord()
    }

    fun isRecording(): Boolean = recording

    private fun flattenToFloats(): FloatArray {
        val snapshot = synchronized(chunks) {
            val copy = ArrayList(chunks)
            chunks.clear()
            copy
        }
        var totalLen = 0
        for (c in snapshot) totalLen += c.size
        val out = FloatArray(totalLen)
        var idx = 0
        for (c in snapshot) {
            for (s in c) {
                out[idx++] = s / 32768f
            }
        }
        return out
    }

    /**
     * 全增益链统一峰值限幅（唯一一处）：把浮点增益后的样本硬夹回 16-bit PCM 量程，
     * 防削波爆音。爬坡补偿与 AGC-lite 两段增益都经过这里，故「限幅」只此一份。
     */
    private fun clampPcm(sample: Float): Short =
        sample.toInt().coerceIn(PCM_PEAK_MIN, PCM_PEAK_MAX).toShort()

    private fun safeReleaseRecord() {
        // Atomic claim: whichever thread wins getAndSet(null) owns the release;
        // every other caller sees null and does nothing. No double release().
        val rec = record.getAndSet(null)
        if (rec != null) {
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "stop failed", t)
            }
            try {
                rec.release()
            } catch (t: Throwable) {
                Log.e(TAG, "release failed", t)
            }
        }
    }

    private companion object {
        private const val TAG = "PcmRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 4096
        private const val MAX_SAMPLES = 16000 * 120 // 120s cap
        private const val AWAIT_MS = 1000L
        // AGC 爬坡补偿窗口与起始增益（vivo iQOO 8 落盘 PCM 标定：0-0.1s 置零、
        // 0.1-0.45s 衰减 3-4 倍）。
        private const val RAMP_SAMPLES = 16000 * 45 / 100 // 450ms
        private const val RAMP_START_GAIN = 4f
        // AGC-lite 参数（vol25/40 落盘标定）：正常音量语音块 rms ~800-1700（恒 1x），
        // 小声残留 rms 16-81（提升至 ~130-650 可解码）。
        // 真机标定结论（vivo iQOO 8，2026-07-11 r5 轮，勿再尝试）：通过 audiofx
        // NoiseSuppressor/AEC.create(sessionId)+setEnabled(false) 禁用通路音效会触发
        // 输入链重配——采集起始 ~0.1s 置零 + ~1s 频谱损坏（RMS 正常但 ASR 不可解码，
        // 流式/批式同废），即刻开口丢句首反而更严重；AGC 在本机 audiofx 不可见(n/a)。
        // 故小声全抑（R4-011）只能走本软件增益路线，不碰 audiofx。
        private const val AGC_NOISE_FLOOR = 25f
        private const val AGC_TARGET_RMS = 500f
        private const val AGC_MAX_GAIN = 8f
        private const val AGC_ADAPT = 0.6f
        // 统一增益预算（集中记此一处）：句首 450ms 爬坡补偿（≤RAMP_START_GAIN=4x）→
        // AGC-lite 按「已补偿后」的块 RMS 自调（≤AGC_MAX_GAIN=8x，正常音量恒 1x，故 AGC
        // 不会与爬坡叠加放大到失控）→ 两段都经唯一的 clampPcm() 硬限幅。峰值量程如下。
        private const val PCM_PEAK_MIN = -32768 // Short.MIN_VALUE
        private const val PCM_PEAK_MAX = 32767 // Short.MAX_VALUE
    }
}
