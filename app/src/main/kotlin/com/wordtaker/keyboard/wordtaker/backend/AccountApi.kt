package com.wordtaker.keyboard.wordtaker.backend

import org.json.JSONObject

/**
 * Account-facing backend boundary. Keeping it separate from polishing makes profile
 * availability observable without turning it into a cloud-polish prerequisite.
 */
interface AccountApi {
    fun getQuota(): QuotaInfo

    fun authEmailSend(email: String)

    fun authEmailLogin(email: String, code: String, inviteCode: String? = null): LoginResult

    fun authSmsSend(phone: String)

    fun authSmsLogin(phone: String, code: String, inviteCode: String? = null): LoginResult

    fun getWechatAuthUrl(): WechatAuthUrl

    fun authWechatLogin(code: String, inviteCode: String? = null): LoginResult

    fun authMe(): JSONObject

    fun redeem(code: String): RedeemOutcome

    fun listPlans(): List<PlanInfo>

    fun createOrder(planCode: String, channel: String): OrderInfo
}

/** Minimal credential/profile persistence boundary used by [AccountRepository]. */
interface AuthSessionStore {
    /**
     * Monotonic process-local identity for the credential set. Replacement and clear increment it;
     * an access/refresh rotation inside the same login family preserves it.
     */
    fun credentialGeneration(): Long

    fun isLoggedIn(): Boolean

    fun account(): AccountInfo?

    fun set(accessToken: String, account: AccountInfo?)

    fun updateAccount(account: AccountInfo?)

    fun clear()
}
