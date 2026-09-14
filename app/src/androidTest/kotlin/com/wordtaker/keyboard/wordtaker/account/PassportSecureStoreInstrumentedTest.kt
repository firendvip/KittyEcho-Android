package com.wordtaker.keyboard.wordtaker.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wordtaker.keyboard.wordtaker.backend.OidcTokens
import com.wordtaker.keyboard.wordtaker.backend.TokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class PassportSecureStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearStoresAndKeys() {
        context.getSharedPreferences(PASSPORT_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        listOf(PASSPORT_KEY_ALIAS, TOKEN_KEY_ALIAS).forEach { alias ->
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    @Test
    fun pendingAuthorizationRoundTripsEncryptedAndKeyLossFailsClosed() {
        val pending = PendingPassportAuthorization(
            state = "s".repeat(24),
            nonce = "n".repeat(24),
            codeVerifier = "v".repeat(43),
            createdAtMillis = 1_000L,
        )
        val store = AndroidPassportPendingStore(context)
        assertTrue(store.write(pending))
        assertEquals(pending, store.read())

        val prefs = context.getSharedPreferences(PASSPORT_PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(PENDING_KEY, null).orEmpty()
        assertFalse(encrypted.contains(pending.state))
        assertFalse(encrypted.contains(pending.codeVerifier))

        deleteKey(PASSPORT_KEY_ALIAS)
        assertNull(AndroidPassportPendingStore(context).read())
        assertFalse(prefs.contains(PENDING_KEY))
    }

    @Test
    fun oidcSessionRoundTripsEncryptedAndKeyLossDeletesUnreadableCredentials() {
        val tokens = OidcTokens("session-a", "family-a", 2_000L)
        TokenStore(context).setOidc(tokens, null)

        val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(OIDC_KEY, null).orEmpty()
        assertFalse(encrypted.contains(tokens.accessToken))
        assertFalse(encrypted.contains(tokens.refreshToken.orEmpty()))
        assertEquals(tokens, TokenStore(context).oidcTokens())

        deleteKey(TOKEN_KEY_ALIAS)
        assertNull(TokenStore(context).oidcTokens())
        assertFalse(prefs.contains(OIDC_KEY))
    }

    @Test
    fun disabledCleanupRemovesOidcCiphertextWithoutDecryptingOrRecreatingItsKey() {
        TokenStore(context).setOidc(OidcTokens("session-a", "family-a", 2_000L), null)
        deleteKey(TOKEN_KEY_ALIAS)

        TokenStore(context).clearOidc()

        val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        assertFalse(prefs.contains(OIDC_KEY))
        assertFalse(KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(TOKEN_KEY_ALIAS))
        assertFalse(TokenStore(context).isLoggedIn())
    }

    private fun deleteKey(alias: String) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            deleteEntry(alias)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val PASSPORT_PREFS = "wt_passport_pending"
        const val TOKEN_PREFS = "wt_backend_auth"
        const val PENDING_KEY = "pending_encrypted"
        const val OIDC_KEY = "oidc_session_enc"
        const val PASSPORT_KEY_ALIAS = "wt_passport_pending_v1"
        const val TOKEN_KEY_ALIAS = "wt_backend_token"
    }
}
