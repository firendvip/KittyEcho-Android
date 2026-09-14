package com.wordtaker.keyboard.wordtaker.backend

/** Serializes refresh-family rotation and supplies a valid bearer token to BackendClient. */
class OidcTokenManager(
    private val store: AuthSessionStore,
    private val legacyTokenProvider: () -> String?,
    private val tokenApi: PassportOidcTokenApi,
    private val passportEnabled: Boolean = true,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    init {
        if (!passportEnabled) store.clearOidc()
    }

    @Synchronized
    fun accessSession(): AuthRequestSession {
        val current = currentSession()
        if (!passportEnabled) return current.request
        val tokens = current.tokens ?: return current.request
        if (tokens.expiresAtEpochSeconds > nowEpochSeconds() + REFRESH_SKEW_SECONDS) {
            return current.request
        }
        return refresh(current, allowStillValidFallback = true)
    }

    @Synchronized
    fun refreshAfterUnauthorized(failedSession: AuthRequestSession): AuthRequestSession? {
        if (!passportEnabled) return null
        val current = currentSession()
        if (current.request.generation != failedSession.generation) throw sessionChanged()
        val tokens = current.tokens ?: return null
        if (failedSession.accessToken != null && tokens.accessToken != failedSession.accessToken) {
            return current.request
        }
        return refresh(current, allowStillValidFallback = false)
    }

    private fun refresh(session: ManagedSession, allowStillValidFallback: Boolean): AuthRequestSession {
        val tokens = requireNotNull(session.tokens)
        val refreshToken = tokens.refreshToken
        if (refreshToken == null) {
            if (allowStillValidFallback && tokens.expiresAtEpochSeconds > nowEpochSeconds()) {
                return session.request
            }
            synchronized(store) {
                ensureCurrent(session)
                store.clear()
            }
            throw expiredSession()
        }
        return try {
            val refreshed = tokenApi.refresh(refreshToken)
            synchronized(store) {
                ensureCurrent(session)
                store.updateOidcTokens(refreshed)
                AuthRequestSession(session.request.generation, refreshed.accessToken)
            }
        } catch (error: BackendException) {
            if (error.code == BackendException.CODE_AUTH_SESSION_CHANGED) throw error
            synchronized(store) {
                ensureCurrent(session)
                if (error.isAuthExpired) store.clear()
            }
            if (!error.isAuthExpired && allowStillValidFallback && tokens.expiresAtEpochSeconds > nowEpochSeconds()) {
                return session.request
            }
            throw error
        }
    }

    private fun currentSession(): ManagedSession = synchronized(store) {
        val generation = store.credentialGeneration()
        val tokens = if (passportEnabled) store.oidcTokens() else null
        val accessToken = tokens?.accessToken ?: legacyTokenProvider()
        ManagedSession(AuthRequestSession(generation, accessToken), tokens)
    }

    private fun ensureCurrent(expected: ManagedSession) {
        val current = currentSession()
        if (current.request.generation != expected.request.generation || current.tokens != expected.tokens) {
            throw sessionChanged()
        }
    }

    private fun sessionChanged() = BackendException(
        BackendException.Kind.HTTP,
        "Authentication session changed",
        code = BackendException.CODE_AUTH_SESSION_CHANGED,
        status = 409,
    )

    private fun expiredSession() = BackendException(
        BackendException.Kind.HTTP,
        "OIDC session expired",
        code = BackendException.CODE_NOT_LOGGED_IN,
        status = 401,
    )

    private companion object {
        const val REFRESH_SKEW_SECONDS = 60L
    }

    private data class ManagedSession(
        val request: AuthRequestSession,
        val tokens: OidcTokens?,
    )
}
