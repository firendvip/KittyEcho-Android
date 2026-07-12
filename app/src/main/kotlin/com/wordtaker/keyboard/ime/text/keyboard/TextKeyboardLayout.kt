/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package com.wordtaker.keyboard.ime.text.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.animation.AccelerateInterpolator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.unit.toSize
import com.wordtaker.keyboard.FlorisImeService
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.app.FlorisPreferenceStore
import com.wordtaker.keyboard.editorInstance
import com.wordtaker.keyboard.glideTypingManager
import com.wordtaker.keyboard.ime.editor.OperationScope
import com.wordtaker.keyboard.ime.editor.OperationUnit
import com.wordtaker.keyboard.ime.input.InputEventDispatcher
import com.wordtaker.keyboard.ime.keyboard.ComputingEvaluator
import com.wordtaker.keyboard.ime.keyboard.FlorisImeSizing
import com.wordtaker.keyboard.ime.keyboard.KeyboardMode
import com.wordtaker.keyboard.ime.keyboard.SpaceBarMode
import com.wordtaker.keyboard.ime.popup.ExceptionsForKeyCodes
import com.wordtaker.keyboard.ime.popup.PopupUiController
import com.wordtaker.keyboard.ime.popup.rememberPopupUiController
import com.wordtaker.keyboard.ime.text.gestures.GlideTypingGesture
import com.wordtaker.keyboard.ime.text.gestures.SwipeAction
import com.wordtaker.keyboard.ime.text.gestures.SwipeGesture
import com.wordtaker.keyboard.ime.text.key.KeyCode
import com.wordtaker.keyboard.ime.text.key.KeyType
import com.wordtaker.keyboard.ime.text.key.KeyVariation
import com.wordtaker.keyboard.ime.theme.FlorisImeUi
import com.wordtaker.keyboard.ime.window.LocalWindowController
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.lib.FlorisRect
import com.wordtaker.keyboard.lib.Pointer
import com.wordtaker.keyboard.lib.PointerMap
import com.wordtaker.keyboard.lib.devtools.LogTopic
import com.wordtaker.keyboard.lib.devtools.flogDebug
import com.wordtaker.keyboard.lib.toIntOffset
import com.wordtaker.keyboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.wordtaker.lib.android.isOrientationLandscape
import com.wordtaker.lib.compose.DisposableLifecycleEffect
import com.wordtaker.lib.snygg.SnyggSelector
import com.wordtaker.lib.snygg.value.SnyggStaticColorValue
import com.wordtaker.lib.snygg.ui.SnyggBox
import com.wordtaker.lib.snygg.ui.SnyggIcon
import com.wordtaker.lib.snygg.ui.SnyggText
import com.wordtaker.lib.snygg.ui.rememberSnyggThemeQuery
import kotlin.math.abs
import kotlin.math.sqrt

// WordTaker: bottom-left collapse chevron sizing (overlay, does not affect key layout).
// Kept small and pinned to the extreme bottom-left corner so its clickable footprint stays
// inside the key margin/gap and never covers the tappable body of the "123" key beneath it.
private const val WT_COLLAPSE_CHEVRON_SIZE_DP = 18
private const val WT_COLLAPSE_CHEVRON_ICON_DP = 14
private const val WT_COLLAPSE_CHEVRON_PADDING_DP = 0

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TextKeyboardLayout(
    modifier: Modifier = Modifier,
    evaluator: ComputingEvaluator,
): Unit = with(LocalDensity.current) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val glideTypingManager by context.glideTypingManager()

    val keyboard = evaluator.keyboard as TextKeyboard
    val glideEnabledInternal by prefs.glide.enabled.collectAsState()
    // Glide typing uses a Latin-shaped statistical classifier matched against the active
    // keyboard's key layout. On the full pinyin qwerty subtype the "words" it matches are
    // pinyin spellings (see PinyinLanguageProvider's bundled glide word list) which are then
    // fed into the normal pinyin composing/decoding path -- so it's safe there. On the other
    // Chinese subtypes (shuangpin/t9/wubi/stroke) the key layout doesn't map to plain a-z
    // spellings the same way, so glide stays disabled there.
    val glideEnabled = glideEnabledInternal && evaluator.editorInfo.isRichInputEditor &&
        evaluator.state.keyVariation != KeyVariation.PASSWORD &&
        (evaluator.subtype.primaryLocale.language != "zh" || evaluator.subtype.primaryLocale.variant == "pinyin")
    val glideShowTrail by prefs.glide.showTrail.collectAsState()
    val glideTrailStyle = rememberSnyggThemeQuery(FlorisImeUi.GlideTrail.elementName)
    val glideTrailColor = glideTrailStyle.foreground(default = Color.Green)

    val controller = remember { TextKeyboardLayoutController(context) }.also {
        it.keyboard = keyboard
        if (glideEnabled && keyboard.mode == KeyboardMode.CHARACTERS) {
            val keys = keyboard.keys().asSequence().toList()
            glideTypingManager.setLayout(keys)
        }
    }
    val touchEventChannel = remember { Channel<MotionEvent>(64) }

    fun resetAllKeys() {
        try {
            val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            controller.onTouchEventInternal(event)
            controller.popupUiController.hide()
            event.recycle()
        } catch (_: Throwable) {
            // Ignore
        }
    }

    DisposableEffect(Unit) {
        controller.glideTypingDetector.registerListener(controller)
        controller.glideTypingDetector.registerListener(glideTypingManager)
        onDispose {
            controller.glideTypingDetector.unregisterListener(controller)
            controller.glideTypingDetector.unregisterListener(glideTypingManager)
            resetAllKeys()
        }
    }

    DisposableLifecycleEffect(
        onResume = { /* Do nothing */ },
        onPause = { resetAllKeys() },
    )

    Box(modifier = modifier) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.keyboardUiHeight())
            .onGloballyPositioned { coords ->
                controller.size = coords.size.toSize()
            }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                        -> {
                        val clonedEvent = MotionEvent.obtain(event)
                        touchEventChannel
                            .trySend(clonedEvent)
                            .onFailure {
                                // Make sure to prevent MotionEvent memory leakage
                                // in case the input channel is full
                                clonedEvent.recycle()
                            }
                        return@pointerInteropFilter true
                    }
                }
                return@pointerInteropFilter false
            }
            .drawWithContent {
                drawContent()
                if (glideEnabled && glideShowTrail) {
                    val targetDist = 3.0f
                    val radius = 20.0f

                    val radiusReductionFactor = 0.99f
                    if (controller.fadingGlideRadius > 0) {
                        controller.drawGlideTrail(
                            this,
                            controller.fadingGlide,
                            targetDist,
                            controller.fadingGlideRadius,
                            radiusReductionFactor,
                            glideTrailColor,
                        )
                    }
                    if (controller.isGliding && controller.glideDataForDrawing.isNotEmpty()) {
                        controller.drawGlideTrail(
                            this, controller.glideDataForDrawing, targetDist, radius,
                            radiusReductionFactor, glideTrailColor,
                        )
                    }
                }
            },
    ) {
        // FIXME (when rewriting TextKeyboardLayout): constrains.maxWidth is not stable!
        val keyboardWidth = constraints.maxWidth.toFloat()
        val keyboardHeight = constraints.maxHeight.toFloat()
        val keyboardRowBaseHeight = FlorisImeSizing.keyboardRowBaseHeight

        val windowController = LocalWindowController.current
        val windowSpec by windowController.activeWindowSpec.collectAsState()
        val keyMarginH by remember { derivedStateOf { windowSpec.keyMarginH.toPx() } }
        val keyMarginV by remember { derivedStateOf { windowSpec.keyMarginV.toPx() } }

        val desiredKey = remember(
            keyboard, keyboardWidth, keyboardHeight, keyMarginH, keyMarginV,
            keyboardRowBaseHeight, evaluator
        ) {
            TextKey(data = TextKeyData.UNSPECIFIED).also { desiredKey ->
                desiredKey.touchBounds.apply {
                    width = keyboardWidth / 10f
                    height = when (keyboard.mode) {
                        KeyboardMode.CHARACTERS,
                        KeyboardMode.NUMERIC_ADVANCED,
                        KeyboardMode.SYMBOLS,
                        KeyboardMode.SYMBOLS2 -> {
                            (keyboardHeight / keyboard.rowCount)
                                .coerceAtMost(keyboardRowBaseHeight.toPx() * 1.12f)
                        }
                        else -> keyboardRowBaseHeight.toPx()
                    }
                }
                desiredKey.visibleBounds.applyFrom(desiredKey.touchBounds).deflateBy(keyMarginH, keyMarginV)
                keyboard.layout(keyboardWidth, keyboardHeight, desiredKey, true)
            }
        }

        val desiredKeyHack = rememberUpdatedState(desiredKey) // TODO quick'n'dirty hack
        val popupUiController = rememberPopupUiController(
            key1 = keyboard,
            key2 = Unit, // TODO quick'n'dirty hack
            boundsProvider = { key ->
                // WordTaker (P0-3): detached preview bubble floating ABOVE the key (WeChat/
                // Gboard style) instead of a tall panel covering the key body.
                //   width  = key width x 1.4 (but never wider than key width + 2 key heights,
                //            which keeps bubbles on extra-wide T9 keys compact)
                //   height = key height x 1.35, bottom edge = key top - 4dp
                // Bounds are clamped horizontally so edge-column bubbles stay on screen (P2-6).
                val desired = desiredKeyHack.value.visibleBounds
                val keyPopupWidth: Float
                val keyPopupHeight: Float
                when {
                    configuration.isOrientationLandscape() -> {
                        keyPopupWidth = (key.visibleBounds.width * 1.2f)
                            .coerceAtMost(key.visibleBounds.width + desired.height * 2.0f)
                        keyPopupHeight = desired.height * 1.35f
                    }
                    else -> {
                        keyPopupWidth = (key.visibleBounds.width * 1.4f)
                            .coerceAtMost(key.visibleBounds.width + desired.height * 2.0f)
                        keyPopupHeight = desired.height * 1.35f
                    }
                }
                val gapToKey = 4.dp.toPx()
                val keyPopupDiffX = (key.visibleBounds.width - keyPopupWidth) / 2.0f
                FlorisRect.new().apply {
                    left = (key.visibleBounds.left + keyPopupDiffX)
                        .coerceIn(0.0f, (keyboardWidth - keyPopupWidth).coerceAtLeast(0.0f))
                    // NOTE: top may go negative for the top key row — popups intentionally
                    // render above the keyboard area (over the smartbar), same as before.
                    top = key.visibleBounds.top - gapToKey - keyPopupHeight
                    right = left + keyPopupWidth
                    bottom = top + keyPopupHeight
                }
            },
            isSuitableForBasicPopup = { key ->
                if (key is TextKey) {
                    val keyCode = key.computedData.code
                    val keyType = key.computedData.type
                    val numeric = keyboard.mode == KeyboardMode.NUMERIC ||
                        keyboard.mode == KeyboardMode.PHONE || keyboard.mode == KeyboardMode.PHONE2 ||
                        keyboard.mode == KeyboardMode.NUMERIC_ADVANCED && keyType == KeyType.NUMERIC
                    keyCode > KeyCode.SPACE && keyCode != KeyCode.CJK_SPACE && !numeric
                } else {
                    true
                }
            },
            isSuitableForExtendedPopup = { key ->
                if (key is TextKey) {
                    val keyCode = key.computedData.code
                    keyCode > KeyCode.SPACE && keyCode != KeyCode.CJK_SPACE || ExceptionsForKeyCodes.contains(keyCode)
                } else {
                    true
                }
            },
        )
        popupUiController.evaluator = evaluator
        popupUiController.keyHintConfiguration = prefs.keyboard.keyHintConfiguration()
        controller.popupUiController = popupUiController
        val debugShowTouchBoundaries by prefs.devtools.showKeyTouchBoundaries.collectAsState()
        for (textKey in keyboard.keys()) {
            TextKeyButton(
                textKey, evaluator, desiredKey,
                debugShowTouchBoundaries,
            )
        }

        popupUiController.RenderPopups()
    }

        // WordTaker: WeChat/iOS-style collapse chevron at the bottom-left corner. Tapping it
        // hides the keyboard window (same call the toolbar collapse button uses). Rendered in the
        // OUTER Box — outside the keyboard's pointerInteropFilter — so its clickable actually
        // receives taps, while the function-row keys keep their fixed positions/sizes.
        if (keyboard.mode == KeyboardMode.CHARACTERS) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(WT_COLLAPSE_CHEVRON_PADDING_DP.dp)
                    .size(WT_COLLAPSE_CHEVRON_SIZE_DP.dp)
                    .clickable { FlorisImeService.hideUi() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_wt_collapse),
                    contentDescription = "收起键盘",
                    modifier = Modifier.size(WT_COLLAPSE_CHEVRON_ICON_DP.dp),
                    // P2-303: 深色模式下 #3C4043 在深底上几乎不可见，改为随深浅取色。
                    tint = if (isSystemInDarkTheme()) Color(0xFFBFC3C7) else Color(0xFF3C4043),
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        for (event in touchEventChannel) {
            if (!isActive) break
            controller.onTouchEventInternal(event)
            event.recycle()
        }
    }
}

// 中/英 切换键里"未选中"那个字的柔和灰色 (Google 风中性灰 #9AA0A6)。
private val LANGUAGE_SWITCH_MUTED = Color(0xFF9AA0A6)
// 中/英 对角双字排布参数：当前态字号略放大、非当前态缩小，两字沿对角错开的位移比例 (相对基准字号)。
private const val LANGUAGE_SWITCH_ACTIVE_SCALE = 0.92f
private const val LANGUAGE_SWITCH_MUTED_SCALE = 0.6f
private const val LANGUAGE_SWITCH_DIAG_FACTOR = 0.34f
private val LANGUAGE_SWITCH_FALLBACK_SIZE = 22.sp

// WordTaker T9 (P0-1): key labels of the form "2 ABC".."9 WXYZ" render as a split
// digit (small, top-start) + letter group (large, centered) instead of one clipped line.
// Case-insensitive: auto_text_key lowercases labels while the keyboard is unshifted.
private val T9_KEY_LABEL_REGEX = """^(\d) ([A-Za-z]+)$""".toRegex()

// WordTaker (P1-2) press motion constants.
private const val KEY_RELEASE_COLOR_FADE_MILLIS = 120
private const val KEY_PRESS_SCALE = 0.96f
private const val KEY_LABEL_DIM_ALPHA = 0.35f
private const val KEY_LABEL_DIM_MILLIS = 90

// WordTaker (P1-1 方案2): key-cap bottom edge — a copy of the key shape drawn 1dp lower,
// underneath the key background, so only a thin dark line peeks out at the bottom.
// Color comes from the theme's `key-edge` element (absent = no edge, e.g. borderless).
private const val KEY_EDGE_OFFSET_DP = 1

// WordTaker (P2-3): space bar press ripple — a soft circle expanding from the key center,
// clipped to the key shape. Expand on press, keep expanding + fade on release.
private const val SPACE_RIPPLE_ALPHA = 0.12f
private const val SPACE_RIPPLE_EXPAND_MILLIS = 180
private const val SPACE_RIPPLE_FADE_MILLIS = 150
private const val SPACE_RIPPLE_MAX_RADIUS_FACTOR = 0.55f

@Composable
private fun TextKeyButton(
    key: TextKey,
    evaluator: ComputingEvaluator,
    desiredKey: TextKey,
    debugShowTouchBoundaries: Boolean,
) = with(LocalDensity.current) {
    val attributes = mapOf(
        FlorisImeUi.Attr.Code to key.computedData.code,
        FlorisImeUi.Attr.Mode to evaluator.keyboard.mode.toString(),
        FlorisImeUi.Attr.ShiftState to evaluator.state.inputShiftState.toString(),
    )
    val selector = when {
        !key.isEnabled -> SnyggSelector.DISABLED
        key.isPressed -> SnyggSelector.PRESSED
        else -> SnyggSelector.NONE
    }
    val size = remember(key, desiredKey) {
        key.visibleBounds.size.toDpSize()
    }

    // WordTaker (P1-2): press feedback motion. Press state applies instantly (snap), release
    // fades pressed -> normal background over 120ms. Keys WITHOUT a preview popup (space,
    // enter, shift, delete, view switchers, ...) additionally get a subtle 0.96 press-down
    // scale with a spring release. Only transform/color are animated — no relayout.
    val prefs by FlorisPreferenceStore
    val popupEnabled by prefs.keyboard.popupEnabled.collectAsState()
    val keyCode = key.computedData.code
    val keyboardMode = evaluator.keyboard.mode
    val isNumericMode = keyboardMode == KeyboardMode.NUMERIC ||
        keyboardMode == KeyboardMode.PHONE || keyboardMode == KeyboardMode.PHONE2 ||
        (keyboardMode == KeyboardMode.NUMERIC_ADVANCED && key.computedData.type == KeyType.NUMERIC)
    val hasPopupPreview = popupEnabled && keyCode > KeyCode.SPACE &&
        keyCode != KeyCode.CJK_SPACE && !isNumericMode
    val restStyle = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName, attributes, SnyggSelector.NONE)
    val pressedStyle = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName, attributes, SnyggSelector.PRESSED)
    val restBg = (restStyle.background as? SnyggStaticColorValue)?.color
    val pressedBg = (pressedStyle.background as? SnyggStaticColorValue)?.color
    val hasStaticBg = restBg != null && pressedBg != null && key.isEnabled
    val animatedBgState = animateColorAsState(
        targetValue = when {
            !hasStaticBg -> Color.Transparent
            key.isPressed -> pressedBg!!
            else -> restBg!!
        },
        animationSpec = if (key.isPressed) {
            snap()
        } else {
            tween(durationMillis = KEY_RELEASE_COLOR_FADE_MILLIS, easing = FastOutSlowInEasing)
        },
        label = "keyBackground",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (key.isPressed && !hasPopupPreview && key.isEnabled) KEY_PRESS_SCALE else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "keyPressScale",
    )
    // WordTaker (P2-4): while the preview bubble is up, dim the key's own label so the eye
    // focuses on the bubble (WeChat behavior).
    val labelAlpha by animateFloatAsState(
        targetValue = if (key.isPressed && hasPopupPreview) KEY_LABEL_DIM_ALPHA else 1.0f,
        animationSpec = tween(durationMillis = KEY_LABEL_DIM_MILLIS, easing = FastOutSlowInEasing),
        label = "keyLabelAlpha",
    )
    // WordTaker (P1-1 方案2): resolve the theme's key-edge color (bottom dark edge). Rest
    // selector on purpose — the edge is part of the cap's static depth, not a press state.
    val keyEdgeStyle = rememberSnyggThemeQuery(FlorisImeUi.KeyEdge.elementName, attributes, SnyggSelector.NONE)
    val keyEdgeColor = keyEdgeStyle.background()
    val keyShape = restStyle.shape()
    val drawKeyEdge = key.isEnabled && keyEdgeColor.isSpecified && keyEdgeColor.alpha > 0.0f && hasStaticBg
    // WordTaker (P2-3): space bar press ripple state.
    val isSpaceBar = keyCode == KeyCode.SPACE || keyCode == KeyCode.CJK_SPACE
    val rippleRadius = remember { Animatable(0.0f) }
    val rippleAlpha = remember { Animatable(0.0f) }
    if (isSpaceBar) {
        LaunchedEffect(key.isPressed) {
            if (key.isPressed) {
                rippleRadius.snapTo(0.0f)
                rippleAlpha.snapTo(SPACE_RIPPLE_ALPHA)
                rippleRadius.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(SPACE_RIPPLE_EXPAND_MILLIS, easing = FastOutSlowInEasing),
                )
            } else if (rippleAlpha.value > 0.0f) {
                // Release: let the wave finish expanding while it fades out.
                coroutineScope {
                    launch {
                        rippleRadius.animateTo(
                            targetValue = 1.0f,
                            animationSpec = tween(SPACE_RIPPLE_FADE_MILLIS, easing = FastOutSlowInEasing),
                        )
                    }
                    rippleAlpha.animateTo(
                        targetValue = 0.0f,
                        animationSpec = tween(SPACE_RIPPLE_FADE_MILLIS, easing = FastOutSlowInEasing),
                    )
                }
                rippleRadius.snapTo(0.0f)
            }
        }
    }
    val spaceRippleColor = restStyle.foreground(default = Color.Unspecified)

    SnyggBox(
        FlorisImeUi.Key.elementName,
        attributes = attributes,
        selector = selector,
        modifier = Modifier
            .requiredSize(size)
            .absoluteOffset { key.visibleBounds.topLeft.toIntOffset() }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            // WordTaker (P1-1 方案2): paint the bottom edge BEFORE the key background (outer
            // draw modifiers render underneath), so only a 1dp dark line shows below the cap.
            .drawBehind {
                if (drawKeyEdge) {
                    val outline = keyShape.createOutline(this.size, layoutDirection, this)
                    translate(top = KEY_EDGE_OFFSET_DP.dp.toPx()) {
                        drawOutline(outline, keyEdgeColor)
                    }
                }
            },
        backgroundColorOverride = if (hasStaticBg) animatedBgState.value else null,
    ) {
        // WordTaker (P2-3): space bar ripple layer — under the label, clipped to the key shape.
        if (isSpaceBar && spaceRippleColor.isSpecified) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val alpha = rippleAlpha.value
                        if (alpha > 0.0f) {
                            val outline = keyShape.createOutline(this.size, layoutDirection, this)
                            clipPath(Path().apply { addOutline(outline) }) {
                                drawCircle(
                                    color = spaceRippleColor,
                                    radius = rippleRadius.value * this.size.maxDimension * SPACE_RIPPLE_MAX_RADIUS_FACTOR,
                                    alpha = alpha,
                                )
                            }
                        }
                    },
            )
        }
        val isTelPadKey = key.computedData.type == KeyType.NUMERIC && evaluator.keyboard.mode == KeyboardMode.PHONE
        // WordTaker 中/英 切换键: 同时显示 "中" 与 "英" 两字，当前输入模式的字用键的正常前景色高亮，
        // 另一个字用柔和灰色淡化。SnyggText 只能整体一个颜色，无法逐字上色，故此键单独用
        // AnnotatedString + Material3 Text 渲染 (字号/字体/字重仍取自 Key 的 Snygg 样式，保持一致)。
        // 切换行为不变 (仍走 KeyCode.LANGUAGE_SWITCH)，这里只改视觉标签。
        if (key.computedData.code == KeyCode.LANGUAGE_SWITCH) {
            val keyStyle = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName, attributes, selector)
            val activeColor = keyStyle.foreground(default = LocalContentColor.current)
            val isEnglish = evaluator.state.isEnglishMode
            // 「中」「英」对角分布同显：当前输入模式的字用正常前景色、稍大字号高亮，
            // 另一字用柔和灰、略小字号淡化，斜向错开排布 (中↖ / 英↘)，两字均完整不裁切、不换行。
            // keyStyle.fontSize() 可能为 Unspecified，需回退到具体字号，否则 *Float 得 NaN。
            val baseSize = keyStyle.fontSize()
                .takeOrElse { keyStyle.lineHeight() }
                .takeOrElse { LANGUAGE_SWITCH_FALLBACK_SIZE }
            val activeSize = baseSize * LANGUAGE_SWITCH_ACTIVE_SCALE
            val mutedSize = baseSize * LANGUAGE_SWITCH_MUTED_SCALE
            val diag = with(LocalDensity.current) { baseSize.toPx().toDp() * LANGUAGE_SWITCH_DIAG_FACTOR }
            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .align(Alignment.Center),
            ) {
                Text(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .absoluteOffset(x = -diag, y = -diag),
                    text = "中",
                    color = if (isEnglish) LANGUAGE_SWITCH_MUTED else activeColor,
                    fontSize = if (isEnglish) mutedSize else activeSize,
                    fontStyle = keyStyle.fontStyle(),
                    fontWeight = keyStyle.fontWeight(),
                    letterSpacing = keyStyle.letterSpacing(),
                )
                Text(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .absoluteOffset(x = diag, y = diag),
                    text = "英",
                    color = if (isEnglish) activeColor else LANGUAGE_SWITCH_MUTED,
                    fontSize = if (isEnglish) activeSize else mutedSize,
                    fontStyle = keyStyle.fontStyle(),
                    fontWeight = keyStyle.fontWeight(),
                    letterSpacing = keyStyle.letterSpacing(),
                )
            }
        } else {
        key.label?.let { label ->
            var customLabel = label
            if (key.computedData.code == KeyCode.SPACE) {
                val prefs by FlorisPreferenceStore
                val spaceBarMode by prefs.keyboard.spaceBarMode.collectAsState()
                when (spaceBarMode) {
                    SpaceBarMode.NOTHING -> return@let
                    SpaceBarMode.CURRENT_LANGUAGE -> {}
                    SpaceBarMode.SPACE_BAR_KEY -> customLabel = "␣"
                }
            } else if (
                key.computedData.type == KeyType.CHARACTER &&
                key.computedData.code in 'a'.code..'z'.code
            ) {
                // WordTaker WeChat-style minimal keyboard: letter keys ALWAYS render
                // uppercase (cosmetic only). The emitted code stays lowercase, so the
                // pinyin decoder (which lowercases composing text) is unaffected.
                customLabel = customLabel.uppercase()
            }
            // WordTaker T9 (P0-1): split "2 ABC" into letter group (main visual, centered)
            // + digit (small, top-start), WeChat/Sogou style. Data (label/code) is unchanged.
            val t9Match = if (keyboardMode == KeyboardMode.CHARACTERS) {
                T9_KEY_LABEL_REGEX.matchEntire(customLabel)
            } else {
                null
            }
            if (t9Match != null) {
                SnyggText(
                    elementName = FlorisImeUi.KeyT9Letters.elementName,
                    attributes = attributes,
                    selector = selector,
                    modifier = Modifier
                        .wrapContentSize()
                        .align(Alignment.Center)
                        .graphicsLayer { alpha = labelAlpha },
                    text = t9Match.groupValues[2].uppercase(),
                )
                SnyggText(
                    elementName = FlorisImeUi.KeyT9Digit.elementName,
                    attributes = attributes,
                    selector = selector,
                    modifier = Modifier
                        .wrapContentSize()
                        .align(Alignment.TopStart)
                        .graphicsLayer { alpha = labelAlpha },
                    text = t9Match.groupValues[1],
                )
            } else {
                SnyggText(
                    modifier = Modifier
                        .wrapContentSize()
                        .align(if (isTelPadKey) BiasAlignment(-0.5f, 0f) else Alignment.Center)
                        .graphicsLayer { alpha = labelAlpha },
                    text = customLabel,
                )
            }
        }
        } // end else (non language-switch label rendering)
        key.hintedLabel?.let { hintedLabel ->
            // WordTaker: on the 全拼 QWERTY the per-letter number/symbol hint sits centered ABOVE
            // the letter (WeChat/iOS pinyin style, matching the reference). The phone T9 pad keeps
            // its top-right hint; other layouts keep the Gboard-style top-end position.
            val hintAlignment = when {
                isTelPadKey -> BiasAlignment(0.5f, 0f)
                keyboardMode == KeyboardMode.CHARACTERS -> Alignment.TopCenter
                else -> Alignment.TopEnd
            }
            SnyggText(
                elementName = FlorisImeUi.KeyHint.elementName,
                attributes = attributes,
                selector = selector,
                modifier = Modifier
                    .wrapContentSize()
                    .align(hintAlignment),
                text = hintedLabel,
            )
        }
        key.foregroundImageVector?.let { imageVector ->
            SnyggIcon(
                modifier = Modifier.align(Alignment.Center),
                imageVector = imageVector,
                contentDescription = null,
            )
        }
    }
    if (debugShowTouchBoundaries) {
        Box(
            modifier = Modifier
                .requiredSize(key.touchBounds.size.toDpSize())
                .absoluteOffset { key.touchBounds.topLeft.toIntOffset() }
                .border(Dp.Hairline, Color.Red),
        )
    }
}

@Suppress("unused_parameter")
private class TextKeyboardLayoutController(
    context: Context,
) : SwipeGesture.Listener, GlideTypingGesture.Listener {
    private val prefs by FlorisPreferenceStore
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()
    private val subtypeManager by context.subtypeManager()

    private val inputEventDispatcher get() = keyboardManager.inputEventDispatcher
    private val inputFeedbackController get() = FlorisImeService.inputFeedbackController()
    private val keyHintConfiguration = prefs.keyboard.keyHintConfiguration()
    private val pointerMap: PointerMap<TouchPointer> = PointerMap { TouchPointer() }
    lateinit var popupUiController: PopupUiController

    private var initSelectionStart: Int = 0
    private var initSelectionEnd: Int = 0
    var isGliding by mutableStateOf(false)

    val glideTypingDetector = GlideTypingGesture.Detector(context)
    val glideDataForDrawing = mutableStateListOf<Pair<GlideTypingGesture.Detector.Position, Long>>()
    val fadingGlide = mutableStateListOf<Pair<GlideTypingGesture.Detector.Position, Long>>()
    var fadingGlideRadius by mutableFloatStateOf(0.0f)
    private val swipeGestureDetector = SwipeGesture.Detector(this)

    lateinit var keyboard: TextKeyboard
    var size = Size.Zero

    val isGlideEnabled: Boolean get() = prefs.glide.enabled.get() && editorInstance.activeInfo.isRichInputEditor &&
        keyboardManager.activeState.keyVariation != KeyVariation.PASSWORD &&
        (subtypeManager.activeSubtype.primaryLocale.language != "zh" ||
            subtypeManager.activeSubtype.primaryLocale.variant == "pinyin")

    fun onTouchEventInternal(event: MotionEvent) {
        flogDebug { "event=$event" }
        swipeGestureDetector.onTouchEvent(event)
        if (isGlideEnabled && keyboard.mode == KeyboardMode.CHARACTERS) {
            val glidePointer = pointerMap.findById(0)
            val isNotBlocked = glidePointer?.hasTriggeredLongPress != true
            if (isNotBlocked && glideTypingDetector.onTouchEvent(event, glidePointer?.initialKey)) {
                for (pointer in pointerMap) {
                    if (pointer.activeKey != null) {
                        onTouchCancelInternal(event, pointer)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointerMap.clear()
                }
                isGliding = true
                return
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val pointer = pointerMap.add(pointerId, pointerIndex)
                if (pointer != null) {
                    swipeGestureDetector.onTouchDown(event, pointer)
                    onTouchDownInternal(event, pointer)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val oldPointer = pointerMap.findById(pointerId)
                if (oldPointer != null) {
                    swipeGestureDetector.onTouchCancel(event, oldPointer)
                    onTouchCancelInternal(event, oldPointer)
                    pointerMap.removeById(oldPointer.id)
                }
                // Search for active character keys and cancel them
                for (pointer in pointerMap) {
                    val activeKey = pointer.activeKey
                    if (activeKey != null && popupUiController.isSuitableForPopups(activeKey)) {
                        swipeGestureDetector.onTouchCancel(event, pointer)
                        onTouchUpInternal(event, pointer)
                    }
                }
                val pointer = pointerMap.add(pointerId, pointerIndex)
                if (pointer != null) {
                    swipeGestureDetector.onTouchDown(event, pointer)
                    onTouchDownInternal(event, pointer)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (pointerIndex in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(pointerIndex)
                    val pointer = pointerMap.findById(pointerId)
                    if (pointer != null) {
                        pointer.index = pointerIndex
                        val alwaysTriggerOnMove = (pointer.hasTriggeredGestureMove
                            && (pointer.initialKey?.computedData?.code == KeyCode.DELETE
                            && prefs.gestures.deleteKeySwipeLeft.get().let {
                                it == SwipeAction.DELETE_CHARACTERS_PRECISELY || it == SwipeAction.SELECT_CHARACTERS_PRECISELY
                            }
                            || pointer.initialKey?.computedData?.code == KeyCode.SPACE
                            || pointer.initialKey?.computedData?.code == KeyCode.CJK_SPACE))
                        if (swipeGestureDetector.onTouchMove(event, pointer, alwaysTriggerOnMove) || pointer.hasTriggeredGestureMove) {
                            pointer.hasTriggeredGestureMove = true
                            pointer.activeKey?.let { activeKey ->
                                inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                            }
                        } else {
                            onTouchMoveInternal(event, pointer)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val pointer = pointerMap.findById(pointerId)
                if (pointer != null) {
                    pointer.index = pointerIndex
                    if (swipeGestureDetector.onTouchUp(event, pointer) || pointer.hasTriggeredGestureMove) {
                        if (pointer.hasTriggeredGestureMove && pointer.initialKey?.computedData?.code == KeyCode.DELETE) {
                            val selection = editorInstance.activeContent.selection
                            if (selection.isSelectionMode) {
                                editorInstance.deleteBackwards(OperationUnit.CHARACTERS)
                            }
                        }
                        onTouchCancelInternal(event, pointer)
                    } else {
                        onTouchUpInternal(event, pointer)
                    }
                    pointerMap.removeById(pointer.id)
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                for (pointer in pointerMap) {
                    if (pointer.id == pointerId) {
                        pointer.index = pointerIndex
                        if (swipeGestureDetector.onTouchUp(event, pointer) || pointer.hasTriggeredGestureMove) {
                            if (pointer.hasTriggeredGestureMove &&
                                pointer.initialKey?.computedData?.code == KeyCode.DELETE &&
                                prefs.gestures.deleteKeySwipeLeft.get() != SwipeAction.SELECT_CHARACTERS_PRECISELY &&
                                prefs.gestures.deleteKeySwipeLeft.get() != SwipeAction.SELECT_WORDS_PRECISELY) {
                                val selection = editorInstance.activeContent.selection
                                if (selection.isSelectionMode) {
                                    editorInstance.deleteBackwards(OperationUnit.CHARACTERS)
                                }
                            }
                            onTouchCancelInternal(event, pointer)
                        } else {
                            onTouchUpInternal(event, pointer)
                        }
                    } else {
                        swipeGestureDetector.onTouchCancel(event, pointer)
                        onTouchCancelInternal(event, pointer)
                    }
                }
                pointerMap.clear()
            }
            MotionEvent.ACTION_CANCEL -> {
                for (pointer in pointerMap) {
                    swipeGestureDetector.onTouchCancel(event, pointer)
                    onTouchCancelInternal(event, pointer)
                }
                pointerMap.clear()
            }
        }
    }

    private fun onTouchDownInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }

        val key = keyboard.getKeyForPos(event.getX(pointer.index), event.getY(pointer.index))
        if (key != null && key.isEnabled) {
            key.computedDataOnDown = key.computedData
            pointer.pressedKeyInfo = inputEventDispatcher.sendDown(
                data = key.computedData,
                onLongPress = onLongPress@ {
                    pointer.hasTriggeredLongPress = true
                    when (key.computedData.code) {
                        KeyCode.SPACE, KeyCode.CJK_SPACE -> {
                            when (prefs.gestures.spaceBarLongPress.get()) {
                                SwipeAction.NO_ACTION,
                                SwipeAction.INSERT_SPACE -> {
                                }
                                else -> {
                                    keyboardManager.executeSwipeAction(prefs.gestures.spaceBarLongPress.get())
                                }
                            }
                            true
                        }
                        KeyCode.SHIFT -> {
                            if (inputEventDispatcher.isUninterruptedEventSequence(key.computedData)) {
                                inputEventDispatcher.sendDownUp(TextKeyData.CAPS_LOCK)
                                inputFeedbackController?.keyLongPress(key.computedData)
                            }
                            // We always return false here to prevent blockade for the up touch event
                            false
                        }
                        KeyCode.LANGUAGE_SWITCH -> {
                            inputEventDispatcher.sendDownUp(TextKeyData.SYSTEM_INPUT_METHOD_PICKER)
                            true
                        }
                        else -> {
                            if (popupUiController.isSuitableForPopups(key) && key.computedPopups.getPopupKeys(
                                    keyHintConfiguration
                                ).isNotEmpty()
                            ) {
                                popupUiController.extend(key, size)
                                inputFeedbackController?.keyLongPress(key.computedData)
                                true
                            } else {
                                false
                            }
                        }
                    }
                },
            )
            if (prefs.keyboard.popupEnabled.get() && popupUiController.isSuitableForPopups(key)) {
                popupUiController.show(key)
            }
            inputFeedbackController?.keyPress(key.computedData)
            key.isPressed = true
            if (pointer.initialKey == null) {
                pointer.initialKey = key
            }
            pointer.activeKey = key
            initSelectionStart = editorInstance.activeContent.selection.start
            initSelectionEnd = editorInstance.activeContent.selection.end
        } else {
            pointer.activeKey = null
        }
    }

    private fun onTouchMoveInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }

        val initialKey = pointer.initialKey
        val activeKey = pointer.activeKey
        if (initialKey != null && activeKey != null) {
            if (popupUiController.isShowingExtendedPopup) {
                val x = event.getX(pointer.index)
                val y = event.getY(pointer.index)
                if (!popupUiController.propagateMotionEvent(activeKey, x, y)) {
                    onTouchCancelInternal(event, pointer)
                    onTouchDownInternal(event, pointer)
                }
            } else {
                if ((event.getX(pointer.index) < activeKey.visibleBounds.left - 0.1f * activeKey.visibleBounds.width)
                    || (event.getX(pointer.index) > activeKey.visibleBounds.right + 0.1f * activeKey.visibleBounds.width)
                    || (event.getY(pointer.index) < activeKey.visibleBounds.top - 0.35f * activeKey.visibleBounds.height)
                    || (event.getY(pointer.index) > activeKey.visibleBounds.bottom + 0.35f * activeKey.visibleBounds.height)
                ) {
                    onTouchCancelInternal(event, pointer)
                    onTouchDownInternal(event, pointer)
                }
            }
        }
    }

    private fun onTouchUpInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }
        pointer.pressedKeyInfo?.cancelJobs()
        pointer.pressedKeyInfo = null

        if (pointer.hasTriggeredMassSelection) {
            pointer.hasTriggeredMassSelection = false
            editorInstance.massSelection.end()
        }

        val initialKey = pointer.initialKey
        val activeKey = pointer.activeKey
        if (initialKey != null && activeKey != null) {
            activeKey.isPressed = false
            if (popupUiController.isSuitableForPopups(activeKey)) {
                val retData = popupUiController.getActiveKeyData(activeKey)
                if (retData != null && !pointer.hasTriggeredGestureMove) {
                    if (retData == activeKey.computedData) {
                        if (activeKey.computedData != activeKey.computedDataOnDown) {
                            inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                            inputEventDispatcher.sendDownUp(activeKey.computedData)
                        } else {
                            inputEventDispatcher.sendUp(activeKey.computedDataOnDown)
                        }
                    } else {
                        inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                        inputEventDispatcher.sendDownUp(retData)
                    }
                } else {
                    inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                }
                popupUiController.hide()
            } else {
                if (pointer.hasTriggeredGestureMove) {
                    inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                } else {
                    if (activeKey.computedData != activeKey.computedDataOnDown) {
                        inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                        inputEventDispatcher.sendDownUp(activeKey.computedData)
                    } else {
                        inputEventDispatcher.sendUp(activeKey.computedDataOnDown)
                    }
                }
            }
            pointer.activeKey = null
        }
        pointer.hasTriggeredGestureMove = false
    }

    private fun onTouchCancelInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }
        pointer.pressedKeyInfo?.cancelJobs()
        pointer.pressedKeyInfo = null

        if (pointer.hasTriggeredMassSelection) {
            pointer.hasTriggeredMassSelection = false
            editorInstance.massSelection.end()
        }

        val activeKey = pointer.activeKey
        if (activeKey != null) {
            activeKey.isPressed = false
            inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
            if (popupUiController.isSuitableForPopups(activeKey)) {
                popupUiController.hide()
            }
            pointer.activeKey = null
        }
        pointer.hasTriggeredGestureMove = false
    }

    override fun onSwipe(event: SwipeGesture.Event): Boolean {
        val pointer = pointerMap.findById(event.pointerId) ?: return false
        val initialKey = pointer.initialKey ?: return false
        val activeKey = pointer.activeKey
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW)

        return when (initialKey.computedData.code) {
            KeyCode.DELETE -> handleDeleteSwipe(event)
            KeyCode.SPACE, KeyCode.CJK_SPACE -> handleSpaceSwipe(event)
            else -> when {
                (initialKey.computedData.code == KeyCode.SHIFT && activeKey?.computedData?.code == KeyCode.SPACE ||
                    initialKey.computedData.code == KeyCode.SHIFT && activeKey?.computedData?.code == KeyCode.CJK_SPACE) &&
                    event.type == SwipeGesture.Type.TOUCH_MOVE -> handleSpaceSwipe(event)
                initialKey.computedData.code == KeyCode.SHIFT && activeKey?.computedData?.code != KeyCode.SHIFT &&
                    event.type == SwipeGesture.Type.TOUCH_UP -> {
                    activeKey?.let {
                        inputEventDispatcher.sendUp(popupUiController.getActiveKeyData(it) ?: it.computedDataOnDown)
                    }
                    inputEventDispatcher.sendCancel(TextKeyData.SHIFT)
                    true
                }
                initialKey.computedData.code > KeyCode.SPACE && !popupUiController.isShowingExtendedPopup -> when {
                    !isGlideEnabled && !pointer.hasTriggeredGestureMove -> when (event.type) {
                        SwipeGesture.Type.TOUCH_UP -> {
                            val swipeAction = when (event.direction) {
                                SwipeGesture.Direction.UP -> prefs.gestures.swipeUp.get()
                                SwipeGesture.Direction.DOWN -> prefs.gestures.swipeDown.get()
                                SwipeGesture.Direction.LEFT -> prefs.gestures.swipeLeft.get()
                                SwipeGesture.Direction.RIGHT -> prefs.gestures.swipeRight.get()
                                else -> SwipeAction.NO_ACTION
                            }
                            if (swipeAction != SwipeAction.NO_ACTION) {
                                keyboardManager.executeSwipeAction(swipeAction)
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                    else -> false
                }
                else -> false
            }
        }
    }

    private fun handleDeleteSwipe(event: SwipeGesture.Event): Boolean {
        if (editorInstance.activeInfo.isRawInputEditor) return false

        return when (event.type) {
            SwipeGesture.Type.TOUCH_MOVE -> when (prefs.gestures.deleteKeySwipeLeft.get()) {
                SwipeAction.DELETE_CHARACTERS_PRECISELY, SwipeAction.SELECT_CHARACTERS_PRECISELY -> {
                    if (abs(event.relUnitCountX) > 0) {
                        inputFeedbackController?.gestureMovingSwipe(TextKeyData.DELETE)
                    }
                    val activeSelection = editorInstance.activeContent.selection
                    if (activeSelection.isValid) {
                        if (!inputEventDispatcher.isPressed(KeyCode.SHIFT)) {
                            // Backward select
                            editorInstance.setSelectionSurrounding(
                                n = -event.absUnitCountX - 1,
                                unit = OperationUnit.CHARACTERS,
                                scope = OperationScope.BEFORE_CURSOR,
                            )
                        } else {
                            // Forward select
                            editorInstance.setSelectionSurrounding(
                                n = -event.absUnitCountX - 1,
                                unit = OperationUnit.CHARACTERS,
                                scope = OperationScope.AFTER_CURSOR,
                            )
                        }
                    }
                    true
                }
                SwipeAction.DELETE_WORDS_PRECISELY, SwipeAction.SELECT_WORDS_PRECISELY -> {
                    if (abs(event.relUnitCountX) > 0) {
                        inputFeedbackController?.gestureMovingSwipe(TextKeyData.DELETE)
                    }
                    val activeSelection = editorInstance.activeContent.selection
                    if (activeSelection.isValid) {
                        if (!inputEventDispatcher.isPressed(KeyCode.SHIFT)) {
                            // Backward select
                            editorInstance.setSelectionSurrounding(
                                n = -event.absUnitCountX / 2 - 1,
                                unit = OperationUnit.WORDS,
                                scope = OperationScope.BEFORE_CURSOR,
                            )
                        } else {
                            // Forward select
                            editorInstance.setSelectionSurrounding(
                                n = -event.absUnitCountX / 2 - 1,
                                unit = OperationUnit.WORDS,
                                scope = OperationScope.AFTER_CURSOR,
                            )
                        }
                    }
                    true
                }
                else -> false
            }
            SwipeGesture.Type.TOUCH_UP -> {
                if (event.direction == SwipeGesture.Direction.LEFT &&
                    prefs.gestures.deleteKeySwipeLeft.get() == SwipeAction.DELETE_WORD
                ) {
                    keyboardManager.executeSwipeAction(prefs.gestures.deleteKeySwipeLeft.get())
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun handleSpaceSwipe(event: SwipeGesture.Event): Boolean {
        val pointer = pointerMap.findById(event.pointerId) ?: return false

        return when (event.type) {
            SwipeGesture.Type.TOUCH_MOVE -> when (event.direction) {
                SwipeGesture.Direction.LEFT -> {
                    val action = prefs.gestures.spaceBarSwipeLeft.get()
                    if (action == SwipeAction.MOVE_CURSOR_LEFT) {
                        abs(event.relUnitCountX).let {
                            val count = if (!pointer.hasTriggeredGestureMove) it - 1 else it
                            if (count > 0) {
                                inputFeedbackController?.gestureMovingSwipe(TextKeyData.SPACE)
                                if (!pointer.hasTriggeredMassSelection) {
                                    pointer.hasTriggeredMassSelection = true
                                    editorInstance.massSelection.begin()
                                }
                                keyboardManager.handleArrow(KeyCode.ARROW_LEFT, count)
                            }
                        }
                        true
                    } else {
                        action != SwipeAction.NO_ACTION
                    }
                }
                SwipeGesture.Direction.RIGHT -> {
                    val action = prefs.gestures.spaceBarSwipeRight.get()
                    if (action == SwipeAction.MOVE_CURSOR_RIGHT) {
                        abs(event.relUnitCountX).let {
                            val count = if (!pointer.hasTriggeredGestureMove) it - 1 else it
                            if (count > 0) {
                                inputFeedbackController?.gestureMovingSwipe(TextKeyData.SPACE)
                                if (!pointer.hasTriggeredMassSelection) {
                                    pointer.hasTriggeredMassSelection = true
                                    editorInstance.massSelection.begin()
                                }
                                keyboardManager.handleArrow(KeyCode.ARROW_RIGHT, count)
                            }
                        }
                        true
                    } else {
                        action != SwipeAction.NO_ACTION
                    }
                }
                else -> false
            }
            SwipeGesture.Type.TOUCH_UP -> when (event.direction) {
                SwipeGesture.Direction.LEFT -> {
                    prefs.gestures.spaceBarSwipeLeft.get().let {
                        when {
                            it == SwipeAction.NO_ACTION -> {
                                false
                            }
                            it != SwipeAction.MOVE_CURSOR_LEFT -> {
                                keyboardManager.executeSwipeAction(it)
                                true
                            }
                            else -> {
                                false
                            }
                        }
                    }
                }
                SwipeGesture.Direction.RIGHT -> {
                    prefs.gestures.spaceBarSwipeRight.get().let {
                        when {
                            it == SwipeAction.NO_ACTION -> {
                                false
                            }
                            it != SwipeAction.MOVE_CURSOR_RIGHT -> {
                                keyboardManager.executeSwipeAction(it)
                                true
                            }
                            else -> {
                                false
                            }
                        }
                    }
                }
                else -> {
                    if (event.absUnitCountY < -6) {
                        keyboardManager.executeSwipeAction(prefs.gestures.spaceBarSwipeUp.get())
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
        if (isGlideEnabled) {
            glideDataForDrawing.add(point to System.currentTimeMillis())
        }
    }

    override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
        onGlideCancelled()
    }

    override fun onGlideCancelled() {
        if (prefs.glide.showTrail.get()) {
            fadingGlide.clear()
            fadingGlide.addAll(glideDataForDrawing)

            val animator = ValueAnimator.ofFloat(20.0f, 0.0f)
            animator.interpolator = AccelerateInterpolator()
            animator.duration = prefs.glide.trailDuration.get().toLong()
            animator.addUpdateListener {
                fadingGlideRadius = it.animatedValue as Float
            }
            animator.start()

            glideDataForDrawing.clear()
            isGliding = false
        }
    }

    fun drawGlideTrail(
        drawScope: ContentDrawScope,
        gestureData: MutableList<Pair<GlideTypingGesture.Detector.Position, Long>>,
        targetDist: Float,
        initialRadius: Float,
        radiusReductionFactor: Float,
        color: Color,
    ) {
        var radius = initialRadius
        var drawnPoints = 0
        var prevX = gestureData.lastOrNull()?.first?.x ?: 0.0f
        var prevY = gestureData.lastOrNull()?.first?.y ?: 0.0f
        val time = System.currentTimeMillis()

        outer@ for (i in gestureData.size - 1 downTo 1) {
            if (time - gestureData[i - 1].second > prefs.glide.trailDuration.get()) break

            val dx = prevX - gestureData[i - 1].first.x
            val dy = prevY - gestureData[i - 1].first.y
            val dist = sqrt(dx * dx + dy * dy)

            val numPoints = (dist / targetDist).toInt()
            for (j in 0 until numPoints) {
                radius *= radiusReductionFactor
                val intermediateX =
                    gestureData[i].first.x * (1 - j.toFloat() / numPoints) + gestureData[i - 1].first.x * (j.toFloat() / numPoints)
                val intermediateY =
                    gestureData[i].first.y * (1 - j.toFloat() / numPoints) + gestureData[i - 1].first.y * (j.toFloat() / numPoints)
                drawScope.drawCircle(color, radius, center = Offset(intermediateX, intermediateY))
                drawnPoints += 1
                prevX = intermediateX
                prevY = intermediateY
            }
        }
    }

    private class TouchPointer : Pointer() {
        var initialKey: TextKey? = null
        var activeKey: TextKey? = null
        var hasTriggeredGestureMove: Boolean = false
        var hasTriggeredLongPress: Boolean = false
        var hasTriggeredMassSelection: Boolean = false
        var pressedKeyInfo: InputEventDispatcher.PressedKeyInfo? = null

        override fun reset() {
            super.reset()
            initialKey = null
            activeKey = null
            hasTriggeredGestureMove = false
            hasTriggeredLongPress = false
            hasTriggeredMassSelection = false
            pressedKeyInfo = null
        }

        override fun toString(): String {
            return "${TouchPointer::class.simpleName} { id=$id, index=$index, initialKey=$initialKey, activeKey=$activeKey }"
        }
    }
}
