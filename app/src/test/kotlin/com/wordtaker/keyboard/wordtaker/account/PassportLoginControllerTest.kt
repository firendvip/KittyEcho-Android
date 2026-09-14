package com.wordtaker.keyboard.wordtaker.account

import com.wordtaker.keyboard.wordtaker.backend.AccountApi
import com.wordtaker.keyboard.wordtaker.backend.AccountInfo
import com.wordtaker.keyboard.wordtaker.backend.AuthSessionStore
import com.wordtaker.keyboard.wordtaker.backend.BackendException
import com.wordtaker.keyboard.wordtaker.backend.LoginResult
import com.wordtaker.keyboard.wordtaker.backend.OidcTokens
import com.wordtaker.keyboard.wordtaker.backend.OrderInfo
import com.wordtaker.keyboard.wordtaker.backend.PassportOidcTokenApi
import com.wordtaker.keyboard.wordtaker.backend.PlanInfo
import com.wordtaker.keyboard.wordtaker.backend.QuotaInfo
import com.wordtaker.keyboard.wordtaker.backend.RedeemOutcome
import com.wordtaker.keyboard.wordtaker.backend.WechatAuthUrl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PassportLoginControllerTest : FunSpec({

    test("availability and an unavailable begin fail closed") {
        val pending = ControllerPendingStore()
        val flow = PassportOidcFlow(
            PassportOidcConfig(false, "https://auth.yaa3.com", "kittyecho-android", "kittyecho://auth"),
            pending,
        )
        val store = ControllerAuthStore()
        val controller = PassportLoginController(
            flow,
            ControllerTokenApi(),
            AccountRepository(ControllerAccountApi(), store),
        )

        controller.isAvailable shouldBe false
        controller.begin().shouldBeInstanceOf<PassportStartResult.Unavailable>()
        controller.status.value.shouldBeInstanceOf<PassportLoginStatus.Error>()
        pending.value shouldBe null
    }

    test("default-off callback performs no central request and no legacy fallback") {
        runTest {
            val pending = ControllerPendingStore(
                PendingPassportAuthorization("s".repeat(24), "n".repeat(24), "v".repeat(43), 1_000L),
            )
            val store = ControllerAuthStore()
            val api = ControllerAccountApi()
            val tokens = ControllerTokenApi()
            val controller = PassportLoginController(
                PassportOidcFlow(
                    PassportOidcConfig(false, "https://auth.yaa3.com", "kittyecho-android", "kittyecho://auth"),
                    pending,
                    nowMillis = { 1_000L },
                ),
                tokens,
                AccountRepository(api, store, scope = backgroundScope),
                StandardTestDispatcher(testScheduler),
            )

            controller.handleCallback(
                "kittyecho://auth?code=authorization-code-123456&state=${"s".repeat(24)}",
            ).shouldBeInstanceOf<AccountResult.Err>()
            tokens.exchangeCalls shouldBe 0
            api.legacyLoginCalls shouldBe 0
            pending.value shouldBe null
        }
    }

    test("cold-start recovery remains visible and cancellation clears it") {
        val pending = ControllerPendingStore(
            PendingPassportAuthorization("s".repeat(24), "n".repeat(24), "v".repeat(43), 1_000L),
        )
        val fixture = fixture(pending)

        fixture.controller.status.value shouldBe PassportLoginStatus.AwaitingBrowser
        fixture.controller.cancel()
        fixture.controller.status.value shouldBe PassportLoginStatus.Idle
        pending.value shouldBe null
    }

    test("cold-start rejects expired persisted state before exposing browser recovery") {
        val pending = ControllerPendingStore(
            PendingPassportAuthorization(
                "s".repeat(24),
                "n".repeat(24),
                "v".repeat(43),
                1_000L - PassportOidcFlow.PENDING_TTL_MS - 1,
            ),
        )
        val fixture = fixture(pending)

        fixture.controller.status.value shouldBe PassportLoginStatus.Idle
        pending.value shouldBe null
    }

    test("cold-start callback consumes state once and never exchanges a replay") {
        runTest {
            val pending = ControllerPendingStore()
            val first = fixture(pending, StandardTestDispatcher(testScheduler))
            first.controller.begin()
            val state = pending.value!!.state

            val recreated = fixture(pending, StandardTestDispatcher(testScheduler))
            recreated.controller.status.value shouldBe PassportLoginStatus.AwaitingBrowser
            val callback = "kittyecho://auth?code=authorization-code-123456&state=$state"
            recreated.controller.handleCallback(callback).shouldBeInstanceOf<AccountResult.Ok<Unit>>()
            recreated.controller.handleCallback(callback).shouldBeInstanceOf<AccountResult.Err>()

            recreated.tokens.exchangeCalls shouldBe 1
            pending.value shouldBe null
        }
    }

    test("explicit logout clears local state before best-effort family revocation") {
        runTest {
            val fixture = fixture(dispatcher = StandardTestDispatcher(testScheduler))
            fixture.controller.begin()
            fixture.store.set("legacy-session", null)
            fixture.store.setOidc(OidcTokens("session-a", "family-a", 2_000L), null)
            fixture.tokens.onRevoke = {
                fixture.pending.value shouldBe null
                fixture.store.isLoggedIn() shouldBe false
                fixture.controller.status.value shouldBe PassportLoginStatus.Idle
            }

            fixture.controller.logout()

            fixture.pending.value shouldBe null
            fixture.store.isLoggedIn() shouldBe false
            fixture.store.oidc shouldBe null
            fixture.controller.status.value shouldBe PassportLoginStatus.Idle
            fixture.tokens.revokeCalls shouldBe emptyList()
            advanceUntilIdle()
            fixture.tokens.revokeCalls shouldBe listOf("family-a")
        }
    }

    test("disabled logout stays local and a failed revocation never restores credentials") {
        runTest {
            val disabledPending = ControllerPendingStore(
                PendingPassportAuthorization("s".repeat(24), "n".repeat(24), "v".repeat(43), 1_000L),
            )
            val disabledStore = ControllerAuthStore().apply {
                setOidc(OidcTokens("session-a", "family-a", 2_000L), null)
            }
            val disabledTokens = ControllerTokenApi()
            val disabled = PassportLoginController(
                PassportOidcFlow(
                    PassportOidcConfig(false, "https://auth.yaa3.com", "kittyecho-android", "kittyecho://auth"),
                    disabledPending,
                    nowMillis = { 1_000L },
                ),
                disabledTokens,
                AccountRepository(disabledTokens.accountApi, disabledStore, scope = backgroundScope),
                StandardTestDispatcher(testScheduler),
            )
            disabled.logout()
            advanceUntilIdle()
            disabledTokens.revokeCalls shouldBe emptyList()
            disabledStore.isLoggedIn() shouldBe false
            disabledPending.value shouldBe null

            val enabled = fixture(dispatcher = StandardTestDispatcher(testScheduler))
            enabled.store.setOidc(OidcTokens("session-b", "family-b", 2_000L), null)
            enabled.tokens.revokeFailure = IllegalStateException("private revoke detail")
            enabled.controller.logout()
            advanceUntilIdle()
            enabled.tokens.revokeCalls shouldBe listOf("family-b")
            enabled.store.isLoggedIn() shouldBe false
            enabled.store.oidc shouldBe null
            enabled.controller.status.value shouldBe PassportLoginStatus.Idle
        }
    }

    test("logout racing a callback exchange prevents late token persistence and keeps idle state") {
        val pending = ControllerPendingStore()
        var generated = 0
        val flow = PassportOidcFlow(
            PassportOidcConfig(true, "https://auth.yaa3.com", "kittyecho-android", "kittyecho://auth"),
            pending,
            randomBytes = { size -> ByteArray(size) { (generated++ and 0xff).toByte() } },
            nowMillis = { 1_000L },
        )
        val store = ControllerAuthStore()
        val accountApi = ControllerAccountApi()
        val repository = AccountRepository(accountApi, store, ioDispatcher = Dispatchers.IO)
        val tokenApi = BlockingControllerTokenApi()
        val controller = PassportLoginController(flow, tokenApi, repository, Dispatchers.IO)
        controller.begin()
        val state = pending.value!!.state
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = executor.submit<AccountResult<Unit>> {
                runBlocking {
                    controller.handleCallback(
                        "kittyecho://auth?code=authorization-code-123456&state=$state",
                    )
                }
            }
            tokenApi.exchangeEntered.await(5, TimeUnit.SECONDS) shouldBe true

            controller.logout()
            tokenApi.allowExchangeToFinish.countDown()

            result.get(5, TimeUnit.SECONDS).shouldBeInstanceOf<AccountResult.Err>()
            pending.value shouldBe null
            store.isLoggedIn() shouldBe false
            store.oidc shouldBe null
            controller.status.value shouldBe PassportLoginStatus.Idle
        } finally {
            tokenApi.allowExchangeToFinish.countDown()
            executor.shutdownNow()
        }
    }

    test("begin and cancel cannot return inside callback check-before-set or allow later writes") {
        assertInvalidationWaitsForCallbackPersist(
            invalidate = PassportLoginController::cancel,
            expectedStatus = PassportLoginStatus.Idle,
            expectPending = false,
        )
        assertInvalidationWaitsForCallbackPersist(
            invalidate = { it.begin() },
            expectedStatus = PassportLoginStatus.AwaitingBrowser,
            expectPending = true,
        )
    }

    test("a valid callback exchanges PKCE code, stores rotating tokens and hydrates profile") {
        runTest {
            val fixture = fixture(dispatcher = StandardTestDispatcher(testScheduler))
            val start = fixture.controller.begin().shouldBeInstanceOf<PassportStartResult.Ready>()
            val state = fixture.pending.value!!.state
            val verifier = fixture.pending.value!!.codeVerifier
            val nonce = fixture.pending.value!!.nonce

            fixture.controller.handleCallback(
                "kittyecho://auth?code=authorization-code-123456&state=$state",
            ).shouldBeInstanceOf<AccountResult.Ok<Unit>>()

            fixture.tokens.exchangedCode shouldBe "authorization-code-123456"
            fixture.tokens.exchangedVerifier shouldBe verifier
            fixture.tokens.exchangedNonce shouldBe nonce
            fixture.store.oidc shouldBe fixture.tokens.next
            fixture.repository.state.value.account?.userId shouldBe "passport-user"
            fixture.controller.status.value shouldBe PassportLoginStatus.Idle
            start.authorizationUrl.contains("client_secret") shouldBe false
        }
    }

    test("token endpoint network errors stay generic and never fall back to a legacy login") {
        runTest {
            val fixture = fixture(dispatcher = StandardTestDispatcher(testScheduler))
            fixture.tokens.failure = BackendException(BackendException.Kind.NETWORK, "private detail")
            fixture.controller.begin()
            val state = fixture.pending.value!!.state

            val result = fixture.controller.handleCallback(
                "kittyecho://auth?code=authorization-code-123456&state=$state",
            ).shouldBeInstanceOf<AccountResult.Err>()

            result.message shouldBe "无法连接服务器，请检查网络"
            result.message.contains("private detail") shouldBe false
            fixture.store.oidc shouldBe null
            fixture.api.legacyLoginCalls shouldBe 0
            fixture.controller.status.value.shouldBeInstanceOf<PassportLoginStatus.Error>()
        }
    }

    test("provider cancellation and rejected callbacks publish bounded states") {
        runTest {
            val cancelled = fixture(dispatcher = StandardTestDispatcher(testScheduler))
            cancelled.controller.begin()
            val cancelState = cancelled.pending.value!!.state
            cancelled.controller.handleCallback(
                "kittyecho://auth?error=access_denied&state=$cancelState",
            ).shouldBeInstanceOf<AccountResult.Err>().message shouldBe "登录已取消"
            cancelled.controller.status.value shouldBe PassportLoginStatus.Idle

            val rejected = fixture(dispatcher = StandardTestDispatcher(testScheduler))
            rejected.controller.begin()
            val result = rejected.controller.handleCallback(
                "kittyecho://auth?code=authorization-code-123456&state=wrong-state-value",
            ).shouldBeInstanceOf<AccountResult.Err>()
            result.message shouldBe PassportOidcFlow.MESSAGE_INVALID_CALLBACK
            rejected.controller.status.value.shouldBeInstanceOf<PassportLoginStatus.Error>()
        }
    }

    test("unexpected token exchange failures remain generic") {
        runTest {
            val fixture = fixture(dispatcher = StandardTestDispatcher(testScheduler))
            fixture.tokens.failure = IllegalStateException("sensitive failure")
            fixture.controller.begin()
            val state = fixture.pending.value!!.state

            fixture.controller.handleCallback(
                "kittyecho://auth?code=authorization-code-123456&state=$state",
            ).shouldBeInstanceOf<AccountResult.Err>().message shouldBe "请求失败，请稍后再试"
            fixture.controller.status.value.shouldBeInstanceOf<PassportLoginStatus.Error>()
            fixture.store.oidc shouldBe null
        }
    }
})

private data class ControllerFixture(
    val controller: PassportLoginController,
    val repository: AccountRepository,
    val pending: ControllerPendingStore,
    val tokens: ControllerTokenApi,
    val store: ControllerAuthStore,
    val api: ControllerAccountApi,
)

private fun assertInvalidationWaitsForCallbackPersist(
    invalidate: (PassportLoginController) -> Unit,
    expectedStatus: PassportLoginStatus,
    expectPending: Boolean,
) {
    val pending = ControllerPendingStore()
    var generated = 0
    val flow = PassportOidcFlow(
        PassportOidcConfig(true, "https://auth.yaa3.com", "kittyecho-android", "kittyecho://auth"),
        pending,
        randomBytes = { size -> ByteArray(size) { (generated++ and 0xff).toByte() } },
        nowMillis = { 1_000L },
    )
    val setEntered = CountDownLatch(1)
    val allowSet = CountDownLatch(1)
    val store = BlockingControllerAuthStore(setEntered, allowSet)
    val repository = AccountRepository(ControllerAccountApi(), store, ioDispatcher = Dispatchers.IO)
    val controller = PassportLoginController(flow, ControllerTokenApi(), repository, Dispatchers.IO)
    controller.begin()
    val state = pending.value!!.state
    val executor = Executors.newFixedThreadPool(2)

    try {
        val callback = executor.submit<AccountResult<Unit>> {
            runBlocking {
                controller.handleCallback(
                    "kittyecho://auth?code=authorization-code-123456&state=$state",
                )
            }
        }
        setEntered.await(5, TimeUnit.SECONDS) shouldBe true

        val invalidationReturned = CountDownLatch(1)
        val invalidation = executor.submit {
            invalidate(controller)
            invalidationReturned.countDown()
        }
        invalidationReturned.await(200, TimeUnit.MILLISECONDS) shouldBe false

        allowSet.countDown()
        invalidation.get(5, TimeUnit.SECONDS)
        val writesWhenInvalidationReturned = store.persistentWrites
        callback.get(5, TimeUnit.SECONDS)

        store.persistentWrites shouldBe writesWhenInvalidationReturned
        controller.status.value shouldBe expectedStatus
        (pending.value != null) shouldBe expectPending
    } finally {
        allowSet.countDown()
        executor.shutdownNow()
    }
}

private fun fixture(
    pending: ControllerPendingStore = ControllerPendingStore(),
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = StandardTestDispatcher(),
): ControllerFixture {
    var generated = 0
    val flow = PassportOidcFlow(
        PassportOidcConfig(true, "https://auth.yaa3.com", "kittyecho-android", "kittyecho://auth"),
        pending,
        randomBytes = { size -> ByteArray(size) { (generated++ and 0xff).toByte() } },
        nowMillis = { 1_000L },
    )
    val store = ControllerAuthStore()
    val api = ControllerAccountApi()
    val repository = AccountRepository(api, store, ioDispatcher = dispatcher)
    val tokens = ControllerTokenApi()
    val controller = PassportLoginController(flow, tokens, repository, dispatcher)
    return ControllerFixture(controller, repository, pending, tokens, store, api)
}

private class ControllerPendingStore(
    var value: PendingPassportAuthorization? = null,
) : PassportPendingStore {
    override fun read(): PendingPassportAuthorization? = value
    override fun write(pending: PendingPassportAuthorization): Boolean {
        value = pending
        return true
    }
    override fun clear() {
        value = null
    }
}

private class ControllerTokenApi : PassportOidcTokenApi {
    val next = OidcTokens("passport-access", "passport-refresh", 2_000L)
    var failure: Throwable? = null
    var exchangedCode: String? = null
    var exchangedVerifier: String? = null
    var exchangedNonce: String? = null
    var exchangeCalls: Int = 0
    var revokeFailure: Throwable? = null
    var onRevoke: (() -> Unit)? = null
    val revokeCalls = mutableListOf<String>()
    val accountApi = ControllerAccountApi()

    override fun exchangeAuthorizationCode(code: String, codeVerifier: String, expectedNonce: String): OidcTokens {
        exchangeCalls += 1
        failure?.let { throw it }
        exchangedCode = code
        exchangedVerifier = codeVerifier
        exchangedNonce = expectedNonce
        return next
    }

    override fun refresh(refreshToken: String): OidcTokens = next

    override fun revoke(refreshToken: String) {
        revokeCalls += refreshToken
        onRevoke?.invoke()
        revokeFailure?.let { throw it }
    }
}

private class BlockingControllerTokenApi : PassportOidcTokenApi {
    val exchangeEntered = CountDownLatch(1)
    val allowExchangeToFinish = CountDownLatch(1)

    override fun exchangeAuthorizationCode(code: String, codeVerifier: String, expectedNonce: String): OidcTokens {
        exchangeEntered.countDown()
        check(allowExchangeToFinish.await(5, TimeUnit.SECONDS))
        return OidcTokens("session-a", "family-a", 2_000L)
    }

    override fun refresh(refreshToken: String): OidcTokens = error("not used")

    override fun revoke(refreshToken: String) = Unit
}

private class ControllerAuthStore : AuthSessionStore {
    @Volatile var oidc: OidcTokens? = null
    @Volatile private var profile: AccountInfo? = null
    @Volatile private var generation = 0L
    override fun credentialGeneration(): Long = generation
    override fun isLoggedIn(): Boolean = oidc != null
    override fun account(): AccountInfo? = profile
    override fun set(accessToken: String, account: AccountInfo?) = Unit
    override fun setOidc(tokens: OidcTokens, account: AccountInfo?) {
        oidc = tokens
        profile = account
        generation += 1
    }
    override fun oidcTokens(): OidcTokens? = oidc
    override fun updateOidcTokens(tokens: OidcTokens) {
        oidc = tokens
    }
    override fun clearOidc() {
        oidc = null
        profile = null
        generation += 1
    }
    override fun updateAccount(account: AccountInfo?) {
        profile = account
    }
    override fun clear() {
        oidc = null
        profile = null
        generation += 1
    }
}

private class BlockingControllerAuthStore(
    private val setEntered: CountDownLatch,
    private val allowSet: CountDownLatch,
) : AuthSessionStore {
    @Volatile private var oidc: OidcTokens? = null
    @Volatile private var profile: AccountInfo? = null
    @Volatile var persistentWrites: Int = 0
        private set
    @Volatile private var generation = 0L

    override fun credentialGeneration(): Long = generation
    override fun isLoggedIn(): Boolean = oidc != null
    override fun account(): AccountInfo? = profile
    override fun set(accessToken: String, account: AccountInfo?) = Unit
    override fun setOidc(tokens: OidcTokens, account: AccountInfo?) {
        setEntered.countDown()
        check(allowSet.await(5, TimeUnit.SECONDS))
        oidc = tokens
        profile = account
        persistentWrites += 1
        generation += 1
    }
    override fun oidcTokens(): OidcTokens? = oidc
    override fun updateOidcTokens(tokens: OidcTokens) {
        oidc = tokens
    }
    override fun clearOidc() {
        oidc = null
        profile = null
        generation += 1
    }
    override fun updateAccount(account: AccountInfo?) {
        profile = account
        persistentWrites += 1
    }
    override fun clear() {
        oidc = null
        profile = null
        generation += 1
    }
}

private class ControllerAccountApi : AccountApi {
    var legacyLoginCalls = 0
    override fun getQuota() = QuotaInfo(null, false, null, null, null, null, null)
    override fun authEmailSend(email: String) = Unit
    override fun authEmailLogin(email: String, code: String, inviteCode: String?): LoginResult {
        legacyLoginCalls += 1
        error("legacy login must stay unreachable")
    }
    override fun authSmsSend(phone: String) = Unit
    override fun authSmsLogin(phone: String, code: String, inviteCode: String?): LoginResult {
        legacyLoginCalls += 1
        error("legacy login must stay unreachable")
    }
    override fun getWechatAuthUrl() = WechatAuthUrl("", null)
    override fun authWechatLogin(code: String, inviteCode: String?): LoginResult {
        legacyLoginCalls += 1
        error("legacy login must stay unreachable")
    }
    override fun authMe() = JSONObject("""{"account":{"userId":"passport-user","phone":"13800138000"}}""")
    override fun redeem(code: String) = RedeemOutcome(null, null)
    override fun listPlans(): List<PlanInfo> = emptyList()
    override fun createOrder(planCode: String, channel: String) =
        OrderInfo("", null, null, null, null, null)
}
