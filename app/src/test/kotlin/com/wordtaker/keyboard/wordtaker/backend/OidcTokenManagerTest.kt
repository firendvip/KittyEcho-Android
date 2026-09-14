package com.wordtaker.keyboard.wordtaker.backend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OidcTokenManagerTest : FunSpec({

    test("legacy sessions and healthy OIDC access tokens do not call refresh") {
        val api = FakeOidcTokenApi()
        val legacyStore = MemoryAuthStore()
        OidcTokenManager(legacyStore, { "legacy-access" }, api) { 1_000L }
            .accessToken() shouldBe "legacy-access"

        val oidcStore = MemoryAuthStore(OidcTokens("oidc-access", "refresh-1", 2_000L))
        OidcTokenManager(oidcStore, { "legacy-access" }, api) { 1_000L }
            .accessToken() shouldBe "oidc-access"
        api.refreshCalls shouldBe emptyList()
    }

    test("near-expiry and forced refresh rotate stored credentials") {
        val store = MemoryAuthStore(OidcTokens("access-1", "refresh-1", 1_050L))
        val api = FakeOidcTokenApi().apply {
            next = OidcTokens("access-2", "refresh-2", 1_900L)
        }
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }

        manager.accessToken() shouldBe "access-2"
        store.tokens shouldBe OidcTokens("access-2", "refresh-2", 1_900L)
        api.refreshCalls shouldBe listOf("refresh-1")

        api.next = OidcTokens("access-3", "refresh-3", 2_000L)
        manager.refreshAfterUnauthorized("access-2") shouldBe "access-3"
        store.tokens shouldBe OidcTokens("access-3", "refresh-3", 2_000L)
    }

    test("a failed token from an older concurrent request never refreshes the new token again") {
        val store = MemoryAuthStore(OidcTokens("access-new", "refresh-new", 2_000L))
        val api = FakeOidcTokenApi()
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }

        manager.refreshAfterUnauthorized("access-old") shouldBe "access-new"
        api.refreshCalls shouldBe emptyList()
    }

    test("temporary refresh failure uses a still-valid token but never an expired token") {
        val network = BackendException(BackendException.Kind.NETWORK, "transport detail")
        val api = FakeOidcTokenApi().apply { failure = network }
        val stillValid = MemoryAuthStore(OidcTokens("access-1", "refresh-1", 1_010L))
        OidcTokenManager(stillValid, { null }, api) { 1_000L }
            .accessToken() shouldBe "access-1"
        stillValid.cleared shouldBe false

        val expired = MemoryAuthStore(OidcTokens("access-1", "refresh-1", 999L))
        val error = shouldThrow<BackendException> {
            OidcTokenManager(expired, { null }, api) { 1_000L }.accessToken()
        }
        error.kind shouldBe BackendException.Kind.NETWORK
        expired.cleared shouldBe false
    }

    test("permanent refresh failure and an expired session without refresh clear credentials") {
        val invalidGrant = BackendException(
            BackendException.Kind.HTTP,
            "invalid grant detail",
            code = BackendException.CODE_NOT_LOGGED_IN,
            status = 401,
        )
        val invalidStore = MemoryAuthStore(OidcTokens("access-1", "refresh-1", 999L))
        val invalidApi = FakeOidcTokenApi().apply { failure = invalidGrant }
        shouldThrow<BackendException> {
            OidcTokenManager(invalidStore, { null }, invalidApi) { 1_000L }.accessToken()
        }.isAuthExpired shouldBe true
        invalidStore.cleared shouldBe true

        val noRefreshStore = MemoryAuthStore(OidcTokens("access-1", null, 999L))
        shouldThrow<BackendException> {
            OidcTokenManager(noRefreshStore, { null }, FakeOidcTokenApi()) { 1_000L }.accessToken()
        }.isAuthExpired shouldBe true
        noRefreshStore.cleared shouldBe true
    }
})

private class FakeOidcTokenApi : PassportOidcTokenApi {
    var next = OidcTokens("access-next", "refresh-next", 2_000L)
    var failure: BackendException? = null
    val refreshCalls = mutableListOf<String>()

    override fun exchangeAuthorizationCode(code: String, codeVerifier: String): OidcTokens = next

    override fun refresh(refreshToken: String): OidcTokens {
        refreshCalls += refreshToken
        failure?.let { throw it }
        return next
    }
}

private class MemoryAuthStore(
    var tokens: OidcTokens? = null,
) : AuthSessionStore {
    var accountInfo: AccountInfo? = null
    var cleared = false

    override fun isLoggedIn(): Boolean = tokens != null
    override fun account(): AccountInfo? = accountInfo
    override fun set(accessToken: String, account: AccountInfo?) = Unit
    override fun updateAccount(account: AccountInfo?) {
        accountInfo = account
    }
    override fun clear() {
        tokens = null
        cleared = true
    }
    override fun oidcTokens(): OidcTokens? = tokens
    override fun updateOidcTokens(tokens: OidcTokens) {
        this.tokens = tokens
    }
}
