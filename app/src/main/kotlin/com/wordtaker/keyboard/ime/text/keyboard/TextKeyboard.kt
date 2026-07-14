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

package com.wordtaker.keyboard.ime.text.keyboard

import com.wordtaker.keyboard.ime.keyboard.Key
import com.wordtaker.keyboard.ime.keyboard.Keyboard
import com.wordtaker.keyboard.ime.keyboard.KeyboardMode
import com.wordtaker.keyboard.ime.popup.PopupMapping
import com.wordtaker.keyboard.ime.text.key.KeyCode
import kotlin.math.abs

class TextKeyboard(
    val arrangement: Array<Array<TextKey>>,
    override val mode: KeyboardMode,
    val extendedPopupMapping: PopupMapping?,
    val extendedPopupMappingDefault: PopupMapping?,
) : Keyboard() {
    val rowCount: Int
        get() = arrangement.size

    val keyCount: Int
        get() = arrangement.sumOf { it.size }

    override fun getKeyForPos(pointerX: Float, pointerY: Float): TextKey? {
        for (key in keys()) {
            if (key.touchBounds.contains(pointerX, pointerY)) {
                return key
            }
        }
        return null
    }

    override fun layout(
        keyboardWidth: Float,
        keyboardHeight: Float,
        desiredKey: Key,
        extendTouchBoundariesDownwards: Boolean,
    ) {
        if (arrangement.isEmpty()) return

        val desiredTouchBounds = desiredKey.touchBounds
        val desiredVisibleBounds = desiredKey.visibleBounds
        if (desiredTouchBounds.isEmpty() || desiredVisibleBounds.isEmpty()) return
        if (keyboardWidth.isNaN() || keyboardHeight.isNaN()) return

        // WordTaker 12·34 数字页: iOS-style calculator grid with a 4-key operator column
        // (3 rows tall), a double-height enter block and a dedicated bottom row. Detected by
        // shape + key inventory so every other layout keeps the generic flow layout below.
        if (mode == KeyboardMode.NUMERIC && isWtCalculatorArrangement()) {
            layoutWtCalculator(keyboardWidth, keyboardHeight, desiredKey, extendTouchBoundariesDownwards)
            return
        }

        val rowMarginH = abs(desiredTouchBounds.width - desiredVisibleBounds.width)
        val rowMarginV = (keyboardHeight - desiredTouchBounds.height * rowCount.toFloat()) / (rowCount - 1).coerceAtLeast(1).toFloat()

        for ((r, row) in rows().withIndex()) {
            val posY = (desiredTouchBounds.height + rowMarginV) * r
            val availableWidth = (keyboardWidth - rowMarginH) / desiredTouchBounds.width
            var requestedWidth = 0.0f
            var shrinkSum = 0.0f
            var growSum = 0.0f
            for (key in row) {
                requestedWidth += key.flayWidthFactor
                shrinkSum += key.flayShrink
                growSum += key.flayGrow
            }
            if (requestedWidth <= availableWidth) {
                // Requested with is smaller or equal to the available with, so we can grow
                val additionalWidth = availableWidth - requestedWidth
                var posX = rowMarginH / 2.0f
                for ((k, key) in row.withIndex()) {
                    val keyWidth = desiredTouchBounds.width * when (growSum) {
                        0.0f -> when (k) {
                            0, row.size - 1 -> key.flayWidthFactor + additionalWidth / 2.0f
                            else -> key.flayWidthFactor
                        }
                        else -> key.flayWidthFactor + additionalWidth * (key.flayGrow / growSum)
                    }
                    key.touchBounds.apply {
                        left = posX
                        top = posY
                        right = posX + keyWidth
                        bottom = posY + desiredTouchBounds.height
                    }
                    key.visibleBounds.apply {
                        left = key.touchBounds.left + abs(desiredTouchBounds.left - desiredVisibleBounds.left) + when {
                            growSum == 0.0f && k == 0 -> ((additionalWidth / 2.0f) * desiredTouchBounds.width)
                            else -> 0.0f
                        }
                        top = key.touchBounds.top + abs(desiredTouchBounds.top - desiredVisibleBounds.top)
                        right = key.touchBounds.right - abs(desiredTouchBounds.right - desiredVisibleBounds.right) - when {
                            growSum == 0.0f && k == row.size - 1 -> ((additionalWidth / 2.0f) * desiredTouchBounds.width)
                            else -> 0.0f
                        }
                        bottom = key.touchBounds.bottom - abs(desiredTouchBounds.bottom - desiredVisibleBounds.bottom)
                    }
                    posX += keyWidth
                    // After-adjust touch bounds for the row margin
                    key.touchBounds.apply {
                        if (k == 0) {
                            left = 0.0f
                        } else if (k == row.size - 1) {
                            right = keyboardWidth
                        }
                        if (extendTouchBoundariesDownwards && r + 1 == arrangement.size) {
                            bottom += height
                        }
                    }
                }
            } else {
                // Requested size too big, must shrink.
                val clippingWidth = requestedWidth - availableWidth
                var posX = rowMarginH / 2.0f
                for ((k, key) in row.withIndex()) {
                    val keyWidth = desiredTouchBounds.width * if (key.flayShrink == 0.0f) {
                        key.flayWidthFactor
                    } else {
                        key.flayWidthFactor - clippingWidth * (key.flayShrink / shrinkSum)
                    }
                    key.touchBounds.apply {
                        left = posX
                        top = posY
                        right = posX + keyWidth
                        bottom = posY + desiredTouchBounds.height
                    }
                    key.visibleBounds.apply {
                        left = key.touchBounds.left + abs(desiredTouchBounds.left - desiredVisibleBounds.left)
                        top = key.touchBounds.top + abs(desiredTouchBounds.top - desiredVisibleBounds.top)
                        right = key.touchBounds.right - abs(desiredTouchBounds.right - desiredVisibleBounds.right)
                        bottom = key.touchBounds.bottom - abs(desiredTouchBounds.bottom - desiredVisibleBounds.bottom)
                    }
                    posX += keyWidth
                    // After-adjust touch bounds for the row margin
                    key.touchBounds.apply {
                        if (k == 0) {
                            left = 0.0f
                        } else if (k == row.size - 1) {
                            right = keyboardWidth
                        }
                        if (extendTouchBoundariesDownwards && r + 1 == arrangement.size) {
                            bottom += height
                        }
                    }
                }
            }
        }
    }

    private fun keyDataCode(key: TextKey): Int = (key.data as? TextKeyData)?.code ?: KeyCode.UNSPECIFIED

    /** True for the WordTaker 12·34 calculator pad (4 rows x 5 keys incl. `*`, `←`, enter). */
    private fun isWtCalculatorArrangement(): Boolean {
        if (arrangement.size != 4 || arrangement.any { it.size != 5 }) return false
        val codes = arrangement.flatMap { row -> row.map { keyDataCode(it) } }
        return 42 in codes && KeyCode.VIEW_CHARACTERS in codes && KeyCode.ENTER in codes
    }

    /**
     * Custom geometry for the 12·34 page, all in fractions of the 5-column x 4-row grid:
     * ```
     * %  1 2 3  ⌫        <- operator column: 4 keys, each 3/4 row high (rows 1-3 tall)
     * +  4 5 6  .
     * -  7 8 9  ⏎        <- enter spans rows 3-4 (double height)
     * *
     * ←  符 0 空格 (⏎)    <- bottom row
     * ```
     */
    private fun layoutWtCalculator(
        keyboardWidth: Float,
        keyboardHeight: Float,
        desiredKey: Key,
        extendTouchBoundariesDownwards: Boolean,
    ) {
        val colW = keyboardWidth / 5.0f
        val rowH = keyboardHeight / 4.0f
        val opH = rowH * 3.0f / 4.0f // operator-column key height (4 keys over 3 rows)
        val marginH = abs(desiredKey.touchBounds.left - desiredKey.visibleBounds.left)
        val marginV = abs(desiredKey.touchBounds.top - desiredKey.visibleBounds.top)

        fun place(key: TextKey, col: Int, top: Float, height: Float, isBottom: Boolean) {
            key.touchBounds.apply {
                left = col * colW
                this.top = top
                right = (col + 1) * colW
                bottom = top + height
                if (col == 0) left = 0.0f
                if (col == 4) right = keyboardWidth
                if (isBottom && extendTouchBoundariesDownwards) bottom += rowH
            }
            key.visibleBounds.apply {
                left = col * colW + marginH
                this.top = top + marginV
                right = (col + 1) * colW - marginH
                bottom = top + height - marginV
            }
        }

        for (row in arrangement) {
            for (key in row) {
                when (val code = keyDataCode(key)) {
                    37 /* % */ -> place(key, 0, 0.0f, opH, false)
                    43 /* + */ -> place(key, 0, opH, opH, false)
                    45 /* - */ -> place(key, 0, opH * 2, opH, false)
                    42 /* * */ -> place(key, 0, opH * 3, opH, false)
                    in 49..57 /* 1-9 */ -> {
                        val d = code - 49
                        place(key, 1 + d % 3, (d / 3) * rowH, rowH, false)
                    }
                    KeyCode.DELETE -> place(key, 4, 0.0f, rowH, false)
                    46 /* . */ -> place(key, 4, rowH, rowH, false)
                    KeyCode.ENTER -> place(key, 4, rowH * 2, rowH * 2, true)
                    KeyCode.VIEW_CHARACTERS -> place(key, 0, rowH * 3, rowH, true)
                    KeyCode.VIEW_SYMBOLS -> place(key, 1, rowH * 3, rowH, true)
                    48 /* 0 */ -> place(key, 2, rowH * 3, rowH, true)
                    KeyCode.SPACE -> place(key, 3, rowH * 3, rowH, true)
                    else -> { // unknown key: collapse so it neither renders nor grabs touches
                        key.touchBounds.apply { left = 0f; top = 0f; right = 0f; bottom = 0f }
                        key.visibleBounds.applyFrom(key.touchBounds)
                    }
                }
            }
        }
    }

    override fun keys(): Iterator<TextKey> {
        return TextKeyboardIterator(arrangement)
    }

    fun rows(): Iterator<Array<TextKey>> {
        return arrangement.iterator()
    }

    class TextKeyboardIterator internal constructor(
        private val arrangement: Array<Array<TextKey>>
    ) : Iterator<TextKey> {
        private var rowIndex: Int = 0
        private var keyIndex: Int = 0

        override fun hasNext(): Boolean {
            return rowIndex < arrangement.size && keyIndex < arrangement[rowIndex].size
        }

        override fun next(): TextKey {
            val next = arrangement[rowIndex][keyIndex]
            if (keyIndex + 1 == arrangement[rowIndex].size) {
                rowIndex++
                keyIndex = 0
            } else {
                keyIndex++
            }
            return next
        }
    }
}
