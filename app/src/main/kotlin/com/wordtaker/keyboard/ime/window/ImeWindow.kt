/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wordtaker.keyboard.ime.window

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width as dpRectWidth
import androidx.compose.ui.unit.roundToIntRect
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.app.devtools.DevtoolsOverlay
import com.wordtaker.keyboard.ime.ImeUiMode
import com.wordtaker.keyboard.ime.clipboard.ClipboardInputLayout
import com.wordtaker.keyboard.wordtaker.voice.CatKeyboardLayout
import com.wordtaker.keyboard.wordtaker.voice.ImeSettingsVoiceToolbarRow
import com.wordtaker.keyboard.wordtaker.voice.VoiceTrigger
import com.wordtaker.keyboard.wordtaker.history.ImeHistoryLayout
import com.wordtaker.keyboard.wordtaker.settings.ImeSettingsLayout
import com.wordtaker.keyboard.wordtaker.toolbar.ImeToolbar
import com.wordtaker.keyboard.ime.input.LocalInputFeedbackController
import com.wordtaker.keyboard.ime.keyboard.ProvideKeyboardRowBaseHeight
import com.wordtaker.keyboard.ime.media.MediaInputLayout
import com.wordtaker.keyboard.ime.sheet.BottomSheetWindow
import com.wordtaker.keyboard.ime.text.TextInputLayout
import com.wordtaker.keyboard.ime.theme.FlorisImeUi
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.wordtaker.voice.catStripHeight
import kotlinx.coroutines.delay
import com.wordtaker.lib.compose.ProvideActualLayoutDirection
import com.wordtaker.lib.compose.conditional
import com.wordtaker.lib.compose.drawBorder
import com.wordtaker.lib.compose.drawableRes
import com.wordtaker.lib.compose.fold
import com.wordtaker.lib.compose.ifIsInstance
import com.wordtaker.lib.snygg.ui.SnyggBox
import com.wordtaker.lib.snygg.ui.SnyggIcon
import com.wordtaker.lib.snygg.ui.SnyggIconButton
import com.wordtaker.lib.snygg.ui.rememberSnyggThemeQuery

/**
 * The main entry point of the IME user interface. This includes the keyboard itself, devtools overlays,
 * bottom sheets, and system bars management.
 *
 * Typically, the root window corresponds to the screen bounds, however this is not guaranteed. For
 * consistency reasons, all sub sizes and positions should be derived from the root window.
 *
 * The size and position of this composable are used to calculate [ImeWindowController.activeRootInsets].
 *
 * @see ImeWindow
 * @see BottomSheetWindow
 * @see DevtoolsOverlay
 */
// item3: 键盘↔设置↔历史等模式切换的淡入淡出时长 —— 高度已恒定，只做 120ms fade。
private const val MODE_SWITCH_FADE_MS = 120

@Composable
fun ImeRootWindow() {
    val density = LocalDensity.current
    val windowController = LocalWindowController.current

    val editorState by windowController.editor.state.collectAsState()
    val isEditorEnabled by remember {
        derivedStateOf {
            editorState.isEnabled
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .conditional(isEditorEnabled) {
                Modifier.pointerInput(isEditorEnabled) {
                    detectTapGestures {
                        windowController.editor.disableIfNoGestureInProgress()
                    }
                }
            }
            .onGloballyPositioned { coords ->
                val boundsPx = coords.boundsInRoot().roundToIntRect()
                val newInsets = with(density) { ImeInsets.Root.of(boundsPx) }
                windowController.updateRootInsets(newInsets)
            },
    ) {
        DevtoolsOverlay()
        ImeWindow()
        BottomSheetWindow()
        ImeSystemUi()
    }
}

/**
 * The IME window contains all components that users would describe as the keyboard user interface. Its placement
 * and size is dependent on the window config, with the main factor being the window mode.
 *
 * Most of the time all draw operations are contained within the bounds of this composable. Exceptions to
 * this may be resize handles, popups, tooltips, and any other composable positioned absolutely.
 *
 * The size and position of this composable are used to calculate [ImeWindowController.activeWindowInsets].
 *
 * @see ImeWindowMode
 */
@Composable
fun BoxScope.ImeWindow() {
    val density = LocalDensity.current
    val windowController = LocalWindowController.current

    val windowSpec by windowController.activeWindowSpec.collectAsState()
    val windowConfig by windowController.activeWindowConfig.collectAsState()

    val attributes = remember(windowConfig.mode) {
        mapOf(
            FlorisImeUi.Attr.WindowMode to windowConfig.mode.toString(),
        )
    }

    // BUG #11: give the window an explicit MINIMUM height so the very first frame can't
    // collapse to zero/partial height (which shows up as black bands top & bottom while
    // the content measures). We keep wrapContentHeight() for the natural size, but floor it
    // at the spec's baseline keyboard height so there is always a non-zero, sane first frame.
    val minWindowHeight = windowSpec.props.keyboardHeight

    FloatingDockToFixedIndicator()

    SnyggBox(
        elementName = FlorisImeUi.Window.elementName,
        attributes = attributes,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .ifIsInstance<ImeWindowProps.Fixed>(windowSpec.props) {
                Modifier
                    .fillMaxWidth()
            }
            .ifIsInstance<ImeWindowProps.Floating>(windowSpec.props) { props ->
                Modifier
                    .offset(props.offsetLeft, -props.offsetBottom)
                    .width(props.keyboardWidth)
            }
            .heightIn(min = minWindowHeight)
            .wrapContentHeight()
            .onGloballyPositioned { coords ->
                val boundsPx = coords.boundsInRoot().roundToIntRect()
                val newInsets = with(density) { ImeInsets.Window.of(boundsPx) }
                windowController.updateWindowInsets(newInsets)
            },
        supportsBackgroundImage = true,
        allowClip = false,
    ) {
        OneHandedPanel()
        ProvideKeyboardRowBaseHeight {
            ImeInnerWindow()
        }
        ImeWindowResizeHandlesFloating()
    }
}

@Composable
private fun ImeInnerWindow() {
    val context = LocalContext.current
    val windowController = LocalWindowController.current

    val keyboardManager by context.keyboardManager()

    val state by keyboardManager.activeState.collectAsState()
    val windowSpec by windowController.activeWindowSpec.collectAsState()
    val windowConfig by windowController.activeWindowConfig.collectAsState()

    // This gap belongs to WindowInner, outside every mode's content. Growing the bottom-anchored
    // measured window raises all content equally while preserving its sizes and touch geometry.
    val fixedBottomGap = ImeErgonomicOffsetPolicy.upwardOffset(
        rootWidth = windowSpec.constraints.rootBounds.dpRectWidth.value,
        formFactor = windowSpec.constraints.formFactor.typeGuess,
        windowMode = windowConfig.mode,
        fixedMode = windowConfig.fixedMode,
    ).dp

    ProvideActualLayoutDirection {
        val layoutDirection = LocalLayoutDirection.current
        LaunchedEffect(layoutDirection) {
            keyboardManager.activeState.layoutDirection = layoutDirection
        }
    }

    SnyggBox(
        elementName = FlorisImeUi.WindowInner.elementName,
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .ifIsInstance<ImeWindowProps.Fixed>(windowSpec.props) { props ->
                Modifier
                    .safeDrawingPadding()
                    .systemGestureExclusion()
                    .padding(
                        start = props.paddingLeft.coerceAtLeast(0.dp),
                        end = props.paddingRight.coerceAtLeast(0.dp),
                        // Preserve user padding and add the ergonomic gap outside all mode content.
                        bottom = props.paddingBottom.coerceAtLeast(0.dp) + fixedBottomGap,
                    )
            }
            .ifIsInstance<ImeWindowProps.Floating>(windowSpec.props) {
                Modifier.systemGestureExclusion()
            },
        allowClip = false,
    ) {
        Column {
            // WordTaker单界面 (item6): the cat voice surface and the keyboard surface are
            // merged into ONE panel — CAT_VOICE/TEXT both render [toolbar + CatKeyboardLayout].
            // CatKeyboardLayout is待机时 = 顶条睡猫 + 键盘，录音时 = 纯色底 + 放大走动的猫，
            // 同一界面内状态过渡、面板高度恒定。其余特殊模式 (媒体/剪贴/历史/设置) 保持原样。
            // item2: 在 TEXT/CAT_VOICE 单界面里，设置/语音/折叠图标已与睡猫合并到
            // CatKeyboardLayout 顶条同一行 —— 故此处不再额外渲染 ImeToolbar，避免出现两条。
            // 其余特殊模式 (媒体/剪贴/历史/设置) 仍需顶部工具栏做导航，保留 ImeToolbar。
            // item3: 各子界面总高已统一 (设置/历史面板 = keyboardUiHeight+15dp，工具栏44 +
            // 面板 == 顶条59 + 键盘)，所以模式切换时窗口高度恒定；用 120ms 线性 Crossfade
            // 替代瞬时内容替换，切换只有淡入淡出、零跳动。TEXT/CAT_VOICE 共用同一 surface
            // key，二者互切不触发 crossfade (避免录音面板被重建)。
            val surfaceKey = when (state.imeUiMode) {
                ImeUiMode.TEXT, ImeUiMode.CAT_VOICE -> ImeUiMode.TEXT
                else -> state.imeUiMode
            }
            Crossfade(
                targetState = surfaceKey,
                animationSpec = tween(durationMillis = MODE_SWITCH_FADE_MS),
                label = "ime_mode_crossfade",
            ) { mode ->
                Column {
                    when (mode) {
                        ImeUiMode.TEXT,
                        ImeUiMode.CAT_VOICE -> CatKeyboardLayout()
                        ImeUiMode.MEDIA -> { ImeToolbar(); ProvideActualLayoutDirection { MediaInputLayout() } }
                        ImeUiMode.CLIPBOARD -> { ImeToolbar(); ProvideActualLayoutDirection { ClipboardInputLayout() } }
                        ImeUiMode.HISTORY -> { ImeToolbar(); ProvideActualLayoutDirection { ImeHistoryLayout() } }
                        ImeUiMode.SETTINGS -> {
                            ImeSettingsToolbarSlot()
                            ProvideActualLayoutDirection { ImeSettingsLayout() }
                        }
                    }
                }
            }
            ImeSystemUiFloating()
        }
        ImeWindowResizeHandlesFixed()
    }
}

/** Settings uses the exact same full-height primary toolbar actions as idle text. */
@Composable
private fun ImeSettingsToolbarSlot() {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(catStripHeight()),
        contentAlignment = Alignment.Center,
    ) {
        ImeSettingsVoiceToolbarRow(
            onCatClick = {
                keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
            },
            onStartRecording = {
                keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                VoiceTrigger.requestStart()
            },
        )
    }
}

@Composable
private fun BoxScope.FloatingDockToFixedIndicator() {
    val inputFeedbackController = LocalInputFeedbackController.current
    val windowController = LocalWindowController.current

    val windowSpec by windowController.activeWindowSpec.collectAsState()
    val editorState by windowController.editor.state.collectAsState()

    val visible by remember {
        derivedStateOf {
            windowSpec.let { spec ->
                editorState.isMoveGesture && spec is ImeWindowSpec.Floating &&
                    spec.props.offsetBottom <= spec.constraints.dockToFixedHeight
            }
        }
    }

    val transition = updateTransition(
        targetState = visible,
        label = "FloatingDockToFixedIndicator_visibility",
    )
    val animatedAlpha by transition.animateFloat(label = "alpha") { visible ->
        if (visible) 1f else 0f
    }
    val animatedHeightRatio by transition.animateFloat(label = "height") { visible ->
        if (visible) 1f else 0f
    }
    val indicatorTheme = rememberSnyggThemeQuery(FlorisImeUi.FloatingDockToFixedIndicator.elementName)
    val color = indicatorTheme.background(default = Color.Gray)

    LaunchedEffect(visible) {
        if (visible) {
            delay(150)
            inputFeedbackController.keyPress()
        }
    }

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(windowSpec.constraints.dockToFixedHeight)
            .navigationBarsPadding()
            .graphicsLayer { alpha = animatedAlpha }
            .drawBehind {
                val animatedTopLeft = Offset(
                    x = 0f,
                    y = size.height * (1f - animatedHeightRatio),
                )
                drawRect(
                    color = color,
                    topLeft = animatedTopLeft,
                    alpha = 0.5f,
                )
                drawBorder(
                    color = color,
                    topLeft = animatedTopLeft,
                    stroke = Stroke(windowSpec.constraints.dockToFixedBorder.toPx()),
                )
            },
    )
}

@Composable
private fun BoxScope.OneHandedPanel() {
    val windowController = LocalWindowController.current
    val windowSpec by windowController.activeWindowSpec.collectAsState()

    when (val spec = windowSpec) {
        is ImeWindowSpec.Fixed if spec.fixedMode == ImeWindowMode.Fixed.COMPACT -> {
            OneHandedPanel(spec)
        }
        else -> { }
    }
}

@Composable
private fun BoxScope.OneHandedPanel(spec: ImeWindowSpec.Fixed) {
    val windowController = LocalWindowController.current
    val editorState by windowController.editor.state.collectAsState()
    val windowConfig by windowController.activeWindowConfig.collectAsState()

    val attributes = remember(windowConfig.mode) {
        mapOf(
            FlorisImeUi.Attr.WindowMode to windowConfig.mode.toString()
        )
    }

    if (!editorState.isEnabled) {
        Box(Modifier.matchParentSize()) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .safeDrawingPadding()
                    .padding(bottom = spec.props.paddingBottom.coerceAtLeast(0.dp))
                    .fold(
                        condition = spec.props.paddingLeft >= spec.props.paddingRight,
                        ifTrue = {
                            Modifier
                                .width(spec.props.paddingLeft)
                                .align(Alignment.CenterStart)
                        },
                        ifFalse = {
                            Modifier
                                .width(spec.props.paddingRight)
                                .align(Alignment.CenterEnd)
                        }
                    ),
                verticalArrangement = Arrangement.SpaceAround,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SnyggIconButton(
                    elementName = FlorisImeUi.OneHandedPanelButton.elementName,
                    attributes = attributes,
                    onClick = {
                        windowController.actions.toggleCompactLayout()
                    },
                ) {
                    SnyggIcon(
                        attributes = attributes,
                        imageVector = drawableRes(R.drawable.ic_zoom_out_map),
                    )
                }
                SnyggIconButton(
                    elementName = FlorisImeUi.OneHandedPanelButton.elementName,
                    attributes = attributes,
                    onClick = {
                        windowController.actions.compactLayoutFlipSide()
                    },
                ) {
                    if (spec.props.paddingLeft > spec.props.paddingRight) {
                        SnyggIcon(
                            imageVector = drawableRes(R.drawable.ic_chevron_left),
                            attributes = attributes,
                        )
                    } else {
                        SnyggIcon(
                            attributes = attributes,
                            imageVector = drawableRes(R.drawable.ic_chevron_right),
                        )
                    }
                }
                SnyggIconButton(
                    elementName = FlorisImeUi.OneHandedPanelButton.elementName,
                    attributes = attributes,
                    onClick = {
                        windowController.editor.toggleEnabled()
                    },
                ) {
                    SnyggIcon(
                        imageVector = drawableRes(R.drawable.ic_resize),
                        attributes = attributes,
                    )
                }
            }
        }
    }
}
