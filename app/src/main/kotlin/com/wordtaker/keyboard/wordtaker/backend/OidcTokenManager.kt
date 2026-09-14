package com.wordtaker.keyboard.wordtaker.backend

/** Serializes refresh-family rotation and supplies a valid bearer token to BackendClient. */
class OidcTokenManager(
    private val store: AuthSessionStore,
    private val legacyTokenProvider: () -> String?,
    private val tokenApi: PassportOidcTokenApi,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    @Synchronized
    fun accessToken(): String? {
        val tokens = store.oidcTokens() ?: return legacyTokenProvider()
        if (tokens.expiresAtEpochSeconds > nowEpochSeconds() + REFRESH_SKEW_SECONDS) {
            return tokens.accessToken
        }
        return refresh(tokens, allowStillValidFallback = true)
    }

    @Synchronized
    fun refreshAfterUnauthorized(failedToken: String?): String? {
        val tokens = store.oidcTokens() ?: return null
        if (failedToken != null && tokens.accessToken != failedToken) return tokens.accessToken
        return refresh(tokens, allowStillValidFallback = false)
    }

    private fun refresh(tokens: OidcTokens, allowStillValidFallback: Boolean): String {
        val refreshToken = tokens.refreshToken
        if (refreshToken == null) {
            if (allowStillValidFallback && tokens.expiresAtEpochSeconds > nowEpochSeconds()) {
                return tokens.accessToken
            }
            store.clear()
            throw expiredSession()
        }
        return try {
            tokenApi.refresh(refreshToken).also(store::updateOidcTokens).accessToken
        } catch (error: BackendException) {
            if (error.isAuthExpired) {
                store.clear()
            } else if (allowStillValidFallback && tokens.expiresAtEpochSeconds > nowEpochSeconds()) {
                return tokens.accessToken
            }
            throw error
        }
    }

    private fun expiredSession() = BackendException(
        BackendException.Kind.HTTP,
        "OIDC session expired",
        code = BackendException.CODE_NOT_LOGGED_IN,
        status = 401,
    )

    private companion object {
        const val REFRESH_SKEW_SECONDS = 60L
    }
}
