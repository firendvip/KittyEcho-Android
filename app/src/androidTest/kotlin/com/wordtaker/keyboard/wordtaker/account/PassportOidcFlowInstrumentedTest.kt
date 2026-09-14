package com.wordtaker.keyboard.wordtaker.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PassportOidcFlowInstrumentedTest {
    @Test
    fun urlCodecRunsOnAndroidAndMalformedInputFailsClosed() {
        val successfulStore = MemoryPendingStore()
        val successfulFlow = flow(successfulStore)
        val start = successfulFlow.begin() as PassportStartResult.Ready
        assertTrue(start.authorizationUrl.contains("redirect_uri=kittyecho%3A%2F%2Fauth"))
        assertTrue(start.authorizationUrl.contains("scope=openid%20profile%20offline_access%20aim.api"))

        val accepted = successfulFlow.handleCallback(
            "kittyecho://auth?code=authorization%2Dcode%2D123456&state=${successfulStore.pending!!.state}",
        ) as PassportCallback.AuthorizationCode
        assertEquals("authorization-code-123456", accepted.code)

        listOf("%", "%GG", "%C3%28", "%FF").forEach { malformedValue ->
            val rejectedStore = MemoryPendingStore()
            val rejectedFlow = flow(rejectedStore)
            rejectedFlow.begin()
            assertTrue(
                rejectedFlow.handleCallback(
                    "kittyecho://auth?code=$malformedValue&state=${rejectedStore.pending!!.state}",
                ) is PassportCallback.Rejected,
            )
        }
    }

    private fun flow(store: MemoryPendingStore): PassportOidcFlow = PassportOidcFlow(
        config = PassportOidcConfig(
            enabled = true,
            issuer = "https://auth.yaa3.com",
            clientId = "kittyecho-android",
            redirectUri = "kittyecho://auth",
        ),
        pendingStore = store,
        randomBytes = { size -> ByteArray(size) { it.toByte() } },
        nowMillis = { 1_000L },
    )

    private class MemoryPendingStore : PassportPendingStore {
        var pending: PendingPassportAuthorization? = null

        override fun read(): PendingPassportAuthorization? = pending

        override fun write(pending: PendingPassportAuthorization): Boolean {
            this.pending = pending
            return true
        }

        override fun clear() {
            pending = null
        }
    }
}
