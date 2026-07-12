package com.wordtaker.keyboard.wordtaker.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordtaker.keyboard.editorInstance
import com.wordtaker.keyboard.ime.theme.FlorisImeUi
import com.wordtaker.lib.snygg.ui.rememberSnyggThemeQuery

/**
 * WordTaker: horizontal, scrollable quick-symbol strip shown ABOVE the key rows on the
 * symbols and numeric keyboards. Tapping a chip commits that symbol directly. This mirrors
 * the target design's quick-punctuation strip and is intentionally minimal — it bypasses the
 * pinyin candidate pipeline (fine for standalone punctuation) by committing text verbatim.
 */
private val QUICK_SYMBOLS = listOf(
    "#", "%", "&", "+", "……", "《", "》", "「", "」", "×",
)

private const val STRIP_HEIGHT_DP = 38

// P1-7: width of the scroll-affordance fade masks at both ends of the strip.
private const val EDGE_FADE_WIDTH_DP = 12

@Composable
fun QuickSymbolStrip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val editorInstance by context.editorInstance()
    val isDark = isSystemInDarkTheme()
    val chipBg = if (isDark) Color(0xFF3A3A3A) else Color.White
    val chipText = if (isDark) Color(0xFFEDEDED) else Color(0xFF3C4043)
    // P1-7: fade the clipped edges into the keyboard background so the horizontal scroll
    // affordance is obvious instead of a hard cut at the screen edge. The mask color comes
    // from the active Snygg window background so it tracks the theme.
    val windowStyle = rememberSnyggThemeQuery(FlorisImeUi.Window.elementName)
    val fadeColor = windowStyle.background(default = if (isDark) Color(0xFF1C1C1E) else Color(0xFFECECEC))
    val scrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxWidth().height(STRIP_HEIGHT_DP.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(STRIP_HEIGHT_DP.dp)
                .horizontalScroll(scrollState)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (symbol in QUICK_SYMBOLS) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = 44.dp, height = 30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(chipBg)
                        .clickable { editorInstance.commitText(symbol) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = symbol,
                        color = chipText,
                        fontSize = 16.sp,
                    )
                }
            }
        }
        if (scrollState.canScrollBackward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(width = EDGE_FADE_WIDTH_DP.dp, height = STRIP_HEIGHT_DP.dp)
                    .background(Brush.horizontalGradient(listOf(fadeColor, fadeColor.copy(alpha = 0f)))),
            )
        }
        if (scrollState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(width = EDGE_FADE_WIDTH_DP.dp, height = STRIP_HEIGHT_DP.dp)
                    .background(Brush.horizontalGradient(listOf(fadeColor.copy(alpha = 0f), fadeColor))),
            )
        }
    }
}
