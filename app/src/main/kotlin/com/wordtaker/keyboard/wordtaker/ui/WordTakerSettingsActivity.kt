package com.wordtaker.keyboard.wordtaker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wordtaker.keyboard.app.FlorisPreferenceStore
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import com.wordtaker.keyboard.wordtaker.settings.SettingsRepository
import com.wordtaker.keyboard.wordtaker.settings.SettingsState
import com.wordtaker.keyboard.wordtaker.speech.ParaformerModelSettingsSection
import dev.patrickgold.jetpref.datastore.model.observeAsState
import kotlinx.coroutines.launch

/**
 * Standalone WordTaker settings screen (NOT the FlorisBoard settings).
 *
 * Lets the user pick the AI role, toggle the prompt tone, toggle the minimal
 * panel mode and view the (fixed) cat skin. Backed by [SettingsRepository].
 */
class WordTakerSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)
        setContent {
            WordTakerTheme {
                WordTakerSettingsScreen(
                    repository = AppGraph.settingsRepository,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun WordTakerSettingsScreen(
    repository: SettingsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by repository.settings.collectAsStateWithLifecycle(initialValue = SettingsState())
    val prefs by FlorisPreferenceStore
    val keyboardHapticEnabled by prefs.inputFeedback.hapticEnabled.observeAsState()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            TopBar(title = "弦外小猫", onBack = onBack)

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))

                // Account & quota entry (阶段3: 登录/额度/兑换/套餐).
                SectionLabel("账户")
                val context = androidx.compose.ui.platform.LocalContext.current
                SelectableRow(
                    title = "账户与额度",
                    subtitle = "登录、云端剩余字数、兑换码、字数包",
                    selected = false,
                    onClick = {
                        context.startActivity(
                            android.content.Intent(context, WordTakerAccountActivity::class.java),
                        )
                    },
                )

                Spacer(Modifier.height(20.dp))

                // Skin (read-only — only the cat skin is available).
                SectionLabel("皮肤")
                SelectableRow(
                    title = "小猫",
                    subtitle = "当前皮肤",
                    selected = true,
                    onClick = {},
                )

                Spacer(Modifier.height(20.dp))

                // AI role.
                SectionLabel("AI 角色")
                SelectableRow(
                    title = "常规",
                    subtitle = "将你的话改写得通顺、自然、规范",
                    selected = state.role == ROLE_NORMAL,
                    onClick = { scope.launch { repository.setRole(ROLE_NORMAL) } },
                )
                Spacer(Modifier.height(8.dp))
                SelectableRow(
                    title = "高情商改写",
                    subtitle = "将你的话改写成得体、有温度的高情商表达",
                    selected = state.role == ROLE_GAOEQ,
                    onClick = { scope.launch { repository.setRole(ROLE_GAOEQ) } },
                )
                Spacer(Modifier.height(8.dp))
                SelectableRow(
                    title = "VibeCoding专用",
                    subtitle = "将你的话改写成让AI更能看懂的语言",
                    selected = state.role == ROLE_VIBECODING,
                    onClick = { scope.launch { repository.setRole(ROLE_VIBECODING) } },
                )

                Spacer(Modifier.height(20.dp))

                SectionLabel("语音识别")
                ParaformerModelSettingsSection(
                    manager = AppGraph.paraformerModelManager,
                )

                Spacer(Modifier.height(20.dp))

                // General.
                SectionLabel("通用")
                ToggleRow(
                    title = "提示音",
                    subtitle = "录音/粘贴音效",
                    checked = state.tone,
                    onCheckedChange = { scope.launch { repository.setTone(it) } },
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "键盘震动反馈",
                    subtitle = "按键时震动",
                    checked = keyboardHapticEnabled,
                    onCheckedChange = {
                        scope.launch { prefs.inputFeedback.hapticEnabled.set(it) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "极简模式",
                    subtitle = "开启后语音界面只保留小猫，不显示提示与状态文案，更专注。",
                    checked = state.minimal,
                    onCheckedChange = { scope.launch { repository.setMinimal(it) } },
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
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

@Composable
private fun SelectableRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String = "",
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}


const val ROLE_NORMAL = "normal"
const val ROLE_GAOEQ = "gaoeq"
const val ROLE_VIBECODING = "vibecoding"

// Product-selectable Chinese keyboard style ids. The internal handwriting engine remains
// available to the IME implementation, but it is intentionally not exposed by settings.
const val KEYBOARD_STYLE_QWERTY = "qwerty_pinyin"
const val KEYBOARD_STYLE_T9 = "t9_pinyin"
