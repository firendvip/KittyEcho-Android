package com.wordtaker.keyboard.wordtaker.account

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class PassportSecuritySourceTest : FunSpec({

    val projectRoot = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        .canonicalFile

    test("rollout is default-off and pins the reviewed public native registration") {
        val properties = File(projectRoot, "gradle.properties").readText()
        val build = File(projectRoot, "app/build.gradle.kts").readText()

        properties.contains("wangsanPassportEnabled=false") shouldBe true
        properties.contains("wangsanPassportIssuer=https://auth.yaa3.com") shouldBe true
        properties.contains("wangsanPassportClientId=kittyecho-android") shouldBe true
        properties.contains("wangsanPassportRedirectUri=kittyecho://auth") shouldBe true
        build.contains("WANGSAN_PASSPORT_ENABLED") shouldBe true
        build.contains("client_secret") shouldBe false
    }

    test("pending PKCE material and rotating OIDC tokens have no plaintext preference path") {
        val pending = File(
            projectRoot,
            "app/src/main/kotlin/com/wordtaker/keyboard/wordtaker/account/AndroidPassportPendingStore.kt",
        ).readText()
        val tokens = File(
            projectRoot,
            "app/src/main/kotlin/com/wordtaker/keyboard/wordtaker/backend/TokenStore.kt",
        ).readText()

        pending.contains("AndroidKeyStore") shouldBe true
        pending.contains("AES/GCM/NoPadding") shouldBe true
        pending.contains("putString(KEY_PENDING_ENCRYPTED, encrypted)") shouldBe true
        pending.contains("putString(\"state\"") shouldBe false
        pending.contains("putString(\"codeVerifier\"") shouldBe false

        tokens.contains("KEY_OIDC_SESSION_ENC") shouldBe true
        tokens.contains("putString(KEY_OIDC_SESSION_ENC, encrypted)") shouldBe true
        tokens.contains("putString(KEY_TOKEN_PLAIN") shouldBe false
        tokens.contains("putString(\"refreshToken\"") shouldBe false
    }

    test("backend requests use proactive refresh and at most one authenticated retry") {
        val graph = File(
            projectRoot,
            "app/src/main/kotlin/com/wordtaker/keyboard/wordtaker/di/AppGraph.kt",
        ).readText()
        val backend = File(
            projectRoot,
            "app/src/main/kotlin/com/wordtaker/keyboard/wordtaker/backend/BackendClient.kt",
        ).readText()

        graph.contains("authSessionProvider = oidcTokenManager::accessSession") shouldBe true
        graph.contains("authSessionRefresher = oidcTokenManager::refreshAfterUnauthorized") shouldBe true
        backend.contains("MAX_AUTH_ATTEMPTS = 2") shouldBe true
    }

    test("Passport URL codecs use the minSdk 26 charset-name overloads") {
        val flow = File(
            projectRoot,
            "app/src/main/kotlin/com/wordtaker/keyboard/wordtaker/account/PassportOidcFlow.kt",
        ).readText()

        flow.contains("URLEncoder.encode(value, Charsets.UTF_8.name())") shouldBe true
        flow.contains("URLDecoder.decode(value, Charsets.UTF_8.name())") shouldBe true
        flow.contains("URLEncoder.encode(value, Charsets.UTF_8)") shouldBe false
        flow.contains("URLDecoder.decode(value, Charsets.UTF_8)") shouldBe false
    }
})
