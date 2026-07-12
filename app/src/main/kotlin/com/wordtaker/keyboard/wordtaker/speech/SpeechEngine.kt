package com.wordtaker.keyboard.wordtaker.speech

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Speech-to-text engine boundary. Real implementations (e.g. RealSpeechEngine with
 * the streaming Zipformer) can be swapped without touching the UI layer.
 *
 * Streaming contract: between [start] and [stop], the engine pushes live partial
 * transcripts into [partials] (edge-to-edge, latest wins) and fires [endpoints]
 * once when its silence detector decides the user finished speaking. The UI reacts
 * to an endpoint by calling [stop] — the engine itself never self-stops.
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

    /** Stop capturing and return the recognized text. */
    suspend fun stop(): String

    /** Cancel an active recording without processing. */
    fun cancel()

    /** Live partial transcript of the CURRENT recording ("" when idle). */
    val partials: StateFlow<String>

    /** Fires when end-of-speech silence is detected (auto-finish signal). */
    val endpoints: SharedFlow<Unit>
}

/** Thrown by a real engine when RECORD_AUDIO has not been granted. */
class MicPermissionRequiredException : Exception()

/** Thrown by a real engine when the offline ASR model is not installed/ready. */
class ModelNotReadyException : Exception()

/** Mock engine that fakes a recognition delay and returns a fixed transcript. */
class MockSpeechEngine : SpeechEngine {

    private val _partials = MutableStateFlow("")
    override val partials: StateFlow<String> = _partials.asStateFlow()

    private val _endpoints = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val endpoints: SharedFlow<Unit> = _endpoints.asSharedFlow()

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

    private companion object {
        const val RECOGNIZE_DELAY_MS = 1200L
        const val MOCK_TRANSCRIPT =
            "嗯那个我想说的就是这个方案我觉得整体上是可以的然后细节方面可能还需要再讨论一下"
    }
}
