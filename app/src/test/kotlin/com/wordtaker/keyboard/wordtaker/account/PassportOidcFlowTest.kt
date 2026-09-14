package com.wordtaker.keyboard.wordtaker.account

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.URI
import java.net.URLDecoder

class PassportOidcFlowTest : FunSpec({

    test("default-off and incomplete configuration fail closed without creating pending state") {
        val store = MemoryPendingStore()
        flow(store = store, enabled = false).begin()
            .shouldBeInstanceOf<PassportStartResult.Unavailable>()
        store.pending shouldBe null

        flow(store = store, clientId = "").begin()
            .shouldBeInstanceOf<PassportStartResult.Unavailable>()
        store.pending shouldBe null

        flow(store = store, issuer = "http://auth.yaa3.com").begin()
            .shouldBeInstanceOf<PassportStartResult.Unavailable>()
        store.pending shouldBe null

        flow(store = store, issuer = "https://attacker.example").begin()
            .shouldBeInstanceOf<PassportStartResult.Unavailable>()
        store.pending shouldBe null

        flow(store = store, redirectUri = "kittyecho://attacker").begin()
            .shouldBeInstanceOf<PassportStartResult.Unavailable>()
        store.pending shouldBe null

        flow(store = store, issuer = "https://user@auth.yaa3.com").begin()
            .shouldBeInstanceOf<PassportStartResult.Unavailable>()
        store.pending shouldBe null
    }

    test("default entropy and clock produce a valid recoverable request") {
        val store = MemoryPendingStore()
        val passport = PassportOidcFlow(
            PassportOidcConfig(true, "https://auth.yaa3.com", "kittyecho-android", "kittyecho://auth"),
            store,
        )

        passport.isAvailable shouldBe true
        passport.begin().shouldBeInstanceOf<PassportStartResult.Ready>()
        passport.hasRecoverableLogin shouldBe true
    }

    test("begin persists recoverable state and emits the exact public native PKCE request") {
        val store = MemoryPendingStore()
        val result = flow(store).begin().shouldBeInstanceOf<PassportStartResult.Ready>()
        val query = query(URI(result.authorizationUrl).rawQuery)

        URI(result.authorizationUrl).let {
            it.scheme shouldBe "https"
            it.host shouldBe "auth.yaa3.com"
            it.path shouldBe "/oauth2/authorize"
        }
        query.keys.sorted().shouldContainExactly(
            "client_id",
            "code_challenge",
            "code_challenge_method",
            "nonce",
            "redirect_uri",
            "response_type",
            "scope",
            "state",
        )
        query["client_id"] shouldBe listOf("kittyecho-android")
        query["redirect_uri"] shouldBe listOf("kittyecho://auth")
        query["response_type"] shouldBe listOf("code")
        query["scope"] shouldBe listOf("openid profile offline_access aim.api")
        query["code_challenge_method"] shouldBe listOf("S256")
        query.getValue("state").single().length shouldBe 32
        query.getValue("nonce").single().length shouldBe 32
        query.getValue("code_challenge").single().length shouldBe 43
        store.pending?.codeVerifier?.length shouldBe 43
        store.pending?.state shouldBe query.getValue("state").single()
        flow(store).hasRecoverableLogin shouldBe true
    }

    test("matching callback is single-use and returns only code plus the stored verifier") {
        val store = MemoryPendingStore()
        val passport = flow(store)
        val start = passport.begin().shouldBeInstanceOf<PassportStartResult.Ready>()
        val state = query(URI(start.authorizationUrl).rawQuery).getValue("state").single()
        val verifier = store.pending!!.codeVerifier
        val code = "authorization-code-123456"

        val accepted = passport.handleCallback("kittyecho://auth?code=$code&state=$state")
            .shouldBeInstanceOf<PassportCallback.AuthorizationCode>()
        accepted.code shouldBe code
        accepted.codeVerifier shouldBe verifier
        accepted.redirectUri shouldBe "kittyecho://auth"
        store.pending shouldBe null

        passport.handleCallback("kittyecho://auth?code=$code&state=$state")
            .shouldBeInstanceOf<PassportCallback.Rejected>()
    }

    test("callback rejects mismatched state and every non-exact URI or ambiguous query") {
        val invalidCallbacks = listOf(
            "kittyecho://auth?code=authorization-code-123456&state=wrong-state-value",
            "KITTYECHO://auth?code=authorization-code-123456&state=%STATE%",
            "kittyecho://AUTH?code=authorization-code-123456&state=%STATE%",
            "kittyecho://auth/path?code=authorization-code-123456&state=%STATE%",
            "kittyecho://auth?code=authorization-code-123456&state=%STATE%#fragment",
            "kittyecho://auth?code=authorization-code-123456&state=%STATE%&next=evil",
            "kittyecho://auth?code=one-code-value-1234&code=two-code-value-1234&state=%STATE%",
            "kittyecho://auth?code=authorization-code-123456&state=%STATE%&state=%STATE%",
            "kittyecho://auth?state=%STATE%",
            "kittyecho://auth?code=short&state=%STATE%",
            "kittyecho://auth?code=${"a".repeat(4096)}&state=%STATE%",
        )

        invalidCallbacks.forEach { raw ->
            val store = MemoryPendingStore()
            val passport = flow(store)
            val start = passport.begin().shouldBeInstanceOf<PassportStartResult.Ready>()
            val state = query(URI(start.authorizationUrl).rawQuery).getValue("state").single()
            passport.handleCallback(raw.replace("%STATE%", state))
                .shouldBeInstanceOf<PassportCallback.Rejected>()
        }
    }

    test("disabled flow malformed URI and invalid OAuth error fail closed") {
        val disabledStore = MemoryPendingStore().apply {
            pending = PendingPassportAuthorization("s".repeat(24), "n".repeat(24), "v".repeat(43), 1_000_000L)
        }
        flow(disabledStore, enabled = false).handleCallback(
            "kittyecho://auth?code=authorization-code-123456&state=${"s".repeat(24)}",
        ).shouldBeInstanceOf<PassportCallback.Rejected>()
        disabledStore.pending shouldBe null

        val malformed = flow(MemoryPendingStore())
        startState(malformed)
        malformed.handleCallback("kittyecho://auth?%")
            .shouldBeInstanceOf<PassportCallback.Rejected>()

        val invalidError = flow(MemoryPendingStore())
        val state = startState(invalidError)
        invalidError.handleCallback("kittyecho://auth?error=bad-error&state=$state")
            .shouldBeInstanceOf<PassportCallback.Rejected>()
    }

    test("provider cancellation and error consume matching state without exposing descriptions") {
        val cancelStore = MemoryPendingStore()
        val cancelFlow = flow(cancelStore)
        val cancelState = startState(cancelFlow)
        cancelFlow.handleCallback(
            "kittyecho://auth?error=access_denied&error_description=sensitive&state=$cancelState",
        ) shouldBe PassportCallback.Cancelled
        cancelStore.pending shouldBe null

        val errorStore = MemoryPendingStore()
        val errorFlow = flow(errorStore)
        val errorState = startState(errorFlow)
        errorFlow.handleCallback(
            "kittyecho://auth?error=server_error&error_description=sensitive&state=$errorState",
        ).shouldBeInstanceOf<PassportCallback.Rejected>()
            .message.contains("sensitive") shouldBe false
        errorStore.pending shouldBe null
    }

    test("expired state and explicit cancellation clear recoverable credentials") {
        var now = 1_000_000L
        val store = MemoryPendingStore()
        val passport = flow(store = store, nowMillis = { now })
        val state = startState(passport)
        now += PassportOidcFlow.PENDING_TTL_MS + 1

        passport.handleCallback(
            "kittyecho://auth?code=authorization-code-123456&state=$state",
        ).shouldBeInstanceOf<PassportCallback.Rejected>()
        store.pending shouldBe null

        startState(passport)
        passport.cancel()
        store.pending shouldBe null
        passport.hasRecoverableLogin shouldBe false
    }

    test("secure storage failure prevents opening the browser") {
        val store = MemoryPendingStore(canWrite = false)
        flow(store).begin().shouldBeInstanceOf<PassportStartResult.Unavailable>()
        store.pending shouldBe null
    }
})

private fun flow(
    store: MemoryPendingStore,
    enabled: Boolean = true,
    issuer: String = "https://auth.yaa3.com",
    clientId: String = "kittyecho-android",
    redirectUri: String = "kittyecho://auth",
    nowMillis: () -> Long = { 1_000_000L },
): PassportOidcFlow {
    var seed = 0
    return PassportOidcFlow(
        config = PassportOidcConfig(enabled, issuer, clientId, redirectUri),
        pendingStore = store,
        randomBytes = { size -> ByteArray(size) { (seed++ and 0xff).toByte() } },
        nowMillis = nowMillis,
    )
}

private fun startState(flow: PassportOidcFlow): String {
    val start = flow.begin().shouldBeInstanceOf<PassportStartResult.Ready>()
    return query(URI(start.authorizationUrl).rawQuery).getValue("state").single()
}

private fun query(raw: String): Map<String, List<String>> = raw.split('&')
    .map { it.split('=', limit = 2) }
    .groupBy(
        keySelector = { URLDecoder.decode(it[0], Charsets.UTF_8) },
        valueTransform = { URLDecoder.decode(it.getOrElse(1) { "" }, Charsets.UTF_8) },
    )

private class MemoryPendingStore(
    private val canWrite: Boolean = true,
) : PassportPendingStore {
    var pending: PendingPassportAuthorization? = null

    override fun read(): PendingPassportAuthorization? = pending

    override fun write(pending: PendingPassportAuthorization): Boolean {
        if (!canWrite) return false
        this.pending = pending
        return true
    }

    override fun clear() {
        pending = null
    }
}
