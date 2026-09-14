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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject

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

    test("a valid callback exchanges PKCE code, stores rotating tokens and hydrates profile") {
        runTest {
            val fixture = fixture(dispatcher = StandardTestDispatcher(testScheduler))
            val start = fixture.controller.begin().shouldBeInstanceOf<PassportStartResult.Ready>()
            val state = fixture.pending.value!!.state
            val verifier = fixture.pending.value!!.codeVerifier

            fixture.controller.handleCallback(
                "kittyecho://auth?code=authorization-code-123456&state=$state",
            ).shouldBeInstanceOf<AccountResult.Ok<Unit>>()

            fixture.tokens.exchangedCode shouldBe "authorization-code-123456"
            fixture.tokens.exchangedVerifier shouldBe verifier
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

    override fun exchangeAuthorizationCode(code: String, codeVerifier: String): OidcTokens {
        failure?.let { throw it }
        exchangedCode = code
        exchangedVerifier = codeVerifier
        return next
    }

    override fun refresh(refreshToken: String): OidcTokens = next
}

private class ControllerAuthStore : AuthSessionStore {
    var oidc: OidcTokens? = null
    private var profile: AccountInfo? = null
    override fun isLoggedIn(): Boolean = oidc != null
    override fun account(): AccountInfo? = profile
    override fun set(accessToken: String, account: AccountInfo?) = Unit
    override fun setOidc(tokens: OidcTokens, account: AccountInfo?) {
        oidc = tokens
        profile = account
    }
    override fun oidcTokens(): OidcTokens? = oidc
    override fun updateOidcTokens(tokens: OidcTokens) {
        oidc = tokens
    }
    override fun updateAccount(account: AccountInfo?) {
        profile = account
    }
    override fun clear() {
        oidc = null
        profile = null
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
