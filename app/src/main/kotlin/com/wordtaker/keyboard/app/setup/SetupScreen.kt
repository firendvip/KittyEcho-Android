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

package com.wordtaker.keyboard.app.setup

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.app.FlorisPreferenceStore
import com.wordtaker.keyboard.app.LocalNavController
import com.wordtaker.keyboard.app.Routes
import com.wordtaker.keyboard.lib.compose.FlorisScreen
import com.wordtaker.keyboard.lib.util.InputMethodUtils
import com.wordtaker.lib.compose.FlorisCanvasIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Keyboard style ids persisted into prefs.internal.selectedKeyboardStyle.
private const val STYLE_QWERTY_PINYIN = "qwerty_pinyin"
private const val STYLE_T9_PINYIN = "t9_pinyin"
private const val STYLE_SHUANGPIN = "shuangpin"
private const val STYLE_WUBI = "wubi"
private const val STYLE_STROKE = "stroke"

// Brand green used for selection accents in onboarding.
private val BrandGreen = Color(0xFF07C160)

// Process-lifetime scope for persisting onboarding prefs. The composition-bound
// rememberCoroutineScope() is cancelled the instant we navigate away from the setup
// screen, which can drop the suspend prefs.set(...) write before it commits — leaving
// isImeSetUp=false and bouncing the user back to onboarding on next launch. Using a
// detached scope guarantees these short writes complete regardless of navigation.
private val setupPrefsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Three-step first-launch onboarding:
 *  Step 1 — 请选择键盘: choose Chinese keyboard style (only 全拼 + 九宫格 selectable).
 *  Step 2 — enable + switch to 弦外小猫 (two stacked single-action buttons with live detection).
 *  Step 3 — grant the microphone permission (last step; completing it finishes onboarding).
 */
@Composable
fun SetupScreen() = FlorisScreen {
    title = "弦外小猫"
    navigationIconVisible = false
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore

    var step by remember { mutableStateOf(0) }

    val finishSetup: () -> Unit = {
        // Persist on a process-lifetime scope so the write commits even though navigating
        // away immediately tears down this composition (and its rememberCoroutineScope).
        setupPrefsScope.launch {
            prefs.internal.isImeSetUp.set(true)
            // The new onboarding no longer includes a notification-permission step.
            // FlorisAppActivity resets isImeSetUp back to false on every cold start while
            // notificationPermissionState is still NOT_SET (it expected the old setup flow to
            // resolve it), which would bounce the user back into onboarding forever. Resolve it
            // here so onboarding stays completed.
            if (prefs.internal.notificationPermissionState.get() == NotificationPermissionState.NOT_SET) {
                prefs.internal.notificationPermissionState.set(NotificationPermissionState.DENIED)
            }
        }
        navController.navigate(Routes.Settings.MinimalSettings) {
            popUpTo(Routes.Setup.Screen) {
                inclusive = true
            }
        }
    }

    content {
        when (step) {
            0 -> ChooseKeyboardStep(
                onNext = { selectedStyle ->
                    setupPrefsScope.launch { prefs.internal.selectedKeyboardStyle.set(selectedStyle) }
                    step = 1
                },
            )
            1 -> EnableSwitchStep(
                onContext = context,
                // Enable/switch is now the middle step; the mic permission is the last step.
                onFinish = { step = 2 },
            )
            else -> MicPermissionStep(
                onFinish = finishSetup,
            )
        }
    }
}

// region Step 1: choose keyboard style ------------------------------------------------------------

private data class KeyboardStyleOption(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val preview: KeyboardPreviewKind,
)

private enum class KeyboardPreviewKind { QWERTY, GRID, STROKE }

@Composable
private fun ChooseKeyboardStep(
    onNext: (String) -> Unit,
) {
    val options = remember {
        // This onboarding stage only offers the two pinyin styles backed by the AOSP
        // pinyin decoder: 全键盘拼音 (全拼) and 九宫格拼音 (T9). 双拼/五笔/笔画/手写 are
        // intentionally down-lined from the selection page — their layouts/subtypes still
        // exist in code and may be re-enabled later, they are just not offered here.
        listOf(
            KeyboardStyleOption("qwerty_card", "全键盘拼音", enabled = true, KeyboardPreviewKind.QWERTY),
            KeyboardStyleOption("t9_card", "九宫格拼音", enabled = true, KeyboardPreviewKind.GRID),
        )
    }

    // Default selection = 全键盘拼音 (verified working, most stable).
    var selectedId by remember { mutableStateOf("qwerty_card") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "请选择键盘",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        // Two-column grid of six cards.
        options.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowOptions.forEach { option ->
                    Box(modifier = Modifier.weight(1f)) {
                        KeyboardStyleCard(
                            option = option,
                            selected = option.enabled && option.id == selectedId,
                            onClick = { if (option.enabled) selectedId = option.id },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val style = when (selectedId) {
                    "t9_card" -> STYLE_T9_PINYIN
                    "shuangpin" -> STYLE_SHUANGPIN
                    "wubi" -> STYLE_WUBI
                    "stroke" -> STYLE_STROKE
                    else -> STYLE_QWERTY_PINYIN
                }
                onNext(style)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
        ) {
            Text(text = "下一步", fontSize = 16.sp)
        }
    }
}

@Composable
private fun KeyboardStyleCard(
    option: KeyboardStyleOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) BrandGreen else Color(0xFFE2E2E2)
    val cardModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(Color.White)
        .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
        .clickable(enabled = option.enabled, onClick = onClick)
        .padding(10.dp)

    Box {
        Column(
            modifier = cardModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KeyboardMiniPreview(
                kind = option.preview,
                dimmed = !option.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.45f),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (option.enabled) Color(0xFF222222) else Color(0xFFAAAAAA),
                )
                Spacer(Modifier.size(6.dp))
                SelectionCircle(selected = selected, enabled = option.enabled)
            }
        }

        // "敬请期待" badge for disabled cards.
        if (!option.enabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFEDEDED))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(text = "敬请期待", fontSize = 10.sp, color = Color(0xFF999999))
            }
        }
    }
}

@Composable
private fun SelectionCircle(selected: Boolean, enabled: Boolean) {
    val ringColor = when {
        selected -> BrandGreen
        enabled -> Color(0xFFCCCCCC)
        else -> Color(0xFFDDDDDD)
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) BrandGreen else Color.Transparent)
            .border(width = 1.5.dp, color = ringColor, shape = RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** Minimal schematic keyboard preview drawn purely with Compose primitives. */
@Composable
private fun KeyboardMiniPreview(
    kind: KeyboardPreviewKind,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val surface = if (dimmed) Color(0xFFECECEC) else Color(0xFFF2F3F5)
    val keyColor = if (dimmed) Color(0xFFF7F7F7) else Color.White
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(surface)
            .padding(6.dp),
    ) {
        when (kind) {
            KeyboardPreviewKind.QWERTY -> QwertyPreview(keyColor)
            KeyboardPreviewKind.GRID -> GridPreview(keyColor)
            KeyboardPreviewKind.STROKE -> StrokePreview(keyColor)
        }
    }
}

@Composable
private fun QwertyPreview(keyColor: Color) {
    val rowCounts = listOf(10, 9, 7)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rowCounts.forEach { count ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(count) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(keyColor),
                    )
                }
            }
        }
    }
}

/** Five basic-stroke keys (一丨丿丶乙) in a single row — the 笔画 layout schematic. */
@Composable
private fun StrokePreview(keyColor: Color) {
    val strokes = listOf("一", "丨", "丿", "丶", "乙")
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            strokes.forEach { glyph ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(keyColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = glyph, fontSize = 13.sp, color = Color(0xFF555555))
                }
            }
        }
    }
}

@Composable
private fun GridPreview(keyColor: Color) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(13.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(keyColor),
                    )
                }
            }
        }
    }
}

// endregion

// region Step 2: enable + switch ------------------------------------------------------------------

@Composable
private fun EnableSwitchStep(
    onContext: android.content.Context,
    onFinish: () -> Unit,
) {
    val imeEnabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
    val imeSelected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)

    // Tracks whether the switch picker was already auto-popped once after enable so it does not
    // re-pop every recomposition while the user is in the picker.
    var autoPoppedPicker by remember { mutableStateOf(false) }
    // Tracks whether the user tapped Button 2 manually (counts toward completion).
    var tappedSwitch by remember { mutableStateOf(false) }

    // When the IME becomes enabled and returns to foreground, auto-pop the system switch picker
    // exactly once so the user can pick 弦外小猫 as the current IME.
    LaunchedEffect(imeEnabled) {
        if (imeEnabled && !autoPoppedPicker) {
            autoPoppedPicker = true
            InputMethodUtils.showImePicker(onContext)
        }
    }

    // Completion: IME is the current one, or the user has gone through enable + tapped switch.
    LaunchedEffect(imeSelected, tappedSwitch) {
        if (imeSelected || (imeEnabled && tappedSwitch)) {
            onFinish()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlorisCanvasIcon(
            modifier = Modifier.size(96.dp),
            iconId = R.drawable.ic_brand_cat,
            contentDescription = "弦外小猫",
        )
        Spacer(Modifier.height(12.dp))
        Text(text = "弦外小猫", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "KittyEcho",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        // Button 1: enable. Highlighted until enabled; then shows done.
        StepButton(
            text = if (imeEnabled) "第一步 已启用 ✓" else "第一步 启用弦外小猫输入法",
            enabled = !imeEnabled,
            highlighted = !imeEnabled,
            onClick = { InputMethodUtils.showImeEnablerActivity(onContext) },
        )

        Spacer(Modifier.height(14.dp))

        // Button 2: switch. Disabled until enabled; then highlighted.
        StepButton(
            text = "第二步 切换到弦外小猫输入法",
            enabled = imeEnabled,
            highlighted = imeEnabled,
            onClick = {
                tappedSwitch = true
                InputMethodUtils.showImePicker(onContext)
            },
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = when {
                !imeEnabled -> "请先点击第一步，在系统设置中启用「弦外小猫」。"
                else -> "已启用，请点击第二步切换为当前输入法。"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepButton(
    text: String,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlighted) BrandGreen else Color(0xFFBFE9CF),
            disabledContainerColor = Color(0xFFE5E7E9),
            disabledContentColor = Color(0xFFAAAAAA),
        ),
    ) {
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

// endregion

// region Step 3: microphone permission (last step) ------------------------------------------------

/**
 * Final onboarding step: request RECORD_AUDIO. Unlike the IME (a Service that cannot
 * request runtime permissions), this screen runs inside the host Activity, so the
 * permission can be requested directly here. Granting OR denying finishes onboarding —
 * the user can always grant it later from the keyboard's cat panel.
 */
@Composable
private fun MicPermissionStep(
    onFinish: () -> Unit,
) {
    val context = LocalContext.current

    fun hasMic(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasMic()) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
        // Either outcome completes onboarding; mic is optional and can be granted later.
        onFinish()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlorisCanvasIcon(
            modifier = Modifier.size(96.dp),
            iconId = R.drawable.ic_brand_cat,
            contentDescription = "弦外小猫",
        )
        Spacer(Modifier.height(16.dp))
        Text(text = "开启语音输入", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "弦外小猫需要麦克风权限才能进行语音输入。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        StepButton(
            text = if (granted) "已授权 ✓ 完成" else "授权麦克风权限",
            enabled = true,
            highlighted = true,
            onClick = {
                if (granted || hasMic()) {
                    onFinish()
                } else {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
    }
}

// endregion
