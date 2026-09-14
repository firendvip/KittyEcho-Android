package com.wordtaker.keyboard.wordtaker.account

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class AccountLoginVisibilitySourceTest : FunSpec({

    test("ordinary account UI exposes only the central two-method passport entry") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val source = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/ui/WordTakerAccountActivity.kt",
        ).readText()
        val imeSettings = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/settings/ImeSettingsLayout.kt",
        ).readText()

        source.contains("望三通行证登录") shouldBe true
        source.contains("手机号验证码和微信") shouldBe true
        source.contains("passportLogin.begin()") shouldBe true

        source.contains("repository.sendEmailCode(") shouldBe false
        source.contains("repository.loginWithEmail(") shouldBe false
        source.contains("repository.sendSmsCode(") shouldBe false
        source.contains("repository.loginWithSms(") shouldBe false
        source.contains("repository.wechatAuthUrl(") shouldBe false
        source.contains("repo.loginWithWechatCode(") shouldBe false
        source.contains("KeyboardType.Email") shouldBe false
        source.contains("邮箱验证码") shouldBe false
        source.contains("其他登录方式") shouldBe false
        source.contains("忘记密码") shouldBe false
        source.contains("重置密码") shouldBe false
        source.contains("?: account.email") shouldBe false
        imeSettings.contains("state.account?.email") shouldBe false
        imeSettings.contains("邮箱/验证码") shouldBe false
    }

    test("callback entry delegates to the PKCE flow instead of trusting a raw code") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val source = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/ui/WordTakerAccountActivity.kt",
        ).readText()
        val manifest = File(moduleRoot, "src/main/AndroidManifest.xml").readText()
        val callbackActivity = manifest.substringAfter("WordTakerAccountActivity")
            .substringBefore("</activity>")

        source.contains("passportLogin.handleCallback(") shouldBe true
        source.contains("getQueryParameter(\"code\")") shouldBe false
        source.contains("loginWithWechatCode") shouldBe false
        callbackActivity.contains("android:scheme=\"kittyecho\"") shouldBe true
        callbackActivity.contains("android:host=\"auth\"") shouldBe true
        callbackActivity.contains("android:path") shouldBe false
    }
})
