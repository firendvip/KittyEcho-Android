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

package com.wordtaker.keyboard.app.settings

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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordtaker.keyboard.BuildConfig
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.app.FlorisPreferenceStore
import com.wordtaker.keyboard.lib.compose.FlorisScreen
import com.wordtaker.keyboard.lib.util.InputMethodUtils
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.settings.SettingsState
import com.wordtaker.keyboard.wordtaker.ui.KEYBOARD_STYLE_QWERTY
import com.wordtaker.keyboard.wordtaker.ui.KEYBOARD_STYLE_T9
import com.wordtaker.keyboard.wordtaker.ui.ROLE_GAOEQ
import com.wordtaker.keyboard.wordtaker.ui.ROLE_NORMAL
import com.wordtaker.keyboard.wordtaker.ui.ROLE_VIBECODING
import com.wordtaker.lib.compose.FlorisCanvasIcon
import dev.patrickgold.jetpref.datastore.model.observeAsState
import kotlinx.coroutines.launch

/**
 * Unified minimal settings landing page. This is the single screen reached when the
 * app icon is tapped (after onboarding): brand header on top, then all settings in a
 * simple vertical layout — AI role, tone, minimal mode, skin, model status, and the
 * About section (Mac-style copy) inline at the bottom.
 */
@Composable
fun MinimalSettingsScreen() = FlorisScreen {
    title = "弦外小猫"
    navigationIconVisible = false
    previewFieldVisible = false

    val repo = AppGraph.settingsRepository

    content {
        val scope = rememberCoroutineScope()
        val state by repo.settings.collectAsState(initial = SettingsState())
        val prefs by FlorisPreferenceStore
        val keyboardStyle by prefs.internal.selectedKeyboardStyle.observeAsState()

        // item6: 整体配色对齐微信「+」面板 —— 页面浅灰底 + 分组白卡，干净统一。
        // 内容/功能完全不变，仅调背景与卡片观感。深色模式协调深灰。
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        val pageBg = if (dark) Color(0xFF1C1D1F) else Color(0xFFF2F3F5)
        val cardBg = if (dark) Color(0xFF2A2B2E) else Color.White

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(pageBg)
                .padding(horizontal = 16.dp),
        ) {
            // Brand header
            BrandHeader()

            Spacer(Modifier.height(8.dp))

            // 启用 / 切换 / 麦克风授权 entries on the home page (#6).
            SettingsCard(cardBg) {
                SetupEntriesSection()
            }

            Spacer(Modifier.height(20.dp))

            // AI role
            SectionLabel("AI 角色")
            SettingsCard(cardBg) {
                SelectableRow(
                    title = "常规",
                    subtitle = "将你的话改写得通顺、自然、规范",
                    selected = state.role == ROLE_NORMAL,
                    onClick = { scope.launch { repo.setRole(ROLE_NORMAL) } },
                )
                Spacer(Modifier.height(4.dp))
                SelectableRow(
                    title = "高情商改写",
                    subtitle = "将你的话改写成得体、有温度的高情商表达",
                    selected = state.role == ROLE_GAOEQ,
                    onClick = { scope.launch { repo.setRole(ROLE_GAOEQ) } },
                )
                Spacer(Modifier.height(4.dp))
                SelectableRow(
                    title = "VibeCoding专用",
                    subtitle = "将你的话改写成让AI更能看懂的语言",
                    selected = state.role == ROLE_VIBECODING,
                    onClick = { scope.launch { repo.setRole(ROLE_VIBECODING) } },
                )
            }

            Spacer(Modifier.height(20.dp))

            // Tone
            SettingsCard(cardBg) {
                ToggleRow(
                    title = "提示音",
                    checked = state.tone,
                    onCheckedChange = { scope.launch { repo.setTone(it) } },
                )
            }

            // #17 极简模式 hidden / #18 皮肤 hidden — minimal stays default false underneath.

            Spacer(Modifier.height(20.dp))

            // 键盘管理 — two side-by-side mini preview cards (全拼 / 九宫格), same style as the
            // onboarding step-1 cards, shrunk to fit phones side by side. Tap to switch (#4).
            SectionLabel("键盘管理")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    KeyboardManageCard(
                        name = "全拼",
                        kind = MiniPreviewKind.QWERTY,
                        selected = keyboardStyle == KEYBOARD_STYLE_QWERTY,
                        onClick = { scope.launch { prefs.internal.selectedKeyboardStyle.set(KEYBOARD_STYLE_QWERTY) } },
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    KeyboardManageCard(
                        name = "九宫格",
                        kind = MiniPreviewKind.GRID,
                        selected = keyboardStyle == KEYBOARD_STYLE_T9,
                        onClick = { scope.launch { prefs.internal.selectedKeyboardStyle.set(KEYBOARD_STYLE_T9) } },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // About — redesigned (#19/#20)
            AboutSection()

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BrandHeader() {
    Column(
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
    ) {
        FlorisCanvasIcon(
            modifier = Modifier.requiredSize(80.dp),
            iconId = R.drawable.ic_brand_cat,
            contentDescription = "弦外小猫",
        )
        // #20 subtitle — two lines, unified size & regular weight.
        Text(
            text = "弦外小猫是一款 AI 语音输入法，",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            text = "能听你弦外，说你未说。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

@Composable
private fun AboutSection() {
    SectionLabel("关于")

    Spacer(Modifier.height(16.dp))

    // 功能建议 / bug反馈: centered WeChat QR (#19).
    AboutBlockTitle("功能建议 / bug反馈")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Image(
            painter = painterResource(id = R.drawable.feedback_qr),
            contentDescription = "微信反馈二维码",
            modifier = Modifier.requiredSize(180.dp),
        )
    }

    Spacer(Modifier.height(16.dp))

    // 数据安全: two lines per spec (#19).
    AboutBlockTitle("数据安全")
    AboutLine("🔒 本地：转写文本只保存在本机，不存服务器、不用于训练。语音识别全程离线。")
    AboutLine("🗑 删除：历史记录可随时删除，即从本机彻底移除。")

    // 版本 row — moved to the very bottom of the 关于 page, below 数据安全 (#5).
    Text(
        text = "版本 ${BuildConfig.VERSION_NAME}",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}

@Composable
private fun AboutLine(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun AboutBlockTitle(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** White rounded card grouping a section's rows — mirrors WeChat「+」面板 card surfaces. */
@Composable
private fun SettingsCard(cardBg: Color, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        content()
    }
}

@Composable
private fun SelectableRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// region #6 — 启用 / 切换 / 麦克风授权 entries on the home page ----------------------------------

private val BrandGreen = Color(0xFF07C160)

@Composable
private fun SetupEntriesSection() {
    val context = LocalContext.current

    val imeEnabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
    val imeSelected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)

    fun hasMic(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    var micGranted by remember { mutableStateOf(hasMic()) }
    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted }

    SectionLabel("启用与授权")
    SetupEntryRow(
        title = "启用输入法",
        done = imeEnabled,
        onClick = { InputMethodUtils.showImeEnablerActivity(context) },
    )
    Spacer(Modifier.height(6.dp))
    SetupEntryRow(
        title = "切换输入法",
        done = imeSelected,
        onClick = { InputMethodUtils.showImePicker(context) },
    )
    Spacer(Modifier.height(6.dp))
    SetupEntryRow(
        title = "麦克风授权",
        done = micGranted,
        onClick = {
            if (hasMic()) {
                micGranted = true
            } else {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    )
}

@Composable
private fun SetupEntryRow(
    title: String,
    done: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !done, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        if (done) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = BrandGreen,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(text = "已完成", fontSize = 13.sp, color = BrandGreen)
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(BrandGreen)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text(text = "去开启", fontSize = 13.sp, color = Color.White)
            }
        }
    }
}

// endregion

// region #4 — keyboard management mini preview cards ----------------------------------------------

private enum class MiniPreviewKind { QWERTY, GRID }

@Composable
private fun KeyboardManageCard(
    name: String,
    kind: MiniPreviewKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) BrandGreen else Color(0xFFE2E2E2)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KbMiniPreview(
            kind = kind,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.55f),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF222222),
            )
            Spacer(Modifier.size(6.dp))
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) BrandGreen else Color.Transparent)
                    .border(
                        width = 1.5.dp,
                        color = if (selected) BrandGreen else Color(0xFFCCCCCC),
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}

/** Minimal schematic keyboard preview drawn with Compose primitives (mirrors onboarding). */
@Composable
private fun KbMiniPreview(kind: MiniPreviewKind, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF2F3F5))
            .padding(6.dp),
    ) {
        when (kind) {
            MiniPreviewKind.QWERTY -> KbQwertyPreview()
            MiniPreviewKind.GRID -> KbGridPreview()
        }
    }
}

@Composable
private fun KbQwertyPreview() {
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
                            .height(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

@Composable
private fun KbGridPreview() {
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
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

// endregion
