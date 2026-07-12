package com.wordtaker.keyboard.wordtaker.backend

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * 稳定设备身份（x-device-id）—— 对齐 Mac 端 deviceIdentity.js 的口径：
 * 硬件级标识 + 固定前缀域分离 → sha256 截 32 位小写 hex。
 *
 * Android 用 ANDROID_ID（按 app 签名 + 用户稳定，重装不变），加盐哈希后不泄露原始值，
 * 且 32 位 hex 天然满足后端 8-64 位 [A-Za-z0-9._:-] 约束。
 * ANDROID_ID 异常缺失时回退：SharedPreferences 持久化随机 UUID（尽力跨会话稳定）。
 */
object DeviceIdentity {

    private const val PREFS_NAME = "wt_device"
    private const val KEY_BACKEND_DEVICE_ID = "backend_device_id"
    private const val KEY_FALLBACK_SEED = "backend_fallback_seed"
    private const val HASH_SALT_PREFIX = "wordtaker-device:"
    private const val HASH_HEX_LEN = 32

    @Volatile
    private var cached: String? = null

    /** 返回稳定 deviceId（32 位小写 hex）。线程安全，首次计算后缓存。 */
    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val id = compute(context.applicationContext)
            cached = id
            return id
        }
    }

    private fun compute(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 已算过就复用，保证跨进程重启稳定（即使系统日后改变 ANDROID_ID 行为）。
        prefs.getString(KEY_BACKEND_DEVICE_ID, null)?.let { if (it.isNotBlank()) return it }

        val seed = readAndroidId(context) ?: fallbackSeed(prefs)
        val id = derive(seed)
        prefs.edit().putString(KEY_BACKEND_DEVICE_ID, id).apply()
        return id
    }

    @SuppressLint("HardwareIds")
    private fun readAndroidId(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" /* 已知重复值 */ }

    private fun fallbackSeed(prefs: android.content.SharedPreferences): String {
        prefs.getString(KEY_FALLBACK_SEED, null)?.let { if (it.isNotBlank()) return it }
        val seed = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_FALLBACK_SEED, seed).apply()
        return seed
    }

    /** 加盐 sha256 截 32 位小写 hex（与 Mac deriveFromGuid 同构）。 */
    internal fun derive(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((HASH_SALT_PREFIX + seed.lowercase()).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_HEX_LEN)
    }
}
