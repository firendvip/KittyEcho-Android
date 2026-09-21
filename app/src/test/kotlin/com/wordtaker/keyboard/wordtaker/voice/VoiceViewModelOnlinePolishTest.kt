package com.wordtaker.keyboard.wordtaker.voice

import app.cash.turbine.test
import com.wordtaker.keyboard.wordtaker.audio.ToneController
import com.wordtaker.keyboard.wordtaker.audio.VoiceToneFeedback
import com.wordtaker.keyboard.wordtaker.history.HistoryDao
import com.wordtaker.keyboard.wordtaker.history.HistoryEntity
import com.wordtaker.keyboard.wordtaker.history.HistoryRepository
import com.wordtaker.keyboard.wordtaker.network.InternetConnection
import com.wordtaker.keyboard.wordtaker.polish.OnlineOnlyPolisher
import com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind
import com.wordtaker.keyboard.wordtaker.polish.PolishResult
import com.wordtaker.keyboard.wordtaker.polish.Polisher
import com.wordtaker.keyboard.wordtaker.settings.SettingsSource
import com.wordtaker.keyboard.wordtaker.settings.SettingsState
import com.wordtaker.keyboard.wordtaker.speech.CaptureStartFailure
import com.wordtaker.keyboard.wordtaker.speech.CaptureStartResult
import com.wordtaker.keyboard.wordtaker.speech.MicPermissionRequiredException
import com.wordtaker.keyboard.wordtaker.speech.AsrResult
import com.wordtaker.keyboard.wordtaker.speech.ModelNotReadyException
import com.wordtaker.keyboard.wordtaker.speech.PendingAsrResult
import com.wordtaker.keyboard.wordtaker.speech.RecorderReadException
import com.wordtaker.keyboard.wordtaker.speech.SpeechEngine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelOnlinePolishTest : FunSpec({

    test("offline transcript uses existing FIFO history and pending chain without cloud calls") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val speech = FakeSpeechEngine("  离线长文本内容  ")
                val online = FakePolisher { _, _ -> "不应调用" }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = OnlineOnlyPolisher(InternetConnection { false }, online),
                    history = history,
                )
                runCurrent()

                vm.committed.test {
                    vm.onTap()
                    runCurrent()
                    vm.onTap()
                    vm.pending.value shouldBe 1

                    runCurrent()

                    awaitItem().text shouldBe "离线长文本内容"
                    vm.pending.value shouldBe 0
                    cancelAndIgnoreRemainingEvents()
                }

                online.calls shouldBe 0
                history.inserted.map { it.raw to it.polished } shouldContainExactly
                    listOf("离线长文本内容" to "离线长文本内容")
                vm.state.value.phase shouldBe VoicePhase.Success
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("validated internet polishes and online failure still commits raw") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val speech = FakeSpeechEngine("online ok", "online fails")
                val online = FakePolisher { raw, _ ->
                    if (raw == "online fails") error("network failed") else "polished:$raw"
                }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    awaitItem().text shouldBe "polished:online ok"

                    recordOneSegment(vm)
                    awaitItem().text shouldBe "online fails"
                    cancelAndIgnoreRemainingEvents()
                }

                online.calls shouldBe 2
                history.inserted.map { it.raw to it.polished } shouldContainExactly listOf(
                    "online ok" to "polished:online ok",
                    "online fails" to "online fails",
                )
                vm.pending.value shouldBe 0
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("parallel online polishing still commits and writes history in recording FIFO order") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val speech = FakeSpeechEngine("first01", "second2")
                val online = FakePolisher { raw, _ ->
                    if (raw == "first01") delay(1_000)
                    "polished:$raw"
                }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    recordOneSegment(vm)
                    vm.pending.value shouldBe 2

                    advanceTimeBy(1_000)
                    runCurrent()

                    awaitItem().text shouldBe "polished:first01"
                    awaitItem().text shouldBe "polished:second2"
                    cancelAndIgnoreRemainingEvents()
                }

                history.inserted.map { it.raw } shouldContainExactly listOf("first01", "second2")
                vm.pending.value shouldBe 0
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("second recording starts while first stop is pending and both still commit FIFO") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val firstStopGate = CompletableDeferred<Unit>()
                val firstPolishGate = CompletableDeferred<Unit>()
                val speech = FirstStopGatedSpeechEngine(
                    firstStopGate,
                    "first01",
                    "second2",
                )
                val online = FakePolisher { raw, _ ->
                    if (raw == "first01") firstPolishGate.await()
                    "polished:$raw"
                }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                )
                runCurrent()

                vm.committed.test {
                    vm.onTap()
                    runCurrent()
                    vm.onTap()
                    runCurrent()
                    vm.state.value.phase shouldBe VoicePhase.Recognizing
                    vm.pending.value shouldBe 1

                    vm.startFromExternal()
                    runCurrent()
                    vm.state.value.phase shouldBe VoicePhase.Recording
                    vm.pending.value shouldBe 1
                    // Capture release already completed even though first decode is gated.
                    speech.startCalls shouldBe 2
                    speech.cancelCalls shouldBe 0

                    firstStopGate.complete(Unit)
                    runCurrent()
                    speech.startCalls shouldBe 2
                    vm.state.value.phase shouldBe VoicePhase.Recording

                    vm.onTap()
                    runCurrent()
                    vm.pending.value shouldBe 2
                    expectNoEvents()

                    firstPolishGate.complete(Unit)
                    runCurrent()
                    awaitItem().text shouldBe "polished:first01"
                    awaitItem().text shouldBe "polished:second2"
                    cancelAndIgnoreRemainingEvents()
                }

                speech.stopCalls shouldBe 2
                history.inserted.map { it.raw } shouldContainExactly listOf("first01", "second2")
                vm.pending.value shouldBe 0
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("six visible graphemes or fewer preserve text and bypass every online polisher call") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val speech = FakeSpeechEngine(
                    " \u00A0甲a1，👍🏽🇨🇳 \u3000",
                    " e\u0301👍🏽🇨🇳👨‍👩‍👧‍👦中A ",
                    " \ta b\nc\u3000",
                )
                val online = FakePolisher { raw, _ -> "polished:$raw" }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    awaitItem().text shouldBe "甲a1，👍🏽🇨🇳"
                    recordOneSegment(vm)
                    awaitItem().text shouldBe "e\u0301👍🏽🇨🇳👨‍👩‍👧‍👦中A"
                    recordOneSegment(vm)
                    awaitItem().text shouldBe "a b\nc"
                    cancelAndIgnoreRemainingEvents()
                }

                online.calls shouldBe 0
                history.inserted.map { it.raw } shouldContainExactly listOf(
                    "甲a1，👍🏽🇨🇳",
                    "e\u0301👍🏽🇨🇳👨‍👩‍👧‍👦中A",
                    "a b\nc",
                )
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("seven visible graphemes cross the online polish boundary") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val speech = FakeSpeechEngine("甲a1，👍🏽🇨🇳家")
                val online = FakePolisher { raw, _ -> "polished:$raw" }
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = FakeHistoryDao(),
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    awaitItem().text shouldBe "polished:甲a1，👍🏽🇨🇳家"
                    cancelAndIgnoreRemainingEvents()
                }

                online.calls shouldBe 1
                vm.state.value.polishOutcome shouldBe PolishOutcomeKind.Polished
                voiceStatusLabel(
                    vm.state.value.phase,
                    vm.state.value.polishOutcome,
                ) shouldBe "已完成"
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("same-text online response is still a polished outcome") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val raw = "一二三四五六七"
                val online = FakePolisher { text, _ -> text }
                val vm = voiceViewModel(
                    speech = FakeSpeechEngine(raw),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = FakeHistoryDao(),
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    awaitItem().text shouldBe raw
                    cancelAndIgnoreRemainingEvents()
                }

                online.calls shouldBe 1
                vm.state.value.polishOutcome shouldBe PolishOutcomeKind.Polished
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("typed fallback outcomes commit raw and expose fixed non-sensitive status") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val raw = "隐私文本一二三四五六七"
                val forbidden = listOf(raw, "sensitive-token-value")
                val cases = listOf(
                    PolishOutcomeKind.FallbackQuota to "未润色：额度不足",
                    PolishOutcomeKind.FallbackAuthExpired to "未润色：登录已失效",
                    PolishOutcomeKind.FallbackTimeout to "未润色：服务超时",
                    PolishOutcomeKind.FallbackNetwork to "未润色：网络异常",
                    PolishOutcomeKind.FallbackServer to "未润色：服务异常",
                    PolishOutcomeKind.FallbackUnknown to "未润色：服务异常",
                )

                cases.forEach { (outcome, expectedLabel) ->
                    val vm = voiceViewModel(
                        speech = FakeSpeechEngine(raw),
                        polisher = OutcomePolisher { text, _ -> PolishResult(text, outcome) },
                        history = FakeHistoryDao(),
                    )
                    runCurrent()

                    vm.committed.test {
                        recordOneSegment(vm)
                        awaitItem().text shouldBe raw
                        cancelAndIgnoreRemainingEvents()
                    }

                    vm.state.value.polishOutcome shouldBe outcome
                    val label = voiceStatusLabel(vm.state.value.phase, vm.state.value.polishOutcome)
                    label shouldBe expectedLabel
                    forbidden.any(label::contains) shouldBe false
                    advanceTimeBy(1_200)
                    runCurrent()
                }
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("short and offline direct outcomes are explicit without pretending AI success") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val shortOnline = FakePolisher { text, _ -> "unused:$text" }
                val shortVm = voiceViewModel(
                    speech = FakeSpeechEngine("一二三四五六"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, shortOnline),
                    history = FakeHistoryDao(),
                )
                val offlineDelegate = FakePolisher { text, _ -> "unused:$text" }
                val offlineVm = voiceViewModel(
                    speech = FakeSpeechEngine("一二三四五六七"),
                    polisher = OnlineOnlyPolisher(InternetConnection { false }, offlineDelegate),
                    history = FakeHistoryDao(),
                )
                runCurrent()

                shortVm.committed.test {
                    recordOneSegment(shortVm)
                    awaitItem().text shouldBe "一二三四五六"
                    cancelAndIgnoreRemainingEvents()
                }
                shortVm.state.value.polishOutcome shouldBe PolishOutcomeKind.ShortDirect
                voiceStatusLabel(
                    shortVm.state.value.phase,
                    shortVm.state.value.polishOutcome,
                ) shouldBe "短句直出"

                offlineVm.committed.test {
                    recordOneSegment(offlineVm)
                    awaitItem().text shouldBe "一二三四五六七"
                    cancelAndIgnoreRemainingEvents()
                }
                offlineVm.state.value.polishOutcome shouldBe PolishOutcomeKind.OfflineDirect
                voiceStatusLabel(
                    offlineVm.state.value.phase,
                    offlineVm.state.value.polishOutcome,
                ) shouldBe "离线直出"

                shortOnline.calls shouldBe 0
                offlineDelegate.calls shouldBe 0
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("fallback and polished segments retain FIFO commit order") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val polisher = OutcomePolisher { raw, _ ->
                    if (raw == "第二段长文本7") delay(1_000)
                    if (raw == "第一段长文本7") {
                        PolishResult(raw, PolishOutcomeKind.FallbackNetwork)
                    } else {
                        PolishResult("已润色:$raw", PolishOutcomeKind.Polished)
                    }
                }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = FakeSpeechEngine("第一段长文本7", "第二段长文本7"),
                    polisher = polisher,
                    history = history,
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    recordOneSegment(vm)
                    awaitItem().text shouldBe "第一段长文本7"
                    advanceTimeBy(1_000)
                    runCurrent()
                    awaitItem().text shouldBe "已润色:第二段长文本7"
                    cancelAndIgnoreRemainingEvents()
                }

                polisher.calls shouldBe 2
                history.inserted.map { it.raw } shouldContainExactly
                    listOf("第一段长文本7", "第二段长文本7")
                vm.pending.value shouldBe 0
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("transcripts made only of Unicode whitespace control and format clusters are discarded") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val online = FakePolisher { raw, _ -> "polished:$raw" }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = FakeSpeechEngine("\u00A0\u200B\u0000\u2060\u3000"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }

                online.calls shouldBe 0
                history.inserted shouldBe emptyList()
                vm.pending.value shouldBe 0
                vm.toast.value shouldBe "未识别到语音"
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("long segment followed by short segment polishes concurrently but commits FIFO") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val online = FakePolisher { raw, _ ->
                    if (raw == "1234567") delay(1_000)
                    "polished:$raw"
                }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = FakeSpeechEngine("1234567", "短文本"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    recordOneSegment(vm)
                    vm.pending.value shouldBe 2

                    advanceTimeBy(1_000)
                    runCurrent()

                    awaitItem().text shouldBe "polished:1234567"
                    awaitItem().text shouldBe "短文本"
                    cancelAndIgnoreRemainingEvents()
                }

                online.calls shouldBe 1
                history.inserted.map { it.raw } shouldContainExactly listOf("1234567", "短文本")
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("short segment followed by long segment keeps FIFO and only polishes the long text") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val online = FakePolisher { raw, _ ->
                    delay(500)
                    "polished:$raw"
                }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = FakeSpeechEngine("短文本", "7654321"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    awaitItem().text shouldBe "短文本"
                    recordOneSegment(vm)
                    advanceTimeBy(500)
                    runCurrent()
                    awaitItem().text shouldBe "polished:7654321"
                    cancelAndIgnoreRemainingEvents()
                }

                online.calls shouldBe 1
                history.inserted.map { it.raw } shouldContainExactly listOf("短文本", "7654321")
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("cancelled recording and ASR failure never polish commit or write history") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val online = FakePolisher { raw, _ -> "polished:$raw" }
                val history = FakeHistoryDao()
                val cancelledVm = voiceViewModel(
                    speech = FakeSpeechEngine("unused transcript"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                )
                val failedVm = voiceViewModel(
                    speech = FailingSpeechEngine(),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                )
                runCurrent()

                cancelledVm.committed.test {
                    cancelledVm.onTap()
                    runCurrent()
                    cancelledVm.cancel()
                    runCurrent()
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
                failedVm.committed.test {
                    recordOneSegment(failedVm)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }

                online.calls shouldBe 0
                history.inserted shouldBe emptyList()
                cancelledVm.pending.value shouldBe 0
                failedVm.pending.value shouldBe 0
                failedVm.toast.value shouldBe "录音停止失败"
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("success suppresses the next FIFO head stage without losing background progress") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val firstGate = CompletableDeferred<Unit>()
                val online = FakePolisher { raw, _ ->
                    if (raw == "1234567") firstGate.await() else delay(5_000)
                    "polished:$raw"
                }
                val vm = voiceViewModel(
                    speech = FakeSpeechEngine("1234567", "7654321"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = FakeHistoryDao(),
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    recordOneSegment(vm)
                    vm.pending.value shouldBe 2
                    vm.state.value.phase shouldBe VoicePhase.Polishing

                    firstGate.complete(Unit)
                    runCurrent()
                    awaitItem().text shouldBe "polished:1234567"
                    vm.pending.value shouldBe 1
                    vm.state.value.phase shouldBe VoicePhase.Success

                    advanceTimeBy(1_200)
                    runCurrent()
                    vm.state.value.phase shouldBe VoicePhase.Polishing

                    advanceTimeBy(3_800)
                    runCurrent()
                    awaitItem().text shouldBe "polished:7654321"
                    cancelAndIgnoreRemainingEvents()
                }

                advanceTimeBy(1_200)
                runCurrent()
                vm.state.value.phase shouldBe VoicePhase.Idle
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("recording success and FIFO head stages follow the required display priority") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val stopGate = CompletableDeferred<Unit>()
                val speech = GatedSpeechEngine("1234567", stopGate)
                val online = FakePolisher { raw, _ ->
                    delay(1_000)
                    "polished:$raw"
                }
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = FakeHistoryDao(),
                )
                runCurrent()

                vm.committed.test {
                    vm.onTap()
                    runCurrent()
                    vm.onTap()
                    runCurrent()
                    vm.state.value.phase shouldBe VoicePhase.Recognizing

                    stopGate.complete(Unit)
                    runCurrent()
                    vm.state.value.phase shouldBe VoicePhase.Polishing

                    vm.onTap()
                    runCurrent()
                    vm.state.value.phase shouldBe VoicePhase.Recording
                    vm.cancel()
                    vm.state.value.phase shouldBe VoicePhase.Polishing

                    advanceTimeBy(1_000)
                    runCurrent()
                    awaitItem().text shouldBe "polished:1234567"
                    vm.state.value.phase shouldBe VoicePhase.Success

                    vm.onTap()
                    runCurrent()
                    vm.state.value.phase shouldBe VoicePhase.Recording
                    vm.cancel()
                    vm.state.value.phase shouldBe VoicePhase.Success

                    advanceTimeBy(1_199)
                    runCurrent()
                    vm.state.value.phase shouldBe VoicePhase.Success
                    advanceTimeBy(1)
                    runCurrent()
                    vm.state.value.phase shouldBe VoicePhase.Idle
                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("external recording start is idempotent and lifecycle stop releases the mic") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val speech = FakeSpeechEngine("unused")
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                )
                runCurrent()

                vm.startFromExternal()
                runCurrent()
                vm.startFromExternal()
                runCurrent()

                vm.state.value.phase shouldBe VoicePhase.Recording
                speech.startCalls shouldBe 1

                vm.stopRecordingAndEndTone()
                runCurrent()

                vm.state.value.phase shouldBe VoicePhase.Idle
                speech.cancelCalls shouldBe 1
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("recording opens complete PCM before one haptic pulse and repeated start is idempotent") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val events = mutableListOf<String>()
                val speech = OrderedSpeechEngine(events, "unused")
                val tones = RecordingToneFeedback(events)
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    toneFeedback = tones,
                    startHaptic = VoiceStartHaptic { events += "haptic" },
                )
                runCurrent()

                vm.startFromExternal()
                vm.startFromExternal()
                vm.state.value.phase shouldBe VoicePhase.Idle
                runCurrent()

                events shouldContainExactly listOf("pcm.start", "haptic")
                speech.startCalls shouldBe 1
                vm.state.value.phase shouldBe VoicePhase.Recording
                tones.played shouldBe emptyList()
                vm.cancel()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("haptic failure and animation-independent recording state never block capture") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val speech = FakeSpeechEngine("unused")
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    startHaptic = VoiceStartHaptic { error("haptic unavailable") },
                )
                runCurrent()

                vm.onTap()
                vm.state.value.phase shouldBe VoicePhase.Idle
                runCurrent()

                speech.startCalls shouldBe 1
                vm.state.value.phase shouldBe VoicePhase.Recording
                vm.cancel()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("normal end plays tone only after PCM is frozen and still commits the transcript") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val stopGate = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                val tones = RecordingToneFeedback(events)
                val vm = voiceViewModel(
                    speech = OrderedSpeechEngine(events, "首字完整", stopGate),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    settings = MutableSettingsSource(tone = true),
                    toneFeedback = tones,
                )
                runCurrent()

                vm.committed.test {
                    vm.onTap()
                    runCurrent()
                    vm.onTap()
                    runCurrent()

                    events shouldContainExactly listOf("pcm.start", "pcm.stop.begin")
                    stopGate.complete(Unit)
                    runCurrent()

                    events shouldContainExactly listOf(
                        "pcm.start",
                        "pcm.stop.begin",
                        "pcm.frozen",
                        "tone.end",
                    )
                    awaitItem().text shouldBe "首字完整"
                    cancelAndIgnoreRemainingEvents()
                }
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("tone disabled or failing never blocks stop commit or final state") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val disabledEvents = mutableListOf<String>()
                val disabledTone = RecordingToneFeedback(disabledEvents)
                val disabledVm = voiceViewModel(
                    speech = OrderedSpeechEngine(disabledEvents, "关闭提示音"),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    settings = MutableSettingsSource(tone = false),
                    toneFeedback = disabledTone,
                )
                val failingEvents = mutableListOf<String>()
                val failingVm = voiceViewModel(
                    speech = OrderedSpeechEngine(failingEvents, "提示音异常"),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    settings = MutableSettingsSource(tone = true),
                    toneFeedback = RecordingToneFeedback(failingEvents, fail = true),
                )
                runCurrent()

                disabledVm.committed.test {
                    recordOneSegment(disabledVm)
                    awaitItem().text shouldBe "关闭提示音"
                    cancelAndIgnoreRemainingEvents()
                }
                disabledTone.played shouldBe emptyList()

                failingVm.committed.test {
                    recordOneSegment(failingVm)
                    awaitItem().text shouldBe "提示音异常"
                    cancelAndIgnoreRemainingEvents()
                }
                failingVm.pending.value shouldBe 0
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("stop failure closes capture before tone and suppresses tone when fallback close also fails") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val recoveredEvents = mutableListOf<String>()
                val recoveredVm = voiceViewModel(
                    speech = ThrowingStopOrderedSpeechEngine(recoveredEvents),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    settings = MutableSettingsSource(tone = true),
                    toneFeedback = RecordingToneFeedback(recoveredEvents),
                )
                val unsafeEvents = mutableListOf<String>()
                val unsafeVm = voiceViewModel(
                    speech = ThrowingStopOrderedSpeechEngine(unsafeEvents, cancelFails = true),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    settings = MutableSettingsSource(tone = true),
                    toneFeedback = RecordingToneFeedback(unsafeEvents),
                )
                runCurrent()

                recordOneSegment(recoveredVm)
                recoveredEvents shouldContainExactly listOf(
                    "pcm.start",
                    "pcm.stop.failed",
                    "pcm.cancelled",
                    "tone.end",
                )

                recordOneSegment(unsafeVm)
                unsafeEvents shouldContainExactly listOf(
                    "pcm.start",
                    "pcm.stop.failed",
                    "pcm.cancel.failed",
                )
                recoveredVm.pending.value shouldBe 0
                unsafeVm.pending.value shouldBe 0
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("cancel closes capture before the existing end tone and discards the segment") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val events = mutableListOf<String>()
                val tones = RecordingToneFeedback(events)
                val vm = voiceViewModel(
                    speech = OrderedSpeechEngine(events, "must not commit"),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    settings = MutableSettingsSource(tone = true),
                    toneFeedback = tones,
                )
                runCurrent()

                vm.committed.test {
                    vm.onTap()
                    runCurrent()
                    vm.cancel()
                    runCurrent()

                    events shouldContainExactly listOf("pcm.start", "pcm.cancelled", "tone.end")
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
                vm.pending.value shouldBe 0
                vm.state.value.phase shouldBe VoicePhase.Idle
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("quick cancel before mic open aborts capture start and keeps the safe end feedback") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val events = mutableListOf<String>()
                val speech = OrderedSpeechEngine(events, "unused")
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    settings = MutableSettingsSource(tone = true),
                    toneFeedback = RecordingToneFeedback(events),
                )

                vm.startFromExternal()
                vm.cancel()
                runCurrent()

                events shouldContainExactly listOf("tone.end")
                speech.startCalls shouldBe 0
                vm.state.value.phase shouldBe VoicePhase.Idle
                vm.cancel()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("cancel failure never risks playing an end tone into a possibly open microphone") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val cancelEvents = mutableListOf<String>()
                val cancelVm = voiceViewModel(
                    speech = ThrowingCancelSpeechEngine(cancelEvents),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    settings = MutableSettingsSource(tone = true),
                    toneFeedback = RecordingToneFeedback(cancelEvents),
                )
                val lifecycleEvents = mutableListOf<String>()
                val lifecycleVm = voiceViewModel(
                    speech = ThrowingCancelSpeechEngine(lifecycleEvents),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    settings = MutableSettingsSource(tone = true),
                    toneFeedback = RecordingToneFeedback(lifecycleEvents),
                )
                runCurrent()

                cancelVm.onTap()
                lifecycleVm.onTap()
                runCurrent()
                cancelVm.cancel()
                lifecycleVm.stopRecordingAndEndTone()
                runCurrent()

                cancelEvents shouldContainExactly listOf("pcm.start", "pcm.cancel.failed")
                lifecycleEvents shouldContainExactly listOf("pcm.start", "pcm.cancel.failed")
                cancelVm.state.value.phase shouldBe VoicePhase.Idle
                lifecycleVm.state.value.phase shouldBe VoicePhase.Idle
                lifecycleVm.stopRecordingAndEndTone()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("recording cat level follows the speech engine input") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val speech = FakeSpeechEngine("unused").apply {
                    inputLevel = 0.82f
                }
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                )
                runCurrent()

                vm.onTap()
                runCurrent()
                advanceTimeBy(140)
                runCurrent()

                vm.state.value.level shouldBe 0.82f
                vm.cancel()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("not-ready engine stays idle and toast can be consumed") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val vm = voiceViewModel(
                    speech = NotReadySpeechEngine,
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                )
                runCurrent()

                vm.startFromExternal()
                runCurrent()

                vm.state.value.phase shouldBe VoicePhase.Idle
                vm.toast.value shouldBe "语音正在准备，请稍候"
                vm.consumeToast()
                vm.toast.value shouldBe null
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("permission emits an event while model preparation stays local and clears processing") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val permissionVm = voiceViewModel(
                    speech = ThrowingStopSpeechEngine(MicPermissionRequiredException()),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                )
                val modelVm = voiceViewModel(
                    speech = ThrowingStopSpeechEngine(ModelNotReadyException()),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                )
                runCurrent()

                permissionVm.event.test {
                    recordOneSegment(permissionVm)
                    awaitItem() shouldBe VoiceEvent.PermissionRequired
                    cancelAndIgnoreRemainingEvents()
                }
                modelVm.event.test {
                    recordOneSegment(modelVm)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }

                permissionVm.toast.value shouldBe "需要麦克风权限"
                modelVm.toast.value shouldBe "语音模型正在准备，请稍候"
                permissionVm.pending.value shouldBe 0
                modelVm.pending.value shouldBe 0
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("online timeout falls back to raw and non-minimal mode reports history") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val vm = voiceViewModel(
                    speech = FakeSpeechEngine("timeout7"),
                    polisher = FakePolisher { _, _ ->
                        delay(10_001)
                        "too late"
                    },
                    history = FakeHistoryDao(),
                    settings = VerboseSettingsSource,
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    vm.state.value.isProcessing shouldBe true
                    advanceTimeBy(10_000)
                    runCurrent()
                    awaitItem().text shouldBe "timeout7"
                    cancelAndIgnoreRemainingEvents()
                }

                vm.toast.value shouldBe "已写入历史"
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("factory constructs the configured voice view model") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val history = FakeHistoryDao()
                val vm = VoiceViewModel.Factory(
                    speechEngine = FakeSpeechEngine("unused"),
                    polisher = FakePolisher { raw, _ -> raw },
                    historyRepository = HistoryRepository(history),
                    settingsRepository = FixedSettingsSource,
                    toneController = ToneController(),
                    startHaptic = VoiceStartHaptic.NONE,
                ).create(VoiceViewModel::class.java)

                runCurrent()
                vm.state.value.phase shouldBe VoicePhase.Idle
                vm.pending.value shouldBe 0
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("password finals never polish or write history while local-only keeps local history") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val online = FakePolisher { raw, _ -> "cloud:$raw" }
                val privateHistory = FakeHistoryDao()
                val privateVm = voiceViewModel(
                    speech = FakeSpeechEngine("敏感内容超过六字"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = privateHistory,
                    privacy = VoicePrivacySource {
                        VoicePrivacyContext(
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                            noPersonalizedLearning = false,
                            incognito = false,
                        )
                    },
                )
                val localHistory = FakeHistoryDao()
                val localVm = voiceViewModel(
                    speech = FakeSpeechEngine("本地内容超过六字"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = localHistory,
                    settings = MutableSettingsSource(tone = false, localOnly = true),
                )
                runCurrent()

                privateVm.committed.test {
                    recordOneSegment(privateVm)
                    awaitItem().text shouldBe "敏感内容超过六字"
                    cancelAndIgnoreRemainingEvents()
                }
                localVm.committed.test {
                    recordOneSegment(localVm)
                    awaitItem().text shouldBe "本地内容超过六字"
                    cancelAndIgnoreRemainingEvents()
                }

                online.calls shouldBe 0
                privateHistory.inserted shouldBe emptyList()
                localHistory.inserted.map { it.raw } shouldContainExactly listOf("本地内容超过六字")
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("permission-denied capture start stays idle without haptic or tone") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val events = mutableListOf<String>()
                val speech = SequencedStartSpeechEngine(
                    CaptureStartResult.Failed(CaptureStartFailure.PermissionDenied),
                )
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    toneFeedback = RecordingToneFeedback(events),
                    startHaptic = VoiceStartHaptic { events += "haptic" },
                )
                runCurrent()

                vm.event.test {
                    vm.startFromExternal()
                    runCurrent()
                    awaitItem() shouldBe VoiceEvent.PermissionRequired
                    cancelAndIgnoreRemainingEvents()
                }
                vm.state.value.phase shouldBe VoicePhase.Idle
                vm.state.value.recording shouldBe false
                vm.toast.value shouldBe "需要麦克风权限"
                speech.startCalls shouldBe 1
                events shouldBe emptyList()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("recorder false and thrown starts stay idle and a later retry can record") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val haptics = mutableListOf<String>()
                val speech = SequencedStartSpeechEngine(
                    CaptureStartResult.Failed(CaptureStartFailure.RecorderStartFailed()),
                    CaptureStartResult.Failed(
                        CaptureStartFailure.RecorderStartFailed(IllegalStateException("start")),
                    ),
                    CaptureStartResult.Started,
                )
                val vm = voiceViewModel(
                    speech = speech,
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    startHaptic = VoiceStartHaptic { haptics += "haptic" },
                )
                runCurrent()

                vm.startFromExternal()
                vm.startFromExternal()
                runCurrent()
                speech.startCalls shouldBe 1
                vm.state.value.phase shouldBe VoicePhase.Idle
                vm.toast.value shouldBe "无法启动录音"
                haptics shouldBe emptyList()

                vm.startFromExternal()
                runCurrent()
                speech.startCalls shouldBe 2
                vm.state.value.phase shouldBe VoicePhase.Idle
                haptics shouldBe emptyList()

                vm.startFromExternal()
                runCurrent()
                speech.startCalls shouldBe 3
                vm.state.value.phase shouldBe VoicePhase.Recording
                haptics shouldContainExactly listOf("haptic")
                vm.startFromExternal()
                runCurrent()
                speech.startCalls shouldBe 3
                vm.cancel()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("capture read error is typed and never becomes an empty successful segment") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = ThrowingStopSpeechEngine(RecorderReadException(-3)),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = history,
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
                vm.toast.value shouldBe "录音读取失败"
                vm.pending.value shouldBe 0
                history.inserted shouldBe emptyList()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("privacy source failure is strict and discards without cloud history or commit") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val online = FakePolisher { raw, _ -> "cloud:$raw" }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = FakeSpeechEngine("隐私源异常内容超过六字"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                    privacy = VoicePrivacySource { error("editor unavailable") },
                )
                runCurrent()

                vm.committed.test {
                    vm.discarded.test {
                        recordOneSegment(vm)
                        awaitItem() shouldBe VoiceSegmentDiscardReason.PrivacyUnavailable
                        cancelAndIgnoreRemainingEvents()
                    }
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
                online.calls shouldBe 0
                history.inserted shouldBe emptyList()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("normal to password and password to normal monotonically disable cloud and history") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val normal = verifiedPrivacy(password = false, sessionToken = 7)
                val password = verifiedPrivacy(password = true, sessionToken = 7)
                val online = FakePolisher { raw, _ -> "cloud:$raw" }

                suspend fun exercise(start: VoicePrivacyContext, stop: VoicePrivacyContext) {
                    val privacy = MutableVoicePrivacySource(start)
                    val history = FakeHistoryDao()
                    val raw = "敏感切换内容超过六字"
                    val vm = voiceViewModel(
                        speech = FakeSpeechEngine(raw),
                        polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                        history = history,
                        privacy = privacy,
                    )
                    runCurrent()
                    vm.committed.test {
                        vm.onTap()
                        runCurrent()
                        privacy.context = stop
                        vm.onTap()
                        runCurrent()
                        awaitItem().text shouldBe raw
                        cancelAndIgnoreRemainingEvents()
                    }
                    history.inserted shouldBe emptyList()
                    advanceTimeBy(1_200)
                    runCurrent()
                }

                exercise(normal, password)
                exercise(password, normal)
                online.calls shouldBe 0
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("local-only enabled while ASR is pending prevents cloud but keeps local history") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val stopGate = CompletableDeferred<Unit>()
                val settings = MutableSettingsSource(tone = false, localOnly = false)
                val online = FakePolisher { raw, _ -> "cloud:$raw" }
                val history = FakeHistoryDao()
                val raw = "运行中切换仅本地内容"
                val vm = voiceViewModel(
                    speech = GatedSpeechEngine(raw, stopGate),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                    settings = settings,
                    privacy = MutableVoicePrivacySource(verifiedPrivacy(sessionToken = 11)),
                )
                runCurrent()

                vm.committed.test {
                    vm.onTap()
                    runCurrent()
                    vm.onTap()
                    runCurrent()
                    settings.setLocalOnly(true)
                    runCurrent()
                    stopGate.complete(Unit)
                    runCurrent()
                    awaitItem().text shouldBe raw
                    cancelAndIgnoreRemainingEvents()
                }
                online.calls shouldBe 0
                history.inserted.map { it.raw } shouldContainExactly listOf(raw)
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("editor session change discards old segment with zero cloud history and commit") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val stopGate = CompletableDeferred<Unit>()
                val privacy = MutableVoicePrivacySource(verifiedPrivacy(sessionToken = 21))
                val online = FakePolisher { raw, _ -> "cloud:$raw" }
                val history = FakeHistoryDao()
                val vm = voiceViewModel(
                    speech = GatedSpeechEngine("旧输入框内容超过六字", stopGate),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, online),
                    history = history,
                    privacy = privacy,
                )
                runCurrent()

                vm.committed.test {
                    vm.discarded.test {
                        vm.onTap()
                        runCurrent()
                        vm.onTap()
                        runCurrent()
                        privacy.context = verifiedPrivacy(sessionToken = 22)
                        stopGate.complete(Unit)
                        runCurrent()
                        awaitItem() shouldBe VoiceSegmentDiscardReason.EditorSessionChanged
                        cancelAndIgnoreRemainingEvents()
                    }
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
                online.calls shouldBe 0
                history.inserted shouldBe emptyList()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("emitted commit retains start token and collector rejects a later editor session") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val privacy = MutableVoicePrivacySource(verifiedPrivacy(sessionToken = 31))
                val vm = voiceViewModel(
                    speech = FakeSpeechEngine("旧段"),
                    polisher = FakePolisher { raw, _ -> raw },
                    history = FakeHistoryDao(),
                    privacy = privacy,
                )
                runCurrent()

                vm.committed.test {
                    recordOneSegment(vm)
                    val emitted = awaitItem()
                    emitted.text shouldBe "旧段"
                    emitted.editorSessionToken shouldBe 31L
                    privacy.context = verifiedPrivacy(sessionToken = 32)
                    emitted.belongsTo(privacy.context.editorSessionToken) shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("settings first emission delay or failure is fail-closed for cloud polishing") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val loaded = CompletableDeferred<Unit>()
                val delayedOnline = FakePolisher { raw, _ -> "cloud:$raw" }
                val failedOnline = FakePolisher { raw, _ -> "cloud:$raw" }
                val delayedVm = voiceViewModel(
                    speech = FakeSpeechEngine("延迟设置内容超过六字"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, delayedOnline),
                    history = FakeHistoryDao(),
                    settings = DelayedSettingsSource(loaded, localOnly = true),
                )
                val failedVm = voiceViewModel(
                    speech = FakeSpeechEngine("设置异常内容超过六字"),
                    polisher = OnlineOnlyPolisher(InternetConnection { true }, failedOnline),
                    history = FakeHistoryDao(),
                    settings = FailingSettingsSource,
                )
                runCurrent()

                delayedVm.committed.test {
                    recordOneSegment(delayedVm)
                    awaitItem().text shouldBe "延迟设置内容超过六字"
                    cancelAndIgnoreRemainingEvents()
                }
                failedVm.committed.test {
                    recordOneSegment(failedVm)
                    awaitItem().text shouldBe "设置异常内容超过六字"
                    cancelAndIgnoreRemainingEvents()
                }
                delayedOnline.calls shouldBe 0
                failedOnline.calls shouldBe 0
                loaded.complete(Unit)
                advanceTimeBy(1_200)
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("verified privacy signals independently force local processing without history") {
        val normal = android.text.InputType.TYPE_CLASS_TEXT
        VoicePrivacyPolicy.decide(
            inputType = normal,
            noPersonalizedLearning = true,
            incognito = false,
            localRecognitionOnly = false,
        ) shouldBe VoicePrivacyDecision(allowCloudPolish = false, saveHistory = false)
        VoicePrivacyPolicy.decide(
            inputType = normal,
            noPersonalizedLearning = false,
            incognito = true,
            localRecognitionOnly = false,
        ) shouldBe VoicePrivacyDecision(allowCloudPolish = false, saveHistory = false)
        VoicePrivacyPolicy.decide(
            inputType = normal,
            noPersonalizedLearning = false,
            incognito = false,
            localRecognitionOnly = true,
        ) shouldBe VoicePrivacyDecision(allowCloudPolish = false, saveHistory = true)
    }
})

@OptIn(ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.test.TestScope.recordOneSegment(vm: VoiceViewModel) {
    vm.onTap()
    runCurrent()
    vm.onTap()
    runCurrent()
}

private fun voiceViewModel(
    speech: SpeechEngine,
    polisher: Polisher,
    history: FakeHistoryDao,
    settings: SettingsSource = FixedSettingsSource,
    toneFeedback: VoiceToneFeedback = ToneController(),
    startHaptic: VoiceStartHaptic = VoiceStartHaptic.NONE,
    privacy: VoicePrivacySource = VoicePrivacySource.NORMAL,
): VoiceViewModel = VoiceViewModel(
    speechEngine = speech,
    polisher = polisher,
    historyRepository = HistoryRepository(history),
    settingsRepository = settings,
    toneController = toneFeedback,
    startHaptic = startHaptic,
    privacySource = privacy,
)

private object FixedSettingsSource : SettingsSource {
    override val settings: Flow<SettingsState> = MutableStateFlow(
        SettingsState(tone = false, minimal = true),
    )
}

private object VerboseSettingsSource : SettingsSource {
    override val settings: Flow<SettingsState> = MutableStateFlow(
        SettingsState(tone = false, minimal = false),
    )
}

private class MutableSettingsSource(tone: Boolean, localOnly: Boolean = false) : SettingsSource {
    private val mutableSettings = MutableStateFlow(
        SettingsState(
            tone = tone,
            toneVolume = 30,
            minimal = true,
            localRecognitionOnly = localOnly,
        ),
    )
    override val settings: Flow<SettingsState> = mutableSettings

    fun setLocalOnly(enabled: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(localRecognitionOnly = enabled)
    }
}

private class DelayedSettingsSource(
    private val gate: CompletableDeferred<Unit>,
    private val localOnly: Boolean,
) : SettingsSource {
    override val settings: Flow<SettingsState> = flow {
        gate.await()
        emit(SettingsState(tone = false, minimal = true, localRecognitionOnly = localOnly))
    }
}

private object FailingSettingsSource : SettingsSource {
    override val settings: Flow<SettingsState> = flow { error("settings unavailable") }
}

private class MutableVoicePrivacySource(
    var context: VoicePrivacyContext,
) : VoicePrivacySource {
    override fun current(): VoicePrivacyContext = context
}

private fun verifiedPrivacy(
    password: Boolean = false,
    sessionToken: Long,
): VoicePrivacyContext = VoicePrivacyContext(
    inputType = android.text.InputType.TYPE_CLASS_TEXT or if (password) {
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    } else {
        android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
    },
    noPersonalizedLearning = false,
    incognito = false,
    editorSessionToken = sessionToken,
)

private class RecordingToneFeedback(
    private val events: MutableList<String>,
    private val fail: Boolean = false,
) : VoiceToneFeedback {
    val played = mutableListOf<Float>()

    override fun endBeep(enabled: Boolean, style: String, volume: Float) {
        if (!enabled) return
        events += "tone.end"
        played += volume
        if (fail) error("tone failed")
    }

    override fun release() = Unit
}

private class OrderedSpeechEngine(
    private val events: MutableList<String>,
    private val transcript: String,
    private val stopGate: CompletableDeferred<Unit>? = null,
) : SpeechEngine {
    override val partials: StateFlow<String> = MutableStateFlow("")
    var startCalls = 0
        private set

    override fun start(): CaptureStartResult {
        startCalls += 1
        events += "pcm.start"
        return CaptureStartResult.Started
    }

    override suspend fun stop(): String {
        events += "pcm.stop.begin"
        stopGate?.await()
        events += "pcm.frozen"
        return transcript
    }

    override fun cancel() {
        events += "pcm.cancelled"
    }

    override fun isReady(): Boolean = true
}

private class SequencedStartSpeechEngine(
    vararg startResults: CaptureStartResult,
) : SpeechEngine {
    private val results = ArrayDeque(startResults.toList())
    override val partials: StateFlow<String> = MutableStateFlow("")
    var startCalls = 0
        private set

    override fun start(): CaptureStartResult {
        startCalls += 1
        return results.removeFirst()
    }

    override suspend fun stop(): String = "unused"

    override fun cancel() = Unit

    override fun isReady(): Boolean = true
}

private class ThrowingCancelSpeechEngine(
    private val events: MutableList<String>,
) : SpeechEngine {
    override val partials: StateFlow<String> = MutableStateFlow("")

    override fun start(): CaptureStartResult {
        events += "pcm.start"
        return CaptureStartResult.Started
    }

    override suspend fun stop(): String = ""

    override fun cancel() {
        events += "pcm.cancel.failed"
        error("capture close failed")
    }

    override fun isReady(): Boolean = true
}

private class ThrowingStopOrderedSpeechEngine(
    private val events: MutableList<String>,
    private val cancelFails: Boolean = false,
) : SpeechEngine {
    override val partials: StateFlow<String> = MutableStateFlow("")

    override fun start(): CaptureStartResult {
        events += "pcm.start"
        return CaptureStartResult.Started
    }

    override suspend fun stop(): String {
        events += "pcm.stop.failed"
        error("capture stop failed")
    }

    override fun cancel() {
        if (cancelFails) {
            events += "pcm.cancel.failed"
            error("capture close failed")
        }
        events += "pcm.cancelled"
    }

    override fun isReady(): Boolean = true
}

private class FakeSpeechEngine(vararg transcripts: String) : SpeechEngine {
    private val results = ArrayDeque(transcripts.toList())
    override val partials: StateFlow<String> = MutableStateFlow("")
    var startCalls = 0
        private set
    var cancelCalls = 0
        private set
    var inputLevel = 0f

    override fun start(): CaptureStartResult {
        startCalls += 1
        return CaptureStartResult.Started
    }

    override suspend fun stop(): String = results.removeFirst()

    override fun cancel() {
        cancelCalls += 1
    }

    override fun isReady(): Boolean = true

    override fun currentLevel(): Float = inputLevel
}

private object NotReadySpeechEngine : SpeechEngine {
    override val partials: StateFlow<String> = MutableStateFlow("")

    override fun start() = CaptureStartResult.Started

    override suspend fun stop(): String = ""

    override fun cancel() = Unit

    override fun isReady(): Boolean = false
}

private class ThrowingStopSpeechEngine(
    private val failure: Exception,
) : SpeechEngine {
    override val partials: StateFlow<String> = MutableStateFlow("")

    override fun start() = CaptureStartResult.Started

    override suspend fun stop(): String = throw failure

    override fun cancel() = Unit

    override fun isReady(): Boolean = true
}

private class GatedSpeechEngine(
    private val transcript: String,
    private val stopGate: CompletableDeferred<Unit>,
) : SpeechEngine {
    override val partials: StateFlow<String> = MutableStateFlow("")

    override fun start() = CaptureStartResult.Started

    override suspend fun stop(): String {
        stopGate.await()
        return transcript
    }

    override fun cancel() = Unit

    override fun isReady(): Boolean = true
}

private class FirstStopGatedSpeechEngine(
    private val firstStopGate: CompletableDeferred<Unit>,
    vararg transcripts: String,
) : SpeechEngine {
    private val results = ArrayDeque(transcripts.toList())
    override val partials: StateFlow<String> = MutableStateFlow("")
    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var cancelCalls = 0
        private set

    override fun start(): CaptureStartResult {
        startCalls += 1
        return CaptureStartResult.Started
    }

    override suspend fun stop(): String = error("legacy stop must not be used")

    override suspend fun stopCapture(): PendingAsrResult {
        stopCalls += 1
        val ordinal = stopCalls
        val text = results.removeFirst()
        return PendingAsrResult {
            if (ordinal == 1) firstStopGate.await()
            AsrResult(text, "fake", "1")
        }
    }

    override fun cancel() {
        cancelCalls += 1
    }

    override fun isReady(): Boolean = true
}

private class FailingSpeechEngine : SpeechEngine {
    override val partials: StateFlow<String> = MutableStateFlow("")

    override fun start() = CaptureStartResult.Started

    override suspend fun stop(): String = error("ASR failed")

    override fun cancel() = Unit

    override fun isReady(): Boolean = true
}

private class FakePolisher(
    private val result: suspend (String, String) -> String,
) : Polisher {
    var calls: Int = 0
        private set

    override suspend fun polish(raw: String, role: String): String {
        calls += 1
        return result(raw, role)
    }
}

private class OutcomePolisher(
    private val result: suspend (String, String) -> PolishResult,
) : Polisher {
    var calls: Int = 0
        private set

    override suspend fun polish(raw: String, role: String): String =
        polishResult(raw, role).text

    override suspend fun polishResult(raw: String, role: String): PolishResult {
        calls += 1
        return result(raw, role)
    }
}

private class FakeHistoryDao : HistoryDao {
    val inserted = mutableListOf<HistoryEntity>()

    override suspend fun insert(entity: HistoryEntity): Long {
        inserted += entity
        return inserted.size.toLong()
    }

    override fun observeAll(): Flow<List<HistoryEntity>> = MutableStateFlow(inserted)

    override fun search(q: String): Flow<List<HistoryEntity>> = MutableStateFlow(
        inserted.filter { q in it.raw || q in it.polished },
    )

    override suspend fun delete(id: Long) = Unit

    override suspend fun clearAll() {
        inserted.clear()
    }

    override suspend fun count(): Int = 1
}
