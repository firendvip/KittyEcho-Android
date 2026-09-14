package com.wordtaker.keyboard.wordtaker.account

import com.wordtaker.keyboard.wordtaker.backend.BackendException
import com.wordtaker.keyboard.wordtaker.backend.PassportOidcTokenApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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
    private val _status = MutableStateFlow<PassportLoginStatus>(
        if (flow.hasRecoverableLogin) PassportLoginStatus.AwaitingBrowser else PassportLoginStatus.Idle,
    )
    val status: StateFlow<PassportLoginStatus> = _status.asStateFlow()
    val isAvailable: Boolean get() = flow.isAvailable

    fun begin(): PassportStartResult = flow.begin().also { result ->
        _status.value = when (result) {
            is PassportStartResult.Ready -> PassportLoginStatus.AwaitingBrowser
            is PassportStartResult.Unavailable -> PassportLoginStatus.Error(result.message)
        }
    }

    fun cancel() {
        flow.cancel()
        _status.value = PassportLoginStatus.Idle
    }

    suspend fun handleCallback(rawUri: String): AccountResult<Unit> = when (val callback = flow.handleCallback(rawUri)) {
        PassportCallback.Cancelled -> AccountResult.Err("登录已取消").also {
            _status.value = PassportLoginStatus.Idle
        }
        is PassportCallback.Rejected -> AccountResult.Err(callback.message).also {
            _status.value = PassportLoginStatus.Error(callback.message)
        }
        is PassportCallback.AuthorizationCode -> {
            _status.value = PassportLoginStatus.Exchanging
            val result = try {
                val tokens = withContext(ioDispatcher) {
                    tokenApi.exchangeAuthorizationCode(callback.code, callback.codeVerifier)
                }
                repository.loginWithOidc(tokens)
            } catch (error: BackendException) {
                AccountResult.Err(error.friendlyMessage(), error.code)
            } catch (_: Exception) {
                AccountResult.Err("请求失败，请稍后再试")
            }
            _status.value = when (result) {
                is AccountResult.Ok -> PassportLoginStatus.Idle
                is AccountResult.Err -> PassportLoginStatus.Error(result.message)
            }
            result
        }
    }
}
