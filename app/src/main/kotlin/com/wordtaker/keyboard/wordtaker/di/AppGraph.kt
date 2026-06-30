package com.wordtaker.keyboard.wordtaker.di

import android.content.Context
import com.wordtaker.keyboard.wordtaker.audio.ToneController
import com.wordtaker.keyboard.wordtaker.history.HistoryDatabase
import com.wordtaker.keyboard.wordtaker.history.HistoryRepository
import com.wordtaker.keyboard.wordtaker.polish.Polisher
import com.wordtaker.keyboard.wordtaker.polish.RealPolisher
import com.wordtaker.keyboard.wordtaker.relay.RelayClient
import com.wordtaker.keyboard.wordtaker.settings.SettingsRepository
import com.wordtaker.keyboard.wordtaker.speech.MockSpeechEngine
import com.wordtaker.keyboard.wordtaker.speech.RealSpeechEngine
import com.wordtaker.keyboard.wordtaker.speech.SpeechEngine
import java.util.UUID

/**
 * Minimal manual dependency container (no Hilt). Holds process-wide singletons
 * constructed lazily from an application [Context].
 *
 * Engine wiring:
 *  - ASR     -> RealSpeechEngine (sherpa-onnx SenseVoice, on-device) when
 *               [USE_REAL_ASR] is true; falls back to MockSpeechEngine otherwise.
 *  - Polish  -> RealPolisher(RelayClient)  [real relay]
 *  - History -> Room repository            [real, 入库]
 *  - Settings-> SettingsRepository (DataStore) [real]
 *  - Tone    -> ToneController             [real]
 */
object AppGraph {

    /** Flip to false to fall back to the mock transcript engine for debugging. */
    private const val USE_REAL_ASR = true

    private val appContextRef = java.util.concurrent.atomic.AtomicReference<Context?>(null)

    fun init(context: Context) {
        appContextRef.compareAndSet(null, context.applicationContext)
    }

    private fun requireContext(): Context =
        appContextRef.get() ?: error("AppGraph.init(context) must be called before use")

    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(HistoryDatabase.get(requireContext()).historyDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(requireContext())
    }

    val toneController: ToneController by lazy { ToneController(requireContext()) }

    // ASR: real on-device sherpa-onnx SenseVoice; mock as a debug fallback.
    val speechEngine: SpeechEngine by lazy {
        if (USE_REAL_ASR) RealSpeechEngine(requireContext()) else MockSpeechEngine()
    }

    // Polish: real relay-backed polisher.
    val polisher: Polisher by lazy {
        RealPolisher(RelayClient(deviceId()))
    }

    /** Stable per-install device id, persisted in a small SharedPreferences file. */
    private fun deviceId(): String {
        val prefs = requireContext().getSharedPreferences("wt_device", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id", null)
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", generated).apply()
        return generated
    }
}
