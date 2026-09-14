package com.wordtaker.keyboard.wordtaker.account

import com.wordtaker.keyboard.wordtaker.backend.AccountApi
import com.wordtaker.keyboard.wordtaker.backend.AccountInfo
import com.wordtaker.keyboard.wordtaker.backend.AuthSessionStore
import com.wordtaker.keyboard.wordtaker.backend.BackendException
import com.wordtaker.keyboard.wordtaker.backend.LoginResult
import com.wordtaker.keyboard.wordtaker.backend.OrderInfo
import com.wordtaker.keyboard.wordtaker.backend.OidcTokens
import com.wordtaker.keyboard.wordtaker.backend.PlanInfo
import com.wordtaker.keyboard.wordtaker.backend.PolishOutcome
import com.wordtaker.keyboard.wordtaker.backend.QuotaInfo
import com.wordtaker.keyboard.wordtaker.backend.RedeemOutcome
import com.wordtaker.keyboard.wordtaker.backend.WechatAuthUrl
import com.wordtaker.keyboard.wordtaker.network.InternetConnection
import com.wordtaker.keyboard.wordtaker.polish.OnlineOnlyPolisher
import com.wordtaker.keyboard.wordtaker.polish.PolishBackend
import com.wordtaker.keyboard.wordtaker.polish.PolishDiagnostics
import com.wordtaker.keyboard.wordtaker.polish.PolishEvent
import com.wordtaker.keyboard.wordtaker.polish.RealPolisher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRepositoryTest : FunSpec({

    test("login without an account immediately hydrates auth me inside the repository") {
        runTest {
            val api = FakeAccountApi().apply {
                loginResult = loginResult(account = null)
                authMeResponses += {
                    JSONObject("""{"account":{"userId":"user-1","nickname":"小猫"}}""")
                }
            }
            val store = FakeAuthSessionStore()
            val repository = repository(api, store)

            repository.loginWithEmail("cat@example.com", "123456")
                .shouldBeInstanceOf<AccountResult.Ok<Unit>>()

            api.authMeCalls shouldBe 1
            store.token shouldBe "access-token"
            repository.state.value.profile
                .shouldBeInstanceOf<AccountProfileState.Available>()
                .account.nickname shouldBe "小猫"
        }
    }

    test("an existing token hydrates without waiting for an account page mount") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val api = FakeAccountApi().apply {
                authMeResponses += {
                    JSONObject("""{"account":{"userId":"returning-user","email":"cat@example.com"}}""")
                }
            }
            val store = FakeAuthSessionStore("persisted-token", storedAccount = null)

            val repository = AccountRepository(
                client = api,
                tokenStore = store,
                scope = this,
                ioDispatcher = dispatcher,
            )
            advanceUntilIdle()

            api.authMeCalls shouldBe 1
            repository.state.value.profile
                .shouldBeInstanceOf<AccountProfileState.Available>()
                .account.userId shouldBe "returning-user"
        }
    }

    test("central OIDC login persists rotating tokens before profile hydration") {
        runTest {
            val api = FakeAccountApi().apply {
                authMeResponses += {
                    JSONObject("""{"account":{"userId":"passport-user","phone":"13800138000"}}""")
                }
            }
            val store = FakeAuthSessionStore()
            val repository = repository(api, store)
            val tokens = OidcTokens("oidc-access", "oidc-refresh", 2_000L)

            repository.loginWithOidc(tokens).shouldBeInstanceOf<AccountResult.Ok<Unit>>()

            store.oidcSession shouldBe tokens
            store.token shouldBe "oidc-access"
            repository.state.value.account?.userId shouldBe "passport-user"
        }
    }

    test("profile hydration failure is understandable and retryable without signing out") {
        runTest {
            val api = FakeAccountApi().apply {
                loginResult = loginResult(account = null)
                authMeResponses += {
                    throw BackendException(
                        BackendException.Kind.NETWORK,
                        "transport detail must not reach UI",
                    )
                }
                authMeResponses += {
                    JSONObject("""{"account":{"userId":"user-2","phone":"13800138000"}}""")
                }
            }
            val store = FakeAuthSessionStore()
            val repository = repository(api, store)

            repository.loginWithEmail("cat@example.com", "123456")
                .shouldBeInstanceOf<AccountResult.Ok<Unit>>()

            val unavailable = repository.state.value.profile
                .shouldBeInstanceOf<AccountProfileState.Unavailable>()
            repository.state.value.loggedIn shouldBe true
            unavailable.message.contains("null", ignoreCase = true) shouldBe false

            repository.refreshAccount().shouldBeInstanceOf<AccountResult.Ok<Unit>>()
            repository.state.value.profile
                .shouldBeInstanceOf<AccountProfileState.Available>()
                .account.phone shouldBe "13800138000"
        }
    }

    test("a confirmed 401 clears both persisted credentials and observed state") {
        runTest {
            val api = FakeAccountApi().apply {
                loginResult = loginResult(account = null)
                authMeResponses += {
                    JSONObject("""{"account":{"userId":"user-3"}}""")
                }
                quotaFailure = BackendException(
                    BackendException.Kind.HTTP,
                    "expired",
                    status = 401,
                )
            }
            val store = FakeAuthSessionStore()
            val repository = repository(api, store)
            repository.loginWithEmail("cat@example.com", "123456")

            repository.refreshQuota().shouldBeInstanceOf<AccountResult.Err>()

            store.token shouldBe null
            repository.state.value.profile shouldBe AccountProfileState.SignedOut
            repository.state.value.loggedIn shouldBe false
        }
    }

    test("logout cannot be overwritten by profile hydration already entering account persistence") {
        runTest {
            val updateEntered = CountDownLatch(1)
            val allowUpdateToFinish = CountDownLatch(1)
            val store = BlockingProfileUpdateStore(updateEntered, allowUpdateToFinish)
            val api = FakeAccountApi().apply {
                loginResult = loginResult(account = null)
                authMeResponses += {
                    JSONObject("""{"account":{"userId":"stale-user"}}""")
                }
            }
            val repository = AccountRepository(
                client = api,
                tokenStore = store,
                scope = backgroundScope,
                ioDispatcher = Dispatchers.Default,
            )

            val login = async { repository.loginWithEmail("cat@example.com", "123456") }
            val reachedPersistence = withContext(Dispatchers.IO) {
                updateEntered.await(5, TimeUnit.SECONDS)
            }
            reachedPersistence shouldBe true

            val logoutStarted = CountDownLatch(1)
            val logout = async(Dispatchers.Default) {
                logoutStarted.countDown()
                repository.logout()
            }
            withContext(Dispatchers.IO) {
                logoutStarted.await(5, TimeUnit.SECONDS)
            } shouldBe true
            allowUpdateToFinish.countDown()
            login.await()
            logout.await()

            store.isLoggedIn() shouldBe false
            repository.state.value.profile shouldBe AccountProfileState.SignedOut
            repository.state.value.loggedIn shouldBe false
        }
    }

    test("profile unavailability does not gate a validated long-text polish attempt") {
        runTest {
            val api = FakeAccountApi().apply {
                loginResult = loginResult(account = null)
                authMeResponses += {
                    throw BackendException(BackendException.Kind.TIMEOUT, "profile timeout")
                }
            }
            val repository = repository(api, FakeAuthSessionStore())
            repository.loginWithEmail("cat@example.com", "123456")
            repository.state.value.profile.shouldBeInstanceOf<AccountProfileState.Unavailable>()

            var backendCalls = 0
            val diagnostics = RecordingDiagnostics()
            val polisher = OnlineOnlyPolisher(
                internetConnection = InternetConnection { true },
                onlineDelegate = RealPolisher(
                    backend = PolishBackend { _, _, _ ->
                        backendCalls += 1
                        PolishOutcome(
                            text = "已润色",
                            visibleChars = null,
                            cloudRemaining = null,
                            dailyUsed = null,
                            dailyCap = null,
                        )
                    },
                    diagnostics = diagnostics,
                ),
                diagnostics = diagnostics,
            )

            polisher.polish("一二三四五六七", "normal") shouldBe "已润色"

            backendCalls shouldBe 1
            diagnostics.events.count { it == PolishEvent.TOP_LEVEL_ATTEMPT } shouldBe 1
            diagnostics.events shouldContain PolishEvent.BACKEND_SUCCESS
        }
    }

    test("a polish 401 invalidates the repository session as one atomic observed transition") {
        runTest {
            val api = FakeAccountApi().apply {
                loginResult = loginResult(account = null)
                authMeResponses += {
                    JSONObject("""{"account":{"userId":"user-4"}}""")
                }
            }
            val store = FakeAuthSessionStore()
            val repository = repository(api, store)
            repository.loginWithEmail("cat@example.com", "123456")
            repository.state.value.loggedIn shouldBe true

            val diagnostics = RecordingDiagnostics()
            val polisher = RealPolisher(
                backend = PolishBackend { _, _, _ ->
                    throw BackendException(
                        BackendException.Kind.HTTP,
                        "expired",
                        code = BackendException.CODE_NOT_LOGGED_IN,
                        status = 401,
                    )
                },
                onAuthExpired = repository::invalidateAuthentication,
                diagnostics = diagnostics,
            )

            polisher.polish("一二三四五六七", "normal") shouldBe "一二三四五六七"

            store.token shouldBe null
            repository.state.value.profile shouldBe AccountProfileState.SignedOut
            diagnostics.events shouldContain PolishEvent.BACKEND_AUTH_FAILURE
        }
    }

    test("cached profile is available immediately while repository-owned refresh is pending") {
        runTest {
            val cached = AccountInfo(
                userId = "cached-user",
                nickname = "缓存小猫",
                inviteCode = null,
                email = null,
                phone = null,
            )
            val repository = AccountRepository(
                client = FakeAccountApi(),
                tokenStore = FakeAuthSessionStore("persisted-token", cached),
                scope = backgroundScope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

            repository.state.value.profile shouldBe AccountProfileState.Available(cached)
            repository.state.value.account shouldBe cached
        }
    }

    test("account operations delegate while login variants hydrate and logout stays synchronous") {
        runTest {
            val api = FakeAccountApi().apply {
                authMeResponses += {
                    JSONObject("""{"account":{"userId":"sms-user"}}""")
                }
                authMeResponses += {
                    JSONObject("""{"account":{"userId":"wechat-user"}}""")
                }
                quotaResult = QuotaInfo(null, false, 80, 20, 100, 80, null)
                redeemOutcome = RedeemOutcome(50, 130)
                planList = listOf(PlanInfo("p1", "套餐", 100, "chars", 50, 30))
                wechatUrl = WechatAuthUrl("https://example.invalid/auth", "state")
            }
            val store = FakeAuthSessionStore()
            val repository = repository(api, store)

            repository.sendEmailCode("cat@example.com")
                .shouldBeInstanceOf<AccountResult.Ok<Unit>>()
            repository.sendSmsCode("13800138000")
                .shouldBeInstanceOf<AccountResult.Ok<Unit>>()
            repository.wechatAuthUrl()
                .shouldBeInstanceOf<AccountResult.Ok<WechatAuthUrl>>()
                .value shouldBe api.wechatUrl

            repository.loginWithSms("13800138000", "123456")
                .shouldBeInstanceOf<AccountResult.Ok<Unit>>()
            repository.state.value.account?.userId shouldBe "sms-user"
            repository.logout()
            repository.state.value.profile shouldBe AccountProfileState.SignedOut

            repository.loginWithWechatCode("official-code")
                .shouldBeInstanceOf<AccountResult.Ok<Unit>>()
            repository.state.value.account?.userId shouldBe "wechat-user"
            repository.redeem("redeem-code")
                .shouldBeInstanceOf<AccountResult.Ok<Long?>>()
                .value shouldBe 50
            repository.state.value.quota shouldBe api.quotaResult
            repository.plans()
                .shouldBeInstanceOf<AccountResult.Ok<List<PlanInfo>>>()
                .value shouldBe api.planList
            repository.createOrder("p1", "alipay")
                .shouldBeInstanceOf<AccountResult.Ok<OrderInfo>>()

            api.emailSendCalls shouldBe 1
            api.smsSendCalls shouldBe 1
            api.smsLoginCalls shouldBe 1
            api.wechatLoginCalls shouldBe 1
            api.redeemCalls shouldBe 1
            api.orderCalls shouldBe 1
        }
    }

    test("signed-out refresh and malformed profile fail safely without a visible null") {
        runTest {
            val signedOut = repository(FakeAccountApi(), FakeAuthSessionStore())
            signedOut.refreshAccount()
                .shouldBeInstanceOf<AccountResult.Err>()
                .message.contains("null", ignoreCase = true) shouldBe false

            val api = FakeAccountApi().apply {
                loginResult = loginResult(account = null)
                authMeResponses += { JSONObject("{}") }
            }
            val store = FakeAuthSessionStore()
            val repository = repository(api, store)

            repository.loginWithEmail("cat@example.com", "123456")
                .shouldBeInstanceOf<AccountResult.Ok<Unit>>()

            store.isLoggedIn() shouldBe true
            repository.state.value.profile
                .shouldBeInstanceOf<AccountProfileState.Unavailable>()
                .message.contains("null", ignoreCase = true) shouldBe false
        }
    }

    test("unexpected local failures return a generic message without exposing details") {
        runTest {
            val api = FakeAccountApi().apply {
                emailSendFailure = IllegalStateException("credential detail")
            }
            val result = repository(api, FakeAuthSessionStore())
                .sendEmailCode("cat@example.com")
                .shouldBeInstanceOf<AccountResult.Err>()

            result.message shouldBe "请求失败，请稍后再试"
            result.message.contains("credential detail") shouldBe false
        }
    }
})

private fun kotlinx.coroutines.test.TestScope.repository(
    api: FakeAccountApi,
    store: FakeAuthSessionStore,
): AccountRepository = AccountRepository(
    client = api,
    tokenStore = store,
    scope = backgroundScope,
    ioDispatcher = StandardTestDispatcher(testScheduler),
)

private fun loginResult(account: AccountInfo?): LoginResult = LoginResult(
    accessToken = "access-token",
    account = account,
    isNew = false,
    cloudRemaining = null,
    deviceGift = null,
)

private class RecordingDiagnostics : PolishDiagnostics {
    val events = mutableListOf<PolishEvent>()

    override fun record(event: PolishEvent) {
        events += event
    }
}

private class FakeAuthSessionStore(
    var token: String? = null,
    private var storedAccount: AccountInfo? = null,
) : AuthSessionStore {
    var oidcSession: OidcTokens? = null
    override fun isLoggedIn(): Boolean = !token.isNullOrBlank()

    override fun account(): AccountInfo? = storedAccount

    override fun set(accessToken: String, account: AccountInfo?) {
        token = accessToken
        oidcSession = null
        storedAccount = account
    }

    override fun setOidc(tokens: OidcTokens, account: AccountInfo?) {
        token = tokens.accessToken
        oidcSession = tokens
        storedAccount = account
    }

    override fun oidcTokens(): OidcTokens? = oidcSession

    override fun updateOidcTokens(tokens: OidcTokens) {
        token = tokens.accessToken
        oidcSession = tokens
    }

    override fun clearOidc() {
        if (oidcSession != null) {
            token = null
            oidcSession = null
            storedAccount = null
        }
    }

    override fun updateAccount(account: AccountInfo?) {
        storedAccount = account
    }

    override fun clear() {
        token = null
        oidcSession = null
        storedAccount = null
    }
}

private class BlockingProfileUpdateStore(
    private val updateEntered: CountDownLatch,
    private val allowUpdateToFinish: CountDownLatch,
) : AuthSessionStore {
    @Volatile
    private var token: String? = null
    @Volatile
    private var storedAccount: AccountInfo? = null

    override fun isLoggedIn(): Boolean = !token.isNullOrBlank()

    override fun account(): AccountInfo? = storedAccount

    override fun set(accessToken: String, account: AccountInfo?) {
        token = accessToken
        storedAccount = account
    }

    override fun updateAccount(account: AccountInfo?) {
        updateEntered.countDown()
        check(allowUpdateToFinish.await(5, TimeUnit.SECONDS))
        storedAccount = account
    }

    override fun clear() {
        token = null
        storedAccount = null
    }

    override fun clearOidc() = Unit
}

private class FakeAccountApi : AccountApi {
    var loginResult: LoginResult = loginResult(account = null)
    val authMeResponses = ArrayDeque<() -> JSONObject>()
    var authMeCalls: Int = 0
    var quotaFailure: BackendException? = null
    var quotaResult: QuotaInfo = QuotaInfo(null, false, null, null, null, null, null)
    var redeemOutcome: RedeemOutcome = RedeemOutcome(null, null)
    var planList: List<PlanInfo> = emptyList()
    var wechatUrl: WechatAuthUrl = WechatAuthUrl("", null)
    var emailSendFailure: RuntimeException? = null
    var emailSendCalls: Int = 0
    var smsSendCalls: Int = 0
    var smsLoginCalls: Int = 0
    var wechatLoginCalls: Int = 0
    var redeemCalls: Int = 0
    var orderCalls: Int = 0

    override fun getQuota(): QuotaInfo {
        quotaFailure?.let { throw it }
        return quotaResult
    }

    override fun authEmailSend(email: String) {
        emailSendCalls += 1
        emailSendFailure?.let { throw it }
    }

    override fun authEmailLogin(email: String, code: String, inviteCode: String?): LoginResult =
        loginResult

    override fun authSmsSend(phone: String) {
        smsSendCalls += 1
    }

    override fun authSmsLogin(phone: String, code: String, inviteCode: String?): LoginResult {
        smsLoginCalls += 1
        return loginResult
    }

    override fun getWechatAuthUrl(): WechatAuthUrl = wechatUrl

    override fun authWechatLogin(code: String, inviteCode: String?): LoginResult {
        wechatLoginCalls += 1
        return loginResult
    }

    override fun authMe(): JSONObject {
        authMeCalls += 1
        return authMeResponses.removeFirst().invoke()
    }

    override fun redeem(code: String): RedeemOutcome {
        redeemCalls += 1
        return redeemOutcome
    }

    override fun listPlans(): List<PlanInfo> = planList

    override fun createOrder(planCode: String, channel: String): OrderInfo {
        orderCalls += 1
        return OrderInfo("", null, null, null, null, null)
    }
}
