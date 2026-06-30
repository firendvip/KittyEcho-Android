package com.wordtaker.keyboard.wordtaker.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.wordtaker.keyboard.FlorisImeService
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.ime.ImeUiMode
import com.wordtaker.keyboard.keyboardManager

/**
 * Minimal WeChat-style toolbar shown above the keyboard. Exactly three controls,
 * each rendered as a thin single-colour line icon on a white circular button:
 *
 *   [Settings/Cat]                              [Voice] [Collapse]
 *      ^ opens settings entry                      ^ cat   ^ hide
 *
 * No history, clipboard, keyboard, undo/redo, selection, overflow or P logo here.
 * The settings entry uses the "弦外小猫" smiling cat-head icon. Voice switches to
 * the cat voice surface; collapse hides the keyboard window.
 */
@Composable
fun ImeToolbar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()

    fun switchTo(mode: ImeUiMode) {
        keyboardManager.activeState.imeUiMode = mode
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TOOLBAR_HEIGHT_DP.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leftmost: settings entry (smiling cat head). Opens the in-IME settings
        // panel in place (no Activity). Tapping again while open returns to keyboard.
        ToolbarIconButton(
            iconRes = R.drawable.ic_wt_settings,
            contentDescription = "设置",
            onClick = {
                val current = keyboardManager.activeState.imeUiMode
                switchTo(if (current == ImeUiMode.SETTINGS) ImeUiMode.TEXT else ImeUiMode.SETTINGS)
            },
        )

        Spacer(modifier = Modifier.weight(1f))

        // Voice -> start recording on the unified单界面. The keyboard/cat surface is now
        // one merged panel (item6), so this no longer switches "屏" — it just asks the
        // panel to begin a voice recording (cat walks to centre, keys fade out).
        ToolbarIconButton(
            iconRes = R.drawable.ic_wt_voice,
            contentDescription = "语音输入",
            onClick = {
                val current = keyboardManager.activeState.imeUiMode
                if (current != ImeUiMode.TEXT && current != ImeUiMode.CAT_VOICE) {
                    switchTo(ImeUiMode.TEXT)
                }
                com.wordtaker.keyboard.wordtaker.voice.VoiceTrigger.requestStart()
            },
        )
        Spacer(modifier = Modifier.width(BUTTON_GAP_DP.dp))
        // Collapse -> hide the keyboard window.
        ToolbarIconButton(
            iconRes = R.drawable.ic_wt_collapse,
            contentDescription = "收起键盘",
            onClick = { FlorisImeService.hideUi() },
        )
    }
}

/**
 * WeChat-style white circular button with a thin single-colour line icon.
 * Public so the unified top strip (CatKeyboardLayout) reuses the exact same
 * button styling for settings / voice / collapse, keeping every类设置按钮 visually
 * identical. item4: 白色圆形底缩小到适中尺寸 (微信风) — see WT_TOOLBAR_BUTTON_SIZE_DP.
 */
@Composable
fun ToolbarIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(WT_TOOLBAR_BUTTON_SIZE_DP.dp)
            .clip(CircleShape)
            .background(Color.White, CircleShape),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(WT_TOOLBAR_ICON_SIZE_DP.dp),
            tint = WT_TOOLBAR_ICON_TINT,
        )
    }
}

internal val WT_TOOLBAR_ICON_TINT = Color(0xFF3C4043)
private const val TOOLBAR_HEIGHT_DP = 44
// item4: 微信输入法那种白圆——适中、和谐。圆 34→28dp、图标 20→17dp，比例协调更干净。
const val WT_TOOLBAR_BUTTON_SIZE_DP = 28
const val WT_TOOLBAR_ICON_SIZE_DP = 17
private const val BUTTON_GAP_DP = 10
