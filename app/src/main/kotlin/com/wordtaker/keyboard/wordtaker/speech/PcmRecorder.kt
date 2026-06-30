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
 * Continuous 16kHz mono 16-bit PCM capture for SenseVoice.
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (recording) return false
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return false
            val bufferSize = maxOf(minBuf, SAMPLE_RATE * 2)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
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
            rec.startRecording()
            recording = true
            thread(name = "PcmRecorder", isDaemon = true) {
                val buf = ShortArray(CHUNK_SAMPLES)
                var total = 0
                try {
                    while (recording) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            val copy = buf.copyOf(n)
                            synchronized(chunks) { chunks.add(copy) }
                            total += n
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
    }
}
