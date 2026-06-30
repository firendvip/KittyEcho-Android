package com.wordtaker.keyboard.wordtaker.speech

import kotlinx.coroutines.delay

/**
 * Speech-to-text engine boundary. Real implementations (e.g. SenseVoiceController)
 * can be swapped in later without touching the UI layer.
 */
interface SpeechEngine {
    /** Begin capturing audio. */
    fun start()

    /** Stop capturing and return the recognized text. */
    suspend fun stop(): String

    /** Cancel an active recording without processing. */
    fun cancel()
}

/** Thrown by a real engine when RECORD_AUDIO has not been granted. */
class MicPermissionRequiredException : Exception()

/** Thrown by a real engine when the offline ASR model is not downloaded/ready. */
class ModelNotReadyException : Exception()

/** Mock engine that fakes a recognition delay and returns a fixed transcript. */
class MockSpeechEngine : SpeechEngine {

    override fun start() {
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
