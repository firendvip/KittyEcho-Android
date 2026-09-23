package com.wordtaker.keyboard.wordtaker.backend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class TokenStoreTest : FunSpec({

    test("Passport-only state never authenticates and clear preserves it byte-for-byte") {
        val retained = linkedMapOf<String, Any?>(
            "oidc_session_enc" to "retained-session",
            "account_json" to accountJson("passport-user", "Passport 昵称"),
        )
        val storage = FakeAuthStateStorage(retained)
        val store = TokenStore(storage, FakeTokenCipher())

        store.isLoggedIn() shouldBe false
        store.account().shouldBeNull()
        store.clear()

        storage.values.shouldContainExactly(retained)
        store.credentialGeneration() shouldBe 1L
        store.authRequestSession() shouldBe AuthRequestSession(1L, null)
    }

    test("independent login uses a separate profile and logout never deletes retained Passport state") {
        val sharedProfile = accountJson("passport-user", "Passport 昵称")
        val storage = FakeAuthStateStorage(
            linkedMapOf(
                "oidc_session_enc" to "retained-session",
                "account_json" to sharedProfile,
                "token_plain" to "obsolete-plain-token",
            ),
        )
        val store = TokenStore(storage, FakeTokenCipher())
        val independent = account("independent-user", "独立账号")

        store.set("independent-token", independent)

        store.isLoggedIn() shouldBe true
        store.account() shouldBe independent
        store.authRequestSession() shouldBe AuthRequestSession(1L, "independent-token")
        storage.values["account_json"] shouldBe sharedProfile
        storage.values["oidc_session_enc"] shouldBe "retained-session"
        storage.values["token_plain"].shouldBeNull()
        storage.values["legacy_account_initialized"] shouldBe true

        store.clear()

        store.isLoggedIn() shouldBe false
        store.account().shouldBeNull()
        storage.values.shouldContainExactly(
            mapOf(
                "oidc_session_enc" to "retained-session",
                "account_json" to sharedProfile,
            ),
        )
    }

    test("an explicitly empty independent profile never falls back to retained Passport profile") {
        val storage = FakeAuthStateStorage(
            linkedMapOf("account_json" to accountJson("passport-user", "Passport 昵称")),
        )
        val cipher = FakeTokenCipher()

        TokenStore(storage, cipher).set("independent-token", null)
        val restarted = TokenStore(storage, cipher)

        restarted.isLoggedIn() shouldBe true
        restarted.account().shouldBeNull()
        storage.values["legacy_account_initialized"] shouldBe true
        storage.values["account_json"] shouldBe accountJson("passport-user", "Passport 昵称")
    }

    test("pre-Passport independent token migrates in place and keeps its legacy profile") {
        val profile = accountJson("legacy-user", "旧独立账号")
        val storage = FakeAuthStateStorage(
            linkedMapOf(
                "token_plain" to "legacy-token",
                "account_json" to profile,
            ),
        )
        val store = TokenStore(storage, FakeTokenCipher())

        store.accessToken() shouldBe "legacy-token"
        store.account() shouldBe account("legacy-user", "旧独立账号")
        storage.values["token_enc"] shouldBe "enc:legacy-token"
        storage.values["token_plain"].shouldBeNull()
        storage.values["account_json"] shouldBe profile
    }

    test("failed plaintext migration removes only the insecure token") {
        val sharedProfile = accountJson("legacy-user", "旧独立账号")
        val storage = FakeAuthStateStorage(
            linkedMapOf(
                "token_plain" to "legacy-token",
                "account_json" to sharedProfile,
                "oidc_session_enc" to "retained-session",
            ),
        )
        val store = TokenStore(storage, FakeTokenCipher(encrypts = false))

        store.accessToken().shouldBeNull()
        store.account().shouldBeNull()
        storage.values["token_plain"].shouldBeNull()
        storage.values["account_json"] shouldBe sharedProfile
        storage.values["oidc_session_enc"] shouldBe "retained-session"
    }

    test("profile updates require a valid independent token and malformed JSON fails closed") {
        val storage = FakeAuthStateStorage()
        val store = TokenStore(storage, FakeTokenCipher())
        val first = account("independent-user", "初始昵称")
        val updated = account("independent-user", "更新昵称")

        store.updateAccount(first)
        storage.values.isEmpty() shouldBe true

        store.set("token", first)
        store.updateAccount(updated)
        store.account() shouldBe updated

        storage.values["legacy_account_json"] = "{broken"
        store.account().shouldBeNull()
    }

    test("blank tokens and unavailable secure storage never publish partial login state") {
        val storage = FakeAuthStateStorage(
            linkedMapOf(
                "account_json" to accountJson("passport-user", "Passport 昵称"),
                "oidc_session_enc" to "retained-session",
            ),
        )
        val store = TokenStore(storage, FakeTokenCipher(encrypts = false))

        shouldThrow<IllegalArgumentException> { store.set(" ", null) }
        shouldThrow<IllegalStateException> { store.set("token", account("user", "昵称")) }

        store.isLoggedIn() shouldBe false
        store.credentialGeneration() shouldBe 0L
        storage.values.shouldContainExactly(
            mapOf(
                "account_json" to accountJson("passport-user", "Passport 昵称"),
                "oidc_session_enc" to "retained-session",
            ),
        )
    }
})

private class FakeAuthStateStorage(
    initial: Map<String, Any?> = emptyMap(),
) : AuthStateStorage {
    val values = initial.toMutableMap()

    override fun getString(key: String): String? = values[key] as? String

    override fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false

    override fun update(
        strings: Map<String, String?>,
        booleans: Map<String, Boolean>,
        removals: Set<String>,
    ) {
        removals.forEach(values::remove)
        strings.forEach { (key, value) ->
            if (value == null) values.remove(key) else values[key] = value
        }
        values.putAll(booleans)
    }
}

private class FakeTokenCipher(
    private val encrypts: Boolean = true,
) : TokenCipher {
    override fun encrypt(plain: String): String? = if (encrypts) "enc:$plain" else null

    override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
}

private fun account(userId: String, nickname: String) = AccountInfo(
    userId = userId,
    nickname = nickname,
    inviteCode = null,
    email = null,
    phone = null,
)

private fun accountJson(userId: String, nickname: String): String =
    """{"userId":"$userId","nickname":"$nickname"}"""
