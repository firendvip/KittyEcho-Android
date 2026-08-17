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

interface VoiceToneFeedback {
    fun endBeep(
        enabled: Boolean = true,
        style: String = SettingsState.DEFAULT_TONE_STYLE,
        volume: Float = 1f,
    )

    fun release()
}

/**
 * Plays the short UI tone after recording capture has closed.
 *
 * 需求#8：只保留"喵"声。无论传入什么 [style]，都播放 ported macOS "喵"
 * (res/raw/meow.mp3) via [SoundPool] — low latency, re-triggerable. The 0.85×
 * attenuation mirrors the desktop client. 合成蜂鸣 ([AudioTrack] 正弦音) 已不再使用，
 * 仅作为 meow 加载失败时的兜底。
 *
 * 构造/首用时即 EAGERLY 预加载 meow 采样，避免本段结束音因懒加载被丢弃。
 *
 * All audio failures are caught and logged — playing a tone must never crash the app.
 */
class ToneController(private val appContext: Context? = null) : VoiceToneFeedback {

    // Held behind an AtomicReference so release() can null it out and any concurrent
    // caller sees null instead of touching a freed native pool.
    private val soundPoolRef = AtomicReference<SoundPool?>(null)
    private val poolInitDone = AtomicBoolean(false)
    private val meowLoadStarted = AtomicBoolean(false)

    @Volatile private var meowSoundId: Int = 0
    @Volatile private var meowLoaded: Boolean = false

    init {
        // 立即预热 SoundPool 并异步加载 meow，使第一次结束音不被丢弃。
        // 放在属性声明之后，确保 init 运行时 soundPoolRef 等字段已初始化。
        runCatching { pool()?.let { ensureMeowLoaded(it) } }
    }

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
            // 捕获 load() 返回的 id 到局部变量后再注册监听并比较，避免监听器读到尚未赋值的
            // meowSoundId(=0) 竞态。mp3 解码非瞬时，先 load 再注册不会漏掉回调。
            val id = pool.load(ctx, R.raw.meow, 1)
            meowSoundId = id
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0 && sampleId == id) meowLoaded = true
            }
        }.onFailure {
            Log.w(TAG, "meow load failed", it)
            meowLoadStarted.set(false) // allow a retry on the next tone
        }
    }

    /**
     * End tone. Gated by [enabled]. 需求#8：一律播放"喵"(略轻)；[style] 保留但忽略。
     *
     * @param volume 语音提示音全局音量系数 0..1（用户滑杆），只作用于结束喵叫。
     */
    override fun endBeep(
        enabled: Boolean,
        @Suppress("UNUSED_PARAMETER") style: String,
        volume: Float,
    ) {
        if (!enabled) return
        val v = volume.coerceIn(0f, 1f)
        if (v <= 0f) return
        if (playMeow(MEOW_END_VOLUME * v)) return
        play(buildTone(startFreq = 660f, endFreq = 440f, durationMs = 120), v)
    }

    /** Plays the meow sample. Returns true if playback was dispatched. */
    private fun playMeow(volume: Float): Boolean {
        val pool = pool() ?: return false
        ensureMeowLoaded(pool)
        if (meowSoundId == 0) return false
        return runCatching {
            // SoundPool ignores plays before load completes; a very early first stop may
            // miss its end tone, while subsequent end-tone plays are reliable.
            pool.play(meowSoundId, volume, volume, 1, 0, 1f)
            true
        }.onFailure { Log.w(TAG, "meow play failed", it) }.getOrDefault(false)
    }

    override fun release() {
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

    private fun play(samples: ShortArray, volume: Float = 1f) {
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
            runCatching { track.setVolume(volume.coerceIn(0f, 1f)) }
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
        const val MEOW_END_VOLUME = 0.85f
    }
}
