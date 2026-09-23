package com.wordtaker.keyboard.wordtaker.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordtaker.keyboard.app.FlorisPreferenceStore
import com.wordtaker.keyboard.ime.ImeUiMode
import com.wordtaker.keyboard.ime.keyboard.FlorisImeSizing
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.wordtaker.account.AccountResult
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.speech.ParaformerModelSettingsSection
import com.wordtaker.keyboard.wordtaker.ui.KEYBOARD_STYLE_QWERTY
import com.wordtaker.keyboard.wordtaker.ui.KEYBOARD_STYLE_T9
import com.wordtaker.keyboard.wordtaker.ui.ROLE_GAOEQ
import com.wordtaker.keyboard.wordtaker.ui.ROLE_NORMAL
import com.wordtaker.keyboard.wordtaker.ui.ROLE_VIBECODING
import com.wordtaker.keyboard.wordtaker.ui.WordTakerAccountActivity
import dev.patrickgold.jetpref.datastore.model.observeAsState
import kotlinx.coroutines.launch

/**
 * In-IME settings panel. Rendered inside the keyboard window (ImeUiMode.SETTINGS) —
 * never launches the host settings Activity. Mirrors the in-IME history view's
 * mechanism: the toolbar's settings icon toggles this mode in place.
 *
 * Deliberately极致简约: a single "设置" title with a back arrow, then only the
 * essential controls — AI role and prompt tone — with NO descriptive sub-text,
 * NO "关于", and NO "极简模式" item.
 */
@Composable
fun ImeSettingsLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val repo = remember { AppGraph.settingsRepository }
    val scope = rememberCoroutineScope()
    val state by repo.settings.collectAsState(initial = SettingsState())
    val prefs by FlorisPreferenceStore
    // Reactive current keyboard style. Writing it makes SubtypeManager re-seed the active
    // subtype, so the keyboard layout (全拼/九宫格) changes in place — no Activity, no navigation.
    val keyboardStyle by prefs.internal.selectedKeyboardStyle.observeAsState()
    val keyboardHapticEnabled by prefs.inputFeedback.hapticEnabled.observeAsState()

    fun backToKeyboard() {
        keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
    }

    fun openHistory() {
        keyboardManager.activeState.imeUiMode = ImeUiMode.HISTORY
    }

    // 账户与额度子页（补齐用户口径）: 面板内切子页展示只读额度 —— IME 无法给自身输入框打字，
    // 所以登录表单不内嵌，只有点「登录/管理账户」才真正跳转 WordTakerAccountActivity。
    var showAccountPage by rememberSaveable { mutableStateOf(false) }

    // item6: 整体配色对齐微信「+」面板 —— 外层浅灰底 + 卡片白底，分组清爽统一。
    // 浅色：外层浅灰 #F2F3F5、卡片纯白；深色：与键盘深灰协调。内容/功能不变，仅调背景与卡片观感。
    val dark = isSystemInDarkTheme()
    val panelBg = if (dark) Color(0xFF1C1D1F) else Color(0xFFF2F3F5)
    val cardBg = if (dark) Color(0xFF2A2B2E) else Color.White
    val titleColor = if (dark) Color(0xFFE3E3E6) else Color(0xFF1B1B1F)
    val rowTextColor = if (dark) Color(0xFFE3E3E6) else Color(0xFF1B1B1F)

    // ImeWindow centers the unchanged 44dp toolbar inside the same dynamic strip as TEXT.
    // This panel therefore matches only the keyboard body height, keeping total IME height fixed.
    val panelHeight = FlorisImeSizing.keyboardUiHeight()

    if (showAccountPage) {
        ImeAccountSubPage(
            modifier = modifier,
            panelHeight = panelHeight,
            panelBg = panelBg,
            cardBg = cardBg,
            titleColor = titleColor,
            rowTextColor = rowTextColor,
            onBack = { showAccountPage = false },
            onOpenFullAccount = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            context,
                            WordTakerAccountActivity::class.java,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                backToKeyboard()
            },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(panelHeight)
            .background(panelBg),
    ) {
        // Header: back arrow + minimal "设置" title.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { backToKeyboard() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回键盘",
                    tint = titleColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = "设置",
                color = titleColor,
                fontSize = 16.sp,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            // 历史记录 entry — moved to the TOP of settings (#5), above AI role.
            // Opens the in-IME history view in place (#12).
            SectionLabel("历史记录")
            SettingsCard(cardBg) {
                SelectRow(
                    title = "查看历史记录",
                    selected = false,
                    textColor = rowTextColor,
                    onClick = { openHistory() },
                )
            }

            Spacer(Modifier.height(12.dp))

            // 账户与额度 entry — 面板内子页展示只读额度（不跳 Activity）；登录才跳转（见子页）。
            SectionLabel("账户")
            SettingsCard(cardBg) {
                SelectRow(
                    title = "账户与额度",
                    selected = false,
                    textColor = rowTextColor,
                    onClick = { showAccountPage = true },
                )
            }

            Spacer(Modifier.height(12.dp))

            // AI role — names only, no sub-text. Switching takes effect immediately.
            SectionLabel("AI 角色")
            SettingsCard(cardBg) {
                SelectRow(
                    title = "常规",
                    selected = state.role == ROLE_NORMAL,
                    textColor = rowTextColor,
                    onClick = { scope.launch { repo.setRole(ROLE_NORMAL) } },
                )
                SelectRow(
                    title = "高情商改写",
                    selected = state.role == ROLE_GAOEQ,
                    textColor = rowTextColor,
                    onClick = { scope.launch { repo.setRole(ROLE_GAOEQ) } },
                )
                SelectRow(
                    title = "VibeCoding专用",
                    selected = state.role == ROLE_VIBECODING,
                    textColor = rowTextColor,
                    onClick = { scope.launch { repo.setRole(ROLE_VIBECODING) } },
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionLabel("语音识别")
            SettingsCard(cardBg) {
                ParaformerModelSettingsSection(
                    manager = AppGraph.paraformerModelManager,
                )
                ToggleRow(
                    title = "仅本地处理（关闭云端润色）",
                    checked = state.localRecognitionOnly,
                    textColor = rowTextColor,
                    onCheckedChange = { scope.launch { repo.setLocalRecognitionOnly(it) } },
                )
            }

            Spacer(Modifier.height(12.dp))

            // Prompt tone — on/off toggle only (#8). The style picker was removed; the tone
            // always plays "喵" (handled in ToneController). No sub-text.
            SectionLabel("提示音")
            SettingsCard(cardBg) {
                ToggleRow(
                    title = "开启提示音",
                    checked = state.tone,
                    textColor = rowTextColor,
                    onCheckedChange = { scope.launch { repo.setTone(it) } },
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionLabel("键盘反馈")
            SettingsCard(cardBg) {
                ToggleRow(
                    title = "键盘震动反馈",
                    checked = keyboardHapticEnabled,
                    textColor = rowTextColor,
                    onCheckedChange = {
                        scope.launch { prefs.inputFeedback.hapticEnabled.set(it) }
                    },
                )
            }

            Spacer(Modifier.height(12.dp))

            // 键盘管理只展示本应用支持用户选择的全拼/九宫格入口。
            SectionLabel("键盘管理")
            SettingsCard(cardBg) {
                SelectRow(
                    title = "全拼（全键盘拼音）",
                    selected = keyboardStyle == KEYBOARD_STYLE_QWERTY,
                    textColor = rowTextColor,
                    onClick = { scope.launch { prefs.internal.selectedKeyboardStyle.set(KEYBOARD_STYLE_QWERTY) } },
                )
                SelectRow(
                    title = "九宫格（T9拼音）",
                    selected = keyboardStyle == KEYBOARD_STYLE_T9,
                    textColor = rowTextColor,
                    onClick = { scope.launch { prefs.internal.selectedKeyboardStyle.set(KEYBOARD_STYLE_T9) } },
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 「账户与额度」面板内子页 —— 只读展示：云端剩余额度 + 刷新、登录状态一行。
 * 键盘无法给自身设置面板的输入框打字，因此登录表单不内嵌；只有点击「登录/管理账户」
 * 才会真正 startActivity 到 [WordTakerAccountActivity]（那里才有可输入的邮箱/验证码框）。
 * 复用与主设置面板相同的 panelHeight，保证顶边像素在各态之间恒等、切换零跳动。
 */
@Composable
private fun ImeAccountSubPage(
    modifier: Modifier,
    panelHeight: Dp,
    panelBg: Color,
    cardBg: Color,
    titleColor: Color,
    rowTextColor: Color,
    onBack: () -> Unit,
    onOpenFullAccount: () -> Unit,
) {
    val repo = remember { AppGraph.accountRepository }
    val scope = rememberCoroutineScope()
    val state by repo.state.collectAsState()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    fun refresh() {
        refreshing = true
        scope.launch {
            when (val r = repo.refreshQuota()) {
                is AccountResult.Ok -> statusMessage = null
                is AccountResult.Err -> statusMessage = r.message
            }
            refreshing = false
        }
    }

    // 进入子页即拉一次额度（匿名可用，无需登录）。
    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(panelHeight)
            .background(panelBg),
    ) {
        // Header: back arrow + "账户与额度" title — same shape as main settings header,
        // so the top edge stays pixel-identical across states.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回设置",
                    tint = titleColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = "账户与额度",
                color = titleColor,
                fontSize = 16.sp,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            SectionLabel("云端额度")
            SettingsCard(cardBg) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.quota?.cloudRemaining?.let { "$it 字" } ?: "— —",
                            color = rowTextColor,
                            fontSize = 20.sp,
                        )
                        val detail = state.quota?.let { q ->
                            listOfNotNull(
                                q.deviceRemaining?.let { "本机赠送 $it" },
                                q.accountRemaining?.let { "账号余额 $it" },
                            ).joinToString(" · ").ifBlank { null }
                        }
                        Text(
                            text = detail ?: (statusMessage ?: "剩余可用云端润色字数"),
                            color = rowTextColor.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        text = if (refreshing) "刷新中…" else "刷新",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !refreshing) { refresh() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionLabel("登录状态")
            SettingsCard(cardBg) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.loggedIn) {
                            state.account?.nickname
                                ?: state.account?.email
                                ?: state.account?.phone
                                ?: "已登录"
                        } else {
                            "未登录"
                        },
                        color = rowTextColor,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 唯一必要的跳转：登录须要真实输入框（邮箱/验证码），键盘自身面板无法承载。
            SettingsCard(cardBg) {
                SelectRow(
                    title = if (state.loggedIn) "管理账户" else "登录 / 管理账户",
                    selected = false,
                    textColor = MaterialTheme.colorScheme.primary,
                    onClick = onOpenFullAccount,
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/** White rounded card grouping a section's rows — mirrors WeChat「+」面板 card surfaces. */
@Composable
private fun SettingsCard(cardBg: Color, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun SelectRow(title: String, selected: Boolean, textColor: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = textColor,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, textColor: Color, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = textColor,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
