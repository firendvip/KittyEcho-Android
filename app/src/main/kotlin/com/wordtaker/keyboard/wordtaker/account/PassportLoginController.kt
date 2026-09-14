package com.wordtaker.keyboard.wordtaker.account

import com.wordtaker.keyboard.wordtaker.backend.BackendException
import com.wordtaker.keyboard.wordtaker.backend.PassportOidcTokenApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

sealed interface PassportLoginStatus {
    data object Idle : PassportLoginStatus
    data object AwaitingBrowser : PassportLoginStatus
    data object Exchanging : PassportLoginStatus
    data class Error(val message: String) : PassportLoginStatus
}

class PassportLoginController(
    private val flow: PassportOidcFlow,
    private val tokenApi: PassportOidcTokenApi,
    private val repository: AccountRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lifecycleLock = Any()
    private var lifecycleGeneration = 0L
    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val _status = MutableStateFlow<PassportLoginStatus>(
        if (flow.hasRecoverableLogin) PassportLoginStatus.AwaitingBrowser else PassportLoginStatus.Idle,
    )
    val status: StateFlow<PassportLoginStatus> = _status.asStateFlow()
    val isAvailable: Boolean get() = flow.isAvailable

    fun begin(): PassportStartResult = synchronized(lifecycleLock) {
        lifecycleGeneration += 1
        repository.invalidatePendingAuthentication()
        flow.begin().also { result ->
            _status.value = when (result) {
                is PassportStartResult.Ready -> PassportLoginStatus.AwaitingBrowser
                is PassportStartResult.Unavailable -> PassportLoginStatus.Error(result.message)
            }
        }
    }

    fun cancel() {
        synchronized(lifecycleLock) {
            lifecycleGeneration += 1
            repository.invalidatePendingAuthentication()
            flow.cancel()
            _status.value = PassportLoginStatus.Idle
        }
    }

    fun logout() {
        val revoke = synchronized(lifecycleLock) {
            lifecycleGeneration += 1
            flow.cancel()
            val refreshToken = repository.logoutForPassport()
            _status.value = PassportLoginStatus.Idle
            refreshToken.takeIf { flow.isAvailable }
        }
        if (revoke != null) {
            backgroundScope.launch {
                runCatching { tokenApi.revoke(revoke) }
            }
        }
    }

    suspend fun handleCallback(rawUri: String): AccountResult<Unit> {
        val callbackContext = synchronized(lifecycleLock) {
            Triple(
                flow.handleCallback(rawUri),
                lifecycleGeneration,
                repository.authenticationGeneration(),
            )
        }
        val callback = callbackContext.first
        val lifecycle = callbackContext.second
        val authentication = callbackContext.third
        return when (callback) {
            PassportCallback.Cancelled -> AccountResult.Err("登录已取消").also {
                publishStatus(lifecycle, PassportLoginStatus.Idle)
            }
            is PassportCallback.Rejected -> AccountResult.Err(callback.message).also {
                publishStatus(lifecycle, PassportLoginStatus.Error(callback.message))
            }
            is PassportCallback.AuthorizationCode -> {
                publishStatus(lifecycle, PassportLoginStatus.Exchanging)
                val result = try {
                    val tokens = withContext(ioDispatcher) {
                        tokenApi.exchangeAuthorizationCode(callback.code, callback.codeVerifier)
                    }
                    if (synchronized(lifecycleLock) { lifecycleGeneration != lifecycle }) {
                        AccountResult.Err("登录状态已变化，请重试")
                    } else {
                        repository.loginWithOidc(tokens, authentication)
                    }
                } catch (error: BackendException) {
                    AccountResult.Err(error.friendlyMessage(), error.code)
                } catch (_: Exception) {
                    AccountResult.Err("请求失败，请稍后再试")
                }
                publishStatus(
                    lifecycle,
                    when (result) {
                        is AccountResult.Ok -> PassportLoginStatus.Idle
                        is AccountResult.Err -> PassportLoginStatus.Error(result.message)
                    },
                )
                result
            }
        }
    }

    private fun publishStatus(expectedLifecycle: Long, status: PassportLoginStatus) {
        synchronized(lifecycleLock) {
            if (lifecycleGeneration == expectedLifecycle) {
                _status.value = status
            }
        }
    }
}
