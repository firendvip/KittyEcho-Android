/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
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

package com.wordtaker.keyboard.ime.smartbar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wordtaker.keyboard.app.FlorisPreferenceStore
import com.wordtaker.keyboard.ime.nlp.ClipboardSuggestionCandidate
import com.wordtaker.keyboard.ime.nlp.SuggestionCandidate
import com.wordtaker.keyboard.ime.theme.FlorisImeUi
import com.wordtaker.keyboard.keyboardManager
import com.wordtaker.keyboard.nlpManager
import com.wordtaker.keyboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import com.wordtaker.lib.compose.conditional
import com.wordtaker.lib.compose.florisHorizontalScroll
import com.wordtaker.lib.snygg.SnyggSelector
import com.wordtaker.lib.snygg.ui.SnyggBox
import com.wordtaker.lib.snygg.ui.SnyggColumn
import com.wordtaker.lib.snygg.ui.SnyggIcon
import com.wordtaker.lib.snygg.ui.SnyggRow
import com.wordtaker.lib.snygg.ui.SnyggSpacer
import com.wordtaker.lib.snygg.ui.SnyggText

val CandidatesRowScrollbarHeight = 2.dp

internal object CandidateSelectionVisualSpec {
    /**
     * Theme line box (24dp) + vertical padding (4dp) + vertical margin (2dp).
     * Keeping this outer height fixed makes the white selection card independent of text length
     * or additional labels while the outer candidate box remains the full-strip target.
     */
    const val outerHeightDp = 30f
}

internal fun shouldShowCandidateSecondaryText(
    enabled: Boolean,
    providerId: String?,
    hiddenProviderIds: Set<String>,
): Boolean = enabled && providerId !in hiddenProviderIds

@Composable
fun CandidatesRow(
    modifier: Modifier = Modifier,
    showSecondaryText: Boolean = true,
    hiddenSecondaryTextProviderIds: Set<String> = emptySet(),
    candidatesOverride: List<SuggestionCandidate>? = null,
) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val nlpManager by context.nlpManager()
    val subtypeManager by context.subtypeManager()

    val displayMode by prefs.suggestion.displayMode.collectAsState()
    val liveCandidates by nlpManager.activeCandidatesFlow.collectAsState()
    val touchSnapshot = remember { CandidateTouchSnapshot<List<SuggestionCandidate>>() }
    val currentCandidates = candidatesOverride ?: liveCandidates
    val candidates = touchSnapshot.currentOr(currentCandidates)

    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier
            .fillMaxSize()
            .conditional(displayMode == CandidatesDisplayMode.DYNAMIC_SCROLLABLE && candidates.size > 1) {
                florisHorizontalScroll(scrollbarHeight = CandidatesRowScrollbarHeight)
            },
        horizontalArrangement = if (candidates.size > 1) {
            Arrangement.Start
        } else {
            Arrangement.Center
        },
    ) {
        if (candidates.isNotEmpty()) {
            val candidateModifier = if (candidates.size == 1) {
                Modifier
                    .fillMaxHeight()
                    .weight(1f, fill = false)
            } else {
                Modifier
                    .fillMaxHeight()
                    .conditional(displayMode == CandidatesDisplayMode.CLASSIC) {
                        weight(1f)
                    }
                    .conditional(displayMode != CandidatesDisplayMode.CLASSIC) {
                        wrapContentWidth().widthIn(max = 160.dp)
                    }
            }
            val list = when (displayMode) {
                CandidatesDisplayMode.CLASSIC -> candidates.subList(0, 3.coerceAtMost(candidates.size))
                else -> candidates
            }
            for ((n, candidate) in list.withIndex()) {
                if (n > 0) {
                    SnyggSpacer(
                        elementName = FlorisImeUi.SmartbarCandidateSpacer.elementName,
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight(0.6f)
                            .align(Alignment.CenterVertically),
                    )
                }
                CandidateItem(
                    modifier = candidateModifier,
                    candidate = candidate,
                    isFirst = n == 0,
                    showSecondaryText = shouldShowCandidateSecondaryText(
                        enabled = showSecondaryText,
                        providerId = candidate.sourceProvider?.providerId,
                        hiddenProviderIds = hiddenSecondaryTextProviderIds,
                    ),
                    onTouchStart = {
                        touchSnapshot.begin(candidates.toList())
                    },
                    onTouchEnd = { token ->
                        touchSnapshot.end(token)
                    },
                    onClick = { touchedCandidate ->
                        keyboardManager.commitCandidate(touchedCandidate)
                    },
                    onLongPress = { touchedCandidate ->
                        if (touchedCandidate.isEligibleForUserRemoval) {
                            nlpManager.removeSuggestion(subtypeManager.activeSubtype, touchedCandidate)
                        } else {
                            false
                        }
                    },
                    longPressDelay = prefs.keyboard.longPressDelay.get().toLong(),
                )
            }
        }
    }
}

@Composable
private fun CandidateItem(
    candidate: SuggestionCandidate,
    modifier: Modifier = Modifier,
    isFirst: Boolean = false,
    showSecondaryText: Boolean = true,
    onTouchStart: () -> Any,
    onTouchEnd: (Any) -> Unit,
    onClick: (SuggestionCandidate) -> Unit = { },
    onLongPress: (SuggestionCandidate) -> Boolean = { false },
    longPressDelay: Long,
) {
    var isPressed by remember { mutableStateOf(false) }
    val latestCandidate by rememberUpdatedState(candidate)
    val latestOnTouchStart by rememberUpdatedState(onTouchStart)
    val latestOnTouchEnd by rememberUpdatedState(onTouchEnd)
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    val latestLongPressDelay by rememberUpdatedState(longPressDelay)

    val elementName = if (candidate is ClipboardSuggestionCandidate) {
        FlorisImeUi.SmartbarCandidateClip
    } else {
        FlorisImeUi.SmartbarCandidateWord
    }.elementName
    // "first" marks the top-ranked candidate for the reference-style white/blue selection card.
    // It is purely visual; auto-commit behavior remains untouched.
    val attributes = mapOf(
        "auto-commit" to if (candidate.isEligibleForAutoCommit) 1 else 0,
        "first" to if (isFirst) 1 else 0,
    )
    val selector = if (isPressed) SnyggSelector.PRESSED else SnyggSelector.NONE

    // The outer box owns the stable full-strip hit target. The styled row has a fixed visual
    // height, so single characters, phrases, and optional secondary labels can only affect width.
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    var touchToken: Any? = null
                    try {
                        val down = awaitFirstDown()
                        val touchedCandidate = latestCandidate
                        touchToken = latestOnTouchStart()
                        isPressed = true
                        if (down.pressed != down.previousPressed) down.consume()
                        var upOrCancel: PointerInputChange? = null
                        try {
                            upOrCancel = withTimeout(latestLongPressDelay) {
                                waitForUpOrCancellation()
                            }
                            upOrCancel?.let { if (it.pressed != it.previousPressed) it.consume() }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            if (latestOnLongPress(touchedCandidate)) {
                                upOrCancel = null
                            }
                            waitForUpOrCancellation()?.let {
                                if (it.pressed != it.previousPressed) it.consume()
                            }
                        }
                        if (upOrCancel != null) {
                            latestOnClick(touchedCandidate)
                        }
                    } finally {
                        isPressed = false
                        touchToken?.let(latestOnTouchEnd)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SnyggRow(
            elementName = elementName,
            attributes = attributes,
            selector = selector,
            modifier = Modifier.height(CandidateSelectionVisualSpec.outerHeightDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (candidate.icon != null) {
                SnyggBox(
                    elementName = "$elementName-icon",
                    attributes = attributes,
                    selector = selector,
                ) {
                    SnyggIcon(imageVector = candidate.icon!!)
                }
            }
            SnyggColumn(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SnyggText(
                    elementName = "$elementName-text",
                    attributes = attributes,
                    selector = selector,
                    text = candidate.text.toString(),
                )
                if (showSecondaryText && candidate.secondaryText != null) {
                    SnyggText(
                        elementName = "$elementName-secondary-text",
                        attributes = attributes,
                        selector = selector,
                        text = candidate.secondaryText!!.toString(),
                    )
                }
            }
        }
    }
}
