package com.wordtaker.keyboard.wordtaker.voice

import com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class VoiceFeedbackPolicyTest : FunSpec({

    test("talk pill restores the exact legacy wide visual contract at every width") {
        TalkPillLayoutSpec.heightDp shouldBe 36
        TalkPillLayoutSpec.cornerRadiusDp shouldBe 18
        TalkPillLayoutSpec.horizontalPaddingDp shouldBe 12
        TalkPillLayoutSpec.microphoneSizeDp shouldBe 16
        TalkPillLayoutSpec.iconLabelGapDp shouldBe 6

        listOf(250f, 379.9f, 380f, 800f).forEach { widthDp ->
            val sizing = voiceToolbarSizing(widthDp)
            sizing.talkHorizontalPaddingDp shouldBe TalkPillLayoutSpec.horizontalPaddingDp
            sizing.talkMicSizeDp shouldBe TalkPillLayoutSpec.microphoneSizeDp
            sizing.talkMicGapDp shouldBe TalkPillLayoutSpec.iconLabelGapDp
        }
    }

    test("active composing replaces the whole toolbar even before candidates arrive") {
        voiceToolbarPresentation(
            hasActiveComposing = true,
            hasVisibleCandidates = false,
            isRecording = false,
        ) shouldBe VoiceToolbarPresentation.Candidates

        voiceToolbarElements(
            phase = VoicePhase.Idle,
            pending = 0,
            presentation = VoiceToolbarPresentation.Candidates,
        ) shouldContainExactly listOf(VoiceToolbarElement.Candidates)
    }

    test("visible candidates replace the toolbar and clearing both restores it immediately") {
        voiceToolbarPresentation(
            hasActiveComposing = false,
            hasVisibleCandidates = true,
            isRecording = false,
        ) shouldBe VoiceToolbarPresentation.Candidates
        voiceToolbarPresentation(
            hasActiveComposing = false,
            hasVisibleCandidates = false,
            isRecording = false,
        ) shouldBe VoiceToolbarPresentation.Normal
        voiceToolbarPresentation(
            hasActiveComposing = true,
            hasVisibleCandidates = true,
            isRecording = true,
        ) shouldBe VoiceToolbarPresentation.Normal
    }

    test("maps every aggregate phase to stable Chinese semantics and CatSkin effects") {
        val cases = listOf(
            VoiceUiState(phase = VoicePhase.Recording, recording = true, level = 0.8f) to
                Triple("正在录音，小猫正在聆听", false, false),
            VoiceUiState(phase = VoicePhase.Recognizing, busy = true) to
                Triple("正在转录，小猫正在思考", false, false),
            VoiceUiState(phase = VoicePhase.Polishing, busy = true) to
                Triple("正在在线润色，小猫正在思考", true, false),
            VoiceUiState(phase = VoicePhase.Success) to
                Triple("语音输入完成，小猫送来星光", false, true),
        )

        cases.forEach { (state, expected) ->
            voiceStatusDescription(state.phase) shouldBe expected.first
            state.toCatState().polishing shouldBe expected.second
            state.toCatState().success shouldBe expected.third
        }
    }

    test("maps every polish outcome to an honest visual and accessibility status") {
        voiceStatusLabel(VoicePhase.Recording) shouldBe "录音中"
        voiceStatusLabel(VoicePhase.Recognizing) shouldBe "转录中"
        voiceStatusLabel(VoicePhase.Polishing) shouldBe "润色中"
        voiceStatusLabel(VoicePhase.Idle) shouldBe "点击说话"
        voiceStatusDescription(VoicePhase.Idle) shouldBe "语音输入空闲"

        val cases = listOf(
            PolishOutcomeKind.ShortDirect to "短句直出",
            PolishOutcomeKind.OfflineDirect to "离线直出",
            PolishOutcomeKind.Polished to "已完成",
            PolishOutcomeKind.FallbackQuota to "未润色：额度不足",
            PolishOutcomeKind.FallbackAuthExpired to "未润色：登录已失效",
            PolishOutcomeKind.FallbackTimeout to "未润色：服务超时",
            PolishOutcomeKind.FallbackNetwork to "未润色：网络异常",
            PolishOutcomeKind.FallbackServer to "未润色：服务异常",
            PolishOutcomeKind.FallbackUnknown to "未润色：服务异常",
        )

        cases.forEach { (outcome, expectedLabel) ->
            voiceStatusLabel(VoicePhase.Success, outcome) shouldBe expectedLabel
            voiceStatusDescription(VoicePhase.Success, outcome) shouldBe when (outcome) {
                PolishOutcomeKind.Polished -> "语音输入完成，小猫送来星光"
                else -> expectedLabel
            }
        }
    }

    test("only background segments contribute to the compact count") {
        backgroundPendingCount(VoicePhase.Recording, 3) shouldBe 3
        backgroundPendingCount(VoicePhase.Success, 2) shouldBe 2
        backgroundPendingCount(VoicePhase.Recognizing, 3) shouldBe 2
        backgroundPendingCount(VoicePhase.Polishing, 1) shouldBe 0
        backgroundPendingCount(VoicePhase.Idle, 0) shouldBe 0
    }

    test("non recording toolbar always keeps talk before status and background count") {
        voiceToolbarElements(VoicePhase.Polishing, pending = 3) shouldContainExactly listOf(
            VoiceToolbarElement.Settings,
            VoiceToolbarElement.Talk,
            VoiceToolbarElement.Status,
            VoiceToolbarElement.Background,
            VoiceToolbarElement.Flexible,
            VoiceToolbarElement.Collapse,
        )
        voiceToolbarElements(VoicePhase.Success, pending = 1) shouldContainExactly listOf(
            VoiceToolbarElement.Settings,
            VoiceToolbarElement.Talk,
            VoiceToolbarElement.Status,
            VoiceToolbarElement.Background,
            VoiceToolbarElement.Flexible,
            VoiceToolbarElement.Collapse,
        )
    }

    test("idle and single foreground work keep the full talk capsule without a fake background count") {
        voiceToolbarElements(VoicePhase.Idle, pending = 0) shouldContainExactly listOf(
            VoiceToolbarElement.Settings,
            VoiceToolbarElement.Talk,
            VoiceToolbarElement.Flexible,
            VoiceToolbarElement.Collapse,
        )
        voiceToolbarElements(VoicePhase.Recognizing, pending = 1) shouldContainExactly listOf(
            VoiceToolbarElement.Settings,
            VoiceToolbarElement.Talk,
            VoiceToolbarElement.Status,
            VoiceToolbarElement.Flexible,
            VoiceToolbarElement.Collapse,
        )
    }

    test("narrow toolbar drops status text without shrinking the talk pill contract") {
        val narrow = voiceToolbarSizing(250f)
        val wide = voiceToolbarSizing(380f)

        narrow.showStatusText shouldBe false
        wide.showStatusText shouldBe true
        (narrow.itemGapDp < wide.itemGapDp) shouldBe true
        narrow.talkHorizontalPaddingDp shouldBe wide.talkHorizontalPaddingDp
        narrow.talkMicSizeDp shouldBe wide.talkMicSizeDp
        narrow.talkMicGapDp shouldBe wide.talkMicGapDp
        (narrow.statusCatWidthDp < wide.statusCatWidthDp) shouldBe true
    }

    test("only polishing enlarges the status cat and adds eight dp motion space per side") {
        val narrow = voiceToolbarSizing(379.9f)
        val wide = voiceToolbarSizing(380f)

        StatusCatVisualSpec.compactWidthDp shouldBe 18
        StatusCatVisualSpec.compactHeightDp shouldBe 32
        StatusCatVisualSpec.wideWidthDp shouldBe 35
        StatusCatVisualSpec.wideHeightDp shouldBe 44
        StatusCatVisualSpec.polishingCompactWidthDp shouldBe 23
        StatusCatVisualSpec.polishingCompactHeightDp shouldBe 40
        StatusCatVisualSpec.polishingWideWidthDp shouldBe 46
        StatusCatVisualSpec.polishingWideHeightDp shouldBe 57
        StatusCatVisualSpec.polishingMotionSpacePerSideDp shouldBe 8
        StatusCatVisualSpec.maxHeightDp shouldBe 57

        narrow.statusCatWidthDp shouldBe StatusCatVisualSpec.compactWidthDp
        narrow.statusCatHeightDp shouldBe StatusCatVisualSpec.compactHeightDp
        wide.statusCatWidthDp shouldBe StatusCatVisualSpec.wideWidthDp
        wide.statusCatHeightDp shouldBe StatusCatVisualSpec.wideHeightDp

        val polishingNarrow = statusCatVisualSpec(VoicePhase.Polishing, narrow)
        val polishingWide = statusCatVisualSpec(VoicePhase.Polishing, wide)
        polishingNarrow.widthDp shouldBe 23
        polishingNarrow.heightDp shouldBe 40
        polishingNarrow.motionSpacePerSideDp shouldBe 8
        polishingNarrow.viewportWidthDp shouldBe 39
        polishingWide.widthDp shouldBe 46
        polishingWide.heightDp shouldBe 57
        polishingWide.motionSpacePerSideDp shouldBe 8
        polishingWide.viewportWidthDp shouldBe 62

        listOf(
            VoicePhase.Idle,
            VoicePhase.Recording,
            VoicePhase.Recognizing,
            VoicePhase.Success,
        ).forEach { phase ->
            statusCatVisualSpec(phase, narrow) shouldBe StatusCatVisual(
                widthDp = 18,
                heightDp = 32,
                motionSpacePerSideDp = 0,
            )
            statusCatVisualSpec(phase, wide) shouldBe StatusCatVisual(
                widthDp = 35,
                heightDp = 44,
                motionSpacePerSideDp = 0,
            )
        }

        (polishingNarrow.heightDp <= StatusCatVisualSpec.maxHeightDp) shouldBe true
        (polishingWide.heightDp <= StatusCatVisualSpec.maxHeightDp) shouldBe true
        safeStatusVisualGapDp(narrow.itemGapDp) shouldBe 4
        safeStatusVisualGapDp(wide.itemGapDp) shouldBe 8
    }

    test("candidate presentation suppresses status cat while typed outcome labels remain exact") {
        listOf(
            VoicePhase.Recognizing,
            VoicePhase.Polishing,
            VoicePhase.Success,
        ).forEach { phase ->
            voiceToolbarElements(
                phase = phase,
                pending = 3,
                presentation = VoiceToolbarPresentation.Candidates,
            ) shouldContainExactly listOf(VoiceToolbarElement.Candidates)
        }

        voiceStatusLabel(VoicePhase.Success, PolishOutcomeKind.ShortDirect) shouldBe "短句直出"
        voiceStatusLabel(VoicePhase.Success, PolishOutcomeKind.OfflineDirect) shouldBe "离线直出"
        voiceStatusLabel(VoicePhase.Success, PolishOutcomeKind.Polished) shouldBe "已完成"
        voiceStatusLabel(VoicePhase.Success, PolishOutcomeKind.FallbackQuota) shouldBe
            "未润色：额度不足"
        voiceStatusLabel(VoicePhase.Success, PolishOutcomeKind.FallbackAuthExpired) shouldBe
            "未润色：登录已失效"
        voiceStatusLabel(VoicePhase.Success, PolishOutcomeKind.FallbackTimeout) shouldBe
            "未润色：服务超时"
        voiceStatusLabel(VoicePhase.Success, PolishOutcomeKind.FallbackNetwork) shouldBe
            "未润色：网络异常"
        voiceStatusLabel(VoicePhase.Success, PolishOutcomeKind.FallbackServer) shouldBe
            "未润色：服务异常"
        voiceStatusLabel(VoicePhase.Success, PolishOutcomeKind.FallbackUnknown) shouldBe
            "未润色：服务异常"
    }

    test("status cat can never move inside the talk target or its four dp safety gap") {
        listOf(-40, 0, 1, 3, 4).forEach { requestedGapDp ->
            safeStatusVisualGapDp(requestedGapDp) shouldBe 4
        }
        safeStatusVisualGapDp(8) shouldBe 8
        (voiceToolbarSizing(250f).itemGapDp >= 4) shouldBe true
        (voiceToolbarSizing(380f).itemGapDp >= 4) shouldBe true
    }
})
