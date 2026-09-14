package com.wordtaker.keyboard.wordtaker.backend

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

/**
 * JWT 与账号摘要的安全存储 —— 对齐 Mac 端 tokenStore.js 的角色。
 *
 * accessToken 用 Android Keystore（AES/GCM，密钥不出安全硬件）加密后落
 * SharedPreferences；账号摘要（昵称/邀请码等非机密）明文 JSON 存储。
 * Keystore 不可用时拒绝持久化 token。历史明文兼容值仅允许原地迁移为密文；
 * 无法迁移时立即删除，避免凭据继续以明文留存。
 */
class TokenStore(context: Context) : AuthSessionStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var cachedOidcTokens: OidcTokens? = null
    @Volatile
    private var tokenLoaded = false

    /** 仅返回 accessToken（无则 null）。 */
    fun accessToken(): String? {
        if (tokenLoaded) return cachedOidcTokens?.accessToken ?: cachedToken
        synchronized(this) {
            if (tokenLoaded) return cachedOidcTokens?.accessToken ?: cachedToken
            cachedOidcTokens = readOidcTokens()
            cachedToken = if (cachedOidcTokens == null) readToken() else null
            tokenLoaded = true
            return cachedOidcTokens?.accessToken ?: cachedToken
        }
    }

    override fun isLoggedIn(): Boolean = !accessToken().isNullOrBlank()

    /** 写入登录态：token 加密落盘，account 摘要明文 JSON。 */
    override fun set(accessToken: String, account: AccountInfo?) {
        require(accessToken.isNotBlank()) { "TokenStore.set 需要 accessToken" }
        synchronized(this) {
            val encrypted = encrypt(accessToken)
                ?: throw IllegalStateException("系统安全存储暂不可用")
            val editor = prefs.edit()
            editor.putString(KEY_TOKEN_ENC, encrypted)
                .remove(KEY_TOKEN_PLAIN)
                .remove(KEY_OIDC_SESSION_ENC)
            editor.putString(KEY_ACCOUNT, account?.toJson()?.toString())
            editor.apply()
            cachedToken = accessToken
            cachedOidcTokens = null
            tokenLoaded = true
        }
    }

    override fun setOidc(tokens: OidcTokens, account: AccountInfo?) {
        require(tokens.isValidStoredSession()) { "OIDC session is invalid" }
        synchronized(this) {
            val encrypted = encrypt(tokens.toJson().toString())
                ?: throw IllegalStateException("系统安全存储暂不可用")
            prefs.edit()
                .putString(KEY_OIDC_SESSION_ENC, encrypted)
                .remove(KEY_TOKEN_ENC)
                .remove(KEY_TOKEN_PLAIN)
                .putString(KEY_ACCOUNT, account?.toJson()?.toString())
                .apply()
            cachedOidcTokens = tokens
            cachedToken = null
            tokenLoaded = true
        }
    }

    override fun oidcTokens(): OidcTokens? {
        accessToken()
        return cachedOidcTokens
    }

    override fun updateOidcTokens(tokens: OidcTokens) {
        require(tokens.isValidStoredSession()) { "OIDC session is invalid" }
        synchronized(this) {
            val encrypted = encrypt(tokens.toJson().toString())
                ?: throw IllegalStateException("系统安全存储暂不可用")
            prefs.edit().putString(KEY_OIDC_SESSION_ENC, encrypted).apply()
            cachedOidcTokens = tokens
            cachedToken = null
            tokenLoaded = true
        }
    }

    /** 读取账号摘要（无则 null）。 */
    override fun account(): AccountInfo? = runCatching {
        prefs.getString(KEY_ACCOUNT, null)?.let { AccountInfoJson.from(JSONObject(it)) }
    }.getOrNull()

    /** 更新账号摘要（token 不变）。 */
    override fun updateAccount(account: AccountInfo?) {
        prefs.edit().putString(KEY_ACCOUNT, account?.toJson()?.toString()).apply()
    }

    /** 退出登录 / token 失效时清空。 */
    override fun clear() {
        synchronized(this) {
            prefs.edit()
                .remove(KEY_TOKEN_ENC)
                .remove(KEY_TOKEN_PLAIN)
                .remove(KEY_OIDC_SESSION_ENC)
                .remove(KEY_ACCOUNT)
                .apply()
            cachedToken = null
            cachedOidcTokens = null
            tokenLoaded = true
        }
    }

    // —— 加解密（Android Keystore AES/GCM）——

    private fun readToken(): String? {
        prefs.getString(KEY_TOKEN_ENC, null)?.let { enc ->
            decrypt(enc)?.let { return it }
        }
        val legacy = prefs.getString(KEY_TOKEN_PLAIN, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val migrated = encrypt(legacy)
        if (migrated == null) {
            prefs.edit().remove(KEY_TOKEN_PLAIN).apply()
            return null
        }
        prefs.edit()
            .putString(KEY_TOKEN_ENC, migrated)
            .remove(KEY_TOKEN_PLAIN)
            .apply()
        return legacy
    }

    private fun readOidcTokens(): OidcTokens? {
        val encrypted = prefs.getString(KEY_OIDC_SESSION_ENC, null) ?: return null
        val parsed = decrypt(encrypted)?.let { value ->
            runCatching { JSONObject(value).toOidcTokens() }.getOrNull()
        }?.takeIf(OidcTokens::isValidStoredSession)
        if (parsed == null) prefs.edit().remove(KEY_OIDC_SESSION_ENC).apply()
        return parsed
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(stored: String): String? = runCatching {
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    private fun obtainKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "wt_backend_auth"
        const val KEY_TOKEN_ENC = "token_enc"
        const val KEY_OIDC_SESSION_ENC = "oidc_session_enc"
        const val KEY_TOKEN_PLAIN = "token_plain"
        const val KEY_ACCOUNT = "account_json"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wt_backend_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

private fun OidcTokens.toJson(): JSONObject = JSONObject()
    .put("accessToken", accessToken)
    .putOpt("refreshToken", refreshToken)
    .put("expiresAtEpochSeconds", expiresAtEpochSeconds)

private fun JSONObject.toOidcTokens(): OidcTokens = OidcTokens(
    accessToken = optString("accessToken"),
    refreshToken = optString("refreshToken").takeIf(String::isNotBlank),
    expiresAtEpochSeconds = optLong("expiresAtEpochSeconds", -1L),
)

private fun OidcTokens.isValidStoredSession(): Boolean =
    accessToken.length in 8..8192 &&
        accessToken.none { it.isWhitespace() || it.isISOControl() } &&
        (refreshToken == null || (
            refreshToken.length in 8..8192 && refreshToken.none { it.isWhitespace() || it.isISOControl() }
        )) &&
        expiresAtEpochSeconds > 0

/** AccountInfo <-> JSON（TokenStore 持久化用）。 */
private fun AccountInfo.toJson(): JSONObject = JSONObject()
    .putOpt("userId", userId)
    .putOpt("nickname", nickname)
    .putOpt("inviteCode", inviteCode)
    .putOpt("email", email)
    .putOpt("phone", phone)

internal object AccountInfoJson {
    fun from(json: JSONObject): AccountInfo = AccountInfo(
        userId = json.optString("userId").takeIf { it.isNotBlank() },
        nickname = json.optString("nickname").takeIf { it.isNotBlank() },
        inviteCode = json.optString("inviteCode").takeIf { it.isNotBlank() },
        email = json.optString("email").takeIf { it.isNotBlank() },
        phone = json.optString("phone").takeIf { it.isNotBlank() },
    )
}
