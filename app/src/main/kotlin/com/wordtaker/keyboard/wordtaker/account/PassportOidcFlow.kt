package com.wordtaker.keyboard.wordtaker.account

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class PassportOidcConfig(
    val enabled: Boolean,
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
    internal val allowLoopbackIssuerForTests: Boolean = false,
) {
    internal fun validated(requireEnabled: Boolean = true): ValidPassportOidcConfig? {
        if ((requireEnabled && !enabled) || !CLIENT_ID.matches(clientId) || redirectUri != REDIRECT_URI) return null
        val parsed = runCatching { URI(issuer) }.getOrNull() ?: return null
        val loopbackTestIssuer = allowLoopbackIssuerForTests &&
            parsed.scheme == "http" &&
            parsed.host in LOOPBACK_HOSTS
        if (issuer != PRODUCTION_ISSUER && !loopbackTestIssuer) return null
        if (
            parsed.rawUserInfo != null ||
            parsed.rawQuery != null ||
            parsed.rawFragment != null ||
            parsed.host.isNullOrBlank() ||
            parsed.rawPath !in setOf("", "/") ||
            (!loopbackTestIssuer && parsed.port !in setOf(-1, 443))
        ) {
            return null
        }
        return ValidPassportOidcConfig(
            issuer = issuer.trimEnd('/'),
            clientId = clientId,
            redirectUri = redirectUri,
        )
    }

    companion object {
        const val REDIRECT_URI = "kittyecho://auth"
        const val PRODUCTION_ISSUER = "https://auth.yaa3.com"
        const val SCOPES = "openid profile offline_access aim.api"
        private val CLIENT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$")
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    }
}

internal data class ValidPassportOidcConfig(
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
) {
    val authorizationEndpoint: String = "$issuer/oauth2/authorize"
    val tokenEndpoint: String = "$issuer/oauth2/token"
    val revocationEndpoint: String = "$issuer/oauth2/revoke"
    val jwksEndpoint: String = "$issuer/.well-known/jwks.json"
}

data class PendingPassportAuthorization(
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val createdAtMillis: Long,
)

interface PassportPendingStore {
    fun read(): PendingPassportAuthorization?

    /** Returns false when secure persistence is unavailable. */
    fun write(pending: PendingPassportAuthorization): Boolean

    fun clear()
}

sealed interface PassportStartResult {
    data class Ready(val authorizationUrl: String) : PassportStartResult
    data class Unavailable(val message: String) : PassportStartResult
}

sealed interface PassportCallback {
    data class AuthorizationCode(
        val code: String,
        val codeVerifier: String,
        val expectedNonce: String,
        val redirectUri: String,
    ) : PassportCallback

    data object Cancelled : PassportCallback
    data class Rejected(val message: String) : PassportCallback
}

/**
 * Pure Authorization Code + S256 PKCE state machine. Browser cookies remain in the
 * system browser; this object persists only a short-lived encrypted verifier/state.
 */
class PassportOidcFlow(
    private val config: PassportOidcConfig,
    private val pendingStore: PassportPendingStore,
    private val randomBytes: (Int) -> ByteArray = { size ->
        ByteArray(size).also(SecureRandom()::nextBytes)
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val isAvailable: Boolean
        get() = config.validated() != null

    val hasRecoverableLogin: Boolean
        get() {
            if (!isAvailable) {
                pendingStore.clear()
                return false
            }
            return validPending(clearInvalid = true) != null
        }

    fun begin(): PassportStartResult {
        val valid = config.validated()
            ?: return PassportStartResult.Unavailable(MESSAGE_UNAVAILABLE)
        val verifier = base64Url(randomBytes(PKCE_RANDOM_BYTES))
        val pending = PendingPassportAuthorization(
            state = base64Url(randomBytes(OPAQUE_RANDOM_BYTES)),
            nonce = base64Url(randomBytes(OPAQUE_RANDOM_BYTES)),
            codeVerifier = verifier,
            createdAtMillis = nowMillis(),
        )
        if (!validPendingShape(pending) || !pendingStore.write(pending)) {
            pendingStore.clear()
            return PassportStartResult.Unavailable(MESSAGE_SECURE_STORAGE)
        }
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val query = linkedMapOf(
            "response_type" to "code",
            "client_id" to valid.clientId,
            "redirect_uri" to valid.redirectUri,
            "scope" to PassportOidcConfig.SCOPES,
            "state" to pending.state,
            "nonce" to pending.nonce,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        ).entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return PassportStartResult.Ready("${valid.authorizationEndpoint}?$query")
    }

    fun handleCallback(rawUri: String): PassportCallback {
        val valid = config.validated()
            ?: return PassportCallback.Rejected(MESSAGE_UNAVAILABLE).also { pendingStore.clear() }
        if (rawUri.length > MAX_CALLBACK_LENGTH) {
            return PassportCallback.Rejected(MESSAGE_INVALID_CALLBACK)
        }
        if (!rawUri.startsWith("${valid.redirectUri}?")) {
            return PassportCallback.Rejected(MESSAGE_INVALID_CALLBACK)
        }
        val uri = runCatching { URI(rawUri) }.getOrNull()
            ?: return PassportCallback.Rejected(MESSAGE_INVALID_CALLBACK)
        if (
            uri.scheme != "kittyecho" ||
            uri.rawAuthority != "auth" ||
            uri.rawPath !in setOf("", null) ||
            uri.rawFragment != null ||
            uri.rawQuery.isNullOrBlank()
        ) {
            return PassportCallback.Rejected(MESSAGE_INVALID_CALLBACK)
        }
        val parameters = parseUniqueQuery(uri.rawQuery)
            ?: return PassportCallback.Rejected(MESSAGE_INVALID_CALLBACK)
        if (parameters.keys.any { it !in CALLBACK_PARAMETERS }) {
            return PassportCallback.Rejected(MESSAGE_INVALID_CALLBACK)
        }
        val pending = validPending(clearInvalid = true)
            ?: return PassportCallback.Rejected(MESSAGE_EXPIRED_CALLBACK)
        val state = parameters["state"]
        if (state == null || !constantEquals(state, pending.state)) {
            return PassportCallback.Rejected(MESSAGE_INVALID_CALLBACK)
        }

        val error = parameters["error"]
        val code = parameters["code"]
        if ((error == null) == (code == null)) {
            return PassportCallback.Rejected(MESSAGE_INVALID_CALLBACK)
        }
        if (error != null) {
            if (!OAUTH_ERROR.matches(error)) {
                return PassportCallback.Rejected(MESSAGE_INVALID_CALLBACK)
            }
            pendingStore.clear()
            return if (error == "access_denied") {
                PassportCallback.Cancelled
            } else {
                PassportCallback.Rejected(MESSAGE_PROVIDER_ERROR)
            }
        }
        if (code == null || !AUTHORIZATION_CODE.matches(code)) {
            return PassportCallback.Rejected(MESSAGE_INVALID_CALLBACK)
        }
        pendingStore.clear()
        return PassportCallback.AuthorizationCode(code, pending.codeVerifier, pending.nonce, valid.redirectUri)
    }

    fun cancel() {
        pendingStore.clear()
    }

    private fun validPending(clearInvalid: Boolean): PendingPassportAuthorization? {
        val pending = pendingStore.read() ?: return null
        val age = nowMillis() - pending.createdAtMillis
        val valid = validPendingShape(pending) && age in 0..PENDING_TTL_MS
        if (!valid && clearInvalid) pendingStore.clear()
        return pending.takeIf { valid }
    }

    private fun validPendingShape(pending: PendingPassportAuthorization): Boolean =
        OPAQUE_PARAMETER.matches(pending.state) &&
            OPAQUE_PARAMETER.matches(pending.nonce) &&
            PKCE_VERIFIER.matches(pending.codeVerifier)

    companion object {
        const val PENDING_TTL_MS = 10 * 60 * 1000L
        const val MESSAGE_UNAVAILABLE = "统一登录尚未启用或配置不完整"
        const val MESSAGE_SECURE_STORAGE = "系统安全存储暂不可用，无法登录"
        const val MESSAGE_INVALID_CALLBACK = "登录回调无效，请重新登录"
        const val MESSAGE_EXPIRED_CALLBACK = "登录已过期或已处理，请重新登录"
        const val MESSAGE_PROVIDER_ERROR = "统一登录失败，请稍后再试"

        private const val PKCE_RANDOM_BYTES = 32
        private const val OPAQUE_RANDOM_BYTES = 24
        private const val MAX_CALLBACK_LENGTH = 4096
        private val OPAQUE_PARAMETER = Regex("^[A-Za-z0-9._~-]{16,512}$")
        private val PKCE_VERIFIER = Regex("^[A-Za-z0-9_-]{43,128}$")
        private val AUTHORIZATION_CODE = Regex("^[A-Za-z0-9._~-]{16,512}$")
        private val OAUTH_ERROR = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        private val CALLBACK_PARAMETERS = setOf("code", "state", "error", "error_description")

        private fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private fun parseUniqueQuery(rawQuery: String): Map<String, String>? {
            val values = linkedMapOf<String, String>()
            for (pair in rawQuery.split('&')) {
                val parts = pair.split('=', limit = 2)
                if (parts.size != 2) return null
                val key = decode(parts[0]) ?: return null
                val value = decode(parts[1]) ?: return null
                if (key.isBlank() || values.put(key, value) != null) return null
            }
            return values
        }

        private fun decode(value: String): String? = runCatching {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrNull()

        private fun constantEquals(left: String, right: String): Boolean =
            MessageDigest.isEqual(left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII))
    }
}
