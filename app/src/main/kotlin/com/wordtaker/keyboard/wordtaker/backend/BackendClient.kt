package com.wordtaker.keyboard.wordtaker.backend

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * 收费后端（ai-input-method-server）统一 API client —— Kotlin 对照 Mac 端
 * backendClient.js 重写，接口/字段/错误分类 1:1 对齐。
 *
 * 自动注入请求头：x-device-id、x-platform: android、登录后 Authorization: Bearer。
 * 所有方法为阻塞调用，调用方在 Dispatchers.IO 包裹。
 * 失败统一抛 [BackendException]（kind = NETWORK / TIMEOUT / HTTP + 业务 code）。
 */
class BackendClient(
    private val deviceId: String,
    private val tokenProvider: () -> String?,
    private val tokenRefresher: ((failedToken: String) -> String?)? = null,
    baseUrl: String = BackendConfig.BASE_URL + BackendConfig.API_PREFIX,
    client: OkHttpClient? = null,
) : AccountApi {

    private val baseUrl = baseUrl.trimEnd('/')

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // —— 计费 / 额度 ——

    /**
     * 云端润色（计费）。POST /polish {text, mode, word_map?}。
     * 额度不足/超日上限由后端以 HTTP 错误返回（code=INSUFFICIENT_QUOTA / DAILY_CAP_EXCEEDED）。
     */
    fun polish(text: String, mode: String, wordMap: List<Pair<String, String>>? = null): PolishOutcome {
        val body = JSONObject().put("text", text).put("mode", mode)
        if (!wordMap.isNullOrEmpty()) {
            val rules = JSONArray()
            wordMap.forEach { (from, to) ->
                rules.put(JSONObject().put("from", from).put("to", to))
            }
            body.put("word_map", rules)
        }
        val data = request("/polish", "POST", body, BackendConfig.POLISH_TIMEOUT_MS).dataObject()
        return PolishOutcome(
            text = data.optString("output", ""),
            visibleChars = data.optIntOrNull("visibleChars"),
            cloudRemaining = data.optLongOrNull("cloudRemaining"),
            dailyUsed = data.optLongOrNull("dailyUsed"),
            dailyCap = data.optLongOrNull("dailyCap"),
        )
    }

    /** 云端额度查询（匿名可用）。GET /quota。 */
    override fun getQuota(): QuotaInfo {
        val data = request("/quota", "GET").dataObject()
        val breakdown = data.optJSONObject("breakdown")
        return QuotaInfo(
            userId = data.optString("userId").takeIf { it.isNotBlank() },
            registered = data.optBoolean("registered", false),
            cloudRemaining = data.optLongOrNull("cloudRemaining"),
            dailyUsed = data.optLongOrNull("dailyUsed"),
            dailyCap = data.optLongOrNull("dailyCap"),
            deviceRemaining = breakdown?.optLongOrNull("deviceRemaining"),
            accountRemaining = breakdown?.optLongOrNull("accountRemaining"),
        )
    }

    // —— 云词库联想 ——

    /**
     * 云词库联想候选。POST /dict/suggest {pinyin, limit, prefix:false}。
     * 匿名可用、免费不计费；失败/超时/异常一律静默降级为空列表（调用方按纯本地候选处理）。
     * 请求体不接受上下文参数，只能发送经过调用方全拼规则合法化的纯 ASCII 拼音。
     */
    fun dictSuggest(
        pinyin: String,
        limit: Int = 10,
    ): List<DictCandidate> {
        if (!DICT_PINYIN.matches(pinyin) || limit !in 1..MAX_DICT_SUGGEST_LIMIT) return emptyList()
        return try {
            val body = JSONObject()
                .put("pinyin", pinyin)
                .put("limit", limit)
                .put("prefix", false)
            val json = request("/dict/suggest", "POST", body, DICT_SUGGEST_TIMEOUT_MS)
            // API envelope 边界严格 fail closed：success 必须是布尔 true。
            if (json?.opt("success") != true) return emptyList()
            val arr = json.dataObject().optJSONArray("candidates") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = (o.opt("text") as? String)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val rawScore = o.opt("score")
                val score = when (rawScore) {
                    null, JSONObject.NULL -> 0.0
                    is Number -> rawScore.toDouble()
                    else -> return@mapNotNull null
                }
                DictCandidate(
                    text = text,
                    score = score,
                    source = (o.opt("source") as? String)?.takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // —— 会员 / 计费（套餐 / 下单 / dev 直付 / 兑换码）——

    /** 套餐列表（公开）。GET /payment/plans。 */
    override fun listPlans(): List<PlanInfo> {
        val arr = request("/payment/plans", "GET")?.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PlanInfo(
                code = o.optString("code"),
                name = o.optString("name"),
                priceCents = o.optLong("priceCents", 0L),
                type = o.optString("type"),
                charAmount = o.optLongOrNull("charAmount"),
                validityDays = o.optIntOrNull("validityDays"),
            )
        }
    }

    /** 下单（Bearer）。POST /payment/order {planCode, channel}。 */
    override fun createOrder(planCode: String, channel: String): OrderInfo {
        val data = request(
            "/payment/order", "POST",
            JSONObject().put("planCode", planCode).put("channel", channel),
        ).dataObject()
        return OrderInfo(
            orderId = data.optString("orderId"),
            outTradeNo = data.optString("outTradeNo").takeIf { it.isNotBlank() },
            planCode = data.optString("planCode").takeIf { it.isNotBlank() },
            priceCents = data.optLongOrNull("priceCents"),
            channel = data.optString("channel").takeIf { it.isNotBlank() },
            payload = data.optJSONObject("payload"),
        )
    }

    /** dev 直付（Bearer，生产 PAY_ALLOW_MOCK=false 时 403）。POST /payment/mock/pay。 */
    fun mockPay(orderId: String): JSONObject =
        request("/payment/mock/pay", "POST", JSONObject().put("orderId", orderId)).dataObject()

    /** 兑换码（Bearer）。POST /redeem {code}。错误 code=INVALID_CODE/CODE_USED/CODE_EXPIRED。 */
    override fun redeem(code: String): RedeemOutcome {
        val data = request("/redeem", "POST", JSONObject().put("code", code)).dataObject()
        return RedeemOutcome(
            charAmount = data.optLongOrNull("charAmount"),
            cloudRemaining = data.optLongOrNull("cloudRemaining"),
        )
    }

    // —— 登录 ——

    override fun authSmsSend(phone: String) {
        request("/auth/sms/send", "POST", JSONObject().put("phone", phone))
    }

    override fun authSmsLogin(phone: String, code: String, inviteCode: String?): LoginResult =
        parseLogin(
            request(
                "/auth/sms/login", "POST",
                loginBody(JSONObject().put("phone", phone).put("code", code), inviteCode),
            ),
        )

    override fun authEmailSend(email: String) {
        request("/auth/email/send", "POST", JSONObject().put("email", email))
    }

    override fun authEmailLogin(email: String, code: String, inviteCode: String?): LoginResult =
        parseLogin(
            request(
                "/auth/email/login", "POST",
                loginBody(JSONObject().put("email", email).put("code", code), inviteCode),
            ),
        )

    /** 微信登录第一步：取官方授权 URL（含 redirect_uri + state）。GET /auth/wechat/url。 */
    override fun getWechatAuthUrl(): WechatAuthUrl {
        val data = request("/auth/wechat/url", "GET").dataObject()
        return WechatAuthUrl(
            url = data.optString("url"),
            state = data.optString("state").takeIf { it.isNotBlank() },
        )
    }

    /** 微信登录第二步：回传官方回调 code 换取 JWT。POST /auth/wechat/callback。 */
    override fun authWechatLogin(code: String, inviteCode: String?): LoginResult =
        parseLogin(
            request(
                "/auth/wechat/callback", "POST",
                loginBody(JSONObject().put("code", code), inviteCode),
            ),
        )

    /** 当前账号信息（Bearer）。GET /auth/me → data { account, cloudRemaining, subscription }。 */
    override fun authMe(): JSONObject = request("/auth/me", "GET").dataObject()

    // —— 内部：统一请求 / 组装 ——

    /** 登录 body 追加 deviceId（后端约束 8-64 位 [A-Za-z0-9._:-]）与可选 inviteCode。 */
    private fun loginBody(body: JSONObject, inviteCode: String?): JSONObject {
        if (!inviteCode.isNullOrBlank()) body.put("inviteCode", inviteCode.trim())
        val cleaned = deviceId.replace(Regex("[^A-Za-z0-9._:-]"), "").take(64)
        if (cleaned.length >= 8) body.put("deviceId", cleaned)
        return body
    }

    private fun parseLogin(json: JSONObject?): LoginResult {
        val data = json.dataObject()
        val token = data.optString("accessToken")
        if (token.isBlank()) {
            throw BackendException(BackendException.Kind.HTTP, "登录失败：无 token")
        }
        return LoginResult(
            accessToken = token,
            account = data.optJSONObject("account")?.let { AccountInfoJson.from(it) },
            isNew = data.optBoolean("isNew", false),
            cloudRemaining = data.optLongOrNull("cloudRemaining"),
            deviceGift = data.optString("deviceGift").takeIf { it.isNotBlank() },
        )
    }

    /**
     * 统一请求。成功返回后端 JSON（已解析，可能为 null body）；失败抛结构化错误。
     * 超时到点 → TIMEOUT；连接失败/DNS → NETWORK；非 2xx → HTTP（带业务 code）。
     */
    private fun request(
        pathname: String,
        method: String,
        body: JSONObject? = null,
        timeoutMs: Long = BackendConfig.REQUEST_TIMEOUT_MS,
    ): JSONObject? {
        val bodyText = (body ?: JSONObject()).toString()
        var token = tokenProvider()?.takeIf(String::isNotBlank)
        repeat(MAX_AUTH_ATTEMPTS) { attempt ->
            val builder = Request.Builder()
                .url(baseUrl + pathname)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-device-id", deviceId)
                .addHeader("x-platform", BackendConfig.PLATFORM)
            token?.let { builder.addHeader("Authorization", "Bearer $it") }
            val request = when (method) {
                "GET" -> builder.get()
                else -> builder.method(method, bodyText.toRequestBody(JSON))
            }.build()
            val response = try {
                http.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(request)
                    .execute()
            } catch (e: InterruptedIOException) {
                throw BackendException(BackendException.Kind.TIMEOUT, "后端请求超时", cause = e)
            } catch (e: IOException) {
                throw BackendException(
                    BackendException.Kind.NETWORK, "无法连接后端: ${e.message}", cause = e,
                )
            }

            response.use { res ->
                val text = runCatching { res.body?.string() }.getOrNull().orEmpty()
                val json = runCatching { if (text.isNotBlank()) JSONObject(text) else null }.getOrNull()
                if (res.code == 401 && attempt == 0 && token != null && tokenRefresher != null) {
                    val refreshed = tokenRefresher.invoke(token!!)?.takeIf(String::isNotBlank)
                    if (refreshed != null && refreshed != token) {
                        token = refreshed
                        return@repeat
                    }
                }
                if (!res.isSuccessful) {
                    // 后端业务错误体形如 { code, message } 或 NestJS 默认 { statusCode, message }
                    val code = json?.optString("code")?.takeIf { it.isNotBlank() }
                        ?: json?.optString("error")?.takeIf { it.isNotBlank() }
                    val message = json?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "后端错误 HTTP ${res.code}"
                    throw BackendException(
                        BackendException.Kind.HTTP, message, code = code, status = res.code,
                    )
                }
                return json
            }
        }
        error("unreachable")
    }

    private fun JSONObject?.dataObject(): JSONObject = this?.optJSONObject("data") ?: JSONObject()

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val DICT_SUGGEST_TIMEOUT_MS = 500L
        const val MAX_DICT_SUGGEST_LIMIT = 10
        const val MAX_AUTH_ATTEMPTS = 2
        val DICT_PINYIN = Regex("^[a-z]+$")
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

// —— org.json 可空取值小工具（optLong 默认 0 会把「缺失」误当 0）——
private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null
