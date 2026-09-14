package com.wordtaker.keyboard.wordtaker.backend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
        manager.accessSession().accessToken shouldBe "legacy-session"
        manager.refreshAfterUnauthorized(AuthRequestSession(0L, "session-a")) shouldBe null
        api.refreshCalls shouldBe emptyList()
        legacyReads shouldBe 1
    }

    test("legacy sessions and healthy OIDC access tokens do not call refresh") {
        val api = FakeOidcTokenApi()
        val legacyStore = MemoryAuthStore()
        OidcTokenManager(legacyStore, { "legacy-access" }, api) { 1_000L }
            .accessSession().accessToken shouldBe "legacy-access"

        val oidcStore = MemoryAuthStore(OidcTokens("oidc-access", "refresh-1", 2_000L))
        OidcTokenManager(oidcStore, { "legacy-access" }, api) { 1_000L }
            .accessSession().accessToken shouldBe "oidc-access"
        api.refreshCalls shouldBe emptyList()
    }

    test("near-expiry and forced refresh rotate stored credentials") {
        val store = MemoryAuthStore(OidcTokens("access-1", "refresh-1", 1_050L))
        val api = FakeOidcTokenApi().apply {
            next = OidcTokens("access-2", "refresh-2", 1_900L)
        }
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }

        val firstSession = manager.accessSession()
        firstSession.accessToken shouldBe "access-2"
        store.tokens shouldBe OidcTokens("access-2", "refresh-2", 1_900L)
        api.refreshCalls shouldBe listOf("refresh-1")

        api.next = OidcTokens("access-3", "refresh-3", 2_000L)
        manager.refreshAfterUnauthorized(firstSession)?.accessToken shouldBe "access-3"
        store.tokens shouldBe OidcTokens("access-3", "refresh-3", 2_000L)
    }

    test("a failed token from an older concurrent request never refreshes the new token again") {
        val store = MemoryAuthStore(OidcTokens("access-new", "refresh-new", 2_000L))
        val api = FakeOidcTokenApi()
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }

        manager.refreshAfterUnauthorized(
            AuthRequestSession(store.credentialGeneration(), "access-old"),
        )?.accessToken shouldBe "access-new"
        api.refreshCalls shouldBe emptyList()
    }

    test("temporary refresh failure uses a still-valid token but never an expired token") {
        val network = BackendException(BackendException.Kind.NETWORK, "transport detail")
        val api = FakeOidcTokenApi().apply { failure = network }
        val stillValid = MemoryAuthStore(OidcTokens("access-1", "refresh-1", 1_010L))
        OidcTokenManager(stillValid, { null }, api) { 1_000L }
            .accessSession().accessToken shouldBe "access-1"
        stillValid.cleared shouldBe false

        val expired = MemoryAuthStore(OidcTokens("access-1", "refresh-1", 999L))
        val error = shouldThrow<BackendException> {
            OidcTokenManager(expired, { null }, api) { 1_000L }.accessSession()
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
            OidcTokenManager(invalidStore, { null }, invalidApi) { 1_000L }.accessSession()
        }.isAuthExpired shouldBe true
        invalidStore.cleared shouldBe true

        val noRefreshStore = MemoryAuthStore(OidcTokens("access-1", null, 999L))
        shouldThrow<BackendException> {
            OidcTokenManager(noRefreshStore, { null }, FakeOidcTokenApi()) { 1_000L }.accessSession()
        }.isAuthExpired shouldBe true
        noRefreshStore.cleared shouldBe true
    }

    test("a near-expiry token without a refresh family remains usable until actual expiry") {
        val store = MemoryAuthStore(OidcTokens("session-a", null, 1_050L))
        val manager = OidcTokenManager(store, { null }, FakeOidcTokenApi()) { 1_000L }

        manager.accessSession().accessToken shouldBe "session-a"
        store.cleared shouldBe false
    }

    test("a null failed token forces refresh while signed-out refresh remains a no-op") {
        val signedOut = OidcTokenManager(MemoryAuthStore(), { null }, FakeOidcTokenApi()) { 1_000L }
        signedOut.refreshAfterUnauthorized(AuthRequestSession(0L, null)) shouldBe null

        val store = MemoryAuthStore(OidcTokens("session-a", "family-a", 2_000L))
        val api = FakeOidcTokenApi().apply {
            next = OidcTokens("session-b", "family-b", 3_000L)
        }
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }

        manager.refreshAfterUnauthorized(
            AuthRequestSession(store.credentialGeneration(), null),
        )?.accessToken shouldBe "session-b"
        api.refreshCalls shouldBe listOf("family-a")
    }

    test("forced refresh never falls back to the failed access token on a temporary error") {
        val failure = BackendException(BackendException.Kind.NETWORK, "transport detail")
        val store = MemoryAuthStore(OidcTokens("session-a", "family-a", 2_000L))
        val api = FakeOidcTokenApi().apply { this.failure = failure }
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }

        shouldThrow<BackendException> {
            manager.refreshAfterUnauthorized(
                AuthRequestSession(store.credentialGeneration(), "session-a"),
            )
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
            val result = executor.submit<AuthRequestSession> { manager.accessSession() }
            api.refreshEntered.await(5, TimeUnit.SECONDS) shouldBe true

            store.clear()
            api.allowRefreshToFinish.countDown()

            val error = shouldThrow<java.util.concurrent.ExecutionException> {
                result.get(5, TimeUnit.SECONDS)
            }.cause as BackendException
            error.code shouldBe BackendException.CODE_AUTH_SESSION_CHANGED
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
            val result = executor.submit<AuthRequestSession> { manager.accessSession() }
            api.refreshEntered.await(5, TimeUnit.SECONDS) shouldBe true

            store.setOidc(switched, null)
            api.allowRefreshToFinish.countDown()

            val error = shouldThrow<java.util.concurrent.ExecutionException> {
                result.get(5, TimeUnit.SECONDS)
            }.cause as BackendException
            error.code shouldBe BackendException.CODE_AUTH_SESSION_CHANGED
            store.tokens shouldBe switched
        } finally {
            api.allowRefreshToFinish.countDown()
            executor.shutdownNow()
        }
    }

    test("BackendClient never replays account A request after its 401 arrives in account B session") {
        val requestEntered = CountDownLatch(1)
        val allowUnauthorized = CountDownLatch(1)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestEntered.countDown()
                    check(allowUnauthorized.await(5, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(401).setBody("{}")
                }
            }
            start()
        }
        val accountA = OidcTokens("account-a-access", "account-a-refresh", 2_000L)
        val accountB = OidcTokens("account-b-access", "account-b-refresh", 3_000L)
        val store = MemoryAuthStore(accountA)
        val manager = OidcTokenManager(store, { null }, FakeOidcTokenApi()) { 1_000L }
        val client = BackendClient(
            deviceId = TEST_DEVICE_ID,
            authSessionProvider = manager::accessSession,
            authSessionRefresher = manager::refreshAfterUnauthorized,
            baseUrl = server.url("/aiapi").toString(),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = executor.submit { client.polish("account A payload", "normal") }
            requestEntered.await(5, TimeUnit.SECONDS) shouldBe true

            store.setOidc(accountB, null)
            allowUnauthorized.countDown()

            val error = shouldThrow<java.util.concurrent.ExecutionException> {
                result.get(5, TimeUnit.SECONDS)
            }.cause as BackendException
            error.code shouldBe BackendException.CODE_AUTH_SESSION_CHANGED
            error.isAuthExpired shouldBe false
            server.requestCount shouldBe 1
            server.takeRequest().getHeader("Authorization") shouldBe "Bearer account-a-access"
            store.tokens shouldBe accountB
        } finally {
            allowUnauthorized.countDown()
            executor.shutdownNow()
            server.shutdown()
        }
    }

    test("BackendClient never replays A after a late A refresh completes in account B session") {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            start()
        }
        val accountA = OidcTokens("account-a-access", "account-a-refresh", 2_000L)
        val accountB = OidcTokens("account-b-access", "account-b-refresh", 3_000L)
        val store = MemoryAuthStore(accountA)
        val api = BlockingOidcTokenApi(
            OidcTokens("account-a-rotated", "account-a-refresh-2", 3_000L),
        )
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }
        val client = BackendClient(
            deviceId = TEST_DEVICE_ID,
            authSessionProvider = manager::accessSession,
            authSessionRefresher = manager::refreshAfterUnauthorized,
            baseUrl = server.url("/aiapi").toString(),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = executor.submit { client.polish("account A payload", "normal") }
            api.refreshEntered.await(5, TimeUnit.SECONDS) shouldBe true

            store.setOidc(accountB, null)
            api.allowRefreshToFinish.countDown()

            val error = shouldThrow<java.util.concurrent.ExecutionException> {
                result.get(5, TimeUnit.SECONDS)
            }.cause as BackendException
            error.code shouldBe BackendException.CODE_AUTH_SESSION_CHANGED
            server.requestCount shouldBe 1
            store.tokens shouldBe accountB
        } finally {
            api.allowRefreshToFinish.countDown()
            executor.shutdownNow()
            server.shutdown()
        }
    }

    test("late temporary and permanent A refresh failures never clear or expose account B") {
        val failures = listOf(
            BackendException(BackendException.Kind.NETWORK, "temporary"),
            BackendException(
                BackendException.Kind.HTTP,
                "permanent",
                code = BackendException.CODE_NOT_LOGGED_IN,
                status = 401,
            ),
        )

        failures.forEach { failure ->
            val accountA = OidcTokens("account-a-access", "account-a-refresh", 1_050L)
            val accountB = OidcTokens("account-b-access", "account-b-refresh", 3_000L)
            val store = MemoryAuthStore(accountA)
            val api = BlockingFailingOidcTokenApi(failure)
            val manager = OidcTokenManager(store, { null }, api) { 1_000L }
            val executor = Executors.newSingleThreadExecutor()

            try {
                val result = executor.submit<AuthRequestSession> { manager.accessSession() }
                api.refreshEntered.await(5, TimeUnit.SECONDS) shouldBe true

                store.setOidc(accountB, null)
                api.allowRefreshToFinish.countDown()

                val error = shouldThrow<java.util.concurrent.ExecutionException> {
                    result.get(5, TimeUnit.SECONDS)
                }.cause as BackendException
                error.code shouldBe BackendException.CODE_AUTH_SESSION_CHANGED
                error.isAuthExpired shouldBe false
                store.tokens shouldBe accountB
            } finally {
                api.allowRefreshToFinish.countDown()
                executor.shutdownNow()
            }
        }
    }

    test("logout and same-token login ABA invalidates the original request generation") {
        val store = MemoryAuthStore(OidcTokens("same-access", "family-a", 2_000L))
        val manager = OidcTokenManager(store, { null }, FakeOidcTokenApi()) { 1_000L }
        val original = manager.accessSession()

        store.clear()
        store.setOidc(OidcTokens("same-access", "family-b", 3_000L), null)

        shouldThrow<BackendException> {
            manager.refreshAfterUnauthorized(original)
        }.code shouldBe BackendException.CODE_AUTH_SESSION_CHANGED
        store.tokens shouldBe OidcTokens("same-access", "family-b", 3_000L)
    }

    test("an old expired session without a refresh token cannot clear the replacement account") {
        val store = MemoryAuthStore(OidcTokens("account-a", null, 2_000L))
        val manager = OidcTokenManager(store, { null }, FakeOidcTokenApi()) { 1_000L }
        val accountARequest = manager.accessSession()
        val accountB = OidcTokens("account-b", "family-b", 3_000L)

        store.setOidc(accountB, null)

        shouldThrow<BackendException> {
            manager.refreshAfterUnauthorized(accountARequest)
        }.code shouldBe BackendException.CODE_AUTH_SESSION_CHANGED
        store.tokens shouldBe accountB
        store.cleared shouldBe false
    }

    test("forced refresh with no refresh family expires exactly the current session") {
        val store = MemoryAuthStore(OidcTokens("account-a", null, 2_000L))
        val manager = OidcTokenManager(store, { null }, FakeOidcTokenApi()) { 1_000L }
        val request = manager.accessSession()

        shouldThrow<BackendException> {
            manager.refreshAfterUnauthorized(request)
        }.isAuthExpired shouldBe true
        store.tokens shouldBe null
        store.cleared shouldBe true
    }

    test("a concurrent same-generation rotation aborts a redundant refresh without overwriting it") {
        val original = OidcTokens("account-a", "family-a", 1_050L)
        val rotated = OidcTokens("account-a-new", "family-a-new", 3_000L)
        val store = MemoryAuthStore(original)
        val api = BlockingOidcTokenApi(OidcTokens("stale-result", "stale-family", 4_000L))
        val manager = OidcTokenManager(store, { null }, api) { 1_000L }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = executor.submit<AuthRequestSession> { manager.accessSession() }
            api.refreshEntered.await(5, TimeUnit.SECONDS) shouldBe true
            val generation = store.credentialGeneration()

            store.updateOidcTokens(rotated)
            store.credentialGeneration() shouldBe generation
            api.allowRefreshToFinish.countDown()

            val error = shouldThrow<java.util.concurrent.ExecutionException> {
                result.get(5, TimeUnit.SECONDS)
            }.cause as BackendException
            error.code shouldBe BackendException.CODE_AUTH_SESSION_CHANGED
            store.tokens shouldBe rotated
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

    override fun exchangeAuthorizationCode(code: String, codeVerifier: String, expectedNonce: String): OidcTokens = next

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

    override fun exchangeAuthorizationCode(code: String, codeVerifier: String, expectedNonce: String): OidcTokens = next

    override fun refresh(refreshToken: String): OidcTokens {
        refreshEntered.countDown()
        check(allowRefreshToFinish.await(5, TimeUnit.SECONDS))
        return next
    }

    override fun revoke(refreshToken: String) = Unit
}

private class BlockingFailingOidcTokenApi(
    private val failure: BackendException,
) : PassportOidcTokenApi {
    val refreshEntered = CountDownLatch(1)
    val allowRefreshToFinish = CountDownLatch(1)

    override fun exchangeAuthorizationCode(code: String, codeVerifier: String, expectedNonce: String) =
        error("not used")

    override fun refresh(refreshToken: String): OidcTokens {
        refreshEntered.countDown()
        check(allowRefreshToFinish.await(5, TimeUnit.SECONDS))
        throw failure
    }

    override fun revoke(refreshToken: String) = Unit
}

private class MemoryAuthStore(
    @Volatile var tokens: OidcTokens? = null,
) : AuthSessionStore {
    @Volatile
    private var generation = if (tokens == null) 0L else 1L
    var accountInfo: AccountInfo? = null
    var cleared = false

    @Synchronized override fun credentialGeneration(): Long = generation
    @Synchronized override fun isLoggedIn(): Boolean = tokens != null
    @Synchronized override fun account(): AccountInfo? = accountInfo
    @Synchronized override fun set(accessToken: String, account: AccountInfo?) {
        tokens = null
        accountInfo = account
        generation += 1
    }
    @Synchronized override fun setOidc(tokens: OidcTokens, account: AccountInfo?) {
        this.tokens = tokens
        accountInfo = account
        generation += 1
    }
    @Synchronized override fun updateAccount(account: AccountInfo?) {
        accountInfo = account
    }
    @Synchronized override fun clear() {
        tokens = null
        cleared = true
        generation += 1
    }
    @Synchronized override fun oidcTokens(): OidcTokens? = tokens
    @Synchronized override fun updateOidcTokens(tokens: OidcTokens) {
        this.tokens = tokens
    }
    @Synchronized override fun clearOidc() {
        tokens = null
        cleared = true
        generation += 1
    }
}

private const val TEST_DEVICE_ID = "0123456789abcdef0123456789abcdef"
