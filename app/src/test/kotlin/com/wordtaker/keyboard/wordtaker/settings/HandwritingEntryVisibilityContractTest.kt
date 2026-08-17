package com.wordtaker.keyboard.wordtaker.settings

import com.wordtaker.keyboard.ime.core.isProductSelectableCharacterLayout
import com.wordtaker.keyboard.ime.core.migratePersistedProductSubtypes
import com.wordtaker.keyboard.ime.core.normalizePersistedProductKeyboardStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class HandwritingEntryVisibilityContractTest : FunSpec({

    test("in IME keyboard management users can only see and select qwerty or T9") {
        val source = productionSource("wordtaker/settings/ImeSettingsLayout.kt")
        val keyboardManagement = source
            .substringAfter("SectionLabel(\"键盘管理\")")
            .substringBefore("Spacer(Modifier.height(12.dp))")

        keyboardManagement shouldContain "KEYBOARD_STYLE_QWERTY"
        keyboardManagement shouldContain "KEYBOARD_STYLE_T9"
        keyboardManagement shouldNotContain "KEYBOARD_STYLE_HANDWRITING"
        keyboardManagement shouldNotContain "\"手写输入\""
    }

    test("onboarding does not expose a handwriting card while core Android IME capability stays untouched") {
        val setupSource = productionSource("app/setup/SetupScreen.kt")
        val subtypeSource = productionSource("ime/core/Subtype.kt")

        setupSource shouldContain "\"qwerty_card\""
        setupSource shouldContain "\"t9_card\""
        setupSource shouldNotContain "STYLE_HANDWRITING"
        setupSource shouldNotContain "KeyboardPreviewKind.HANDWRITING"
        setupSource shouldNotContain "HandwritingPreview"
        subtypeSource shouldContain "HANDWRITING_DEFAULT"
    }

    test("legacy persisted handwriting selection migrates to qwerty and migration is idempotent") {
        normalizePersistedProductKeyboardStyle("handwriting") shouldBe "qwerty_pinyin"
        normalizePersistedProductKeyboardStyle(
            normalizePersistedProductKeyboardStyle("handwriting"),
        ) shouldBe "qwerty_pinyin"

        val subtypeManagerSource = productionSource("ime/core/SubtypeManager.kt")
        subtypeManagerSource shouldContain "readNormalizedProductKeyboardStyle"
        subtypeManagerSource shouldContain "prefs.internal.selectedKeyboardStyle.set(normalized)"
    }

    test("migration leaves T9 and every other non-handwriting value unchanged") {
        normalizePersistedProductKeyboardStyle("t9_pinyin") shouldBe "t9_pinyin"
        normalizePersistedProductKeyboardStyle("shuangpin") shouldBe "shuangpin"
        normalizePersistedProductKeyboardStyle("future_style") shouldBe "future_style"
    }

    test("persisted handwriting subtype migrates to pinyin without changing IDs or legal subtypes") {
        val handwriting = com.wordtaker.keyboard.ime.core.Subtype.HANDWRITING_DEFAULT.copy(id = 41L)
        val t9 = com.wordtaker.keyboard.ime.core.Subtype.T9_DEFAULT.copy(id = 42L)

        val migrated = migratePersistedProductSubtypes(listOf(handwriting, t9))

        migrated shouldBe listOf(
            com.wordtaker.keyboard.ime.core.Subtype.PINYIN_DEFAULT.copy(id = 41L),
            t9,
        )
        migratePersistedProductSubtypes(migrated) shouldBe migrated

        val subtypeManagerSource = productionSource("ime/core/SubtypeManager.kt")
        subtypeManagerSource shouldContain "migratePersistedProductSubtypes"
        subtypeManagerSource shouldContain "prefs.localization.subtypes.set("
    }

    test("deep-link reachable subtype editor filters the handwriting character layout") {
        isProductSelectableCharacterLayout("handwriting_pad") shouldBe false
        isProductSelectableCharacterLayout("qwerty") shouldBe true
        isProductSelectableCharacterLayout("pinyin_t9") shouldBe true

        val source = productionSource("app/settings/localization/SubtypeEditorScreen.kt")
        source shouldContain "isProductSelectableCharacterLayout"
    }

    test("product settings API does not expose a handwriting style constant") {
        val source = productionSource("wordtaker/ui/WordTakerSettingsActivity.kt")
        source shouldNotContain "KEYBOARD_STYLE_HANDWRITING"
    }
})

private fun productionSource(relativePath: String): String {
    val moduleRoot = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile
    return File(moduleRoot, "src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()
}
