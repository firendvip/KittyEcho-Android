package com.wordtaker.keyboard.wordtaker.backend

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import com.wordtaker.keyboard.wordtaker.account.PassportOidcConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

fun interface PassportIdTokenVerifier {
    /** Returns the verified, canonical Passport UUID subject. */
    fun verify(idToken: String, expectedNonce: String?): String
}

/** Verifies Passport ID tokens against the issuer-pinned JWKS using Nimbus JOSE. */
class NimbusPassportIdTokenVerifier(
    config: PassportOidcConfig,
    http: OkHttpClient? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) : PassportIdTokenVerifier {
    private val validConfig = config.validated(requireEnabled = false)
    private val http = (http ?: OkHttpClient()).newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val jwksLock = Any()

    @Volatile
    private var cachedJwks: CachedJwks? = null

    override fun verify(idToken: String, expectedNonce: String?): String {
        val config = validConfig ?: throw invalidToken()
        if (!JWT.matches(idToken) || (expectedNonce != null && !OPAQUE.matches(expectedNonce))) {
            throw invalidToken()
        }
        return try {
            val token = SignedJWT.parse(idToken)
            val header = token.header
            if (
                header.algorithm != JWSAlgorithm.RS256 ||
                header.type != JOSEObjectType.JWT ||
                !KEY_ID.matches(header.keyID.orEmpty()) ||
                header.criticalParams?.isNotEmpty() == true ||
                !header.isBase64URLEncodePayload ||
                header.includedParams.any { it in UNTRUSTED_KEY_HEADERS }
            ) {
                throw invalidToken()
            }
            val key = keyFor(header.keyID)
            if (!token.verify(RSASSAVerifier(key))) throw invalidToken()

            val claims = token.jwtClaimsSet
            val issuedAt = claims.issueTime?.time?.floorDiv(1_000L) ?: throw invalidToken()
            val expiresAt = claims.expirationTime?.time?.floorDiv(1_000L) ?: throw invalidToken()
            val notBefore = claims.notBeforeTime?.time?.floorDiv(1_000L)
            val now = nowEpochSeconds()
            val authTime = claims.getLongClaim("auth_time") ?: throw invalidToken()
            val nonce = claims.getStringClaim("nonce") ?: throw invalidToken()
            val subject = claims.subject ?: throw invalidToken()
            if (
                claims.issuer != config.issuer ||
                claims.audience != listOf(config.clientId) ||
                expiresAt <= now - CLOCK_SKEW_SECONDS ||
                issuedAt > now + CLOCK_SKEW_SECONDS ||
                expiresAt - issuedAt !in MIN_ID_TOKEN_SECONDS..MAX_ID_TOKEN_SECONDS ||
                (notBefore != null && notBefore > now + CLOCK_SKEW_SECONDS) ||
                authTime > issuedAt + CLOCK_SKEW_SECONDS ||
                claims.getStringClaim("token_use") != "id" ||
                !OPAQUE.matches(nonce) ||
                (expectedNonce != null && !constantEquals(nonce, expectedNonce)) ||
                !isCanonicalUuid(subject) ||
                !isCanonicalUuid(claims.getStringClaim("sid").orEmpty()) ||
                !isCanonicalUuid(claims.jwtid.orEmpty())
            ) {
                throw invalidToken()
            }
            subject
        } catch (error: BackendException) {
            throw error
        } catch (_: Exception) {
            throw invalidToken()
        }
    }

    private fun keyFor(keyId: String): RSAKey {
        val now = nowEpochSeconds()
        cachedJwks?.takeIf { now < it.expiresAtEpochSeconds }?.let { cached ->
            selectKey(cached.keys, keyId, missingAllowed = true)?.let { return it }
        }
        return synchronized(jwksLock) {
            cachedJwks?.takeIf { now < it.expiresAtEpochSeconds }?.let { cached ->
                selectKey(cached.keys, keyId, missingAllowed = true)?.let { return@synchronized it }
            }
            val keys = fetchJwks()
            cachedJwks = CachedJwks(keys, now + JWKS_CACHE_SECONDS)
            selectKey(keys, keyId, missingAllowed = false) ?: throw invalidToken()
        }
    }

    private fun selectKey(keys: List<RSAKey>, keyId: String, missingAllowed: Boolean): RSAKey? {
        val matching = keys.filter { it.keyID == keyId }
        if (matching.isEmpty() && missingAllowed) return null
        if (matching.size != 1) throw invalidToken()
        val key = matching.single()
        if (
            key.isPrivate ||
            key.keyUse != KeyUse.SIGNATURE ||
            key.algorithm != JWSAlgorithm.RS256 ||
            (key.keyOperations.orEmpty().isNotEmpty() && key.keyOperations != setOf(KeyOperation.VERIFY)) ||
            key.size() < MIN_RSA_BITS
        ) {
            throw invalidToken()
        }
        return key
    }

    private fun fetchJwks(): List<RSAKey> {
        val config = validConfig ?: throw invalidToken()
        val request = Request.Builder()
            .url(config.jwksEndpoint)
            .get()
            .header("Accept", "application/json")
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (error: InterruptedIOException) {
            throw BackendException(BackendException.Kind.TIMEOUT, "OIDC JWKS timeout", cause = error)
        } catch (error: IOException) {
            throw BackendException(BackendException.Kind.NETWORK, "OIDC JWKS network error", cause = error)
        }
        response.use {
            if (!it.isSuccessful) throw invalidToken()
            val body = it.body ?: throw invalidToken()
            val source = body.source()
            source.request(MAX_JWKS_BYTES + 1L)
            if (source.buffer.size > MAX_JWKS_BYTES) throw invalidToken()
            val jwks = runCatching { JWKSet.parse(source.readUtf8()) }.getOrNull() ?: throw invalidToken()
            if (jwks.keys.size !in 1..MAX_JWKS_KEYS) throw invalidToken()
            return jwks.keys.map { key -> key as? RSAKey ?: throw invalidToken() }
        }
    }

    private fun invalidToken() = BackendException(
        BackendException.Kind.HTTP,
        "OIDC ID token invalid",
    )

    private data class CachedJwks(
        val keys: List<RSAKey>,
        val expiresAtEpochSeconds: Long,
    )

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val CALL_TIMEOUT_SECONDS = 15L
        const val MAX_JWKS_BYTES = 64 * 1024L
        const val MAX_JWKS_KEYS = 16
        const val JWKS_CACHE_SECONDS = 5 * 60L
        const val CLOCK_SKEW_SECONDS = 60L
        const val MIN_ID_TOKEN_SECONDS = 60L
        const val MAX_ID_TOKEN_SECONDS = 5 * 60L
        const val MIN_RSA_BITS = 2048
        val JWT = Regex("^[A-Za-z0-9_-]{1,4096}\\.[A-Za-z0-9_-]{1,8192}\\.[A-Za-z0-9_-]{1,4096}$")
        val KEY_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
        val OPAQUE = Regex("^[A-Za-z0-9._~-]{16,512}$")
        val UNTRUSTED_KEY_HEADERS = setOf("jku", "x5u", "jwk", "x5c")

        fun constantEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
            left.toByteArray(Charsets.US_ASCII),
            right.toByteArray(Charsets.US_ASCII),
        )

        fun isCanonicalUuid(value: String): Boolean = runCatching {
            UUID.fromString(value).toString() == value
        }.getOrDefault(false)
    }
}
