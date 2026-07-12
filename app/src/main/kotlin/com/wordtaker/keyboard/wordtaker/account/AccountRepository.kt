package com.wordtaker.keyboard.wordtaker.account

import com.wordtaker.keyboard.wordtaker.backend.AccountInfo
import com.wordtaker.keyboard.wordtaker.backend.BackendClient
import com.wordtaker.keyboard.wordtaker.backend.BackendException
import com.wordtaker.keyboard.wordtaker.backend.PlanInfo
import com.wordtaker.keyboard.wordtaker.backend.QuotaInfo
import com.wordtaker.keyboard.wordtaker.backend.TokenStore
import com.wordtaker.keyboard.wordtaker.backend.WechatAuthUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** 账号侧 UI 状态快照。 */
data class AccountState(
    val loggedIn: Boolean = false,
    val account: AccountInfo? = null,
    val quota: QuotaInfo? = null,
)

/** 统一操作结果：成功值或用户可读错误。 */
sealed class AccountResult<out T> {
    data class Ok<T>(val value: T) : AccountResult<T>()
    data class Err(val message: String, val code: String? = null) : AccountResult<Nothing>()
}

/**
 * 账号/额度仓库：包装 [BackendClient]（IO 线程） + [TokenStore]，向 Compose 层
 * 暴露 [state]。所有网络错误吞掉并转成 [AccountResult.Err]（用户可读），不外抛。
 */
class AccountRepository(
    private val client: BackendClient,
    private val tokenStore: TokenStore,
) {

    private val _state = MutableStateFlow(
        AccountState(loggedIn = tokenStore.isLoggedIn(), account = tokenStore.account()),
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** 拉取额度（匿名/登录均可），成功后更新 state。 */
    suspend fun refreshQuota(): AccountResult<QuotaInfo> = call {
        val quota = client.getQuota()
        _state.update { it.copy(quota = quota) }
        quota
    }

    suspend fun sendEmailCode(email: String): AccountResult<Unit> = call {
        client.authEmailSend(email)
    }

    suspend fun loginWithEmail(email: String, code: String, inviteCode: String? = null): AccountResult<Unit> =
        call {
            val result = client.authEmailLogin(email, code, inviteCode)
            persistLogin(result.accessToken, result.account)
        }

    suspend fun sendSmsCode(phone: String): AccountResult<Unit> = call {
        client.authSmsSend(phone)
    }

    suspend fun loginWithSms(phone: String, code: String, inviteCode: String? = null): AccountResult<Unit> =
        call {
            val result = client.authSmsLogin(phone, code, inviteCode)
            persistLogin(result.accessToken, result.account)
        }

    /** 微信登录第一步：取授权 URL（调用方用系统浏览器打开）。 */
    suspend fun wechatAuthUrl(): AccountResult<WechatAuthUrl> = call { client.getWechatAuthUrl() }

    /** 微信登录第二步：deep link 回跳携带的 code 换 JWT。 */
    suspend fun loginWithWechatCode(code: String): AccountResult<Unit> = call {
        val result = client.authWechatLogin(code)
        persistLogin(result.accessToken, result.account)
    }

    /** 登录后刷新账号摘要（auth/me），容忍失败（仅日志级降级）。 */
    suspend fun refreshAccount(): AccountResult<Unit> = call {
        val data = client.authMe()
        val accountJson = data.optJSONObject("account") ?: data
        val account = com.wordtaker.keyboard.wordtaker.backend.AccountInfoJson.from(accountJson)
        tokenStore.updateAccount(account)
        _state.update { it.copy(account = account) }
    }

    suspend fun redeem(code: String): AccountResult<Long?> = call {
        val outcome = client.redeem(code)
        refreshQuota()
        outcome.charAmount
    }

    suspend fun plans(): AccountResult<List<PlanInfo>> = call { client.listPlans() }

    /** 下单（只到「拿到支付跳转载荷」为止，不做支付本身）。 */
    suspend fun createOrder(planCode: String, channel: String) = call {
        client.createOrder(planCode, channel)
    }

    fun logout() {
        tokenStore.clear()
        _state.update { AccountState(loggedIn = false, account = null, quota = null) }
    }

    private fun persistLogin(accessToken: String, account: AccountInfo?) {
        tokenStore.set(accessToken, account)
        _state.update { it.copy(loggedIn = true, account = account) }
    }

    /** IO 包裹 + 错误分类：BackendException → 用户可读 Err；401 顺手清登录态。 */
    private suspend fun <T> call(block: suspend () -> T): AccountResult<T> =
        withContext(Dispatchers.IO) {
            try {
                AccountResult.Ok(block())
            } catch (e: BackendException) {
                if (e.isAuthExpired) logout()
                AccountResult.Err(e.friendlyMessage(), e.code)
            } catch (e: Exception) {
                AccountResult.Err("请求失败：${e.message ?: "未知错误"}")
            }
        }
}
