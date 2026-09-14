package com.wordtaker.keyboard.wordtaker.backend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject

/**
 * BackendClient 单元级自测：请求头 / 路径 / 序列化 / 错误分类，全部打到 MockWebServer，
 * 不碰真实后端（x-platform 白名单放行前的形状验证）。
 */
class BackendClientTest : FunSpec({

    lateinit var server: MockWebServer
    var token: String? = null

    fun client(): BackendClient = BackendClient(
        deviceId = DEVICE_ID,
        tokenProvider = { token },
        baseUrl = server.url("/aiapi").toString(),
    )

    beforeTest {
        server = MockWebServer()
        server.start()
        token = null
    }

    afterTest {
        server.shutdown()
    }

    test("polish sends contract headers, path and body shape") {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"output":"润色后","visibleChars":3,
                   "cloudRemaining":1997,"dailyUsed":3,"dailyCap":5000000}}""",
            ),
        )
        token = "jwt-token-123"

        val out = client().polish("测试文本", "normal", listOf("周" to "州"))

        val recorded = server.takeRequest()
        recorded.path shouldBe "/aiapi/polish"
        recorded.method shouldBe "POST"
        recorded.getHeader("x-device-id") shouldBe DEVICE_ID
        recorded.getHeader("x-platform") shouldBe "android"
        recorded.getHeader("Authorization") shouldBe "Bearer jwt-token-123"

        val body = JSONObject(recorded.body.readUtf8())
        body.getString("text") shouldBe "测试文本"
        body.getString("mode") shouldBe "normal"
        val rules = body.getJSONArray("word_map")
        rules.length() shouldBe 1
        rules.getJSONObject(0).getString("from") shouldBe "周"
        rules.getJSONObject(0).getString("to") shouldBe "州"
        body.keys().asSequence().toSet() shouldBe setOf("text", "mode", "word_map")

        out.text shouldBe "润色后"
        out.visibleChars shouldBe 3
        out.cloudRemaining shouldBe 1997L
    }

    test("polish omits word_map and Authorization when absent (anonymous)") {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"output":"ok"}}"""))

        client().polish("hi", "gaoeq")

        val recorded = server.takeRequest()
        recorded.getHeader("Authorization").shouldBeNull()
        val body = JSONObject(recorded.body.readUtf8())
        body.has("word_map") shouldBe false
        body.getString("mode") shouldBe "gaoeq"
        body.keys().asSequence().toSet() shouldBe setOf("text", "mode")
    }

    test("quota error maps to structured HTTP exception with backend code") {
        server.enqueue(
            MockResponse().setResponseCode(402)
                .setBody("""{"code":"INSUFFICIENT_QUOTA","message":"云端字数不足"}"""),
        )

        val e = shouldThrow<BackendException> { client().polish("hi", "normal") }
        e.kind shouldBe BackendException.Kind.HTTP
        e.code shouldBe "INSUFFICIENT_QUOTA"
        e.status shouldBe 402
        e.isQuotaError shouldBe true
    }

    test("401 marks auth expired") {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"code":"NOT_LOGGED_IN","message":"未登录"}"""),
        )
        val e = shouldThrow<BackendException> { client().getQuota() }
        e.isAuthExpired shouldBe true
    }

    test("an authenticated 401 refreshes once and retries with the rotated access token") {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"code":"NOT_LOGGED_IN"}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"registered":true,"cloudRemaining":9}}""",
            ),
        )
        token = listOf("expired", "access").joinToString("-")
        var failedToken: String? = null
        val refreshingClient = BackendClient(
            deviceId = DEVICE_ID,
            tokenProvider = { token },
            tokenRefresher = {
                failedToken = it
                "rotated-access"
            },
            baseUrl = server.url("/aiapi").toString(),
        )

        refreshingClient.getQuota().cloudRemaining shouldBe 9L
        failedToken shouldBe "expired-access"
        server.takeRequest().getHeader("Authorization") shouldBe "Bearer expired-access"
        server.takeRequest().getHeader("Authorization") shouldBe "Bearer rotated-access"
    }

    test("anonymous requests and unchanged refresh results are never replayed") {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        var refreshCalls = 0
        val anonymous = BackendClient(
            deviceId = DEVICE_ID,
            tokenProvider = { null },
            tokenRefresher = {
                refreshCalls += 1
                "unexpected"
            },
            baseUrl = server.url("/aiapi").toString(),
        )
        shouldThrow<BackendException> { anonymous.getQuota() }
        refreshCalls shouldBe 0
        server.requestCount shouldBe 1

        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val unchanged = BackendClient(
            deviceId = DEVICE_ID,
            tokenProvider = { "same-token" },
            tokenRefresher = {
                refreshCalls += 1
                "same-token"
            },
            baseUrl = server.url("/aiapi").toString(),
        )
        shouldThrow<BackendException> { unchanged.getQuota() }
        refreshCalls shouldBe 1
        server.requestCount shouldBe 2
    }

    test("network failure maps to NETWORK kind") {
        server.shutdown()
        val e = shouldThrow<BackendException> { client().getQuota() }
        e.kind shouldBe BackendException.Kind.NETWORK
    }

    test("getQuota parses fields and breakdown") {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"userId":"u1","registered":true,
                   "cloudRemaining":12345,"dailyUsed":10,"dailyCap":5000000,
                   "breakdown":{"deviceRemaining":2000,"accountRemaining":10345}}}""",
            ),
        )
        val q = client().getQuota()
        server.takeRequest().path shouldBe "/aiapi/quota"
        q.userId shouldBe "u1"
        q.registered shouldBe true
        q.cloudRemaining shouldBe 12345L
        q.deviceRemaining shouldBe 2000L
        q.accountRemaining shouldBe 10345L
    }

    test("email login carries code, cleaned deviceId and inviteCode; parses token") {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"accessToken":"tok-1","isNew":true,
                   "cloudRemaining":10000,"deviceGift":"already_granted",
                   "account":{"userId":"u9","nickname":"喵","inviteCode":"INV123"}}}""",
            ),
        )

        val result = client().authEmailLogin("a@b.com", "000000", "FRIEND1")

        val recorded = server.takeRequest()
        recorded.path shouldBe "/aiapi/auth/email/login"
        val body = JSONObject(recorded.body.readUtf8())
        body.getString("email") shouldBe "a@b.com"
        body.getString("code") shouldBe "000000"
        body.getString("inviteCode") shouldBe "FRIEND1"
        body.getString("deviceId") shouldBe DEVICE_ID

        result.accessToken shouldBe "tok-1"
        result.isNew shouldBe true
        result.deviceGift shouldBe "already_granted"
        result.account?.nickname shouldBe "喵"
        result.account?.inviteCode shouldBe "INV123"
    }

    test("login without token in response throws") {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{}}"""))
        shouldThrow<BackendException> { client().authEmailLogin("a@b.com", "000000") }
    }

    test("listPlans hits public endpoint and parses array") {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":[
                   {"code":"pkg_small","name":"小包","priceCents":900,
                    "type":"char_package","charAmount":150000,"validityDays":365}]}""",
            ),
        )
        val plans = client().listPlans()
        server.takeRequest().path shouldBe "/aiapi/payment/plans"
        plans.size shouldBe 1
        plans[0].code shouldBe "pkg_small"
        plans[0].priceCents shouldBe 900L
        plans[0].charAmount shouldBe 150000L
    }

    test("createOrder, mockPay, redeem hit contract paths with JSON bodies") {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"orderId":"o1","planCode":"pkg_small",
                   "priceCents":900,"channel":"alipay","payload":{"url":"https://pay"}}}""",
            ),
        )
        val order = client().createOrder("pkg_small", "alipay")
        var recorded = server.takeRequest()
        recorded.path shouldBe "/aiapi/payment/order"
        JSONObject(recorded.body.readUtf8()).getString("planCode") shouldBe "pkg_small"
        order.orderId shouldBe "o1"
        order.payload?.getString("url") shouldBe "https://pay"

        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"ok":true}}"""))
        client().mockPay("o1")
        recorded = server.takeRequest()
        recorded.path shouldBe "/aiapi/payment/mock/pay"
        JSONObject(recorded.body.readUtf8()).getString("orderId") shouldBe "o1"

        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"charAmount":10000,"cloudRemaining":12000}}""",
            ),
        )
        val redeem = client().redeem("CODE-1")
        recorded = server.takeRequest()
        recorded.path shouldBe "/aiapi/redeem"
        JSONObject(recorded.body.readUtf8()).getString("code") shouldBe "CODE-1"
        redeem.charAmount shouldBe 10000L
    }

    test("auth send endpoints and wechat flow hit contract paths") {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        client().authEmailSend("a@b.com")
        server.takeRequest().path shouldBe "/aiapi/auth/email/send"

        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        client().authSmsSend("13800138000")
        server.takeRequest().path shouldBe "/aiapi/auth/sms/send"

        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"url":"https://open.weixin.qq.com/x","state":"s1"}}""",
            ),
        )
        val wx = client().getWechatAuthUrl()
        server.takeRequest().path shouldBe "/aiapi/auth/wechat/url"
        wx.url shouldBe "https://open.weixin.qq.com/x"
        wx.state shouldBe "s1"

        server.enqueue(
            MockResponse().setBody("""{"success":true,"data":{"accessToken":"tok-wx"}}"""),
        )
        val login = client().authWechatLogin("wx-code-1")
        val recorded = server.takeRequest()
        recorded.path shouldBe "/aiapi/auth/wechat/callback"
        JSONObject(recorded.body.readUtf8()).getString("code") shouldBe "wx-code-1"
        login.accessToken shouldBe "tok-wx"
    }

    test("authMe hits contract path") {
        token = "tok"
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"account":{"userId":"u1"},"cloudRemaining":5}}""",
            ),
        )
        val me = client().authMe()
        val recorded = server.takeRequest()
        recorded.path shouldBe "/aiapi/auth/me"
        recorded.getHeader("Authorization") shouldBe "Bearer tok"
        me.getJSONObject("account").getString("userId") shouldBe "u1"
    }

    test("device id derivation is salted sha256 truncated to 32 lowercase hex") {
        val id = DeviceIdentity.derive("some-android-id")
        id.length shouldBe 32
        Regex("^[0-9a-f]{32}$").matches(id) shouldBe true
        // Deterministic for the same seed, distinct for a different one.
        DeviceIdentity.derive("some-android-id") shouldBe id
        (DeviceIdentity.derive("other") == id) shouldBe false
    }
})

// 32-hex like the production derivation, already contract-legal (8-64 [A-Za-z0-9._:-]).
private const val DEVICE_ID = "0123456789abcdef0123456789abcdef"
