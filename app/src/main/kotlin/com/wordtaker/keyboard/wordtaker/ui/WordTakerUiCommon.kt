package com.wordtaker.keyboard.wordtaker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared WeChat-style palette used across the history surfaces (#4) so the standalone
 * Activity and the in-IME panel look identical and stay consistent with the keyboard /
 * settings theme. Resolves to light or dark values via [rememberWeChatPalette].
 */
data class WeChatPalette(
    val background: Color,
    val card: Color,
    val divider: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val danger: Color,
)

/** Brand green shared with the rest of the app. */
val WeChatGreen = Color(0xFF07C160)

@Composable
fun rememberWeChatPalette(): WeChatPalette {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        WeChatPalette(
            background = Color(0xFF1C1D1F),
            card = Color(0xFF2A2B2E),
            divider = Color(0xFF3A3B3E),
            accent = WeChatGreen,
            textPrimary = Color(0xFFE7E7EA),
            textSecondary = Color(0xFFB6B7BB),
            textMuted = Color(0xFF85868A),
            danger = Color(0xFFF2585B),
        )
    } else {
        WeChatPalette(
            background = Color(0xFFF2F3F5),
            card = Color.White,
            divider = Color(0xFFEDEEF0),
            accent = WeChatGreen,
            textPrimary = Color(0xFF1B1B1F),
            textSecondary = Color(0xFF6B6C70),
            textMuted = Color(0xFF9A9B9F),
            danger = Color(0xFFE5484D),
        )
    }
}

/** Material3 theme wrapper that follows the system dark/light setting. */
@Composable
fun WordTakerTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Shared top bar with a back button and a trailing slot. */
@Composable
fun TopBar(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
        if (trailing != null) {
            trailing()
        }
    }
}
