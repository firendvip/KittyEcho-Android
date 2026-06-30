package com.wordtaker.keyboard.wordtaker.voice

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wordtaker.keyboard.FlorisImeService
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.editorInstance
import com.wordtaker.keyboard.ime.ImeUiMode
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.ime.keyboard.FlorisImeSizing
import com.wordtaker.keyboard.ime.text.TextInputLayout
import com.wordtaker.keyboard.wordtaker.cat.CatSkin
import com.wordtaker.keyboard.wordtaker.cat.CatState
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.settings.SettingsState
import com.wordtaker.keyboard.wordtaker.speech.MicPermissionActivity
import com.wordtaker.keyboard.wordtaker.toolbar.ToolbarIconButton
import com.wordtaker.keyboard.wordtaker.ui.WordTakerSettingsActivity
import com.wordtaker.lib.compose.conditional

/**
 * Unified single surface for WordTaker: the keyboard界面 and the猫语音界面 are merged
 * into ONE panel with two in-place visual states (no second "屏"):
 *
 *  - 待机 (Idle): a thin top strip shows a small 趴着睡觉 cat (Zzz); below it the full
 *    keyboard (toolbar + candidates + keys) renders normally.
 *  - 录音中 (Recording): the keyboard keys fade out to a flat solid background and the
 *    SAME cat enlarges, walking back and forth in the centre of the whole panel.
 *  - 处理完成: the cat shrinks back into the top strip and the keyboard fades back in.
 *
 * Total panel height is CONSTANT across all states because every state is laid out
 * inside one [Box] whose height is fixed by [Column] (strip + keyboard). The recording
 * overlay uses `matchParentSize`/`fillMaxSize`, so it can never change the IME window
 * height — the transition is a pure cross-fade + cat scale, no vertical jump.
 *
 * Recording is started/stopped by tapping the cat (or via the toolbar voice icon /
 * long-press space, which drive [VoiceViewModel] the same way through onTap). The whole
 * recording→识别→润色→commit→写历史 chain is the existing [VoiceViewModel] flow.
 */
@Composable
fun CatKeyboardLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val editorInstance by context.editorInstance()
    val keyboardManager by context.keyboardManager()

    val vm: VoiceViewModel = viewModel(
        factory = VoiceViewModel.Factory(
            speechEngine = AppGraph.speechEngine,
            polisher = AppGraph.polisher,
            historyRepository = AppGraph.historyRepository,
            settingsRepository = AppGraph.settingsRepository,
            toneController = AppGraph.toneController,
        )
    )

    val state by vm.state.collectAsStateWithLifecycle()
    val settings by AppGraph.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = SettingsState())

    // Commit polished text into the focused input field.
    LaunchedEffect(vm) {
        vm.committed.collect { text ->
            editorInstance.commitText(text)
        }
    }

    // One-shot events: missing mic permission -> launch the transparent permission relay;
    // model not ready -> open settings (model is bundled and installs silently).
    LaunchedEffect(vm) {
        vm.event.collect { event ->
            when (event) {
                VoiceEvent.PermissionRequired ->
                    launchWordTaker(context, MicPermissionActivity::class.java)
                VoiceEvent.ModelRequired ->
                    launchWordTaker(context, WordTakerSettingsActivity::class.java)
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

    val dark = isSystemInDarkTheme()
    val noRipple = remember { MutableInteractionSource() }

    // "active" = the cat has left the sleeping strip (recording OR processing). The
    // keyboard fades out and the flat recording background fades in only while active.
    val active = state.recording || state.busy

    // Cross-fade + cat-scale driver: 0 = 待机 (keyboard shown), 1 = 录音/处理 (solid + big cat).
    // item5: 点猫即开始录音(触发不变)，但动画上猫由小变大、缓缓走出 —— 不再"直接跳出"。
    // 用 FastOutSlowIn 缓出曲线 + 加长时长，进出都平滑渐进；录音态(进入)比退出更慢，让放大过程
    // 更自然不突兀。
    val activeAnim by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (active) ENTER_TRANSITION_MS else EXIT_TRANSITION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "cat_active",
    )

    val keyboardBodyHeight = FlorisImeSizing.keyboardUiHeight()
    // The cat draw box height interpolates between the small strip cat and the large
    // centred recording cat — same CatSkin instance, so 趴顶条⇄走中间放大 is one fluid
    // scale, never a swap to another sprite.
    val smallBox = CAT_STRIP_HEIGHT_DP.dp
    val largeBox = (keyboardBodyHeight * RECORDING_CAT_FRACTION)
        .coerceIn(CAT_BOX_MIN_DP.dp, CAT_BOX_MAX_DP.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        // ---- Layer 1: the structural Column that FIXES the panel height ----
        // [top strip = 工具栏图标 + 睡猫同一行] + [keyboard body]. Always laid out (height
        // anchor); the keyboard body fades to alpha 0 during recording so the flat solid底
        // shows through. The strip row holds the toolbar icons on the left/right; the猫 is
        // drawn centred over this same row by Layer 3 — so设置/语音/折叠 与睡猫处于同一行
        // (item2)，语音图标与猫垂直对齐、间距一致。工具栏图标在录音时随键盘一起淡出。
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CAT_STRIP_HEIGHT_DP.dp)
                    .alpha(1f - activeAnim)
                    .padding(horizontal = STRIP_SIDE_PADDING_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: settings (gear) — opens the in-IME settings panel in place.
                ToolbarIconButton(
                    iconRes = R.drawable.ic_wt_settings,
                    contentDescription = "设置",
                    onClick = {
                        val current = keyboardManager.activeState.imeUiMode
                        keyboardManager.activeState.imeUiMode =
                            if (current == ImeUiMode.SETTINGS) ImeUiMode.TEXT else ImeUiMode.SETTINGS
                    },
                )
                // Centre is left empty for the sleeping cat (drawn by Layer 3).
                Spacer(modifier = Modifier.weight(1f))
                // Right: voice (mic) + collapse —— 与睡猫垂直对齐、同一行。
                ToolbarIconButton(
                    iconRes = R.drawable.ic_wt_voice,
                    contentDescription = "语音输入",
                    onClick = {
                        val current = keyboardManager.activeState.imeUiMode
                        if (current != ImeUiMode.TEXT && current != ImeUiMode.CAT_VOICE) {
                            keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                        }
                        VoiceTrigger.requestStart()
                    },
                )
                Spacer(modifier = Modifier.width(STRIP_BUTTON_GAP_DP.dp))
                ToolbarIconButton(
                    iconRes = R.drawable.ic_wt_collapse,
                    contentDescription = "收起键盘",
                    onClick = { FlorisImeService.hideUi() },
                )
            }
            // Keyboard body (candidates + keys). Fades out while recording.
            Box(modifier = Modifier
                .fillMaxWidth()
                .alpha(1f - activeAnim)) {
                TextInputLayout()
            }
        }

        // ---- Layer 2: flat recording background, fading in over the keyboard ----
        if (activeAnim > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .alpha(activeAnim)
                    .background(if (dark) DARK_PANEL_BG else WT_PANEL_GRAY),
            )
        }

        // ---- Layer 3: the single cat, sized/positioned by the same activeAnim ----
        // Idle: small box pinned to the top strip. Recording: large box centred over the
        // whole panel. We animate the box height + vertical bias so it slides+grows as one.
        val boxHeight = lerpDp(smallBox, largeBox, activeAnim)
        // Vertical alignment bias: -1f = top (strip), 0f = centre. Slide down as it grows.
        val bias = -1f + activeAnim
        Box(
            modifier = Modifier
                .matchParentSize()
                // CRITICAL (item7 主流程修复)：待机态绝不能在整面板挂 clickable。
                // Compose 里 `clickable(enabled = false)` 仍会占据 pointerInput 节点、
                // 仍然消费 down/up 触摸事件（只是不回调 onClick），会把键盘按键的点击
                // 全部吞掉 → 打不出字。所以只有 active(录音/处理) 时才组合这层全屏点击
                // (用于点任意处停止录音)；待机时点猫开录由 Layer 4 顶条专属点击区负责，
                // 键盘按键的触摸因此可以正常落到下面的 TextInputLayout 上。
                .conditional(active) {
                    Modifier.clickable(
                        interactionSource = noRipple,
                        indication = null,
                        onClick = vm::onTap,
                    )
                },
        ) {
            CatSkin(
                state = CatState(
                    recording = state.recording,
                    level = state.level,
                    busy = state.busy,
                    error = false,
                ),
                modifier = Modifier
                    .fillMaxWidth(lerpFloat(STRIP_CAT_WIDTH_FRACTION, RECORDING_CAT_WIDTH_FRACTION, activeAnim))
                    .height(boxHeight)
                    .align(biasAlignment(bias)),
            )
        }

        // ---- Layer 4: idle tap target — only the CENTRE of the strip (the猫) starts
        // recording. Confined to the strip height AND to the centre fraction so the
        // side toolbar icons (设置/语音/折叠) keep their own clicks, and keyboard keys
        // below keep their touch handling untouched. (item2: 同一行后，点猫开录区只占中段。)
        if (!active) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(IDLE_TAP_WIDTH_FRACTION)
                    .height(CAT_STRIP_HEIGHT_DP.dp)
                    .clickable(
                        interactionSource = noRipple,
                        indication = null,
                        onClick = vm::onTap,
                    ),
            )
        }

        // ---- Hints (suppressed in minimal mode) ----
        if (!settings.minimal && active) {
            if (state.recording) {
                Text(
                    text = "正在倾听...点击结束",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = HINT_EDGE_PADDING_DP.dp)
                        .alpha(activeAnim),
                )
            }
        }
    }
}

// --- small animation helpers (avoid pulling in extra imports) ---
private fun lerpFloat(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerpDp(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp, t: Float) =
    a + (b - a) * t
private fun biasAlignment(bias: Float) = androidx.compose.ui.BiasAlignment(0f, bias.coerceIn(-1f, 1f))

// Sizing constants -----------------------------------------------------------
// Top strip (待机 sleeping cat + 工具栏图标同一行) height — small, so the keyboard keeps
// almost all the panel. The sleeping cat is bottom-anchored inside this strip; the icons
// are vertically centred in the same row.
private const val CAT_STRIP_HEIGHT_DP = 56
// item2: 猫尺寸按手机端调到合适大小 —— 在这一行里与 28dp 图标协调，不过大不过小。
// 0.42→0.34 收窄睡猫宽度盒，使它在同一行里与图标比例和谐 (参考 CatSkinFx 头身比)。
private const val STRIP_CAT_WIDTH_FRACTION = 0.34f
// item2: 工具栏图标贴行两侧的水平内边距 + 语音/折叠之间的间距。
private const val STRIP_SIDE_PADDING_DP = 6
private const val STRIP_BUTTON_GAP_DP = 8
// item2: 待机时"点猫开录"的中段宽度占比 —— 只覆盖中间睡猫，两侧图标保持可点。
private const val IDLE_TAP_WIDTH_FRACTION = 0.5f
// Recording cat: large box centred over the whole panel (keyboard body height fraction).
private const val RECORDING_CAT_FRACTION = 0.62f
private const val RECORDING_CAT_WIDTH_FRACTION = 0.62f
private const val CAT_BOX_MIN_DP = 130
private const val CAT_BOX_MAX_DP = 200
private const val HINT_EDGE_PADDING_DP = 8
// item5: 出场更平滑 —— 进入(放大走出)比退出更慢，缓出曲线见 activeAnim。
private const val ENTER_TRANSITION_MS = 520
private const val EXIT_TRANSITION_MS = 360

// #7 面板背景：与微信键盘底色 (#ECECEC) 一致，冷调浅灰、不发暖。深色模式协调深灰。
// 键盘面板 (TextInputLayout) 也复用 WT_PANEL_GRAY，所以这两个常量定义在 voice 包内共享。
internal val WT_PANEL_GRAY = androidx.compose.ui.graphics.Color(0xFFECECEC)
internal val DARK_PANEL_BG = androidx.compose.ui.graphics.Color(0xFF202124)

/** Launches a WordTaker activity from the IME. */
private fun launchWordTaker(context: Context, target: Class<*>) {
    runCatching {
        context.startActivity(
            Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
