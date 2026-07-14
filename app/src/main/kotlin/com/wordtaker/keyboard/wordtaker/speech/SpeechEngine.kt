package com.wordtaker.keyboard.wordtaker.speech

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Speech-to-text engine boundary. Real implementations (e.g. RealSpeechEngine with
 * the streaming Zipformer) can be swapped without touching the UI layer.
 *
 * Streaming contract: between [start] and [stop], the engine pushes live partial
 * transcripts into [partials] (edge-to-edge, latest wins). batch3-C 攒段：每当静音
 * 检测器判定一句说完（endpoint），引擎把该句定稿文本累积在内部缓冲并自行重置
 * 解码流，继续听下一句 —— 录音不停止、不对外发射。只有 UI 主动调用 [stop]
 * （用户点击结束）才收尾，[stop] 返回「攒下的所有定稿段 + 尾巴」整段文本。
 */
interface SpeechEngine {
    /**
     * Begin capturing audio.
     *
     * @param suppressLeadingMs drop this much leading audio from recognition. Used to
     *        keep the start tone (played right after the mic opens) out of the ASR
     *        stream: otherwise the tone decodes as a junk character and the pause
     *        after it can fire the endpoint detector before the user even speaks.
     */
    fun start(suppressLeadingMs: Long = 0L)

    /** Stop capturing and return the recognized text (攒下的定稿段 + 尾巴，整段). */
    suspend fun stop(): String

    /** Cancel an active recording without processing. */
    fun cancel()

    /**
     * Cheap, synchronous, non-blocking readiness check used by the UI to decide whether
     * tapping the mic will actually start capturing audio right now. During the cold-start
     * window (bundled model still being copied to disk on a background thread) this
     * returns false so the caller can show an immediate "still preparing" toast instead of
     * flipping into a Recording UI that silently captures nothing (D-1: 初始化期点话筒无反馈).
     */
    fun isReady(): Boolean

    /** Live partial transcript of the CURRENT recording ("" when idle). */
    val partials: StateFlow<String>
}

/** Thrown by a real engine when RECORD_AUDIO has not been granted. */
class MicPermissionRequiredException : Exception()

/** Thrown by a real engine when the offline ASR model is not installed/ready. */
class ModelNotReadyException : Exception()

/** Mock engine that fakes a recognition delay and returns a fixed transcript. */
class MockSpeechEngine : SpeechEngine {

    private val _partials = MutableStateFlow("")
    override val partials: StateFlow<String> = _partials.asStateFlow()

    override fun start(suppressLeadingMs: Long) {
        // No-op for the mock pipeline.
    }

    override suspend fun stop(): String {
        delay(RECOGNIZE_DELAY_MS)
        return MOCK_TRANSCRIPT
    }

    override fun cancel() {
        // No-op for the mock pipeline.
    }

    override fun isReady(): Boolean = true

    private companion object {
        const val RECOGNIZE_DELAY_MS = 1200L
        const val MOCK_TRANSCRIPT =
            "嗯那个我想说的就是这个方案我觉得整体上是可以的然后细节方面可能还需要再讨论一下"
    }
}
