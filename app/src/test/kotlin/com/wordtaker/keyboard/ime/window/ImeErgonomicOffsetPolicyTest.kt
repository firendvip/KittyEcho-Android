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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class ImeErgonomicOffsetPolicyTest : FunSpec({

    test("phone portrait fixed normal uses the 8 over 360 width ratio") {
        ImeErgonomicOffsetPolicy.upwardOffset(
            rootWidth = 360f,
            formFactor = ImeFormFactor.Type.PHONE_PORTRAIT,
            windowMode = ImeWindowMode.FIXED,
            fixedMode = ImeWindowMode.Fixed.NORMAL,
        ).shouldBeExactly(8f)
        ImeErgonomicOffsetPolicy.upwardOffset(
            rootWidth = 1080f,
            formFactor = ImeFormFactor.Type.PHONE_PORTRAIT,
            windowMode = ImeWindowMode.FIXED,
            fixedMode = ImeWindowMode.Fixed.NORMAL,
        ).shouldBeExactly(24f)
        ImeErgonomicOffsetPolicy.UPWARD_OFFSET_RATIO.shouldBeExactly(8f / 360f)
    }

    test("every non portrait phone form factor has no ergonomic offset") {
        ImeFormFactor.Type.entries
            .filterNot { it == ImeFormFactor.Type.PHONE_PORTRAIT }
            .forEach { formFactor ->
                ImeErgonomicOffsetPolicy.upwardOffset(
                    rootWidth = 1080f,
                    formFactor = formFactor,
                    windowMode = ImeWindowMode.FIXED,
                    fixedMode = ImeWindowMode.Fixed.NORMAL,
                ).shouldBeExactly(0f)
            }
    }

    test("floating mode has no ergonomic offset") {
        ImeErgonomicOffsetPolicy.upwardOffset(
            rootWidth = 1080f,
            formFactor = ImeFormFactor.Type.PHONE_PORTRAIT,
            windowMode = ImeWindowMode.FLOATING,
            fixedMode = ImeWindowMode.Fixed.NORMAL,
        ).shouldBeExactly(0f)
    }

    test("compact and thumbs fixed modes have no ergonomic offset") {
        listOf(ImeWindowMode.Fixed.COMPACT, ImeWindowMode.Fixed.THUMBS).forEach { fixedMode ->
            ImeErgonomicOffsetPolicy.upwardOffset(
                rootWidth = 1080f,
                formFactor = ImeFormFactor.Type.PHONE_PORTRAIT,
                windowMode = ImeWindowMode.FIXED,
                fixedMode = fixedMode,
            ).shouldBeExactly(0f)
        }
    }

    test("zero negative and non finite root widths fail safe to zero") {
        listOf(
            0f,
            -1f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        ).forEach { rootWidth ->
            ImeErgonomicOffsetPolicy.upwardOffset(
                rootWidth = rootWidth,
                formFactor = ImeFormFactor.Type.PHONE_PORTRAIT,
                windowMode = ImeWindowMode.FIXED,
                fixedMode = ImeWindowMode.Fixed.NORMAL,
            ).shouldBeExactly(0f)
        }
    }

    test("window inner adds the gap after user padding without changing keyboard geometry") {
        val source = sourceFile("src/main/kotlin/com/wordtaker/keyboard/ime/window/ImeWindow.kt")
        val innerWindow = source
            .substringAfter("private fun ImeInnerWindow()")
            .substringBefore("private fun BoxScope.FloatingDockToFixedIndicator()")

        innerWindow shouldContain "ImeErgonomicOffsetPolicy.upwardOffset("
        innerWindow shouldContain
            "bottom = props.paddingBottom.coerceAtLeast(0.dp) + fixedBottomGap"
        innerWindow shouldNotContain ".graphicsLayer"
        innerWindow shouldNotContain ".offset("
        innerWindow shouldNotContain "keyboardHeight"
    }

    test("every equal height mode remains inside the same window inner container") {
        val source = sourceFile("src/main/kotlin/com/wordtaker/keyboard/ime/window/ImeWindow.kt")
        val windowInner = source
            .substringAfter("elementName = FlorisImeUi.WindowInner.elementName")
            .substringBefore("ImeWindowResizeHandlesFixed()")

        listOf(
            "ImeUiMode.TEXT",
            "ImeUiMode.CAT_VOICE",
            "CatKeyboardLayout()",
            "ImeUiMode.MEDIA",
            "MediaInputLayout()",
            "ImeUiMode.CLIPBOARD",
            "ClipboardInputLayout()",
            "ImeUiMode.HISTORY",
            "ImeHistoryLayout()",
            "ImeUiMode.SETTINGS",
            "ImeSettingsLayout()",
        ).forEach { modeContent ->
            windowInner shouldContain modeContent
        }

        val catLayout =
            sourceFile("src/main/kotlin/com/wordtaker/keyboard/wordtaker/voice/CatKeyboardLayout.kt")
        catLayout shouldContain "val showToolbarStrip = true"
        catLayout shouldContain "TextInputLayout()"

        val textLayout =
            sourceFile("src/main/kotlin/com/wordtaker/keyboard/ime/text/TextInputLayout.kt")
        textLayout shouldContain "TextKeyboardLayout(evaluator = evaluator)"
    }

    test("measured ime window bounds remain the source of content and visible insets") {
        val windowSource =
            sourceFile("src/main/kotlin/com/wordtaker/keyboard/ime/window/ImeWindow.kt")
        val measuredWindow = windowSource
            .substringAfter(".wrapContentHeight()")
            .substringBefore("supportsBackgroundImage = true")

        measuredWindow shouldContain ".onGloballyPositioned { coords ->"
        measuredWindow shouldContain "coords.boundsInRoot().roundToIntRect()"
        measuredWindow shouldContain "ImeInsets.Window.of(boundsPx)"
        measuredWindow shouldContain "windowController.updateWindowInsets(newInsets)"

        val controllerSource =
            sourceFile("src/main/kotlin/com/wordtaker/keyboard/ime/window/ImeWindowController.kt")
        val computeInsets = controllerSource
            .substringAfter("fun onComputeInsets(")
            .substringBefore("fun onConfigurationChanged(")

        computeInsets shouldContain "val windowBounds = windowInsets?.boundsPx"
        computeInsets shouldContain "val contentTop = windowBounds?.top ?: rootBounds.bottom"
        computeInsets shouldContain "outInsets.contentTopInsets = contentTop"
        computeInsets shouldContain "outInsets.visibleTopInsets = contentTop"
        computeInsets shouldNotContain "ImeErgonomicOffsetPolicy"
    }
})

private fun sourceFile(relativePath: String): String {
    val start = File(checkNotNull(System.getProperty("user.dir")))
    val candidates = generateSequence(start) { it.parentFile }
        .take(6)
        .map { root -> File(root, relativePath) }
    return candidates.firstOrNull(File::isFile)?.readText()
        ?: error("Unable to locate source file: $relativePath")
}
