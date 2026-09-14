package com.wordtaker.keyboard.wordtaker.backend

import com.wordtaker.keyboard.wordtaker.account.PassportOidcConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

data class OidcTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
)

interface PassportOidcTokenApi {
    fun exchangeAuthorizationCode(code: String, codeVerifier: String, expectedNonce: String): OidcTokens

    fun refresh(refreshToken: String): OidcTokens

    fun revoke(refreshToken: String)
}

/** Public native token endpoint client: no secret, no cookies, no redirects. */
class PassportOidcTokenClient(
    config: PassportOidcConfig,
    http: OkHttpClient? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    idTokenVerifier: PassportIdTokenVerifier? = null,
) : PassportOidcTokenApi {
    private val validConfig = config.validated(requireEnabled = false)
    private val http = (http ?: OkHttpClient()).newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val idTokenVerifier = idTokenVerifier ?: NimbusPassportIdTokenVerifier(
        config = config,
        http = this.http,
        nowEpochSeconds = nowEpochSeconds,
    )

    override fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        expectedNonce: String,
    ): OidcTokens {
        val config = requireConfig()
        if (
            !OPAQUE.matches(code) ||
            !PKCE_VERIFIER.matches(codeVerifier) ||
            !OPAQUE.matches(expectedNonce)
        ) {
            throw malformedResponse()
        }
        return tokenRequest(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", config.clientId)
                .add("redirect_uri", config.redirectUri)
                .add("code", code)
                .add("code_verifier", codeVerifier)
                .build(),
            expectedNonce = expectedNonce,
            requireIdToken = true,
        )
    }

    override fun refresh(refreshToken: String): OidcTokens {
        val config = requireConfig()
        if (!TOKEN.matches(refreshToken)) throw expiredSession()
        return tokenRequest(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", config.clientId)
                .add("refresh_token", refreshToken)
                .build(),
            previousRefreshToken = refreshToken,
            requireIdToken = false,
        )
    }

    override fun revoke(refreshToken: String) {
        val config = requireConfig()
        if (!TOKEN.matches(refreshToken)) throw malformedResponse()
        val request = Request.Builder()
            .url(config.revocationEndpoint)
            .post(
                FormBody.Builder()
                    .add("client_id", config.clientId)
                    .add("token", refreshToken)
                    .build(),
            )
            .header("Accept", "application/json")
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (error: InterruptedIOException) {
            throw BackendException(BackendException.Kind.TIMEOUT, "OIDC revoke timeout", cause = error)
        } catch (error: IOException) {
            throw BackendException(BackendException.Kind.NETWORK, "OIDC revoke network error", cause = error)
        }
        response.use {
            readBoundedBody(it.body)
            if (!it.isSuccessful) {
                throw BackendException(
                    BackendException.Kind.HTTP,
                    "OIDC revoke failed",
                    status = it.code,
                )
            }
        }
    }

    private fun tokenRequest(
        body: FormBody,
        previousRefreshToken: String? = null,
        expectedNonce: String? = null,
        requireIdToken: Boolean,
    ): OidcTokens {
        val request = Request.Builder()
            .url(requireConfig().tokenEndpoint)
            .post(body)
            .header("Accept", "application/json")
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (error: InterruptedIOException) {
            throw BackendException(BackendException.Kind.TIMEOUT, "OIDC token timeout", cause = error)
        } catch (error: IOException) {
            throw BackendException(BackendException.Kind.NETWORK, "OIDC token network error", cause = error)
        }
        response.use {
            val text = readBoundedBody(it.body)
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!it.isSuccessful) {
                val error = json?.optString("error")?.takeIf(OAUTH_ERROR::matches)
                if (error in PERMANENT_GRANT_ERRORS) throw expiredSession()
                throw BackendException(
                    BackendException.Kind.HTTP,
                    "OIDC token request failed",
                    code = error,
                    status = it.code,
                )
            }
            return parseTokens(json, previousRefreshToken, expectedNonce, requireIdToken)
        }
    }

    private fun parseTokens(
        json: JSONObject?,
        previousRefreshToken: String?,
        expectedNonce: String?,
        requireIdToken: Boolean,
    ): OidcTokens {
        val accessToken = json?.optString("access_token").orEmpty()
        val tokenType = json?.optString("token_type").orEmpty()
        val expiresIn = json?.optLong("expires_in", -1L) ?: -1L
        val scope = json?.optString("scope").orEmpty().split(' ').filter(String::isNotBlank)
        val refreshToken = json?.optString("refresh_token")
            ?.takeIf(String::isNotBlank)
            ?: previousRefreshToken
        val idToken = json?.optString("id_token")?.takeIf(String::isNotBlank)
        if (
            !TOKEN.matches(accessToken) ||
            tokenType != "Bearer" ||
            expiresIn !in MIN_EXPIRES_SECONDS..MAX_EXPIRES_SECONDS ||
            scope.toSet().size != scope.size ||
            !scope.containsAll(PassportOidcConfig.SCOPES.split(' ')) ||
            (refreshToken != null && !TOKEN.matches(refreshToken)) ||
            (requireIdToken && idToken == null)
        ) {
            throw malformedResponse()
        }
        if (idToken != null) idTokenVerifier.verify(idToken, expectedNonce)
        return OidcTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = nowEpochSeconds() + expiresIn,
        )
    }

    private fun readBoundedBody(body: okhttp3.ResponseBody?): String {
        if (body == null) throw malformedResponse()
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw malformedResponse()
        return source.readUtf8()
    }

    private fun malformedResponse() = BackendException(
        BackendException.Kind.HTTP,
        "OIDC token response invalid",
    )

    private fun expiredSession() = BackendException(
        BackendException.Kind.HTTP,
        "OIDC grant expired",
        code = BackendException.CODE_NOT_LOGGED_IN,
        status = 401,
    )

    private fun requireConfig() = validConfig ?: throw BackendException(
        BackendException.Kind.HTTP,
        "OIDC configuration unavailable",
    )

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val CALL_TIMEOUT_SECONDS = 15L
        const val MAX_RESPONSE_BYTES = 64 * 1024L
        const val MIN_EXPIRES_SECONDS = 60L
        const val MAX_EXPIRES_SECONDS = 24 * 60 * 60L
        val OPAQUE = Regex("^[A-Za-z0-9._~-]{16,512}$")
        val PKCE_VERIFIER = Regex("^[A-Za-z0-9_-]{43,128}$")
        val TOKEN = Regex("^[^\\s\\u0000-\\u001f\\u007f]{8,8192}$")
        val OAUTH_ERROR = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        val PERMANENT_GRANT_ERRORS = setOf("invalid_grant", "invalid_client")
    }
}
