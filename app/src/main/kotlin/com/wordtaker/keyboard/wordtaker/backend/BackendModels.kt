package com.wordtaker.keyboard.wordtaker.backend

import org.json.JSONObject

/**
 * 后端结构化错误 —— 对齐 Mac 端 backendClient.js 的 err.kind / err.code / err.status。
 * 调用方据 kind/code 决定：贴原文+提示 / 降级回退 relay / 其它。
 */
class BackendException(
    val kind: Kind,
    message: String,
    /** 后端业务码（如 INSUFFICIENT_QUOTA / DAILY_CAP_EXCEEDED / NOT_LOGGED_IN），若有。 */
    val code: String? = null,
    /** HTTP 状态码（kind=HTTP 时）。 */
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { NETWORK, TIMEOUT, HTTP }

    val isQuotaError: Boolean
        get() = code == CODE_INSUFFICIENT_QUOTA || code == CODE_DAILY_CAP_EXCEEDED

    val isAuthExpired: Boolean
        get() = code == CODE_NOT_LOGGED_IN || status == 401

    /** 用户可读报错（UI 直接展示）。 */
    fun friendlyMessage(): String = when {
        kind == Kind.NETWORK -> "无法连接服务器，请检查网络"
        kind == Kind.TIMEOUT -> "服务器响应超时，请稍后再试"
        code == CODE_INSUFFICIENT_QUOTA -> "云端字数不足，请购买字数包或使用兑换码"
        code == CODE_DAILY_CAP_EXCEEDED -> "今日云端用量已达上限，明天再来吧"
        isAuthExpired -> "登录已过期，请重新登录"
        status != null && status >= 500 -> "服务器开小差了，请稍后再试"
        else -> "请求失败，请稍后再试"
    }

    companion object {
        const val CODE_INSUFFICIENT_QUOTA = "INSUFFICIENT_QUOTA"
        const val CODE_DAILY_CAP_EXCEEDED = "DAILY_CAP_EXCEEDED"
        const val CODE_NOT_LOGGED_IN = "NOT_LOGGED_IN"
    }
}

/** POST /polish 成功结果。 */
data class PolishOutcome(
    val text: String,
    val visibleChars: Int?,
    val cloudRemaining: Long?,
    val dailyUsed: Long?,
    val dailyCap: Long?,
)

/** GET /quota（匿名可用）结果。cloudRemaining = 设备赠送剩余 + 账号余额（登录态）。 */
data class QuotaInfo(
    val userId: String?,
    val registered: Boolean,
    val cloudRemaining: Long?,
    val dailyUsed: Long?,
    val dailyCap: Long?,
    /** billing_v5 breakdown（可空）：设备剩余 / 账号剩余。 */
    val deviceRemaining: Long?,
    val accountRemaining: Long?,
)

/** GET /payment/plans 单个套餐。 */
data class PlanInfo(
    val code: String,
    val name: String,
    val priceCents: Long,
    val type: String,
    val charAmount: Long?,
    val validityDays: Int?,
)

/** POST /payment/order 结果。payload 内容依支付渠道而定（如跳转 URL），原样透传。 */
data class OrderInfo(
    val orderId: String,
    val outTradeNo: String?,
    val planCode: String?,
    val priceCents: Long?,
    val channel: String?,
    val payload: JSONObject?,
)

/** POST /redeem 结果。 */
data class RedeemOutcome(
    val charAmount: Long?,
    val cloudRemaining: Long?,
)

/** 账号摘要（登录响应 / auth/me 的 account）。 */
data class AccountInfo(
    val userId: String?,
    val nickname: String?,
    val inviteCode: String?,
    val email: String?,
    val phone: String?,
)

/** 登录成功结果（sms/email/wechat 共用）。 */
data class LoginResult(
    val accessToken: String,
    val account: AccountInfo?,
    val isNew: Boolean,
    val cloudRemaining: Long?,
    /** already_granted / invalid_device / no_device（billing_v5，无 granted/merged）。 */
    val deviceGift: String?,
)

/** GET /auth/wechat/url 结果。 */
data class WechatAuthUrl(
    val url: String,
    val state: String?,
)

/** POST /dict/suggest 单个云词库联想候选。 */
data class DictCandidate(
    val text: String,
    val score: Double,
    val source: String?,
)
