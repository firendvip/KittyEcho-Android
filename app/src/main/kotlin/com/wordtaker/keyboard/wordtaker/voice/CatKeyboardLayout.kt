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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wordtaker.keyboard.FlorisImeService
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.editorInstance
import com.wordtaker.keyboard.ime.ImeUiMode
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.nlpManager
import com.wordtaker.keyboard.ime.keyboard.FlorisImeSizing
import com.wordtaker.keyboard.ime.smartbar.CandidatesRow
import com.wordtaker.keyboard.ime.text.TextInputLayout
import com.wordtaker.keyboard.wordtaker.cat.CatSkin
import com.wordtaker.keyboard.wordtaker.cat.CatState
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.settings.SettingsState
import com.wordtaker.keyboard.wordtaker.speech.MicPermissionActivity
import com.wordtaker.keyboard.wordtaker.toolbar.WT_TOOLBAR_CIRCLE_DARK
import com.wordtaker.keyboard.wordtaker.toolbar.WT_TOOLBAR_ICON_TINT
import com.wordtaker.keyboard.wordtaker.toolbar.WT_TOOLBAR_ICON_TINT_DARK
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
    val nlpManager by context.nlpManager()

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

    // 候选填满整行：用户打拼音/形码时，顶条整行让位给候选行 (CandidatesRow)，小猫头像/睡猫/
    // "点击说话"/语音·折叠 全部隐藏；没有候选时恢复正常工具栏。录音时不显示候选行 (录音中本就
    // 没有候选)，让位给猫的放大动画。候选与工具栏渲染在同一个 56dp 槽位里 (居中)，所以无论哪种
    // 状态行高都恒为 CAT_STRIP_HEIGHT_DP，下方键盘绝不位移。
    val candidates by nlpManager.activeCandidatesFlow.collectAsState()
    val showCandidatesInStrip = candidates.isNotEmpty() && !active

    // VISUAL: the "点击说话" toolbar strip (flower + pill + collapse chevron) belongs ONLY to
    // the LETTER (CHARACTERS) keyboard. On SYMBOLS/SYMBOLS2/NUMERIC/NUMERIC_ADVANCED/PHONE/
    // PHONE2 the top row is the QuickSymbolStrip (rendered inside the keyboard body by
    // TextInputLayout) — so we drop this strip entirely there, and the panel shrinks by 56dp.
    val kbState by keyboardManager.activeState.collectAsState()
    val showToolbarStrip = kbState.keyboardMode == com.wordtaker.keyboard.ime.keyboard.KeyboardMode.CHARACTERS

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

    // item7 / BUG #11: keyboardUiHeight() is a @Composable that reads several
    // collectAsState-backed flows; it is re-evaluated each recomposition. Memoize the
    // derived cat-box sizes on the resolved height so recording-cat sizing stays STABLE
    // across recompositions and never contributes a per-frame height jitter (which reads
    // as shifting / black bands). smallBox is a constant, largeBox derives from height.
    val keyboardBodyHeight = FlorisImeSizing.keyboardUiHeight()
    // The cat draw box height interpolates between the small strip cat and the large
    // centred recording cat — same CatSkin instance, so 趴顶条⇄走中间放大 is one fluid
    // scale, never a swap to another sprite.
    val smallBox = CAT_STRIP_HEIGHT_DP.dp
    val largeBox = remember(keyboardBodyHeight) {
        (keyboardBodyHeight * RECORDING_CAT_FRACTION)
            .coerceIn(CAT_BOX_MIN_DP.dp, CAT_BOX_MAX_DP.dp)
    }

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
            // The strip slot — 56dp on the LETTER keyboard, whatever it contains, so nothing
            // below ever shifts. While typing (candidates present) the whole row becomes the
            // candidate row, filling full width; otherwise it shows the toolbar. On symbol/
            // numeric/phone keyboards the strip is omitted entirely (no 56dp gap) — the
            // QuickSymbolStrip inside the keyboard body becomes the top row instead.
            if (showToolbarStrip) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CAT_STRIP_HEIGHT_DP.dp)
                    .alpha(1f - activeAnim),
                contentAlignment = Alignment.Center,
            ) {
                if (showCandidatesInStrip) {
                    // 候选占满整行 (小猫隐藏)。CandidatesRow 自身 fillMaxSize + 水平滚动，
                    // 这里给它整行宽度并垂直居中于 56dp 槽内。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FlorisImeSizing.smartbarHeight)
                            .align(Alignment.Center),
                    ) {
                        CandidatesRow()
                    }
                } else {
                    // 微信风顶条 (待机)：田-grid 图标 · [🎤 点击说话] 药丸(填满中段) · ⌄ 收起。
                    // 顶条不再显示任何猫 —— 猫只在点"点击说话"进入录音界面后才出现 (Layer 3)。
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = STRIP_SIDE_PADDING_DP.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left: 小猫头像 (item4) — opens the in-IME settings panel in place
                        // (与原 ToolbarCatButton 相同的动作：切换 SETTINGS).
                        StripCatButton(
                            contentDescription = "设置",
                            dark = dark,
                            onClick = {
                                val current = keyboardManager.activeState.imeUiMode
                                keyboardManager.activeState.imeUiMode =
                                    if (current == ImeUiMode.SETTINGS) ImeUiMode.TEXT else ImeUiMode.SETTINGS
                            },
                        )
                        Spacer(modifier = Modifier.width(STRIP_BUTTON_GAP_DP.dp))
                        // 左对齐: 药丸 "点击说话" 只包裹自身内容宽度 (mic + 文字 + 小内边距)，
                        // 紧跟在设置(田-grid)图标之后。点它进入录音界面并开始录音 (与原语音图标同一
                        // 触发：切回 TEXT 界面 + VoiceTrigger.requestStart()).
                        TalkPill(
                            dark = dark,
                            modifier = Modifier
                                .wrapContentWidth()
                                .height(PILL_HEIGHT_DP.dp),
                            onClick = {
                                val current = keyboardManager.activeState.imeUiMode
                                if (current != ImeUiMode.TEXT && current != ImeUiMode.CAT_VOICE) {
                                    keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                                }
                                VoiceTrigger.requestStart()
                            },
                        )
                        // 药丸之后放弹性空白，把收起(折叠)图标顶到最右，右侧留空 (键盘底色)。
                        Spacer(modifier = Modifier.weight(1f))
                        // Right: collapse —— 收起键盘窗口。
                        StripCircleButton(
                            iconRes = R.drawable.ic_wt_collapse,
                            contentDescription = "收起键盘",
                            dark = dark,
                            onClick = { FlorisImeService.hideUi() },
                        )
                    }
                }
            }
            } // end if (showToolbarStrip)
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

        // ---- Layer 3: the recording cat, sized/positioned by the same activeAnim ----
        // The cat ONLY appears in the recording screen — never in the idle toolbar. It
        // grows from the small strip box up to the large centred recording box as the user
        // taps 点击说话 and recording begins. We animate the box height + vertical bias so it
        // slides+grows as one. Only rendered while active (录音/处理); the idle strip shows
        // NO cat.
        val boxHeight = lerpDp(smallBox, largeBox, activeAnim)
        // Vertical alignment bias: -1f = top (strip), 0f = centre. Slide down as it grows.
        val bias = -1f + activeAnim
        if (activeAnim > 0f) {
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
                    .align(biasAlignment(bias))
                    // item5: 猫精灵在盒内是底边锚定的 (CatSkin demo 世界，猫体视觉中心
                    // ≈ 盒高 69% 处)，bias=0 只让「盒子」居中、猫本体偏下。把整盒按猫体
                    // 视觉中心与盒中心的差值上移，使录音满态时猫在面板内真正垂直居中；
                    // 随 activeAnim 渐进，过渡依旧平滑。
                    .offset(y = -boxHeight * (CAT_VISUAL_CENTER_FRACTION * activeAnim)),
            )
        }
        }

        // ---- Idle recording trigger is now the 点击说话 pill in the strip (Layer 1);
        // no separate transparent tap overlay is needed. During recording, tapping
        // anywhere stops (Layer 3's full-screen clickable while active). ----

        // ---- Hints (suppressed in minimal mode) ----
        // 需求#10：忙碌(识别/润色)时也显示状态文案；录音时"正在倾听..."。
        if (!settings.minimal && active) {
            val hint = when (state.phase) {
                VoicePhase.Recording -> "正在倾听...点击结束"
                VoicePhase.Recognizing -> "正在识别..."
                VoicePhase.Polishing -> "正在AI润色中..."
                else -> null
            }
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.titleMedium,
                    // P2-303: IME 组合树没有配套的深色 MaterialTheme，colorScheme.onSurface
                    // 在深色底上仍是近黑色、几乎不可见。按面板底色显式取反色。
                    color = if (dark) OVERLAY_FG_DARK else OVERLAY_FG_LIGHT,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = HINT_EDGE_PADDING_DP.dp)
                        .alpha(activeAnim),
                )
            }
        }

        // ---- 流式实时字幕：边说边出字（阶段2）。识别中也保留，直到定稿/复位清空 ----
        if (active && state.partialText.isNotBlank()) {
            Text(
                text = state.partialText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dark) OVERLAY_FG_DARK else OVERLAY_FG_LIGHT, // P2-303 同上

                maxLines = PARTIAL_MAX_LINES,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = PARTIAL_H_PADDING_DP.dp,
                        end = PARTIAL_H_PADDING_DP.dp,
                        bottom = PARTIAL_BOTTOM_PADDING_DP.dp,
                    )
                    .alpha(activeAnim),
            )
        }

        // ---- 需求#9：微信风"取消"按钮，录音/识别/润色全程可点，随时中止 ----
        if (active) {
            CancelPill(
                dark = dark,
                onClick = vm::cancel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = CANCEL_EDGE_PADDING_DP.dp)
                    .alpha(activeAnim),
            )
        }
    }
}

/**
 * 需求#9 "取消" 药丸：灰底圆角，居中灰字，点击调用 [VoiceViewModel.cancel]。
 * 录音/识别/润色任意阶段都显示，让用户随时中止。
 */
@Composable
private fun CancelPill(
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (dark) CANCEL_BG_DARK else CANCEL_BG_LIGHT
    val fg = if (dark) PILL_FG_DARK else PILL_FG_LIGHT
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(PILL_CORNER_DP.dp))
            .background(bg)
            .clickable(
                interactionSource = noRipple,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = CANCEL_H_PADDING_DP.dp, vertical = CANCEL_V_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "取消",
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
        )
    }
}

/**
 * 微信风 "点击说话" 药丸：中段占满的圆角浅色底 + 麦克风字形 + "点击说话" 灰字，居中。
 * 点它 = 进入录音界面并开始录音 (onClick 由调用处提供，触发 VoiceTrigger.requestStart()).
 * 浅色模式白底、深色模式深灰底；文字/图标用工具栏统一灰 (深色模式转浅)。
 */
@Composable
private fun TalkPill(
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillBg = if (dark) PILL_BG_DARK else PILL_BG_LIGHT
    val fg = if (dark) PILL_FG_DARK else PILL_FG_LIGHT
    Row(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(PILL_CORNER_DP.dp))
            .background(pillBg)
            .clickable(onClick = onClick)
            .padding(horizontal = PILL_H_PADDING_DP.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_wt_voice),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(PILL_MIC_SIZE_DP.dp),
        )
        Spacer(modifier = Modifier.width(PILL_MIC_GAP_DP.dp))
        Text(
            text = "点击说话",
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
        )
    }
}

/**
 * 顶栏白圆钮（微信/iOS 风）：36dp 白色圆底 + 居中线性图标。用 Box 而非 Material3
 * IconButton，避免其最小触控目标(48dp)把圆底撑大 —— 尺寸即所见尺寸。
 */
@Composable
private fun StripCircleButton(
    iconRes: Int,
    contentDescription: String?,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(STRIP_CIRCLE_BUTTON_DP.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (dark) WT_TOOLBAR_CIRCLE_DARK else androidx.compose.ui.graphics.Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (dark) WT_TOOLBAR_ICON_TINT_DARK else WT_TOOLBAR_ICON_TINT,
            modifier = Modifier.size(STRIP_CIRCLE_ICON_DP.dp),
        )
    }
}

/**
 * 顶栏小猫头像钮 (item4)：白圆底 + 品牌猫头 (保留自身彩色)。白圈只比猫头图形大
 * ~5dp：猫头在 ic_brand_cat 的 100 视口里约占 66%，把矢量放大 STRIP_CAT_GLYPH_SCALE
 * 倍后猫头直径 ≈ 0.66×1.25×30 ≈ 25dp，白圈 30dp。放大溢出的装饰边角被 CircleShape
 * clip 裁掉，猫头本体不受影响。
 */
@Composable
private fun StripCatButton(
    contentDescription: String?,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(STRIP_CAT_BUTTON_DP.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (dark) WT_TOOLBAR_CIRCLE_DARK else androidx.compose.ui.graphics.Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_brand_cat),
            contentDescription = contentDescription,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier
                .size(STRIP_CAT_BUTTON_DP.dp)
                .graphicsLayer {
                    scaleX = STRIP_CAT_GLYPH_SCALE
                    scaleY = STRIP_CAT_GLYPH_SCALE
                },
        )
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
// item3: 设置/历史面板需要引用它来对齐总高 (顶条 + 键盘 = 工具栏 + 面板)。
internal const val CAT_STRIP_HEIGHT_DP = 59
// item8: 白圆钮"抱紧"图标 —— 圆只比图标大 6dp (26 vs 20)，与设置态工具栏圆钮一致。
private const val STRIP_CIRCLE_BUTTON_DP = 26
private const val STRIP_CIRCLE_ICON_DP = 20
// item4: 小猫头像钮 —— 30dp 白圆 + 矢量放大 1.25 倍，白圈 ≈ 猫头直径 + 5dp。
private const val STRIP_CAT_BUTTON_DP = 30
private const val STRIP_CAT_GLYPH_SCALE = 1.25f
// item2: 猫尺寸按手机端调到合适大小 —— 在这一行里与 28dp 图标协调，不过大不过小。
// 0.42→0.34 收窄睡猫宽度盒，使它在同一行里与图标比例和谐 (参考 CatSkinFx 头身比)。
private const val STRIP_CAT_WIDTH_FRACTION = 0.34f
// 顶条图标贴行两侧的水平内边距 + 图标/药丸之间的间距。
private const val STRIP_SIDE_PADDING_DP = 6
private const val STRIP_BUTTON_GAP_DP = 8
// 微信风 "点击说话" 药丸尺寸/配色 —— 圆角浅色底填满中段，麦克风+灰字居中。
private const val PILL_HEIGHT_DP = 36
private const val PILL_CORNER_DP = 18
private const val PILL_H_PADDING_DP = 12
private const val PILL_MIC_SIZE_DP = 16
private const val PILL_MIC_GAP_DP = 6
private val PILL_BG_LIGHT = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
private val PILL_BG_DARK = androidx.compose.ui.graphics.Color(0xFF2E3033)
private val PILL_FG_LIGHT = androidx.compose.ui.graphics.Color(0xFF6B6F73)
private val PILL_FG_DARK = androidx.compose.ui.graphics.Color(0xFFBFC3C7)
// Recording cat: large box centred over the whole panel (keyboard body height fraction).
// 录音界面小猫整体缩小 10%（0.62→0.558，钳制值同步 ×0.9），布局逻辑不变。
private const val RECORDING_CAT_FRACTION = 0.558f
private const val RECORDING_CAT_WIDTH_FRACTION = 0.558f
private const val CAT_BOX_MIN_DP = 117
private const val CAT_BOX_MAX_DP = 180
// item5: 猫体视觉中心 (demo-y≈50) 相对盒中心 (demo-y=36) 的偏差 / 盒高 (72) ≈ 0.19。
// 录音满态把猫盒上移这个比例的盒高，使猫本体在面板内垂直居中。
private const val CAT_VISUAL_CENTER_FRACTION = 0.19f
private const val HINT_EDGE_PADDING_DP = 8
// 流式实时字幕（阶段2）：底部居中，最多两行，位于"取消"药丸上方。
private const val PARTIAL_MAX_LINES = 2
private const val PARTIAL_H_PADDING_DP = 24
private const val PARTIAL_BOTTOM_PADDING_DP = 56
// 需求#9 "取消" 药丸尺寸/配色。
private const val CANCEL_EDGE_PADDING_DP = 12
private const val CANCEL_H_PADDING_DP = 20
private const val CANCEL_V_PADDING_DP = 8
private val CANCEL_BG_LIGHT = androidx.compose.ui.graphics.Color(0xFFE0E0E0)
private val CANCEL_BG_DARK = androidx.compose.ui.graphics.Color(0xFF3A3D40)
// item5: 出场更平滑 —— 进入(放大走出)比退出更慢，缓出曲线见 activeAnim。
private const val ENTER_TRANSITION_MS = 520
private const val EXIT_TRANSITION_MS = 360

// #7 面板背景：与微信键盘底色 (#ECECEC) 一致，冷调浅灰、不发暖。深色模式协调深灰。
// 键盘面板 (TextInputLayout) 也复用 WT_PANEL_GRAY，所以这两个常量定义在 voice 包内共享。
internal val WT_PANEL_GRAY = androidx.compose.ui.graphics.Color(0xFFECECEC)
internal val DARK_PANEL_BG = androidx.compose.ui.graphics.Color(0xFF202124)
// P2-303 录音界面状态文字/字幕的前景色：随面板深浅取高对比色。
private val OVERLAY_FG_LIGHT = androidx.compose.ui.graphics.Color(0xFF202124)
private val OVERLAY_FG_DARK = androidx.compose.ui.graphics.Color(0xFFE8EAED)

/** Launches a WordTaker activity from the IME. */
private fun launchWordTaker(context: Context, target: Class<*>) {
    runCatching {
        context.startActivity(
            Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
