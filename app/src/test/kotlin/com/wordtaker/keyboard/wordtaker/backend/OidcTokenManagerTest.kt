package com.wordtaker.keyboard.wordtaker.backend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OidcTokenManagerTest : FunSpec({

    test("disabled Passport clears persisted OIDC credentials without reading or refreshing them") {
        val store = MemoryAuthStore(OidcTokens("session-a", "family-a", 900L))
        val api = FakeOidcTokenApi()
        var legacyReads = 0
        val manager = OidcTokenManager(
            store = store,
            legacyTokenProvider = {
                legacyReads += 1
                "legacy-session"
            },
            tokenApi = api,
            nowEpochSeconds = { 1_000L },
            passportEnabled = false,
        )

        store.tokens shouldBe null
        manager.accessToken() shouldBe "legacy-session"
        manager.refreshAfterUnauthorized("session-a") shouldBe null
        api.refreshCalls shouldBe emptyList()
        legacyReads shouldBe 1
    }

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

    test("a near-expiry token without a refresh family remains usable until actual expiry") {
        val store = MemoryAuthStore(OidcTokens("session-a", null, 1_050L))
        val manager = OidcTokenManager(store, { null }, FakeOidcTokenApi()) { 1_000L }

        manager.accessToken() shouldBe "session-a"
        store.cleared shouldBe false
    }

    test("a null failed token forces refresh while signed-out refresh remains a no-op") {
        val signedOut = OidcTokenManager(MemoryAuthStore(), { null }, FakeOidcTokenApi()) { 1_000L }
        signedOut.refreshAfterUnauthorized(null) shouldBe null

        val store = MemoryAuthStore(OidcTokens("session-a", "family-a", 2_000L))
        val api = FakeOidcTokenApi().apply {
            next = OidcTokens("session-b", "family-b", 3_000L)
        }
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }

        manager.refreshAfterUnauthorized(null) shouldBe "session-b"
        api.refreshCalls shouldBe listOf("family-a")
    }

    test("forced refresh never falls back to the failed access token on a temporary error") {
        val failure = BackendException(BackendException.Kind.NETWORK, "transport detail")
        val store = MemoryAuthStore(OidcTokens("session-a", "family-a", 2_000L))
        val api = FakeOidcTokenApi().apply { this.failure = failure }
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }

        shouldThrow<BackendException> {
            manager.refreshAfterUnauthorized("session-a")
        }.kind shouldBe BackendException.Kind.NETWORK
        store.tokens shouldBe OidcTokens("session-a", "family-a", 2_000L)
        store.cleared shouldBe false
    }

    test("a refresh finishing after explicit logout never restores the cleared session") {
        val original = OidcTokens("session-a", "family-a", 1_050L)
        val store = MemoryAuthStore(original)
        val api = BlockingOidcTokenApi(OidcTokens("session-a2", "family-a2", 2_000L))
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = executor.submit<String?> { manager.accessToken() }
            api.refreshEntered.await(5, TimeUnit.SECONDS) shouldBe true

            store.clear()
            api.allowRefreshToFinish.countDown()

            result.get(5, TimeUnit.SECONDS) shouldBe null
            store.tokens shouldBe null
        } finally {
            api.allowRefreshToFinish.countDown()
            executor.shutdownNow()
        }
    }

    test("a refresh finishing after an account switch never overwrites the new account") {
        val original = OidcTokens("session-a", "family-a", 1_050L)
        val switched = OidcTokens("session-b", "family-b", 2_500L)
        val store = MemoryAuthStore(original)
        val api = BlockingOidcTokenApi(OidcTokens("session-a2", "family-a2", 2_000L))
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = executor.submit<String?> { manager.accessToken() }
            api.refreshEntered.await(5, TimeUnit.SECONDS) shouldBe true

            store.updateOidcTokens(switched)
            api.allowRefreshToFinish.countDown()

            result.get(5, TimeUnit.SECONDS) shouldBe switched.accessToken
            store.tokens shouldBe switched
        } finally {
            api.allowRefreshToFinish.countDown()
            executor.shutdownNow()
        }
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

    override fun revoke(refreshToken: String) = Unit
}

private class BlockingOidcTokenApi(
    private val next: OidcTokens,
) : PassportOidcTokenApi {
    val refreshEntered = CountDownLatch(1)
    val allowRefreshToFinish = CountDownLatch(1)

    override fun exchangeAuthorizationCode(code: String, codeVerifier: String): OidcTokens = next

    override fun refresh(refreshToken: String): OidcTokens {
        refreshEntered.countDown()
        check(allowRefreshToFinish.await(5, TimeUnit.SECONDS))
        return next
    }

    override fun revoke(refreshToken: String) = Unit
}

private class MemoryAuthStore(
    @Volatile var tokens: OidcTokens? = null,
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
    override fun clearOidc() {
        tokens = null
        cleared = true
    }
}
