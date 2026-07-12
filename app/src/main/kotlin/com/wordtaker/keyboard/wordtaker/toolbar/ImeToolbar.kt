package com.wordtaker.keyboard.wordtaker.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
        // Leftmost: settings entry, now shown as OUR cat-head avatar. Opens the in-IME
        // settings panel in place (no Activity). Tapping again while open returns to
        // keyboard. (替换为小猫头像，并保留设置功能。)
        ToolbarCatButton(
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
    // P2-303: 深色模式下白色圆底刺眼、与周边不统一 —— 圆底/图标随系统深浅取色。
    val dark = isSystemInDarkTheme()
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(WT_TOOLBAR_BUTTON_SIZE_DP.dp)
            .clip(CircleShape)
            .background(if (dark) WT_TOOLBAR_CIRCLE_DARK else Color.White, CircleShape),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(WT_TOOLBAR_ICON_SIZE_DP.dp),
            tint = if (dark) WT_TOOLBAR_ICON_TINT_DARK else WT_TOOLBAR_ICON_TINT,
        )
    }
}

/**
 * Same 28dp circular white slot as [ToolbarIconButton], but the glyph is OUR cat
 * head ([R.drawable.ic_brand_cat]) instead of a tinted line icon. The cat head is
 * drawn smaller than the circle (inner padding) and the whole thing is clipped to a
 * clean white CIRCLE, so it reads as a tiny round avatar that sits exactly where the
 * settings gear used to. onClick is preserved (opens settings) — 替换为小猫头像，并保留
 * 设置功能(链接设置).
 */
@Composable
fun ToolbarCatButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(WT_TOOLBAR_BUTTON_SIZE_DP.dp)
            .clip(CircleShape)
            // P2-303: 同 ToolbarIconButton，深色模式用深色圆底（猫头自身彩色不变）。
            .background(
                if (isSystemInDarkTheme()) WT_TOOLBAR_CIRCLE_DARK else Color.White,
                CircleShape,
            ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_brand_cat),
            contentDescription = contentDescription,
            // No tint: keep the cat's own colours. Inner padding shrinks the head so
            // it doesn't touch the circle edge.
            tint = Color.Unspecified,
            modifier = Modifier
                .size(WT_TOOLBAR_BUTTON_SIZE_DP.dp)
                .clip(CircleShape)
                .padding(WT_TOOLBAR_CAT_INSET_DP.dp),
        )
    }
}

internal val WT_TOOLBAR_ICON_TINT = Color(0xFF3C4043)
// P2-303 深色模式配色：圆底与录音"取消"药丸同档深灰，图标转浅灰（与 TalkPill 前景一致）。
internal val WT_TOOLBAR_CIRCLE_DARK = Color(0xFF2E3033)
internal val WT_TOOLBAR_ICON_TINT_DARK = Color(0xFFBFC3C7)
private const val TOOLBAR_HEIGHT_DP = 44
// item(adjust2): 白色圆底"抱紧"图标 —— 圆只比图标每边大 ~3dp (27 vs 21)，更精致小巧。
const val WT_TOOLBAR_BUTTON_SIZE_DP = 27
const val WT_TOOLBAR_ICON_SIZE_DP = 21
// item: 小猫头像在 28dp 白圆里的内缩，让猫头不贴圆边、视觉更小巧。
const val WT_TOOLBAR_CAT_INSET_DP = 3
private const val BUTTON_GAP_DP = 10
