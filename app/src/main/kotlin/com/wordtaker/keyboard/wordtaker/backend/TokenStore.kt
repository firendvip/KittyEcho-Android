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
class TokenStore internal constructor(
    private val storage: AuthStateStorage,
    private val tokenCipher: TokenCipher,
) : AuthSessionStore {

    constructor(
        context: Context,
        prefsName: String = PREFS_NAME,
    ) : this(
        storage = SharedPreferencesAuthStateStorage(context, prefsName),
        tokenCipher = AndroidKeystoreTokenCipher,
    )

    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var tokenLoaded = false
    private var generation = 0L

    @Synchronized
    override fun credentialGeneration(): Long = generation

    @Synchronized
    fun authRequestSession(): AuthRequestSession = AuthRequestSession(
        generation = generation,
        accessToken = accessToken(),
    )

    /** 仅返回 accessToken（无则 null）。 */
    fun accessToken(): String? {
        if (tokenLoaded) return cachedToken
        synchronized(this) {
            if (tokenLoaded) return cachedToken
            cachedToken = readToken()
            tokenLoaded = true
            return cachedToken
        }
    }

    override fun isLoggedIn(): Boolean = !accessToken().isNullOrBlank()

    /** 写入登录态：token 加密落盘，account 摘要明文 JSON。 */
    override fun set(accessToken: String, account: AccountInfo?) {
        require(accessToken.isNotBlank()) { "TokenStore.set 需要 accessToken" }
        synchronized(this) {
            val encrypted = tokenCipher.encrypt(accessToken)
                ?: throw IllegalStateException("系统安全存储暂不可用")
            storage.update(
                strings = mapOf(
                    KEY_TOKEN_ENC to encrypted,
                    KEY_LEGACY_ACCOUNT to account?.toJson()?.toString(),
                ),
                booleans = mapOf(KEY_LEGACY_ACCOUNT_INITIALIZED to true),
                removals = setOf(KEY_TOKEN_PLAIN),
            )
            cachedToken = accessToken
            tokenLoaded = true
            generation += 1
        }
    }

    /** 只为有效的独立业务令牌读取摘要；共享旧值可能属于已停用的统一登录。 */
    override fun account(): AccountInfo? {
        if (!isLoggedIn()) return null
        return runCatching {
            val value = if (storage.getBoolean(KEY_LEGACY_ACCOUNT_INITIALIZED)) {
                storage.getString(KEY_LEGACY_ACCOUNT)
            } else {
                storage.getString(KEY_ACCOUNT)
            }
            value?.let { AccountInfoJson.from(JSONObject(it)) }
        }.getOrNull()
    }

    /** 更新账号摘要（token 不变）。 */
    override fun updateAccount(account: AccountInfo?) {
        if (!isLoggedIn()) return
        storage.update(
            strings = mapOf(KEY_LEGACY_ACCOUNT to account?.toJson()?.toString()),
            booleans = mapOf(KEY_LEGACY_ACCOUNT_INITIALIZED to true),
        )
    }

    /** 退出登录 / token 失效时清空。 */
    override fun clear() {
        synchronized(this) {
            storage.update(
                removals = setOf(
                    KEY_TOKEN_ENC,
                    KEY_TOKEN_PLAIN,
                    KEY_LEGACY_ACCOUNT,
                    KEY_LEGACY_ACCOUNT_INITIALIZED,
                ),
            )
            cachedToken = null
            tokenLoaded = true
            generation += 1
        }
    }

    // —— 加解密（Android Keystore AES/GCM）——

    private fun readToken(): String? {
        storage.getString(KEY_TOKEN_ENC)?.let { enc ->
            tokenCipher.decrypt(enc)?.let { return it }
        }
        val legacy = storage.getString(KEY_TOKEN_PLAIN)?.takeIf { it.isNotBlank() }
            ?: return null
        val migrated = tokenCipher.encrypt(legacy)
        if (migrated == null) {
            storage.update(removals = setOf(KEY_TOKEN_PLAIN))
            return null
        }
        storage.update(
            strings = mapOf(KEY_TOKEN_ENC to migrated),
            removals = setOf(KEY_TOKEN_PLAIN),
        )
        return legacy
    }

    private companion object {
        const val PREFS_NAME = "wt_backend_auth"
        const val KEY_TOKEN_ENC = "token_enc"
        const val KEY_TOKEN_PLAIN = "token_plain"
        const val KEY_LEGACY_ACCOUNT = "legacy_account_json"
        const val KEY_LEGACY_ACCOUNT_INITIALIZED = "legacy_account_initialized"
        // Retained for read-only compatibility with pre-rollback independent profiles.
        const val KEY_ACCOUNT = "account_json"
    }
}

internal interface AuthStateStorage {
    fun getString(key: String): String?

    fun getBoolean(key: String): Boolean

    fun update(
        strings: Map<String, String?> = emptyMap(),
        booleans: Map<String, Boolean> = emptyMap(),
        removals: Set<String> = emptySet(),
    )
}

internal interface TokenCipher {
    fun encrypt(plain: String): String?

    fun decrypt(stored: String): String?
}

private class SharedPreferencesAuthStateStorage(
    context: Context,
    prefsName: String,
) : AuthStateStorage {
    private val prefs = context.applicationContext
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getBoolean(key: String): Boolean = prefs.getBoolean(key, false)

    override fun update(
        strings: Map<String, String?>,
        booleans: Map<String, Boolean>,
        removals: Set<String>,
    ) {
        val editor = prefs.edit()
        removals.forEach(editor::remove)
        strings.forEach(editor::putString)
        booleans.forEach(editor::putBoolean)
        editor.apply()
    }
}

private object AndroidKeystoreTokenCipher : TokenCipher {
    override fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(stored: String): String? = runCatching {
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

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wt_backend_token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
}

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
