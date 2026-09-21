package com.wordtaker.keyboard.wordtaker.speech

internal data class ParaformerModelUiPresentation(
    val status: String,
    val detail: String,
)

internal object ParaformerModelPresentation {
    fun forState(state: ParaformerLifecycleState): ParaformerModelUiPresentation = when (state.phase) {
        ParaformerModelPhase.Installing ->
            presentation("正在准备", "正在安全安装 APK 内置语音模型")
        ParaformerModelPhase.Initializing ->
            presentation("正在初始化", "正在加载本地语音模型")
        ParaformerModelPhase.Ready ->
            presentation("可用", "Paraformer 本地语音识别已就绪")
        ParaformerModelPhase.Error -> forFailure(
            state.failure ?: ParaformerModelFailure.Initialization,
        )
    }

    fun forFailure(failure: ParaformerModelFailure): ParaformerModelUiPresentation = when (failure) {
        ParaformerModelFailure.Storage -> presentation("空间不足", "请释放存储空间后重试")
        ParaformerModelFailure.Integrity -> presentation("校验失败", "内置语音模型未通过安全校验")
        ParaformerModelFailure.Memory -> presentation("内存不足", "请关闭其他应用后重试")
        ParaformerModelFailure.Initialization -> presentation("初始化失败", "请重试模型初始化")
    }

    private fun presentation(status: String, detail: String) =
        ParaformerModelUiPresentation(status, detail)
}

internal object ParaformerCompactStatusPolicy {
    fun shouldShow(candidatesOwnToolbar: Boolean, phase: ParaformerModelPhase): Boolean =
        !candidatesOwnToolbar && phase != ParaformerModelPhase.Ready
}
