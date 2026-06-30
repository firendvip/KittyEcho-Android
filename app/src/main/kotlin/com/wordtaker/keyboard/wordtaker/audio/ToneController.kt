package com.wordtaker.keyboard.wordtaker.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.util.Log
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.wordtaker.settings.SettingsState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays short UI tones for recording start / end.
 *
 * Two styles, selected by [SettingsState.toneStyle]:
 *  - "meow" (default): plays the ported macOS "喵" sound (res/raw/meow.mp3) via
 *    [SoundPool] — low latency, re-triggerable. End tone is slightly quieter,
 *    mirroring the desktop client (start 1.0×, end 0.85×).
 *  - "beep": the original synthesized [AudioTrack] sine tones (no asset).
 *
 * All audio failures are caught and logged — playing a tone must never crash the app.
 * Construction is cheap; the meow sample loads lazily on first use.
 */
class ToneController(private val appContext: Context? = null) {

    // Held behind an AtomicReference so release() can null it out and any concurrent
    // caller sees null instead of touching a freed native pool.
    private val soundPoolRef = AtomicReference<SoundPool?>(null)
    private val poolInitDone = AtomicBoolean(false)
    private val meowLoadStarted = AtomicBoolean(false)

    @Volatile private var meowSoundId: Int = 0
    @Volatile private var meowLoaded: Boolean = false

    /** Lazily build the pool exactly once. Returns null if no context or build failed. */
    private fun pool(): SoundPool? {
        soundPoolRef.get()?.let { return it }
        if (appContext == null) return null
        if (!poolInitDone.compareAndSet(false, true)) return soundPoolRef.get()
        val built = runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        }.getOrNull()
        soundPoolRef.set(built)
        return built
    }

    /** Kick off the one-time async load of meow.mp3 into [pool]. */
    private fun ensureMeowLoaded(pool: SoundPool) {
        val ctx = appContext ?: return
        if (!meowLoadStarted.compareAndSet(false, true)) return
        runCatching {
            // Register the listener BEFORE load() so we never miss the callback; it
            // matches on the captured sample id, avoiding the meowSoundId==0 race.
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0 && sampleId == meowSoundId) meowLoaded = true
            }
            meowSoundId = pool.load(ctx, R.raw.meow, 1)
        }.onFailure {
            Log.w(TAG, "meow load failed", it)
            meowLoadStarted.set(false) // allow a retry on the next tone
        }
    }

    /** Start tone. Gated by [enabled]; style picks meow vs beep. */
    fun startBeep(enabled: Boolean = true, style: String = SettingsState.DEFAULT_TONE_STYLE) {
        if (!enabled) return
        if (style == SettingsState.TONE_MEOW && playMeow(MEOW_START_VOLUME)) return
        play(buildTone(startFreq = 880f, endFreq = 880f, durationMs = 80))
    }

    /** End tone. Gated by [enabled]; style picks meow vs beep. */
    fun endBeep(enabled: Boolean = true, style: String = SettingsState.DEFAULT_TONE_STYLE) {
        if (!enabled) return
        if (style == SettingsState.TONE_MEOW && playMeow(MEOW_END_VOLUME)) return
        play(buildTone(startFreq = 660f, endFreq = 440f, durationMs = 120))
    }

    /** Plays the meow sample. Returns true if playback was dispatched. */
    private fun playMeow(volume: Float): Boolean {
        val pool = pool() ?: return false
        ensureMeowLoaded(pool)
        if (meowSoundId == 0) return false
        return runCatching {
            // SoundPool ignores plays before load completes; the first record press may
            // miss, but subsequent ones (and the matching end tone) play reliably.
            pool.play(meowSoundId, volume, volume, 1, 0, 1f)
            true
        }.onFailure { Log.w(TAG, "meow play failed", it) }.getOrDefault(false)
    }

    fun release() {
        // Null the reference first so any concurrent playMeow() sees null and bails,
        // then release the now-unreferenced native pool.
        val pool = soundPoolRef.getAndSet(null)
        runCatching { pool?.release() }
    }

    private fun buildTone(startFreq: Float, endFreq: Float, durationMs: Int): ShortArray {
        val frameCount = (SAMPLE_RATE * durationMs / 1000)
        val samples = ShortArray(frameCount)
        var phase = 0.0
        val fadeFrames = (frameCount * FADE_RATIO).toInt().coerceAtLeast(1)
        for (i in 0 until frameCount) {
            val t = i.toFloat() / frameCount
            val freq = startFreq + (endFreq - startFreq) * t
            phase += 2.0 * PI * freq / SAMPLE_RATE
            // Linear fade in/out to avoid clicks.
            val env = when {
                i < fadeFrames -> i.toFloat() / fadeFrames
                i > frameCount - fadeFrames -> (frameCount - i).toFloat() / fadeFrames
                else -> 1f
            }
            samples[i] = (sin(phase) * Short.MAX_VALUE * AMPLITUDE * env).toInt().toShort()
        }
        return samples
    }

    private fun play(samples: ShortArray) {
        try {
            val bytes = samples.size * 2
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bytes.coerceAtLeast(MIN_BUFFER_BYTES),
                AudioTrack.MODE_STATIC,
            )
            // If the track failed to initialize, the marker callback would never fire
            // and the native session would leak — release it immediately and bail.
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                Log.w(TAG, "AudioTrack uninitialized; skipping tone")
                return
            }
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    runCatching {
                        t?.stop()
                        t?.release()
                    }
                }

                override fun onPeriodicNotification(t: AudioTrack?) = Unit
            })
            track.play()
        } catch (t: Throwable) {
            Log.w(TAG, "Tone playback failed", t)
        }
    }

    private companion object {
        const val TAG = "ToneController"
        const val SAMPLE_RATE = 44100
        const val AMPLITUDE = 0.5f
        const val FADE_RATIO = 0.15f
        const val MIN_BUFFER_BYTES = 256
        const val MEOW_START_VOLUME = 1.0f
        const val MEOW_END_VOLUME = 0.85f
    }
}
