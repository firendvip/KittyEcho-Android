package com.wordtaker.keyboard.wordtaker.backend

/**
 * 收费后端（ai-input-method-server）接入配置 —— 单一来源，对齐 Mac 端 backendConfig.js。
 *
 * 生产：https://look3.cn + /aiapi（nginx 映射到后端 /api/v1）。
 * Android 无 dev/prod 双轨（模拟器联调本机后端可临时改 BASE_URL 为 http://10.0.2.2:3777 +
 * /api/v1，并放开 cleartext）——默认恒为生产地址，避免散落硬编码。
 */
object BackendConfig {
    /** 后端根地址。 */
    const val BASE_URL = "https://look3.cn"

    /** 统一 API 前缀（nginx → NestJS /api/v1）。 */
    const val API_PREFIX = "/aiapi"

    /** 请求头 x-platform 值。后端无平台白名单，对 android/mac/win 一视同仁（已生产实测放行）。 */
    const val PLATFORM = "android"

    /** 通用请求超时（登录/额度/兑换等）。 */
    const val REQUEST_TIMEOUT_MS = 60_000L

    /**
     * 润色请求超时。VoiceViewModel 对整个 polish 有 10s 硬预算（POLISH_TIMEOUT_MS），
     * 后端主通路给 6s，留 ~4s 给 relay 降级兜底。
     */
    const val POLISH_TIMEOUT_MS = 6_000L

    /** 后端失败（非额度类）时是否降级回退旧 relay。对齐 Mac BACKEND_CLOUD_FALLBACK_RELAY。 */
    const val FALLBACK_TO_RELAY = true

    /** 微信登录 deep link（系统浏览器授权后回跳）。 */
    const val WECHAT_DEEPLINK_SCHEME = "kittyecho"
    const val WECHAT_DEEPLINK_HOST = "auth"
}
