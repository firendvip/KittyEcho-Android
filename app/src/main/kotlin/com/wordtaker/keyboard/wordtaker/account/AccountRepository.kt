package com.wordtaker.keyboard.wordtaker.account

import com.wordtaker.keyboard.wordtaker.backend.AccountInfo
import com.wordtaker.keyboard.wordtaker.backend.AccountApi
import com.wordtaker.keyboard.wordtaker.backend.AccountInfoJson
import com.wordtaker.keyboard.wordtaker.backend.AuthSessionStore
import com.wordtaker.keyboard.wordtaker.backend.BackendException
import com.wordtaker.keyboard.wordtaker.backend.LoginResult
import com.wordtaker.keyboard.wordtaker.backend.PlanInfo
import com.wordtaker.keyboard.wordtaker.backend.QuotaInfo
import com.wordtaker.keyboard.wordtaker.backend.WechatAuthUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Authentication and profile hydration are explicit and independently observable. */
sealed interface AccountProfileState {
    data object SignedOut : AccountProfileState
    data object Loading : AccountProfileState
    data class Available(val account: AccountInfo) : AccountProfileState
    data class Unavailable(val message: String) : AccountProfileState
}

/** 账号侧 UI 状态快照；资料不可用不等于退出登录。 */
data class AccountState(
    val profile: AccountProfileState = AccountProfileState.SignedOut,
    val quota: QuotaInfo? = null,
) {
    val loggedIn: Boolean
        get() = profile != AccountProfileState.SignedOut

    val account: AccountInfo?
        get() = (profile as? AccountProfileState.Available)?.account
}

/** 统一操作结果：成功值或用户可读错误。 */
sealed class AccountResult<out T> {
    data class Ok<T>(val value: T) : AccountResult<T>()
    data class Err(val message: String, val code: String? = null) : AccountResult<Nothing>()
}

/**
 * 账号/额度仓库：包装 [AccountApi]（IO 线程） + [AuthSessionStore]，向 Compose 层
 * 暴露 [state]。所有网络错误吞掉并转成 [AccountResult.Err]（用户可读），不外抛。
 */
class AccountRepository(
    private val client: AccountApi,
    private val tokenStore: AuthSessionStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val sessionLock = Any()
    private var sessionGeneration = 0L
    private val initiallyLoggedIn = tokenStore.isLoggedIn()
    private val initialAccount = tokenStore.account().takeIf { initiallyLoggedIn }
    private val _state = MutableStateFlow(
        AccountState(
            profile = when {
                !initiallyLoggedIn -> AccountProfileState.SignedOut
                initialAccount != null -> AccountProfileState.Available(initialAccount)
                else -> AccountProfileState.Loading
            },
        ),
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    init {
        if (initiallyLoggedIn) {
            scope.launch { refreshAccount() }
        }
    }

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
        login { client.authEmailLogin(email, code, inviteCode) }

    suspend fun sendSmsCode(phone: String): AccountResult<Unit> = call {
        client.authSmsSend(phone)
    }

    suspend fun loginWithSms(phone: String, code: String, inviteCode: String? = null): AccountResult<Unit> =
        login { client.authSmsLogin(phone, code, inviteCode) }

    /** 微信登录第一步：取授权 URL（调用方用系统浏览器打开）。 */
    suspend fun wechatAuthUrl(): AccountResult<WechatAuthUrl> = call { client.getWechatAuthUrl() }

    /** 微信登录第二步：deep link 回跳携带的 code 换 JWT。 */
    suspend fun loginWithWechatCode(code: String): AccountResult<Unit> =
        login { client.authWechatLogin(code) }

    internal fun authenticationGeneration(): Long = synchronized(sessionLock) {
        sessionGeneration
    }

    /** 登录态内刷新账号摘要；失败进入可理解、可重试的资料不可用状态。 */
    suspend fun refreshAccount(): AccountResult<Unit> = withContext(ioDispatcher) {
        val expectedGeneration = synchronized(sessionLock) {
            if (!tokenStore.isLoggedIn()) {
                invalidateAuthenticationLocked()
                null
            } else {
                _state.update { current ->
                    if (current.loggedIn) current.copy(profile = AccountProfileState.Loading) else current
                }
                sessionGeneration
            }
        }
        if (expectedGeneration == null) {
            return@withContext AccountResult.Err(MESSAGE_SIGNED_OUT)
        }
        hydrateProfile(expectedGeneration)
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
        invalidateAuthentication()
    }

    /** Confirmed authentication expiry must update storage and StateFlow together. */
    fun invalidateAuthentication() {
        synchronized(sessionLock) {
            invalidateAuthenticationLocked()
        }
    }

    private fun invalidateAuthenticationLocked() {
        sessionGeneration += 1
        tokenStore.clear()
        _state.value = AccountState()
    }

    /** Login success always hydrates /auth/me here, never from a page-mount side effect. */
    private suspend fun login(
        expectedGeneration: Long = authenticationGeneration(),
        persist: (LoginResult) -> Unit = { result -> tokenStore.set(result.accessToken, result.account) },
        block: () -> LoginResult,
    ): AccountResult<Unit> =
        withContext(ioDispatcher) {
            try {
                val result = block()
                val generation = persistLogin(result, expectedGeneration, persist)
                    ?: return@withContext AccountResult.Err(MESSAGE_SESSION_CHANGED)
                when (val hydration = hydrateProfile(generation)) {
                    is AccountResult.Ok -> hydration
                    is AccountResult.Err ->
                        if (_state.value.loggedIn) AccountResult.Ok(Unit) else hydration
                }
            } catch (e: BackendException) {
                backendFailure(e, expectedGeneration)
            } catch (_: Exception) {
                AccountResult.Err(MESSAGE_REQUEST_FAILED)
            }
        }

    private fun persistLogin(
        result: LoginResult,
        expectedGeneration: Long,
        persist: (LoginResult) -> Unit,
    ): Long? =
        synchronized(sessionLock) {
            if (sessionGeneration != expectedGeneration) return@synchronized null
            persist(result)
            sessionGeneration += 1
            val generation = sessionGeneration
            _state.update { current ->
                current.copy(profile = AccountProfileState.Loading)
            }
            generation
        }

    private fun hydrateProfile(expectedGeneration: Long): AccountResult<Unit> {
        return try {
            val account = client.authMe().requiredAccount()
            if (!publishProfile(expectedGeneration, account)) {
                return AccountResult.Err(MESSAGE_SESSION_CHANGED)
            }
            AccountResult.Ok(Unit)
        } catch (e: BackendException) {
            if (e.isAuthExpired) {
                invalidateAuthenticationIfCurrent(expectedGeneration)
                AccountResult.Err(e.friendlyMessage(), e.code)
            } else {
                profileUnavailable(expectedGeneration, e.friendlyMessage(), e.code)
            }
        } catch (_: InvalidProfileException) {
            profileUnavailable(expectedGeneration, MESSAGE_PROFILE_UNAVAILABLE)
        } catch (_: Exception) {
            profileUnavailable(expectedGeneration, MESSAGE_PROFILE_UNAVAILABLE)
        }
    }

    private fun publishProfile(expectedGeneration: Long, account: AccountInfo): Boolean =
        synchronized(sessionLock) {
            if (!isCurrentSessionLocked(expectedGeneration)) return@synchronized false
            tokenStore.updateAccount(account)
            _state.update { current ->
                current.copy(profile = AccountProfileState.Available(account))
            }
            true
        }

    private fun profileUnavailable(
        expectedGeneration: Long,
        reason: String,
        code: String? = null,
    ): AccountResult.Err {
        val message = "账号资料加载失败：$reason"
        synchronized(sessionLock) {
            if (isCurrentSessionLocked(expectedGeneration)) {
                _state.update { current ->
                    current.copy(profile = AccountProfileState.Unavailable(message))
                }
            }
        }
        return AccountResult.Err(message, code)
    }

    private fun invalidateAuthenticationIfCurrent(expectedGeneration: Long) {
        synchronized(sessionLock) {
            if (sessionGeneration == expectedGeneration) {
                invalidateAuthenticationLocked()
            }
        }
    }

    private fun isCurrentSessionLocked(expectedGeneration: Long): Boolean =
        sessionGeneration == expectedGeneration && tokenStore.isLoggedIn()

    private fun JSONObject.requiredAccount(): AccountInfo {
        val accountJson = optJSONObject("account") ?: throw InvalidProfileException()
        return AccountInfoJson.from(accountJson).takeIf { !it.userId.isNullOrBlank() }
            ?: throw InvalidProfileException()
    }

    /** IO 包裹 + 错误分类：BackendException → 用户可读 Err；401 顺手清登录态。 */
    private suspend fun <T> call(block: suspend () -> T): AccountResult<T> =
        withContext(ioDispatcher) {
            val requestGeneration = authenticationGeneration()
            try {
                AccountResult.Ok(block())
            } catch (e: BackendException) {
                backendFailure(e, requestGeneration)
            } catch (_: Exception) {
                AccountResult.Err(MESSAGE_REQUEST_FAILED)
            }
        }

    private fun backendFailure(
        error: BackendException,
        expectedGeneration: Long,
    ): AccountResult.Err {
        if (error.isAuthExpired) invalidateAuthenticationIfCurrent(expectedGeneration)
        return AccountResult.Err(error.friendlyMessage(), error.code)
    }

    private companion object {
        const val MESSAGE_PROFILE_UNAVAILABLE = "账号资料暂不可用，请重试"
        const val MESSAGE_REQUEST_FAILED = "请求失败，请稍后再试"
        const val MESSAGE_SESSION_CHANGED = "登录状态已变化，请重试"
        const val MESSAGE_SIGNED_OUT = "登录已失效，请重新登录"
    }
}

private class InvalidProfileException : Exception()
