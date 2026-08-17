package com.wordtaker.keyboard.wordtaker.speech

internal data class ParaformerModelUiPresentation(
    val status: String,
    val detail: String,
)

internal object ParaformerModelPresentation {
    fun forState(state: ParaformerLifecycleState): ParaformerModelUiPresentation = when (state.phase) {
        ParaformerModelPhase.Uninstalled -> presentation("未安装", "首次使用需下载约 224 MB")
        ParaformerModelPhase.AwaitingConfirmation ->
            presentation("等待确认", "默认仅在 Wi-Fi 下载；移动数据需单独确认")
        ParaformerModelPhase.Queued -> presentation("等待下载", "任务已加入系统下载队列")
        ParaformerModelPhase.Downloading -> presentation(
            "下载中 ${progressPercent(state)}%",
            "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}",
        )
        ParaformerModelPhase.Paused -> presentation(
            "已暂停",
            when (state.pauseReason) {
                ParaformerPauseReason.Offline -> "网络不可用"
                ParaformerPauseReason.MeteredNetwork -> "已切换到收费网络"
                else -> "可继续断点下载"
            },
        )
        ParaformerModelPhase.Verifying -> presentation("正在校验", "核对文件长度与 SHA-256")
        ParaformerModelPhase.Installing -> presentation("正在安装", "正在安全写入本机私有目录")
        ParaformerModelPhase.Initializing -> presentation("正在初始化", "正在加载本地语音模型")
        ParaformerModelPhase.Ready -> presentation("可用", "Paraformer 本地语音识别已就绪")
        ParaformerModelPhase.Error -> forFailure(
            state.failure ?: ParaformerModelFailure.Initialization,
        )
    }

    fun forFailure(failure: ParaformerModelFailure): ParaformerModelUiPresentation = when (failure) {
        ParaformerModelFailure.Storage -> presentation("空间不足", "需要剩余下载量另加 128 MiB")
        ParaformerModelFailure.Network -> presentation("网络异常", "请检查网络后重试")
        ParaformerModelFailure.Protocol -> presentation("下载响应异常", "模型服务器响应不符合安全下载要求")
        ParaformerModelFailure.Integrity -> presentation("校验失败", "文件未通过长度或 SHA-256 校验")
        ParaformerModelFailure.Memory -> presentation("内存不足", "模型初始化失败，请关闭其他应用后重试")
        ParaformerModelFailure.Initialization -> presentation("初始化失败", "请重试模型初始化")
    }

    private fun presentation(status: String, detail: String) =
        ParaformerModelUiPresentation(status, detail)

    private fun progressPercent(state: ParaformerLifecycleState): Int =
        if (state.totalBytes <= 0L) 0 else
            ((state.downloadedBytes * 100L) / state.totalBytes).coerceIn(0L, 100L).toInt()

    private fun formatBytes(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)
}

internal object ParaformerCompactStatusPolicy {
    fun shouldShow(candidatesOwnToolbar: Boolean, phase: ParaformerModelPhase): Boolean =
        !candidatesOwnToolbar && phase != ParaformerModelPhase.Ready
}
