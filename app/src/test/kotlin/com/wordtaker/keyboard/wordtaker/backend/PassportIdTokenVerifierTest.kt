package com.wordtaker.keyboard.wordtaker.backend

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import com.wordtaker.keyboard.wordtaker.account.PassportOidcConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.net.URI
import java.security.KeyPairGenerator
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PassportIdTokenVerifierTest : FunSpec({

    lateinit var server: MockWebServer
    lateinit var signingKey: RSAKey

    beforeTest {
        server = MockWebServer()
        server.start()
        currentIssuer = server.url("/").toString().trimEnd('/')
        signingKey = RSAKeyGenerator(2048)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .keyID("passport-test-key")
            .generate()
    }

    afterTest {
        runCatching { server.shutdown() }
    }

    test("accepts a central RS256 ID token and caches only the pinned JWKS endpoint") {
        server.enqueue(jwks(signingKey))
        val verifier = verifier(server)
        val token = idToken(signingKey)

        verifier.verify(token, EXPECTED_NONCE) shouldBe SUBJECT
        verifier.verify(token, EXPECTED_NONCE) shouldBe SUBJECT

        val request = server.takeRequest()
        request.method shouldBe "GET"
        request.path shouldBe "/.well-known/jwks.json"
        request.getHeader("Authorization") shouldBe null
        server.requestCount shouldBe 1
    }

    test("authorization-code client persists tokens only after the default verifier accepts the ID token") {
        val token = idToken(signingKey)
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"access-1","refresh_token":"refresh-1","id_token":"$token","token_type":"Bearer","expires_in":900,"scope":"openid profile offline_access aim.api"}""",
            ),
        )
        server.enqueue(jwks(signingKey))
        val client = PassportOidcTokenClient(
            config = testConfig(server),
            nowEpochSeconds = { NOW },
        )

        client.exchangeAuthorizationCode(
            "authorization-code-123456",
            "v".repeat(43),
            EXPECTED_NONCE,
        ) shouldBe OidcTokens("access-1", "refresh-1", NOW + 900)

        server.takeRequest().path shouldBe "/oauth2/token"
        server.takeRequest().path shouldBe "/.well-known/jwks.json"
    }

    test("rejects alg none, algorithm substitution and untrusted header key material before JWKS lookup") {
        val verifier = verifier(server)
        val claims = claims()
        val none = PlainJWT(claims).serialize()
        val hmac = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.HS256).keyID(signingKey.keyID).build(),
            claims,
        ).apply { sign(MACSigner(ByteArray(32) { 7 })) }.serialize()
        val poisonedHeaders = listOf(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID)
                .jwkURL(URI("https://attacker.example/jwks.json")).build(),
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID)
                .x509CertURL(URI("https://attacker.example/cert.pem")).build(),
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID)
                .jwk(signingKey.toPublicJWK()).build(),
        ).map { header -> signed(signingKey, claims, header) }

        (listOf(none, hmac) + poisonedHeaders).forEach { token ->
            shouldThrow<BackendException> { verifier.verify(token, EXPECTED_NONCE) }
                .friendlyMessage() shouldBe "请求失败，请稍后再试"
        }
        server.requestCount shouldBe 0
    }

    test("rejects malformed local inputs, invalid configuration and every unsupported protected header") {
        val invalidConfig = NimbusPassportIdTokenVerifier(
            PassportOidcConfig(true, "https://attacker.example", "kittyecho-android", "kittyecho://auth"),
        )
        shouldThrow<BackendException> { invalidConfig.verify(idToken(signingKey), EXPECTED_NONCE) }

        val verifier = verifier(server)
        shouldThrow<BackendException> { verifier.verify("not-a-jwt", EXPECTED_NONCE) }
        shouldThrow<BackendException> { verifier.verify(idToken(signingKey), "short") }
        val invalidHeaders = listOf(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(),
            JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
            JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID("bad key id").build(),
            JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(signingKey.keyID)
                .criticalParams(setOf("custom")).customParam("custom", true).build(),
            JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(signingKey.keyID)
                .base64URLEncodePayload(false).build(),
        )
        invalidHeaders.forEach { header ->
            shouldThrow<BackendException> {
                verifier.verify(signed(signingKey, claims(), header), EXPECTED_NONCE)
            }
        }
        server.requestCount shouldBe 0
    }

    test("rejects signature, issuer, audience, time, nonce, token use and non-UUID subject substitutions") {
        server.enqueue(jwks(signingKey))
        val verifier = verifier(server)
        val otherKey = RSAKeyGenerator(2048)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .keyID(signingKey.keyID)
            .generate()
        val invalid = listOf(
            idToken(otherKey),
            idToken(signingKey, issuer = "https://attacker.example"),
            idToken(signingKey, audience = "attacker-client"),
            idToken(signingKey, issuedAt = NOW - 400, expiresAt = NOW - 100),
            idToken(signingKey, issuedAt = NOW + 61, expiresAt = NOW + 300),
            idToken(signingKey, issuedAt = NOW, expiresAt = NOW + 301),
            idToken(signingKey, nonce = "different-nonce-value-1234"),
            idToken(signingKey, tokenUse = "access"),
            idToken(signingKey, subject = "not-a-passport-uuid"),
            idToken(signingKey, subject = SUBJECT.uppercase()),
        )

        invalid.forEach { token ->
            shouldThrow<BackendException> { verifier.verify(token, EXPECTED_NONCE) }
                .friendlyMessage() shouldBe "请求失败，请稍后再试"
        }
        server.requestCount shouldBe 1
    }

    test("refresh validation may omit an expected nonce but still validates a supplied ID token") {
        server.enqueue(jwks(signingKey))
        val verifier = verifier(server)

        verifier.verify(idToken(signingKey), null) shouldBe SUBJECT
        shouldThrow<BackendException> {
            verifier.verify(idToken(signingKey, audience = "attacker-client"), null)
        }
    }

    test("requires every central identity and time claim while honoring exact skew boundaries") {
        server.enqueue(jwks(signingKey))
        val verifier = verifier(server)
        verifier.verify(idToken(signingKey, notBefore = NOW + 60), EXPECTED_NONCE) shouldBe SUBJECT
        val invalidClaims = listOf(
            idToken(signingKey, issuedAt = null),
            idToken(signingKey, expiresAt = null),
            idToken(signingKey, authTime = null),
            idToken(signingKey, nonce = null),
            idToken(signingKey, subject = null),
            idToken(signingKey, issuedAt = NOW, expiresAt = NOW + 59),
            idToken(signingKey, notBefore = NOW + 61),
            idToken(signingKey, authTime = NOW + 61),
            idToken(signingKey, nonce = "short"),
            idToken(signingKey, sessionId = "not-a-session-uuid"),
            idToken(signingKey, sessionId = null),
            idToken(signingKey, jwtId = null),
        )
        invalidClaims.forEach { token ->
            shouldThrow<BackendException> { verifier.verify(token, EXPECTED_NONCE) }
        }
        server.requestCount shouldBe 1
    }

    test("does not follow JWKS redirects and bounds every JWKS response") {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/attacker-jwks")),
        )
        shouldThrow<BackendException> {
            verifier(server).verify(idToken(signingKey), EXPECTED_NONCE)
        }
        server.requestCount shouldBe 1

        server.enqueue(MockResponse().setBody("x".repeat(65 * 1024)))
        shouldThrow<BackendException> {
            verifier(server).verify(idToken(signingKey), EXPECTED_NONCE)
        }
        server.requestCount shouldBe 2
    }

    test("rejects unknown, duplicate and non-RS256 JWKS keys") {
        val unknown = RSAKey.Builder(signingKey.toPublicJWK()).keyID("other-key").build()
        val wrongAlgorithm = RSAKey.Builder(signingKey.toPublicJWK())
            .algorithm(JWSAlgorithm.RS512)
            .build()
        val malformedSets = listOf(
            JWKSet(unknown).toString(),
            JWKSet(listOf(signingKey.toPublicJWK(), signingKey.toPublicJWK())).toString(),
            JWKSet(wrongAlgorithm).toString(),
        )

        malformedSets.forEach { body ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            shouldThrow<BackendException> {
                verifier(server).verify(idToken(signingKey), EXPECTED_NONCE)
            }
        }
        server.requestCount shouldBe malformedSets.size
    }

    test("rejects private, non-signing, non-verify, weak, non-RSA and excessive JWKS material") {
        val public = signingKey.toPublicJWK()
        val encryption = RSAKey.Builder(public).keyUse(KeyUse.ENCRYPTION).build()
        val signOperation = RSAKey.Builder(public).keyOperations(setOf(KeyOperation.SIGN)).build()
        val weakPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val weak = RSAKey.Builder(weakPair.public as java.security.interfaces.RSAPublicKey)
            .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyID(signingKey.keyID).build()
        val ec: ECKey = ECKeyGenerator(Curve.P_256).keyID(signingKey.keyID).generate().toPublicJWK()
        val tooMany = (0..16).map { index -> RSAKey.Builder(public).keyID("key-$index").build() }
        val malformedSets = listOf(
            JWKSet(signingKey).toString(false),
            JWKSet(encryption).toString(),
            JWKSet(signOperation).toString(),
            JWKSet(weak).toString(),
            JWKSet(ec).toString(),
            JWKSet().toString(),
            JWKSet(tooMany).toString(),
        )

        malformedSets.forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            shouldThrow<BackendException> {
                verifier(server).verify(idToken(signingKey), EXPECTED_NONCE)
            }
        }
        server.requestCount shouldBe malformedSets.size
    }

    test("accepts an explicit verify-only RSA key and rejects malformed JWKS JSON") {
        val verifyOnly = RSAKey.Builder(signingKey.toPublicJWK())
            .keyOperations(setOf(KeyOperation.VERIFY))
            .build()
        server.enqueue(MockResponse().setBody(JWKSet(verifyOnly).toString()))
        verifier(server).verify(idToken(signingKey), EXPECTED_NONCE) shouldBe SUBJECT

        server.enqueue(MockResponse().setBody("not-json"))
        shouldThrow<BackendException> {
            verifier(server).verify(idToken(signingKey), EXPECTED_NONCE)
        }
    }

    test("concurrent verification shares one in-flight pinned JWKS fetch") {
        server.enqueue(jwks(signingKey).setBodyDelay(100, TimeUnit.MILLISECONDS))
        val verifier = verifier(server)
        val token = idToken(signingKey)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                executor.submit<String> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    verifier.verify(token, EXPECTED_NONCE)
                }
            }
            ready.await(5, TimeUnit.SECONDS) shouldBe true
            start.countDown()
            results.map { it.get(5, TimeUnit.SECONDS) } shouldBe listOf(SUBJECT, SUBJECT)
            server.requestCount shouldBe 1
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    test("categorizes bounded JWKS timeout and network failure without transport details") {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val timeoutHttp = okhttp3.OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build()
        val timeout = shouldThrow<BackendException> {
            verifier(server, timeoutHttp).verify(idToken(signingKey), EXPECTED_NONCE)
        }
        timeout.kind shouldBe BackendException.Kind.TIMEOUT

        server.shutdown()
        val network = shouldThrow<BackendException> {
            verifier(server).verify(idToken(signingKey), EXPECTED_NONCE)
        }
        network.kind shouldBe BackendException.Kind.NETWORK
    }

    test("refreshes the bounded JWKS cache only on expiry or a new key id") {
        var now = NOW
        server.enqueue(jwks(signingKey))
        server.enqueue(jwks(signingKey))
        val expiring = verifier(server, nowEpochSeconds = { now })
        expiring.verify(idToken(signingKey), EXPECTED_NONCE)
        now += 301
        expiring.verify(
            idToken(signingKey, issuedAt = now, expiresAt = now + 300, authTime = now - 60),
            EXPECTED_NONCE,
        )
        server.requestCount shouldBe 2

        val rotated = RSAKeyGenerator(2048)
            .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyID("passport-rotated-key").generate()
        server.enqueue(jwks(signingKey))
        server.enqueue(jwks(rotated))
        val rotationVerifier = verifier(server)
        rotationVerifier.verify(idToken(signingKey), EXPECTED_NONCE)
        rotationVerifier.verify(idToken(rotated), EXPECTED_NONCE) shouldBe SUBJECT
        server.requestCount shouldBe 4
    }
})

private const val NOW = 1_000L
private const val EXPECTED_NONCE = "nonce-value-is-long-enough"
private const val SUBJECT = "13aa4872-6944-40f8-8fd5-d72990823a40"
private const val SESSION_ID = "c6658027-4d8e-4c40-80f2-acde7e7d7ab2"
private var currentIssuer = ""

private fun verifier(
    server: MockWebServer,
    http: okhttp3.OkHttpClient = okhttp3.OkHttpClient(),
    nowEpochSeconds: () -> Long = { NOW },
) = NimbusPassportIdTokenVerifier(
    config = testConfig(server),
    http = http,
    nowEpochSeconds = nowEpochSeconds,
)

private fun testConfig(server: MockWebServer) = PassportOidcConfig(
        enabled = true,
        issuer = server.url("/").toString().trimEnd('/'),
        clientId = "kittyecho-android",
        redirectUri = "kittyecho://auth",
        allowLoopbackIssuerForTests = true,
    )

private fun jwks(key: RSAKey): MockResponse = MockResponse()
    .setHeader("Content-Type", "application/json")
    .setBody(JWKSet(key.toPublicJWK()).toString())

private fun claims(
    issuer: String = currentIssuer,
    audience: String = "kittyecho-android",
    issuedAt: Long? = NOW,
    expiresAt: Long? = NOW + 300,
    nonce: String? = EXPECTED_NONCE,
    tokenUse: String = "id",
    subject: String? = SUBJECT,
    authTime: Long? = issuedAt?.minus(60),
    notBefore: Long? = null,
    sessionId: String? = SESSION_ID,
    jwtId: String? = "87f50f6e-090f-4f13-820f-8228f3dfba1c",
): JWTClaimsSet = JWTClaimsSet.Builder()
    .issuer(issuer)
    .audience(audience)
    .claim("token_use", tokenUse)
    .apply {
        subject?.let(::subject)
        issuedAt?.let { issueTime(Date(it * 1_000L)) }
        expiresAt?.let { expirationTime(Date(it * 1_000L)) }
        authTime?.let { claim("auth_time", it) }
        nonce?.let { claim("nonce", it) }
        notBefore?.let { notBeforeTime(Date(it * 1_000L)) }
        sessionId?.let { claim("sid", it) }
        jwtId?.let(::jwtID)
    }
    .build()

private fun idToken(
    key: RSAKey,
    issuer: String = currentIssuer,
    audience: String = "kittyecho-android",
    issuedAt: Long? = NOW,
    expiresAt: Long? = NOW + 300,
    nonce: String? = EXPECTED_NONCE,
    tokenUse: String = "id",
    subject: String? = SUBJECT,
    authTime: Long? = issuedAt?.minus(60),
    notBefore: Long? = null,
    sessionId: String? = SESSION_ID,
    jwtId: String? = "87f50f6e-090f-4f13-820f-8228f3dfba1c",
): String = signed(
    key,
    claims(
        issuer,
        audience,
        issuedAt,
        expiresAt,
        nonce,
        tokenUse,
        subject,
        authTime,
        notBefore,
        sessionId,
        jwtId,
    ),
    JWSHeader.Builder(JWSAlgorithm.RS256)
        .type(JOSEObjectType.JWT)
        .keyID(key.keyID)
        .build(),
)

private fun signed(key: RSAKey, claims: JWTClaimsSet, header: JWSHeader): String =
    SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
