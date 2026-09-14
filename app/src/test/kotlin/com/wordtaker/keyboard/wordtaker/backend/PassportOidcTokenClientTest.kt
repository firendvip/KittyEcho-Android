package com.wordtaker.keyboard.wordtaker.backend

import com.wordtaker.keyboard.wordtaker.account.PassportOidcConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class PassportOidcTokenClientTest : FunSpec({

    lateinit var server: MockWebServer

    beforeTest {
        server = MockWebServer()
        server.start()
    }

    afterTest {
        runCatching { server.shutdown() }
    }

    test("authorization code exchange is form encoded, public and PKCE-bound") {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"access-1","refresh_token":"refresh-1","token_type":"Bearer","expires_in":900,"scope":"openid profile offline_access aim.api"}""",
            ),
        )
        val client = client(server)

        val tokens = client.exchangeAuthorizationCode(
            code = "authorization-code-123456",
            codeVerifier = "v".repeat(43),
        )

        val request = server.takeRequest()
        request.path shouldBe "/oauth2/token"
        request.method shouldBe "POST"
        request.getHeader("Content-Type")?.startsWith("application/x-www-form-urlencoded") shouldBe true
        request.getHeader("Authorization") shouldBe null
        val form = form(request.body.readUtf8())
        form["grant_type"] shouldBe "authorization_code"
        form["client_id"] shouldBe "kittyecho-android"
        form["redirect_uri"] shouldBe "kittyecho://auth"
        form["code"] shouldBe "authorization-code-123456"
        form["code_verifier"] shouldBe "v".repeat(43)
        form.containsKey("client_secret") shouldBe false
        tokens shouldBe OidcTokens("access-1", "refresh-1", 1_900L)
    }

    test("refresh rotates the family and retains the old refresh token only when omitted") {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"access-2","refresh_token":"refresh-2","token_type":"Bearer","expires_in":900,"scope":"openid profile offline_access aim.api"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"access-3","token_type":"Bearer","expires_in":900,"scope":"openid profile offline_access aim.api"}""",
            ),
        )
        val client = client(server)

        client.refresh("refresh-1") shouldBe OidcTokens("access-2", "refresh-2", 1_900L)
        form(server.takeRequest().body.readUtf8()) shouldBe mapOf(
            "grant_type" to "refresh_token",
            "client_id" to "kittyecho-android",
            "refresh_token" to "refresh-1",
        )

        client.refresh("refresh-2") shouldBe OidcTokens("access-3", "refresh-2", 1_900L)
        server.takeRequest()
    }

    test("malformed successes and OAuth errors fail closed with sanitized categories") {
        val invalidBodies = listOf(
            "not-json",
            "{}",
            """{"access_token":"access-123","token_type":"mac","expires_in":900,"scope":"openid profile offline_access aim.api"}""",
            """{"access_token":"access-123","token_type":"Bearer","expires_in":59,"scope":"openid profile offline_access aim.api"}""",
            """{"access_token":"access-123","token_type":"Bearer","expires_in":86401,"scope":"openid profile offline_access aim.api"}""",
            """{"access_token":"access-123","token_type":"Bearer","expires_in":900,"scope":"openid profile"}""",
            """{"access_token":"access-123","token_type":"Bearer","expires_in":900,"scope":"openid profile offline_access aim.api openid"}""",
            """{"access_token":"access-123","refresh_token":"short","token_type":"Bearer","expires_in":900,"scope":"openid profile offline_access aim.api"}""",
        )
        invalidBodies.forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            shouldThrow<BackendException> {
                client(server).exchangeAuthorizationCode("authorization-code-123456", "v".repeat(43))
            }.friendlyMessage() shouldBe "请求失败，请稍后再试"
        }

        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":"invalid_grant","error_description":"secret server detail"}"""),
        )
        val expired = shouldThrow<BackendException> { client(server).refresh("refresh-1") }
        expired.isAuthExpired shouldBe true
        expired.message?.contains("secret server detail") shouldBe false
    }

    test("invalid local input and configuration fail before sending credentials") {
        val configured = client(server)
        shouldThrow<BackendException> {
            configured.exchangeAuthorizationCode("short", "v".repeat(43))
        }
        shouldThrow<BackendException> {
            configured.exchangeAuthorizationCode("authorization-code-123456", "short")
        }
        shouldThrow<BackendException> { configured.refresh("short") }.isAuthExpired shouldBe true
        shouldThrow<BackendException> { configured.refresh("refresh token with spaces") }
            .isAuthExpired shouldBe true
        server.requestCount shouldBe 0

        val invalid = PassportOidcTokenClient(
            PassportOidcConfig(true, "https://attacker.example", "kittyecho-android", "kittyecho://auth"),
        )
        shouldThrow<BackendException> {
            invalid.exchangeAuthorizationCode("authorization-code-123456", "v".repeat(43))
        }.friendlyMessage() shouldBe "请求失败，请稍后再试"
    }

    test("non-permanent OAuth errors and oversized responses are sanitized") {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"error":"temporarily_unavailable","error_description":"private detail"}"""),
        )
        val unavailable = shouldThrow<BackendException> {
            client(server).exchangeAuthorizationCode("authorization-code-123456", "v".repeat(43))
        }
        unavailable.kind shouldBe BackendException.Kind.HTTP
        unavailable.code shouldBe "temporarily_unavailable"
        unavailable.message?.contains("private detail") shouldBe false

        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":"bad-error","error_description":"private detail"}"""),
        )
        val malformedError = shouldThrow<BackendException> {
            client(server).exchangeAuthorizationCode("authorization-code-123456", "v".repeat(43))
        }
        malformedError.code shouldBe null
        malformedError.message?.contains("private detail") shouldBe false

        server.enqueue(MockResponse().setBody("x".repeat(65 * 1024)))
        shouldThrow<BackendException> {
            client(server).exchangeAuthorizationCode("authorization-code-123456", "v".repeat(43))
        }.friendlyMessage() shouldBe "请求失败，请稍后再试"
    }

    test("token endpoint read timeout is categorized") {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build()
        val error = shouldThrow<BackendException> {
            client(server, http).exchangeAuthorizationCode("authorization-code-123456", "v".repeat(43))
        }
        error.kind shouldBe BackendException.Kind.TIMEOUT
        error.friendlyMessage() shouldBe "服务器响应超时，请稍后再试"
    }

    test("network failure is categorized without leaking transport detail") {
        server.shutdown()
        val error = shouldThrow<BackendException> {
            client(server).exchangeAuthorizationCode("authorization-code-123456", "v".repeat(43))
        }
        error.kind shouldBe BackendException.Kind.NETWORK
        error.friendlyMessage() shouldBe "无法连接服务器，请检查网络"
    }

    test("expiry boundaries, sparse spacing and an absent refresh token are accepted exactly") {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"access-min","token_type":"Bearer","expires_in":60,"scope":"openid  profile offline_access aim.api"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"access-max","refresh_token":"refresh-max","token_type":"Bearer","expires_in":86400,"scope":"openid profile offline_access aim.api"}""",
            ),
        )
        val client = client(server)

        client.exchangeAuthorizationCode(
            "authorization-code-123456",
            "v".repeat(43),
        ) shouldBe OidcTokens("access-min", null, 1_060L)
        client.exchangeAuthorizationCode(
            "authorization-code-123456",
            "v".repeat(43),
        ) shouldBe OidcTokens("access-max", "refresh-max", 87_400L)
    }

    test("token endpoint redirects and empty error bodies are not followed or trusted") {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/credential-sink")),
        )
        val redirect = shouldThrow<BackendException> {
            client(server).exchangeAuthorizationCode("authorization-code-123456", "v".repeat(43))
        }
        redirect.status shouldBe 302
        server.requestCount shouldBe 1

        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        val empty = shouldThrow<BackendException> {
            client(server).exchangeAuthorizationCode("authorization-code-123456", "v".repeat(43))
        }
        empty.code shouldBe null
        empty.status shouldBe 500
    }

    test("revocation is a public form request without credentials and accepts an unknown family") {
        server.enqueue(MockResponse().setBody("{}"))
        val client = client(server)

        client.revoke("refresh-family-1")

        val request = server.takeRequest()
        request.path shouldBe "/oauth2/revoke"
        request.method shouldBe "POST"
        request.getHeader("Authorization") shouldBe null
        val body = form(request.body.readUtf8())
        body shouldBe mapOf(
            "client_id" to "kittyecho-android",
            "token" to "refresh-family-1",
        )
        body.containsKey("client_secret") shouldBe false
    }

    test("invalid revocation input fails locally and server errors stay sanitized") {
        val client = client(server)
        shouldThrow<BackendException> { client.revoke("short") }
        server.requestCount shouldBe 0

        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"error":"temporarily_unavailable","detail":"private"}"""),
        )
        val error = shouldThrow<BackendException> { client.revoke("refresh-family-1") }
        error.kind shouldBe BackendException.Kind.HTTP
        error.status shouldBe 503
        error.message?.contains("private") shouldBe false
    }
})

private fun client(
    server: MockWebServer,
    http: OkHttpClient = OkHttpClient(),
): PassportOidcTokenClient = PassportOidcTokenClient(
    config = PassportOidcConfig(
        enabled = true,
        issuer = server.url("/").toString().trimEnd('/'),
        clientId = "kittyecho-android",
        redirectUri = "kittyecho://auth",
        allowLoopbackIssuerForTests = true,
    ),
    http = http,
    nowEpochSeconds = { 1_000L },
)

private fun form(body: String): Map<String, String> = body.split('&').associate { pair ->
    val parts = pair.split('=', limit = 2)
    URLDecoder.decode(parts[0], Charsets.UTF_8) to
        URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
}
