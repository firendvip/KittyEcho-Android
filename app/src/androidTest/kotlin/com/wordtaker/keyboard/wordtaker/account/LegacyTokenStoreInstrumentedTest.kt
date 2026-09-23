package com.wordtaker.keyboard.wordtaker.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wordtaker.keyboard.wordtaker.backend.AccountInfo
import com.wordtaker.keyboard.wordtaker.backend.TokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyTokenStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences(TEST_PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun resetFixture() {
        prefs.edit().clear().commit()
    }

    @After
    fun cleanFixture() {
        prefs.edit().clear().commit()
    }

    @Test
    fun oidcOnlyDataStaysByteForByteWhileTheRollbackRemainsSignedOut() {
        seedRetainedPassportData()

        val store = TokenStore(context, TEST_PREFS_NAME)
        assertFalse(store.isLoggedIn())
        assertNull(store.account())

        store.clear()

        assertEquals(OIDC_CIPHERTEXT, prefs.getString(KEY_OIDC_SESSION, null))
        assertEquals(PASSPORT_PROFILE, prefs.getString(KEY_SHARED_ACCOUNT, null))
    }

    @Test
    fun independentLoginUsesSeparateProfileStorageWithoutOverwritingPassportData() {
        seedRetainedPassportData()
        val store = TokenStore(context, TEST_PREFS_NAME)
        val independent = AccountInfo(
            userId = "legacy-user",
            nickname = "独立账号",
            inviteCode = null,
            email = "cat@example.com",
            phone = null,
        )

        store.set("legacy-access-token", independent)

        assertEquals("legacy-user", store.account()?.userId)
        assertEquals(OIDC_CIPHERTEXT, prefs.getString(KEY_OIDC_SESSION, null))
        assertEquals(PASSPORT_PROFILE, prefs.getString(KEY_SHARED_ACCOUNT, null))

        store.clear()

        assertFalse(store.isLoggedIn())
        assertNull(store.account())
        assertEquals(OIDC_CIPHERTEXT, prefs.getString(KEY_OIDC_SESSION, null))
        assertEquals(PASSPORT_PROFILE, prefs.getString(KEY_SHARED_ACCOUNT, null))
    }

    @Test
    fun independentLoginWithoutInlineProfileNeverFallsBackToRetainedPassportProfile() {
        seedRetainedPassportData()
        TokenStore(context, TEST_PREFS_NAME).set("legacy-access-token", null)

        val restartedStore = TokenStore(context, TEST_PREFS_NAME)

        assertEquals(true, restartedStore.isLoggedIn())
        assertNull(restartedStore.account())
        assertEquals(OIDC_CIPHERTEXT, prefs.getString(KEY_OIDC_SESSION, null))
        assertEquals(PASSPORT_PROFILE, prefs.getString(KEY_SHARED_ACCOUNT, null))
    }

    private fun seedRetainedPassportData() {
        prefs.edit()
            .putString(KEY_OIDC_SESSION, OIDC_CIPHERTEXT)
            .putString(KEY_SHARED_ACCOUNT, PASSPORT_PROFILE)
            .commit()
    }

    private companion object {
        const val TEST_PREFS_NAME = "wt_backend_auth_rollback_test"
        const val KEY_OIDC_SESSION = "oidc_session_enc"
        const val KEY_SHARED_ACCOUNT = "account_json"
        const val OIDC_CIPHERTEXT = "synthetic-retained-oidc-ciphertext"
        const val PASSPORT_PROFILE =
            "{\"userId\":\"passport-user\",\"email\":\"passport@example.com\"}"
    }
}
