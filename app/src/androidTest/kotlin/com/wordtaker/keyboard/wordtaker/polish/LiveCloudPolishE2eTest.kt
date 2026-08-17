package com.wordtaker.keyboard.wordtaker.polish

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wordtaker.keyboard.wordtaker.audio.ToneController
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.history.HistoryDao
import com.wordtaker.keyboard.wordtaker.history.HistoryEntity
import com.wordtaker.keyboard.wordtaker.history.HistoryRepository
import com.wordtaker.keyboard.wordtaker.settings.SettingsSource
import com.wordtaker.keyboard.wordtaker.settings.SettingsState
import com.wordtaker.keyboard.wordtaker.speech.SpeechEngine
import com.wordtaker.keyboard.wordtaker.speech.CaptureStartResult
import com.wordtaker.keyboard.wordtaker.voice.VoicePhase
import com.wordtaker.keyboard.wordtaker.voice.VoiceStartHaptic
import com.wordtaker.keyboard.wordtaker.voice.VoiceViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in production smoke test.
 *
 * It sends only the fixed synthetic sentence below. Normal device/CI runs skip this test unless
 * `liveCloud=true` is passed as an instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class LiveCloudPolishE2eTest {
    @Test
    fun validatedAndroidClientReachesTheProductionPolishChain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCloud") == "true")

        AppGraph.init(instrumentation.targetContext)
        val raw = "今天下午三点我们开会讨论项目进度请提前准备材料"
        val result = runBlocking {
            withTimeout(15_000L) {
                AppGraph.polisher.polishResult(raw, "normal")
            }
        }

        assertEquals(PolishOutcomeKind.Polished, result.outcome)
        assertTrue(result.text.isNotBlank())
    }

    @Test
    fun transcribedLongTextPolishesAndCommitsThroughTheVoicePipeline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCloud") == "true")

        AppGraph.init(instrumentation.targetContext)
        val raw = "今天下午三点我们开会讨论项目进度请提前准备材料"
        val speechEngine = FixedTranscriptSpeechEngine(raw)
        val viewModel = VoiceViewModel(
            speechEngine = speechEngine,
            polisher = AppGraph.polisher,
            historyRepository = HistoryRepository(InMemoryHistoryDao()),
            settingsRepository = SilentSettingsSource,
            toneController = ToneController(),
            startHaptic = VoiceStartHaptic.NONE,
        )

        val committed = runBlocking {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(15_000L) { viewModel.committed.first() }
            }
            instrumentation.runOnMainSync { viewModel.onTap() }
            instrumentation.runOnMainSync { viewModel.onTap() }
            commit.await()
        }

        assertTrue(committed.text.isNotBlank())
        assertEquals(1, speechEngine.startCalls)
        assertEquals(1, speechEngine.stopCalls)
        assertEquals(VoicePhase.Success, viewModel.state.value.phase)
        assertEquals(PolishOutcomeKind.Polished, viewModel.state.value.polishOutcome)
    }
}

private class FixedTranscriptSpeechEngine(
    private val transcript: String,
) : SpeechEngine {
    override val partials: StateFlow<String> = MutableStateFlow("")
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set

    override fun start(): CaptureStartResult {
        startCalls += 1
        return CaptureStartResult.Started
    }

    override suspend fun stop(): String {
        stopCalls += 1
        return transcript
    }

    override fun cancel() = Unit

    override fun isReady(): Boolean = true
}

private object SilentSettingsSource : SettingsSource {
    override val settings: Flow<SettingsState> = MutableStateFlow(
        SettingsState(tone = false, minimal = true),
    )
}

private class InMemoryHistoryDao : HistoryDao {
    private val rows = MutableStateFlow<List<HistoryEntity>>(emptyList())

    override suspend fun insert(entity: HistoryEntity): Long {
        val id = rows.value.size.toLong() + 1L
        rows.value = rows.value + entity.copy(id = id)
        return id
    }

    override fun observeAll(): Flow<List<HistoryEntity>> = rows

    override fun search(q: String): Flow<List<HistoryEntity>> = MutableStateFlow(
        rows.value.filter { it.raw.contains(q) || it.polished.contains(q) },
    )

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun clearAll() {
        rows.value = emptyList()
    }

    override suspend fun count(): Int = rows.value.size
}
