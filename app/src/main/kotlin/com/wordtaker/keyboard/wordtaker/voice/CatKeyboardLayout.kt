package com.wordtaker.keyboard.wordtaker.voice

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width as dpRectWidth
import com.wordtaker.keyboard.ime.window.LocalWindowController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wordtaker.keyboard.FlorisImeService
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.editorInstance
import com.wordtaker.keyboard.ime.ImeUiMode
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.nlpManager
import com.wordtaker.keyboard.ime.nlp.SuggestionCandidate
import com.wordtaker.keyboard.ime.smartbar.CandidatesRow
import com.wordtaker.keyboard.ime.text.TextInputLayout
import com.wordtaker.keyboard.ime.theme.LocalFlorisImeThemeIsNight
import com.wordtaker.keyboard.wordtaker.cat.CatSkin
import com.wordtaker.keyboard.wordtaker.cat.CatState
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.settings.SettingsState
import com.wordtaker.keyboard.wordtaker.speech.MicPermissionActivity
import com.wordtaker.keyboard.wordtaker.speech.ParaformerCompactStatus
import com.wordtaker.keyboard.wordtaker.toolbar.ToolbarCatButton
import com.wordtaker.keyboard.wordtaker.toolbar.toolbarCatHorizontalInsetDp
import com.wordtaker.keyboard.wordtaker.ui.DoubaoImeSkin
import com.wordtaker.keyboard.wordtaker.ui.WordTakerSettingsActivity
import com.wordtaker.lib.compose.conditional

/**
 * WordTaker's single, fixed-height IME surface.
 *
 * Idle and background processing keep the Doubao-aligned toolbar/candidate strip above the
 * existing keyboard. Only the active recording cross-fades over the fixed bounds; background
 * stages use the existing [CatSkin] in the toolbar so typing can continue without a height jump.
 * Recognition, polishing, committing, and history persistence remain owned by [VoiceViewModel].
 */
@Composable
fun CatKeyboardLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val editorInstance by context.editorInstance()
    val keyboardManager by context.keyboardManager()
    val nlpManager by context.nlpManager()

    val vm: VoiceViewModel = viewModel(
        factory = VoiceViewModel.Factory(
            speechEngine = AppGraph.speechEngine,
            polisher = AppGraph.polisher,
            historyRepository = AppGraph.historyRepository,
            settingsRepository = AppGraph.settingsRepository,
            toneController = AppGraph.toneController,
            startHaptic = VoiceStartHaptic {
                FlorisImeService.inputFeedbackController()?.voiceRecordingStart()
            },
            privacySource = VoicePrivacySource {
                val info = editorInstance.activeInfo
                VoicePrivacyContext.fromEditor(
                    inputType = info.inputAttributes.raw,
                    noPersonalizedLearning = info.imeOptions.flagNoPersonalizedLearning,
                    incognito = keyboardManager.activeState.isIncognitoMode,
                    editorSessionToken = editorInstance.activeInputSessionToken,
                )
            },
        )
    )

    val state by vm.state.collectAsStateWithLifecycle()
    // batch3-C 多猫并行：后台还有 N 段在「转写定稿+润色+上屏」时给出视觉反馈。
    val pending by vm.pending.collectAsStateWithLifecycle()
    val settings by AppGraph.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = SettingsState())

    // Commit polished text into the focused input field.
    LaunchedEffect(vm) {
        vm.committed.collect { commit ->
            if (commit.belongsTo(editorInstance.activeInputSessionToken)) {
                editorInstance.commitText(commit.text)
            }
        }
    }

    // One-shot events: missing mic permission -> launch the transparent permission relay;
    // model not ready -> show the shared in-IME download confirmation; no PCM is captured.
    LaunchedEffect(vm) {
        vm.event.collect { event ->
            when (event) {
                VoiceEvent.PermissionRequired ->
                    launchWordTaker(context, MicPermissionActivity::class.java)
                VoiceEvent.ModelRequired ->
                    AppGraph.paraformerModelManager.onVoiceRequested()
            }
        }
    }

    // External start triggers (toolbar voice icon / long-press space / CAT_VOICE key):
    // begin recording on this unified panel without owning the ViewModel.
    LaunchedEffect(vm) {
        VoiceTrigger.requests.collect {
            vm.startFromExternal()
        }
    }

    // Surface transient toasts (e.g. "语音模型正在准备，请稍候", "未识别到语音").
    val toast by vm.toast.collectAsStateWithLifecycle()
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    val dark = LocalFlorisImeThemeIsNight.current
    val noRipple = remember { MutableInteractionSource() }

    // Only the current recording covers the key grid. Background transcription, online polish,
    // and success feedback stay compact in the fixed toolbar so typing can continue.
    val active = state.recording

    // Composing owns the complete strip. This deliberately observes editor content separately
    // from suggestions so the row switches before an asynchronous provider publishes candidates.
    val candidates by nlpManager.activeCandidatesFlow.collectAsState()
    val editorContent by editorInstance.activeContentFlow.collectAsState()
    val toolbarState = candidateToolbarRenderState(
        composingText = editorContent.composingText,
        hasVisibleCandidates = candidates.isNotEmpty(),
        isRecording = active,
    )
    val toolbarPresentation = toolbarState.presentation
    val pinyinPreedit = toolbarState.preeditText

    // VISUAL (batch3-B): the toolbar strip now renders on EVERY keyboard mode — the former
    // QuickSymbolStrip was removed, and the 符号/12·34 pages carry their controls inside the
    // key grid. Every page is therefore 顶栏 + 4 key rows and shares the exact same total
    // panel height as the letter keyboard (hard requirement: zero height change on switch).
    val showToolbarStrip = true

    // Cross-fade driver: 0 = keyboard, 1 = the Doubao-style recording surface. The structural
    // keyboard remains laid out underneath, so switching states never changes IME height.
    val activeAnim by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (active) ENTER_TRANSITION_MS else EXIT_TRANSITION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "cat_active",
    )

    // batch3-A: 顶栏高按实际屏宽比例 (0.152x屏宽)，真机/模拟器任何密度下占比一致。
    val stripHeight = catStripHeight()
    val requestVoiceStart = {
        val current = keyboardManager.activeState.imeUiMode
        if (current != ImeUiMode.TEXT && current != ImeUiMode.CAT_VOICE) {
            keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
        }
        VoiceTrigger.requestStart()
    }
    val backgroundPending = backgroundPendingCount(state.phase, pending)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        // Layer 1 anchors panel height with a fixed toolbar/candidate slot plus the key grid.
        // It remains laid out while recording and only changes alpha, preventing window jumps.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Alpha alone does not remove the hidden key grid from TalkBack's semantics tree.
                .conditional(active) { Modifier.semantics { hideFromAccessibility() } },
        ) {
            // The strip slot has one persistent action order on every non-recording keyboard mode.
            if (showToolbarStrip) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(stripHeight)
                    .alpha(1f - activeAnim),
                contentAlignment = Alignment.Center,
            ) {
                VoiceToolbarRow(
                    dark = dark,
                    candidates = candidates,
                    pinyinPreedit = pinyinPreedit,
                    presentation = toolbarPresentation,
                    voiceState = state,
                    pending = pending,
                    onSettings = {
                        val current = keyboardManager.activeState.imeUiMode
                        keyboardManager.activeState.imeUiMode =
                            if (current == ImeUiMode.SETTINGS) ImeUiMode.TEXT else ImeUiMode.SETTINGS
                    },
                    onStartRecording = requestVoiceStart,
                )
            }
            } // end if (showToolbarStrip)
            // Keyboard body (candidates + keys). Fades out while recording.
            Box(modifier = Modifier
                .fillMaxWidth()
                .alpha(1f - activeAnim)) {
                TextInputLayout()
            }
        }

        // ---- Layer 2: Doubao-style recording surface, fading over the anchored keyboard ----
        if (activeAnim > 0f) {
            CatRecordingPanel(
                state = state,
                dark = dark,
                showHint = !settings.minimal,
                pending = backgroundPending,
                onCancel = vm::cancel,
                onFinish = vm::onTap,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(activeAnim)
                    // Never attach pointer input during the exit fade. Even a disabled full-size
                    // clickable can intercept the first key press after recording ends.
                    .conditional(state.recording) {
                        Modifier.clickable(
                            interactionSource = noRipple,
                            indication = null,
                            onClickLabel = "结束录音",
                            onClick = vm::onTap,
                        )
                    },
            )
        }
    }
}

@Composable
private fun VoiceToolbarRow(
    dark: Boolean,
    candidates: List<SuggestionCandidate>,
    pinyinPreedit: String?,
    presentation: VoiceToolbarPresentation,
    voiceState: VoiceUiState,
    pending: Int,
    onSettings: () -> Unit,
    onStartRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = DoubaoImeSkin.palette(dark)
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        when (presentation) {
            VoiceToolbarPresentation.Candidates -> CandidateToolbarContent(
                candidates = candidates,
                pinyinPreedit = pinyinPreedit,
                dark = dark,
                modifier = Modifier.fillMaxSize(),
            )
            VoiceToolbarPresentation.Normal -> {
                val sizing = voiceToolbarSizing(maxWidth.value)
                val elements = voiceToolbarElements(
                    phase = voiceState.phase,
                    pending = pending,
                    presentation = presentation,
                )
                val backgroundCount = backgroundPendingCount(voiceState.phase, pending)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = sizing.sidePaddingDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VoiceToolbarPrimaryActions(
                        dark = dark,
                        sizing = sizing,
                        catContentDescription = "设置",
                        onCatClick = onSettings,
                        onTalkClick = onStartRecording,
                    )
                    if (VoiceToolbarElement.Status in elements) {
                        Spacer(
                            modifier = Modifier.width(
                                safeStatusVisualGapDp(sizing.itemGapDp).dp,
                            ),
                        )
                        VoiceStatusIndicator(
                            state = voiceState,
                            dark = dark,
                            sizing = sizing,
                        )
                    }
                    if (VoiceToolbarElement.Background in elements) {
                        Spacer(modifier = Modifier.width(sizing.itemGapDp.dp))
                        ProcessingCatsChip(
                            count = backgroundCount,
                            dark = dark,
                            sizing = sizing,
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        ParaformerCompactStatus(
                            manager = AppGraph.paraformerModelManager,
                            candidatesOwnToolbar = false,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(TOOLBAR_DIVIDER_HEIGHT_DP.dp)
                            .background(
                                Color(palette.secondaryForegroundArgb).copy(alpha = 0.38f),
                            ),
                    )
                    Spacer(modifier = Modifier.width(sizing.itemGapDp.dp))
                    StripCircleButton(
                        iconRes = R.drawable.ic_wt_collapse,
                        contentDescription = "收起键盘",
                        dark = dark,
                        onClick = { FlorisImeService.hideUi() },
                    )
                }
            }
        }
    }
}

/**
 * The complete left anchor group. Idle text and settings both call this exact composable so
 * their cat and talk-pill centres cannot drift through duplicated padding or sizing rules.
 */
@Composable
private fun VoiceToolbarPrimaryActions(
    dark: Boolean,
    sizing: VoiceToolbarSizing,
    catContentDescription: String,
    onCatClick: () -> Unit,
    onTalkClick: () -> Unit,
) {
    ToolbarCatButton(
        contentDescription = catContentDescription,
        onClick = onCatClick,
    )
    Spacer(modifier = Modifier.width(sizing.itemGapDp.dp))
    TalkPill(
        dark = dark,
        sizing = sizing,
        modifier = Modifier.wrapContentWidth(),
        onClick = onTalkClick,
    )
}

/**
 * Settings keeps the idle toolbar's left anchor group and only the collapse action on the
 * right. Recording semantics are supplied by the caller so it can close settings first.
 */
@Composable
internal fun ImeSettingsVoiceToolbarRow(
    onCatClick: () -> Unit,
    onStartRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalFlorisImeThemeIsNight.current
    val palette = DoubaoImeSkin.palette(dark)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sizing = voiceToolbarSizing(maxWidth.value)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = sizing.sidePaddingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceToolbarPrimaryActions(
                dark = dark,
                sizing = sizing,
                catContentDescription = "返回键盘",
                onCatClick = onCatClick,
                onTalkClick = onStartRecording,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(TOOLBAR_DIVIDER_HEIGHT_DP.dp)
                    .background(
                        Color(palette.secondaryForegroundArgb).copy(alpha = 0.38f),
                    ),
            )
            Spacer(modifier = Modifier.width(sizing.itemGapDp.dp))
            StripCircleButton(
                iconRes = R.drawable.ic_wt_collapse,
                contentDescription = "收起键盘",
                dark = dark,
                onClick = { FlorisImeService.hideUi() },
            )
        }
    }
}

/**
 * Composing is an exclusive strip state: only the ordered candidate snapshot and its preedit
 * are composed. Settings, speech, status, background and collapse actions are therefore absent
 * from both pointer hit-testing and the accessibility tree.
 */
@Composable
private fun CandidateToolbarContent(
    candidates: List<SuggestionCandidate>,
    pinyinPreedit: String?,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = DoubaoImeSkin.palette(dark)
    if (pinyinPreedit != null) {
        Column(modifier = modifier) {
            Text(
                text = pinyinPreedit,
                color = Color(palette.foregroundArgb),
                fontSize = PINYIN_PREEDIT_SIZE_SP.sp,
                lineHeight = PINYIN_PREEDIT_LINE_HEIGHT_SP.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DoubaoImeSkin.pinyinPreeditHeightDp.dp)
                    .padding(horizontal = PINYIN_PREEDIT_SIDE_DP.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                CandidatesRow(
                    hiddenSecondaryTextProviderIds =
                        DoubaoImeSkin.liftedSecondaryTextProviderIds,
                    candidatesOverride = candidates,
                )
            }
        }
    } else {
        CandidatesRow(
            modifier = modifier,
            showSecondaryText = true,
            candidatesOverride = candidates,
        )
    }
}

@Composable
private fun CatRecordingPanel(
    state: VoiceUiState,
    dark: Boolean,
    showHint: Boolean,
    pending: Int,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = DoubaoImeSkin.palette(dark)
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(palette.voiceGradientStartArgb),
                        Color(palette.voiceGradientEndArgb),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = VOICE_PANEL_SIDE_DP.dp,
                    end = VOICE_PANEL_SIDE_DP.dp,
                    top = VOICE_PANEL_TOP_DP.dp,
                    bottom = VOICE_PANEL_BOTTOM_DP.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showHint) {
                Text(
                    text = "正在倾听",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(palette.secondaryForegroundArgb),
                    modifier = Modifier.semantics { hideFromAccessibility() },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            VoiceCatFeedback(
                state = state,
                modifier = Modifier
                    .width(VOICE_CAT_WIDTH_DP.dp)
                    .height(VOICE_CAT_HEIGHT_DP.dp),
            )
            if (showHint) {
                Text(
                    text = "点击结束",
                    fontSize = VOICE_HINT_SIZE_SP.sp,
                    color = Color(palette.secondaryForegroundArgb),
                    modifier = Modifier
                        .padding(top = VOICE_HINT_TOP_DP.dp)
                        .semantics { hideFromAccessibility() },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(VOICE_ACTION_GAP_DP.dp),
            ) {
                VoiceActionButton(
                    label = "取消本次",
                    background = Color(palette.keyArgb).copy(alpha = 0.86f),
                    foreground = Color(palette.foregroundArgb),
                    interactive = state.recording,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                VoiceActionButton(
                    label = "结束",
                    background = Color(palette.keyArgb).copy(alpha = 0.96f),
                    foreground = Color(palette.foregroundArgb),
                    interactive = state.recording,
                    onClick = onFinish,
                    modifier = Modifier.weight(1f),
                )
                }
        }
        if (pending > 0) {
            ProcessingCatsChip(
                count = pending,
                dark = dark,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = CHIP_EDGE_PADDING_DP.dp, end = CHIP_EDGE_PADDING_DP.dp),
            )
        }
    }
}

@Composable
private fun VoiceCatFeedback(
    state: VoiceUiState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.semantics {
            contentDescription = voiceStatusDescription(state.phase, state.polishOutcome)
        },
        contentAlignment = Alignment.Center,
    ) {
        CatSkin(
            state = state.toCatState(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun VoiceActionButton(
    label: String,
    background: Color,
    foreground: Color,
    interactive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(VOICE_ACTION_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(VOICE_ACTION_CORNER_DP.dp))
            .background(background)
            .conditional(interactive) {
                Modifier.clickable(
                    interactionSource = noRipple,
                    indication = null,
                    onClick = onClick,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = foreground,
        )
    }
}

@Composable
private fun VoiceStatusIndicator(
    state: VoiceUiState,
    dark: Boolean,
    sizing: VoiceToolbarSizing,
    modifier: Modifier = Modifier,
) {
    val palette = DoubaoImeSkin.palette(dark)
    val visual = statusCatVisualSpec(state.phase, sizing)
    Row(
        modifier = modifier
            .height(
                maxOf(
                    DoubaoImeSkin.toolbarTouchTargetDp,
                    visual.heightDp.toFloat(),
                ).dp,
            )
            .semantics {
                contentDescription = voiceStatusDescription(state.phase, state.polishOutcome)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(visual.viewportWidthDp.dp)
                .height(visual.heightDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            CatSkin(
                state = state.toCatState(),
                modifier = Modifier
                    .width(visual.widthDp.dp)
                    .height(visual.heightDp.dp),
            )
        }
        if (sizing.showStatusText) {
            Text(
                text = voiceStatusLabel(state.phase, state.polishOutcome),
                style = MaterialTheme.typography.bodySmall,
                color = Color(palette.secondaryForegroundArgb),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(end = STATUS_TEXT_END_PADDING_DP.dp)
                    .semantics { hideFromAccessibility() },
            )
        }
    }
}

/** 豆包式语音胶囊；点击后仍通过 KittyEcho 的 [VoiceTrigger] 启动原有语音链路。 */
@Composable
private fun TalkPill(
    dark: Boolean,
    sizing: VoiceToolbarSizing,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = DoubaoImeSkin.palette(dark)
    val pillBg = Color(palette.keyArgb)
    val fg = Color(palette.secondaryForegroundArgb)
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(DoubaoImeSkin.toolbarTouchTargetDp.dp)
            .clickable(
                interactionSource = noRipple,
                indication = null,
                onClickLabel = "开始新录音",
                onClick = onClick,
            )
            .semantics {
                contentDescription = "开始新录音"
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(TalkPillLayoutSpec.heightDp.dp)
                .clip(RoundedCornerShape(TalkPillLayoutSpec.cornerRadiusDp.dp))
                .background(pillBg)
                .padding(horizontal = sizing.talkHorizontalPaddingDp.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_wt_voice),
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(sizing.talkMicSizeDp.dp),
            )
            Spacer(modifier = Modifier.width(sizing.talkMicGapDp.dp))
            Text(
                text = "点击说话",
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
        }
    }
}

internal fun VoiceUiState.toCatState(): CatState = CatState(
    recording = recording,
    level = level,
    busy = phase == VoicePhase.Recognizing || phase == VoicePhase.Polishing,
    polishing = phase == VoicePhase.Polishing,
    success = phase == VoicePhase.Success,
)

internal fun voiceStatusLabel(
    phase: VoicePhase,
    outcome: com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind? = null,
): String = when (phase) {
    VoicePhase.Recording -> "录音中"
    VoicePhase.Recognizing -> "转录中"
    VoicePhase.Polishing -> "润色中"
    VoicePhase.Success -> when (outcome) {
        com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind.ShortDirect -> "短句直出"
        com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind.OfflineDirect -> "离线直出"
        com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind.FallbackQuota ->
            "未润色：额度不足"
        com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind.FallbackAuthExpired ->
            "未润色：登录已失效"
        com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind.FallbackTimeout ->
            "未润色：服务超时"
        com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind.FallbackNetwork ->
            "未润色：网络异常"
        com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind.FallbackServer,
        com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind.FallbackUnknown,
        -> "未润色：服务异常"
        com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind.Polished,
        null,
        -> "已完成"
    }
    VoicePhase.Idle -> "点击说话"
}

internal fun voiceStatusDescription(
    phase: VoicePhase,
    outcome: com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind? = null,
): String = when (phase) {
    VoicePhase.Recording -> "正在录音，小猫正在聆听"
    VoicePhase.Recognizing -> "正在转录，小猫正在思考"
    VoicePhase.Polishing -> "正在在线润色，小猫正在思考"
    VoicePhase.Success -> when (outcome) {
        com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind.Polished,
        null,
        -> "语音输入完成，小猫送来星光"
        else -> voiceStatusLabel(phase, outcome)
    }
    VoicePhase.Idle -> "语音输入空闲"
}

internal fun backgroundPendingCount(phase: VoicePhase, pending: Int): Int = when (phase) {
    VoicePhase.Recognizing, VoicePhase.Polishing -> (pending - 1).coerceAtLeast(0)
    VoicePhase.Recording, VoicePhase.Success, VoicePhase.Idle -> pending
}

internal object TalkPillLayoutSpec {
    const val heightDp = 36
    const val cornerRadiusDp = 18
    const val horizontalPaddingDp = 12
    const val microphoneSizeDp = 16
    const val iconLabelGapDp = 6
}

internal enum class VoiceToolbarPresentation {
    Normal,
    Candidates,
}

internal data class CandidateToolbarRenderState(
    val presentation: VoiceToolbarPresentation,
    val preeditText: String?,
)

/**
 * Editor composing is the sole source of truth for the raw preedit line. Candidate providers may
 * publish empty, reordered, local, or cloud-merged snapshots asynchronously without changing it.
 */
internal fun candidateToolbarRenderState(
    composingText: String,
    hasVisibleCandidates: Boolean,
    isRecording: Boolean,
): CandidateToolbarRenderState {
    val presentation = voiceToolbarPresentation(
        hasActiveComposing = composingText.isNotEmpty(),
        hasVisibleCandidates = hasVisibleCandidates,
        isRecording = isRecording,
    )
    return CandidateToolbarRenderState(
        presentation = presentation,
        preeditText = composingText.takeIf {
            presentation == VoiceToolbarPresentation.Candidates && it.isNotEmpty()
        },
    )
}

internal fun voiceToolbarPresentation(
    hasActiveComposing: Boolean,
    hasVisibleCandidates: Boolean,
    isRecording: Boolean,
): VoiceToolbarPresentation =
    if (!isRecording && (hasActiveComposing || hasVisibleCandidates)) {
        VoiceToolbarPresentation.Candidates
    } else {
        VoiceToolbarPresentation.Normal
    }

internal enum class VoiceToolbarElement {
    Settings,
    Talk,
    Status,
    Background,
    Flexible,
    Collapse,
    Candidates,
}

internal fun voiceToolbarElements(
    phase: VoicePhase,
    pending: Int,
    presentation: VoiceToolbarPresentation = VoiceToolbarPresentation.Normal,
): List<VoiceToolbarElement> =
    if (presentation == VoiceToolbarPresentation.Candidates) {
        listOf(VoiceToolbarElement.Candidates)
    } else {
        buildList {
            add(VoiceToolbarElement.Settings)
            add(VoiceToolbarElement.Talk)
            if (phase == VoicePhase.Recognizing ||
                phase == VoicePhase.Polishing ||
                phase == VoicePhase.Success
            ) {
                add(VoiceToolbarElement.Status)
            }
            if (backgroundPendingCount(phase, pending) > 0) {
                add(VoiceToolbarElement.Background)
            }
            add(VoiceToolbarElement.Flexible)
            add(VoiceToolbarElement.Collapse)
        }
    }

internal const val MIN_STATUS_VISUAL_GAP_DP = 4

internal fun safeStatusVisualGapDp(requestedGapDp: Int): Int =
    requestedGapDp.coerceAtLeast(MIN_STATUS_VISUAL_GAP_DP)

internal object StatusCatVisualSpec {
    const val wideWidthDp = 35
    const val wideHeightDp = 44
    const val compactWidthDp = 18
    const val compactHeightDp = 32
    const val polishingWideWidthDp = 46
    const val polishingWideHeightDp = 57
    const val polishingCompactWidthDp = 23
    const val polishingCompactHeightDp = 40
    const val polishingMotionSpacePerSideDp = 8
    const val maxHeightDp = polishingWideHeightDp
}

internal data class StatusCatVisual(
    val widthDp: Int,
    val heightDp: Int,
    val motionSpacePerSideDp: Int,
) {
    val viewportWidthDp: Int
        get() = widthDp + motionSpacePerSideDp * 2
}

internal data class VoiceToolbarSizing(
    val showStatusText: Boolean,
    val sidePaddingDp: Int,
    val itemGapDp: Int,
    val talkHorizontalPaddingDp: Int,
    val talkMicSizeDp: Int,
    val talkMicGapDp: Int,
    val statusCatWidthDp: Int,
    val statusCatHeightDp: Int,
    val chipHorizontalPaddingDp: Int,
    val chipCatSizeDp: Int,
    val chipGapDp: Int,
)

private val WideVoiceToolbarSizing = VoiceToolbarSizing(
    showStatusText = true,
    sidePaddingDp = toolbarCatHorizontalInsetDp(STATUS_TEXT_MIN_WIDTH_DP.toFloat()),
    itemGapDp = 8,
    talkHorizontalPaddingDp = TalkPillLayoutSpec.horizontalPaddingDp,
    talkMicSizeDp = TalkPillLayoutSpec.microphoneSizeDp,
    talkMicGapDp = TalkPillLayoutSpec.iconLabelGapDp,
    statusCatWidthDp = StatusCatVisualSpec.wideWidthDp,
    statusCatHeightDp = StatusCatVisualSpec.wideHeightDp,
    chipHorizontalPaddingDp = 10,
    chipCatSizeDp = 18,
    chipGapDp = 4,
)

private val CompactVoiceToolbarSizing = VoiceToolbarSizing(
    showStatusText = false,
    sidePaddingDp = toolbarCatHorizontalInsetDp(0f),
    itemGapDp = MIN_STATUS_VISUAL_GAP_DP,
    talkHorizontalPaddingDp = TalkPillLayoutSpec.horizontalPaddingDp,
    talkMicSizeDp = TalkPillLayoutSpec.microphoneSizeDp,
    talkMicGapDp = TalkPillLayoutSpec.iconLabelGapDp,
    statusCatWidthDp = StatusCatVisualSpec.compactWidthDp,
    statusCatHeightDp = StatusCatVisualSpec.compactHeightDp,
    chipHorizontalPaddingDp = 1,
    chipCatSizeDp = 12,
    chipGapDp = 1,
)

/**
 * On narrow IME windows only decoration is compacted: both toolbar actions retain their
 * 44/48dp hit targets and the complete "点击说话" label remains visible.
 */
internal fun voiceToolbarSizing(widthDp: Float): VoiceToolbarSizing =
    if (widthDp >= STATUS_TEXT_MIN_WIDTH_DP) {
        WideVoiceToolbarSizing
    } else {
        CompactVoiceToolbarSizing
    }

internal fun statusCatVisualSpec(
    phase: VoicePhase,
    sizing: VoiceToolbarSizing,
): StatusCatVisual =
    if (phase == VoicePhase.Polishing) {
        if (sizing.showStatusText) {
            StatusCatVisual(
                widthDp = StatusCatVisualSpec.polishingWideWidthDp,
                heightDp = StatusCatVisualSpec.polishingWideHeightDp,
                motionSpacePerSideDp = StatusCatVisualSpec.polishingMotionSpacePerSideDp,
            )
        } else {
            StatusCatVisual(
                widthDp = StatusCatVisualSpec.polishingCompactWidthDp,
                heightDp = StatusCatVisualSpec.polishingCompactHeightDp,
                motionSpacePerSideDp = StatusCatVisualSpec.polishingMotionSpacePerSideDp,
            )
        }
    } else {
        StatusCatVisual(
            widthDp = sizing.statusCatWidthDp,
            heightDp = sizing.statusCatHeightDp,
            motionSpacePerSideDp = 0,
        )
    }

/** 顶栏透明圆形动作区；用 [Box] 保持图标的精确视觉尺寸。 */
@Composable
private fun StripCircleButton(
    iconRes: Int,
    contentDescription: String?,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = DoubaoImeSkin.palette(dark)
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(DoubaoImeSkin.toolbarTouchTargetDp.dp)
            .clip(RoundedCornerShape(DoubaoImeSkin.toolbarTouchTargetDp.dp))
            .background(Color.Transparent)
            .clickable(
                interactionSource = noRipple,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color(palette.foregroundArgb),
            modifier = Modifier.size(STRIP_CIRCLE_ICON_DP.dp),
        )
    }
}

/**
 * batch3-C 多猫并行角标：圆角药丸底 + 无底猫头 + 「×N」。表示后台有 N 段录音在
 * 「转写定稿→润色→上屏」流水线里排队/干活（一段=一只小猫）。待机顶栏与录音界面共用。
 */
@Composable
private fun ProcessingCatsChip(
    count: Int,
    dark: Boolean,
    modifier: Modifier = Modifier,
    sizing: VoiceToolbarSizing = WideVoiceToolbarSizing,
) {
    val palette = DoubaoImeSkin.palette(dark)
    val bg = Color(palette.keyArgb)
    val fg = Color(palette.secondaryForegroundArgb)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(TalkPillLayoutSpec.cornerRadiusDp.dp))
            .background(bg)
            .semantics {
                contentDescription = "${count}段后台处理中"
            }
            .padding(
                horizontal = sizing.chipHorizontalPaddingDp.dp,
                vertical = CHIP_V_PADDING_DP.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_brand_cat_bare),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.size(sizing.chipCatSizeDp.dp),
        )
        Spacer(modifier = Modifier.width(sizing.chipGapDp.dp))
        Text(
            text = "×$count",
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            modifier = Modifier.semantics { hideFromAccessibility() },
        )
    }
}

// Sizing constants -----------------------------------------------------------
// 顶栏高按参考界面量化为实际屏宽的 0.152；设置/历史面板复用该值以保持总高一致。
internal const val CAT_STRIP_HEIGHT_RATIO = DoubaoImeSkin.toolbarHeightRatio
private val CAT_STRIP_HEIGHT_FALLBACK = 59.dp

/** 顶栏高：0.152x实际屏宽，并钳制到可容纳预编辑与候选字形的最小高度。 */
@Composable
internal fun catStripHeight(): Dp {
    val windowController = LocalWindowController.current
    val windowSpec by windowController.activeWindowSpec.collectAsState()
    val width = windowSpec.constraints.rootBounds.dpRectWidth
    return if (width > 0.dp) {
        (width * CAT_STRIP_HEIGHT_RATIO).coerceAtLeast(DoubaoImeSkin.minimumToolbarHeightDp.dp)
    } else {
        CAT_STRIP_HEIGHT_FALLBACK
    }
}

private const val STRIP_CIRCLE_ICON_DP = 20
// Keep KittyEcho's own mark, but place it in the same compact white circular control used by
// Doubao's command button rather than the former oversized bare avatar.
// 顶条图标贴行两侧的水平内边距 + 图标/药丸之间的间距。
private const val TOOLBAR_DIVIDER_HEIGHT_DP = 24

private const val PINYIN_PREEDIT_SIDE_DP = 5
private const val PINYIN_PREEDIT_SIZE_SP = 16
private const val PINYIN_PREEDIT_LINE_HEIGHT_SP = 18
private const val CANDIDATE_ACTION_GAP_DP = 6

private const val VOICE_PANEL_SIDE_DP = 18
private const val VOICE_PANEL_TOP_DP = 14
private const val VOICE_PANEL_BOTTOM_DP = 16
private const val VOICE_CAT_WIDTH_DP = 190
private const val VOICE_CAT_HEIGHT_DP = 136
private const val VOICE_HINT_SIZE_SP = 13
private const val VOICE_HINT_TOP_DP = 8
private const val VOICE_ACTION_HEIGHT_DP = 48
private const val VOICE_ACTION_CORNER_DP = 24
private const val VOICE_ACTION_GAP_DP = 14
private const val STATUS_TEXT_END_PADDING_DP = 4
private const val STATUS_TEXT_MIN_WIDTH_DP = 380
// batch3-C 多猫并行角标（忙碌小猫 ×N）尺寸。
private const val CHIP_V_PADDING_DP = 4
private const val CHIP_EDGE_PADDING_DP = 12
private const val ENTER_TRANSITION_MS = 360
private const val EXIT_TRANSITION_MS = 220

/** Launches a WordTaker activity from the IME. */
private fun launchWordTaker(context: Context, target: Class<*>) {
    runCatching {
        context.startActivity(
            Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
