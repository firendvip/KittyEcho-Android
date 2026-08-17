package com.wordtaker.keyboard.wordtaker.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wordtaker.keyboard.FlorisImeService
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.ime.ImeUiMode
import com.wordtaker.keyboard.ime.theme.LocalFlorisImeThemeIsNight
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.wordtaker.ui.DoubaoImeSkin

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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(TOOLBAR_HEIGHT_DP.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = toolbarCatHorizontalInsetDp(maxWidth.value).dp),
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
    val dark = LocalFlorisImeThemeIsNight.current
    val palette = DoubaoImeSkin.palette(dark)
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(ToolbarCatAvatarSpec.touchSizeDp.dp)
            .clickable(
                interactionSource = noRipple,
                indication = null,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ToolbarCatAvatarSpec.backgroundSizeDp.dp)
                .clip(CircleShape)
                .background(Color(palette.keyArgb)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_brand_cat_bare),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(ToolbarCatAvatarSpec.glyphSizeDp.dp),
            )
        }
    }
}

internal object ToolbarCatAvatarSpec {
    const val touchSizeDp = 44
    const val backgroundSizeDp = 34
    const val glyphSizeDp = 30
}

internal data class ToolbarCatAnchor(
    val centerXDp: Float,
    val centerYDp: Float,
)

internal fun toolbarCatHorizontalInsetDp(widthDp: Float): Int =
    if (widthDp >= TOOLBAR_WIDE_MIN_WIDTH_DP) 6 else 2

internal fun toolbarCatAnchor(widthDp: Float, slotHeightDp: Float): ToolbarCatAnchor =
    ToolbarCatAnchor(
        centerXDp = toolbarCatHorizontalInsetDp(widthDp) +
            ToolbarCatAvatarSpec.touchSizeDp / 2f,
        centerYDp = slotHeightDp / 2f,
    )

internal val WT_TOOLBAR_ICON_TINT = Color(0xFF3C4043)
// P2-303 深色模式配色：圆底与录音"取消"药丸同档深灰，图标转浅灰（与 TalkPill 前景一致）。
internal val WT_TOOLBAR_CIRCLE_DARK = Color(0xFF2E3033)
internal val WT_TOOLBAR_ICON_TINT_DARK = Color(0xFFBFC3C7)
// item3: 统一各子界面总高需要拿到工具栏高度 (ImeSettingsLayout/ImeHistoryLayout 引用)。
const val TOOLBAR_HEIGHT_DP = 44
// item8: 白色圆底"抱紧"图标 —— 圆只比图标大 6dp (26 vs 20)，与顶条圆钮尺寸一致。
const val WT_TOOLBAR_BUTTON_SIZE_DP = 26
const val WT_TOOLBAR_ICON_SIZE_DP = 20
private const val TOOLBAR_WIDE_MIN_WIDTH_DP = 380f
private const val BUTTON_GAP_DP = 10
