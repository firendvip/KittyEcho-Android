package com.wordtaker.keyboard.wordtaker.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores PKCE verifier/state encrypted with a dedicated Android Keystore key. */
class AndroidPassportPendingStore(context: Context) : PassportPendingStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): PendingPassportAuthorization? {
        val encrypted = prefs.getString(KEY_PENDING_ENCRYPTED, null) ?: return null
        val pending = decrypt(encrypted)?.let { value ->
            runCatching {
                val json = JSONObject(value)
                PendingPassportAuthorization(
                    state = json.getString("state"),
                    nonce = json.getString("nonce"),
                    codeVerifier = json.getString("codeVerifier"),
                    createdAtMillis = json.getLong("createdAtMillis"),
                )
            }.getOrNull()
        }
        if (pending == null) clear()
        return pending
    }

    override fun write(pending: PendingPassportAuthorization): Boolean {
        val value = JSONObject()
            .put("state", pending.state)
            .put("nonce", pending.nonce)
            .put("codeVerifier", pending.codeVerifier)
            .put("createdAtMillis", pending.createdAtMillis)
            .toString()
        val encrypted = encrypt(value) ?: return false
        prefs.edit().putString(KEY_PENDING_ENCRYPTED, encrypted).apply()
        return true
    }

    override fun clear() {
        prefs.edit().remove(KEY_PENDING_ENCRYPTED).apply()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(stored: String): String? = runCatching {
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            obtainKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS_NAME = "wt_passport_pending"
        const val KEY_PENDING_ENCRYPTED = "pending_encrypted"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wt_passport_pending_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
