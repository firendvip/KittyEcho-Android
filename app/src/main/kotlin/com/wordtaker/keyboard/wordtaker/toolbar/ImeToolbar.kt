package com.wordtaker.keyboard.wordtaker.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    // item8: 用 Box 而非 Material3 IconButton —— IconButton 的最小触控目标(48dp)会把
    // 白色圆底放大到 ~44dp+，与源码里写的 size 不符；Box 尺寸即所见尺寸。
    val dark = isSystemInDarkTheme()
    Box(
        modifier = modifier
            .size(WT_TOOLBAR_BUTTON_SIZE_DP.dp)
            .clip(CircleShape)
            .background(if (dark) WT_TOOLBAR_CIRCLE_DARK else Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
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
    // batch3-A: 去白圈 —— 只留猫头图形 (ic_brand_cat_bare，无白色圆角矩形底)，深浅色同。
    // 外层 44dp 透明 Box 保证触控目标 ≥44dp；indication=null 避免焦点/水波纹在无底钮上
    // 显示成灰色方块。猫头直径 ≈ 0.62x38 ≈ 24dp，与原白圈时代的视觉尺寸持平。
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(WT_TOOLBAR_CAT_TOUCH_SIZE_DP.dp)
            .clickable(
                interactionSource = noRipple,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_brand_cat_bare),
            contentDescription = contentDescription,
            // No tint: keep the cat's own colours.
            tint = Color.Unspecified,
            modifier = Modifier.size(WT_TOOLBAR_CAT_GLYPH_SIZE_DP.dp),
        )
    }
}

internal val WT_TOOLBAR_ICON_TINT = Color(0xFF3C4043)
// P2-303 深色模式配色：圆底与录音"取消"药丸同档深灰，图标转浅灰（与 TalkPill 前景一致）。
internal val WT_TOOLBAR_CIRCLE_DARK = Color(0xFF2E3033)
internal val WT_TOOLBAR_ICON_TINT_DARK = Color(0xFFBFC3C7)
// item3: 统一各子界面总高需要拿到工具栏高度 (ImeSettingsLayout/ImeHistoryLayout 引用)。
const val TOOLBAR_HEIGHT_DP = 44
// item8: 白色圆底"抱紧"图标 —— 圆只比图标大 6dp (26 vs 20)，与顶条圆钮尺寸一致。
const val WT_TOOLBAR_BUTTON_SIZE_DP = 26
const val WT_TOOLBAR_ICON_SIZE_DP = 20
// batch3-A: 小猫头像去白圈 —— 44dp 不可见触控区 + 38dp 无底猫头矢量
// (头部占视口 ~62%，猫头直径 ≈ 24dp，补偿去圈后的视觉变小)。
const val WT_TOOLBAR_CAT_TOUCH_SIZE_DP = 44
const val WT_TOOLBAR_CAT_GLYPH_SIZE_DP = 38
private const val BUTTON_GAP_DP = 10
