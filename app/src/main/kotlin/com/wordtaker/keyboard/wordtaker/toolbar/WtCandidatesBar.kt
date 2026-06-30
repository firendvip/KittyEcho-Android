package com.wordtaker.keyboard.wordtaker.toolbar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wordtaker.keyboard.ime.keyboard.FlorisImeSizing
import com.wordtaker.keyboard.ime.smartbar.CandidatesRow
import com.wordtaker.keyboard.ime.theme.FlorisImeUi
import com.wordtaker.keyboard.nlpManager
import com.wordtaker.lib.snygg.ui.SnyggBox

/**
 * Minimal candidates bar that REPLACES the full FlorisBoard [Smartbar] (candidate
 * row + action toggle row + quick-action overflow). The WeChat-style toolbar
 * ([ImeToolbar]) owns all controls now, so the only thing the text keyboard needs
 * above the keys is the pinyin candidate strip.
 *
 * When there are no candidates the bar collapses to zero height, keeping the bare
 * keyboard clean (no empty action strip). While typing pinyin, the candidate row
 * appears at the standard smartbar height directly under the toolbar.
 */
@Composable
fun WtCandidatesBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val nlpManager by context.nlpManager()
    val candidates by nlpManager.activeCandidatesFlow.collectAsState()

    if (candidates.isEmpty()) return

    SnyggBox(
        elementName = FlorisImeUi.Smartbar.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.smartbarHeight),
        allowClip = false,
    ) {
        CandidatesRow()
    }
}
