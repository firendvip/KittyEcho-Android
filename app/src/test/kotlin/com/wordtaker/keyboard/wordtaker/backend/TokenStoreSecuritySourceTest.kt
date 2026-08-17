package com.wordtaker.keyboard.wordtaker.backend

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class TokenStoreSecuritySourceTest : FunSpec({

    test("access tokens are never written to the plaintext compatibility key") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val source = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/backend/TokenStore.kt",
        ).readText()

        source.contains("putString(KEY_TOKEN_PLAIN") shouldBe false
        source.contains("明文降级") shouldBe false
    }
})
