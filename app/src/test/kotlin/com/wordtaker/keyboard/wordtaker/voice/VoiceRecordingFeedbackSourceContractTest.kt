package com.wordtaker.keyboard.wordtaker.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class VoiceRecordingFeedbackSourceContractTest : FunSpec({

    test("speech capture API and recorder have no leading PCM discard surface") {
        val speechBoundary = productionSource("wordtaker/speech/SpeechEngine.kt")
        val realEngine = productionSource("wordtaker/speech/RealSpeechEngine.kt")
        val recorder = productionSource("wordtaker/speech/PcmRecorder.kt")

        speechBoundary shouldContain "fun start(): CaptureStartResult"
        speechBoundary shouldContain "data class RecorderStartFailed"
        speechBoundary shouldNotContain "suppressLeading"
        realEngine shouldNotContain "suppressLeading"
        realEngine shouldNotContain "skipLeadingSamples"
        recorder shouldNotContain "skipLeadingSamples"
        recorder shouldNotContain "skipInBuf"
        recorder shouldNotContain "samples dropped/consumed"
        recorder shouldContain "val copy = buf.copyOfRange(0, n)"
    }

    test("record start contains no audio playback or fixed guard and triggers haptic after capture") {
        val viewModel = productionSource("wordtaker/voice/VoiceViewModel.kt")
        val startBlock = viewModel
            .substringAfter("private fun startRecording()")
            .substringBefore("private fun finishSegment()")
        val tones = productionSource("wordtaker/audio/ToneController.kt")

        startBlock shouldContain "speechEngine.start()"
        startBlock shouldContain "CaptureStartResult.Started"
        startBlock shouldContain "handleCaptureStartFailure"
        startBlock shouldContain "startHaptic.pulse()"
        startBlock shouldNotContain "startBeep"
        startBlock shouldNotContain "TONE_GUARD"
        viewModel shouldNotContain "850"
        tones shouldNotContain "fun startBeep("
        tones shouldNotContain "MEOW_START_VOLUME"
    }

    test("normal stop freezes PCM before end tone and abort closes the mic before end tone") {
        val viewModel = productionSource("wordtaker/voice/VoiceViewModel.kt")
        val finishBlock = viewModel
            .substringAfter("private fun finishSegment()")
            .substringBefore("fun consumeToast()")
        val cancelBlock = viewModel
            .substringAfter("fun cancel()")
            .substringBefore("fun startFromExternal()")
        val lifecycleBlock = viewModel
            .substringAfter("fun stopRecordingAndEndTone()")
            .substringBefore("private fun toneVolume()")

        finishBlock.substringAfter("speechEngine.stopCapture()") shouldContain "playEndTone"
        finishBlock.substringBefore("speechEngine.stopCapture()") shouldNotContain "playEndTone"
        val activeCancel = cancelBlock.substringAfter("val captureClosed")
        val activeLifecycle = lifecycleBlock.substringAfter("val captureClosed")
        activeCancel.substringAfter("speechEngine.cancel()") shouldContain "playEndTone"
        activeCancel.substringBefore("speechEngine.cancel()") shouldNotContain "playEndTone"
        activeLifecycle.substringAfter("speechEngine.cancel()") shouldContain "playEndTone"
        activeLifecycle.substringBefore("speechEngine.cancel()") shouldNotContain "playEndTone"
    }

    test("recorder stop releases AudioRecord before awaiting the reader") {
        val recorder = productionSource("wordtaker/speech/PcmRecorder.kt")
        val stopBody = recorder.substringAfter("fun stop(): FloatArray {")
            .substringBefore("\n    }")
        stopBody shouldContain "safeReleaseRecord()"
        stopBody shouldContain "latch?.await"
        stopBody shouldContain "throw RecorderStopException"
        stopBody shouldContain "throw readFailure"
        (stopBody.indexOf("safeReleaseRecord()") < stopBody.indexOf("latch?.await")) shouldBe true
    }

    test("capture read failures and false starts are typed instead of becoming empty speech") {
        val recorder = productionSource("wordtaker/speech/PcmRecorder.kt")
        val realEngine = productionSource("wordtaker/speech/RealSpeechEngine.kt")

        recorder shouldContain "RecorderReadException"
        recorder shouldContain "if (n < 0)"
        recorder shouldContain "captureFailure.compareAndSet"
        realEngine shouldContain "CaptureStartFailure.PermissionDenied"
        realEngine shouldContain "CaptureStartFailure.ModelNotReady"
        realEngine shouldContain "CaptureStartFailure.RecorderStartFailed"
    }

    test("privacy session token is monotonic and production sources fail closed") {
        val editor = productionSource("ime/editor/AbstractEditorInstance.kt")
        val privacy = productionSource("wordtaker/voice/VoicePrivacyPolicy.kt")
        val keyboard = productionSource("wordtaker/voice/CatKeyboardLayout.kt")
        val overlay = productionSource("wordtaker/voice/CatVoiceOverlay.kt")

        editor.substringAfter("open fun handleStartInput").substringBefore("open fun handleStartInputView")
            .shouldContain("activeInputSessionToken")
        privacy shouldContain "val STRICT"
        privacy shouldContain "TYPE_NULL"
        keyboard shouldContain "activeInputSessionToken"
        keyboard shouldContain "fromEditor"
        overlay shouldContain "activeInputSessionToken"
        overlay shouldContain "fromEditor"
    }

    test("main settings exposes the single local-only source and accurate privacy copy") {
        val settings = productionSource("app/settings/MinimalSettingsScreen.kt")

        settings shouldContain "state.localRecognitionOnly"
        settings shouldContain "repo.setLocalRecognitionOnly(it)"
        settings shouldContain "语音识别全程在本地完成"
        settings shouldContain "云端润色开启时，识别正文会发送到服务端处理"
        settings shouldContain "密码等敏感字段永不发送到云端，也不会写入历史记录"
        settings shouldNotContain "转写文本只保存在本机"
    }

    test("one commit collector rechecks the bound editor session immediately before commit") {
        val keyboard = productionSource("wordtaker/voice/CatKeyboardLayout.kt")
        val overlay = productionSource("wordtaker/voice/CatVoiceOverlay.kt")
        val commitBlock = keyboard.substringAfter("vm.committed.collect")
            .substringBefore("// One-shot events")

        commitBlock shouldContain "commit.belongsTo(editorInstance.activeInputSessionToken)"
        commitBlock shouldContain "editorInstance.commitText(commit.text)"
        overlay shouldNotContain "vm.committed.collect"
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
