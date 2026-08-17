package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ParaformerModelLifecyclePolicyTest : FunSpec({

    test("fresh install requires confirmation and completion never starts microphone") {
        val lifecycle = ParaformerModelLifecycle()
        lifecycle.onVoiceRequested(modelReady = false) shouldBe ParaformerLifecycleAction.ShowConfirmation
        lifecycle.state.phase shouldBe ParaformerModelPhase.AwaitingConfirmation

        lifecycle.onDownloadInstalled()
        lifecycle.state.phase shouldBe ParaformerModelPhase.Initializing
        lifecycle.onInitializationSucceeded()
        lifecycle.state.phase shouldBe ParaformerModelPhase.Ready
        lifecycle.consumePendingVoiceStart() shouldBe false
    }

    test("cancel removes partial intent while pause and process recreation retain resumable bytes") {
        val lifecycle = ParaformerModelLifecycle()
        lifecycle.onDownloadProgress(42L, mobileConfirmed = false)
        lifecycle.pause(ParaformerPauseReason.User)
        val restored = ParaformerModelLifecycle(lifecycle.snapshot())
        restored.state.phase shouldBe ParaformerModelPhase.Paused
        restored.state.downloadedBytes shouldBe 42L
        restored.resumeAction() shouldBe ParaformerLifecycleAction.EnqueueWifi

        restored.cancel()
        restored.state.phase shouldBe ParaformerModelPhase.Uninstalled
        restored.state.downloadedBytes shouldBe 0L
    }

    test("concurrent confirmation produces one unique enqueue action") {
        val lifecycle = ParaformerModelLifecycle()
        lifecycle.requestConfirmation()
        lifecycle.confirmWifi() shouldBe ParaformerLifecycleAction.EnqueueWifi
        lifecycle.confirmWifi() shouldBe ParaformerLifecycleAction.None
        lifecycle.confirmMobile() shouldBe ParaformerLifecycleAction.None
    }

    test("legacy Zipformer cleanup is gated by verified initialized successful recognition") {
        ParaformerLegacyCleanupPolicy.mayDelete(
            paraformerVerified = true,
            initialized = true,
            successfulRecognitionCount = 0,
        ) shouldBe false
        ParaformerLegacyCleanupPolicy.mayDelete(
            paraformerVerified = true,
            initialized = false,
            successfulRecognitionCount = 1,
        ) shouldBe false
        ParaformerLegacyCleanupPolicy.mayDelete(
            paraformerVerified = true,
            initialized = true,
            successfulRecognitionCount = 1,
        ) shouldBe true
    }
})
