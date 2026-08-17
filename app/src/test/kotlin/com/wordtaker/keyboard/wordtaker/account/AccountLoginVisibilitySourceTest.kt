package com.wordtaker.keyboard.wordtaker.account

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class AccountLoginVisibilitySourceTest : FunSpec({

    test("account UI hides the WeChat entry while preserving email and callback compatibility") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val source = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/ui/WordTakerAccountActivity.kt",
        ).readText()

        source.contains("""Text("微信登录")""") shouldBe false
        source.contains("repository.wechatAuthUrl()") shouldBe false
        source.contains("repository.loginWithEmail(") shouldBe true
        source.contains("repo.loginWithWechatCode(code)") shouldBe true
        source.contains("""Text("发送验证码")""") shouldBe false
        source.contains("发送验证码") shouldBe true
    }
})
