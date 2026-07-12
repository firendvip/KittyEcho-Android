/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package com.wordtaker.keyboard.ime.popup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.wordtaker.keyboard.ime.keyboard.Key
import com.wordtaker.keyboard.ime.text.key.KeyType
import com.wordtaker.keyboard.ime.text.keyboard.TextKey
import com.wordtaker.keyboard.ime.theme.FlorisImeUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import com.wordtaker.lib.snygg.SnyggQueryAttributes
import com.wordtaker.lib.snygg.SnyggSelector
import com.wordtaker.lib.snygg.ui.SnyggBox
import com.wordtaker.lib.snygg.ui.SnyggColumn
import com.wordtaker.lib.snygg.ui.SnyggIcon
import com.wordtaker.lib.snygg.ui.SnyggRow
import com.wordtaker.lib.snygg.ui.SnyggText

val GlobalStateNumPopupsShowing = MutableStateFlow(0)

// WordTaker T9 (P0-1): same split pattern as the key face ("2 ABC".."9 WXYZ").
// Case-insensitive: auto_text_key lowercases labels while the keyboard is unshifted.
private val T9_POPUP_LABEL_REGEX = """^(\d) ([A-Za-z]+)$""".toRegex()

@Composable
fun PopupBaseBox(
    modifier: Modifier = Modifier,
    attributes: SnyggQueryAttributes,
    key: Key,
    shouldIndicateExtendedPopups: Boolean,
): Unit = with(LocalDensity.current) {
    DisposableEffect(key) {
        GlobalStateNumPopupsShowing.update { it + 1 }
        onDispose {
            GlobalStateNumPopupsShowing.update { it - 1 }
        }
    }

    SnyggBox(
        elementName = FlorisImeUi.KeyPopupBox.elementName,
        attributes = attributes,
        modifier = modifier,
    ) {
        // WordTaker (P0-3.2): the bubble is now a compact box floating above the key, so the
        // character is centered in the whole bubble (magnified via the key-popup-box font
        // size). Letter keys mirror the key face's cosmetic uppercase so bubble and key cap
        // always show the same case; T9 group labels render split (letters main, digit small).
        key.label?.let { label ->
            val displayLabel = if (
                key is TextKey &&
                key.computedData.type == KeyType.CHARACTER &&
                key.computedData.code in 'a'.code..'z'.code
            ) {
                label.uppercase()
            } else {
                label
            }
            val t9Match = T9_POPUP_LABEL_REGEX.matchEntire(displayLabel)
            if (t9Match != null) {
                // Letters inherit the bubble's magnified font size; the digit stays small.
                SnyggText(
                    modifier = Modifier.align(Alignment.Center),
                    text = t9Match.groupValues[2].uppercase(),
                )
                SnyggText(
                    elementName = FlorisImeUi.KeyT9Digit.elementName,
                    attributes = attributes,
                    modifier = Modifier.align(Alignment.TopStart),
                    text = t9Match.groupValues[1],
                )
            } else {
                SnyggText(
                    modifier = Modifier.align(Alignment.Center),
                    text = displayLabel,
                )
            }
        }
        if (shouldIndicateExtendedPopups) {
            SnyggIcon(
                elementName = FlorisImeUi.KeyPopupExtendedIndicator.elementName,
                attributes = attributes,
                modifier = Modifier.align(Alignment.CenterEnd),
                imageVector = Icons.Default.MoreHoriz,
            )
        }
    }
}

@Composable
fun PopupExtBox(
    modifier: Modifier = Modifier,
    attributes: SnyggQueryAttributes,
    elements: List<List<PopupUiController.Element>>,
    elemArrangement: Arrangement.Horizontal,
    elemWidth: Dp,
    elemHeight: Dp,
    activeElementIndex: Int,
): Unit = with(LocalDensity.current) {
    SnyggColumn(FlorisImeUi.KeyPopupBox.elementName, attributes, modifier = modifier) {
        for (row in elements.asReversed()) {
            SnyggRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(elemHeight),
                horizontalArrangement = elemArrangement,
            ) {
                for (element in row) {
                    val selector = if (activeElementIndex == element.orderedIndex) {
                        SnyggSelector.FOCUS
                    } else {
                        null
                    }
                    val localAttrs = attributes.plus(FlorisImeUi.Attr.Code to element.data.code)
                    SnyggBox(
                        elementName = FlorisImeUi.KeyPopupElement.elementName,
                        attributes = localAttrs,
                        selector = selector,
                        modifier = Modifier.size(elemWidth, elemHeight),
                    ) {
                        element.label?.let { label ->
                            SnyggText(
                                modifier = Modifier.align(Alignment.Center),
                                text = label,
                            )
                        }
                        element.icon?.let { icon ->
                            SnyggIcon(
                                modifier = Modifier.align(Alignment.Center),
                                imageVector = icon,
                            )
                        }
                    }
                }
            }
        }
    }
}
