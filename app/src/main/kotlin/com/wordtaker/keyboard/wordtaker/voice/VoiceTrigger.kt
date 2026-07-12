package com.wordtaker.keyboard.wordtaker.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Process-wide one-shot channel that lets外部 entry points (the toolbar voice icon,
 * long-press space, the IME_UI_MODE_CAT_VOICE key) start voice recording on the
 * unified [CatKeyboardLayout] without owning its [VoiceViewModel].
 *
 * The merged单界面 means there is no longer a separate CAT_VOICE "屏" to switch to —
 * those entry points simply request recording to begin. [CatKeyboardLayout] collects
 * [requests] and forwards each one to [VoiceViewModel.startFromExternal].
 *
 * P2-011 修复：底层从 replay=0 的 SharedFlow 换成 CONFLATED [Channel]。SharedFlow 在
 * 「无收集者」瞬间丢事件 —— 从设置面板点语音图标时面板切回 TEXT、[CatKeyboardLayout]
 * 的收集器正随之重建，requestStart() 的事件恰好没人收、被丢弃，导致图标点了没反应。
 * Channel 会把事件缓存到下一个收集者出现（且每个事件只消费一次，不会重复触发）；连点
 * 多次合并为一次。[clearPending] 供 IME 生命周期在窗口隐藏时清掉尚未消费的请求，避免
 * 下次唤起键盘时"凭空"开始录音。
 */
object VoiceTrigger {
    private val requestChannel = Channel<Unit>(Channel.CONFLATED)
    val requests: Flow<Unit> = requestChannel.receiveAsFlow()

    /** Ask the unified panel to start a voice recording (no-op if already recording). */
    fun requestStart() {
        android.util.Log.d("VoiceTrigger", "requestStart")
        requestChannel.trySend(Unit)
    }

    /** Drops any buffered, not-yet-consumed start request (called on window hidden). */
    fun clearPending() {
        val had = requestChannel.tryReceive().isSuccess
        android.util.Log.d("VoiceTrigger", "clearPending had=$had")
    }
}
