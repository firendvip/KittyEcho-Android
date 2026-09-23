package com.wordtaker.keyboard.wordtaker.account

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class AccountLoginVisibilitySourceTest : FunSpec({

    test("account UI restores independent email login without re-exposing a deep link callback") {
        val moduleRoot = moduleRoot()
        val source = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/ui/WordTakerAccountActivity.kt",
        ).readText()
        val manifest = File(moduleRoot, "src/main/AndroidManifest.xml").readText()
        val accountActivity = manifest.substringAfter("WordTakerAccountActivity")
            .substringBefore("/>")

        source.contains("repository.sendEmailCode(") shouldBe true
        source.contains("repository.loginWithEmail(") shouldBe true
        source.contains("KeyboardType.Email") shouldBe true
        source.contains("repo.loginWithWechatCode(code)") shouldBe false
        source.contains("getQueryParameter(\"code\")") shouldBe false
        source.contains("passportLogin") shouldBe false
        source.contains("望三通行证") shouldBe false
        accountActivity.contains("android:exported=\"false\"") shouldBe true
        accountActivity.contains("intent-filter") shouldBe false
    }

    test("Passport runtime configuration and dependencies are absent") {
        val repositoryRoot = moduleRoot().parentFile
        val gradleProperties = File(repositoryRoot, "gradle.properties").readText()
        val appBuild = File(repositoryRoot, "app/build.gradle.kts").readText()
        val versions = File(repositoryRoot, "gradle/libs.versions.toml").readText()

        gradleProperties.contains("wangsanPassport", ignoreCase = true) shouldBe false
        gradleProperties.contains("auth.yaa3.com", ignoreCase = true) shouldBe false
        appBuild.contains("WANGSAN_PASSPORT") shouldBe false
        versions.contains("nimbus-jose-jwt") shouldBe false
    }

    test("user-visible app title follows the single 0.44.0 version increment") {
        val moduleRoot = moduleRoot()
        File(moduleRoot, "src/main/res/values/strings.xml")
            .readText().contains("弦外小猫 v0.44.0") shouldBe true
        File(moduleRoot, "src/main/res/values-zh-rCN/strings.xml")
            .readText().contains("弦外小猫 v0.44.0") shouldBe true
    }

    test("legacy token store ignores retained Passport data instead of deleting it") {
        val source = File(
            moduleRoot(),
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/backend/TokenStore.kt",
        ).readText()

        source.contains("oidc_session_enc") shouldBe false
        source.contains("KEY_OIDC_SESSION_ENC") shouldBe false
        source.contains("KEY_TOKEN_ENC") shouldBe true
    }
})

private fun moduleRoot(): File = sequenceOf(File("."), File("app"))
    .first { File(it, "src/main").isDirectory }
    .canonicalFile
