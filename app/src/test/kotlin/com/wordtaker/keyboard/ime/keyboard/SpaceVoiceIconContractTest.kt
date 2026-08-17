package com.wordtaker.keyboard.ime.keyboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class SpaceVoiceIconContractTest : FunSpec({

    test("main keyboard space uses the existing voice speaking vector rather than an approximation") {
        val source = productionSource("ime/keyboard/ComputingEvaluator.kt")
        val imageEvaluator = source.substringAfter("fun ComputingEvaluator.computeImageVector")
        val spaceVisual = imageEvaluator
            .substringAfter("KeyCode.SPACE, KeyCode.CJK_SPACE -> {")
            .substringBefore("KeyCode.UNDO ->")

        spaceVisual shouldContain "R.drawable.ic_wt_voice"
        spaceVisual shouldNotContain "R.drawable.ic_wt_wave"
        resource("drawable/ic_wt_voice.xml").isFile shouldBe true
    }

    test("space short press and long press routing remain on their existing behavior paths") {
        val managerSource = productionSource("ime/keyboard/KeyboardManager.kt")
        val layoutSource = productionSource("ime/text/keyboard/TextKeyboardLayout.kt")

        managerSource shouldContain "KeyCode.SPACE -> handleSpace(data)"
        layoutSource shouldContain "KeyCode.SPACE, KeyCode.CJK_SPACE -> {"
        layoutSource shouldContain "prefs.gestures.spaceBarLongPress.get()"
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}

private fun resource(relativePath: String): File {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/res/$relativePath")
}
