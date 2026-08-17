/*
 * Copyright (C) 2026 The FlorisBoard Contributors
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

package com.wordtaker.keyboard.ime.window

/**
 * Selects the small bottom gap which raises the fixed portrait phone keyboard without changing
 * any content geometry. [rootWidth] is unit-agnostic, so callers receive the offset in the same
 * unit they provide.
 */
internal object ImeErgonomicOffsetPolicy {
    const val UPWARD_OFFSET_RATIO = 8f / 360f

    fun upwardOffset(
        rootWidth: Float,
        formFactor: ImeFormFactor.Type,
        windowMode: ImeWindowMode,
        fixedMode: ImeWindowMode.Fixed,
    ): Float {
        if (!rootWidth.isFinite() || rootWidth <= 0f) {
            return 0f
        }
        return if (
            formFactor == ImeFormFactor.Type.PHONE_PORTRAIT &&
            windowMode == ImeWindowMode.FIXED &&
            fixedMode == ImeWindowMode.Fixed.NORMAL
        ) {
            rootWidth * UPWARD_OFFSET_RATIO
        } else {
            0f
        }
    }
}
