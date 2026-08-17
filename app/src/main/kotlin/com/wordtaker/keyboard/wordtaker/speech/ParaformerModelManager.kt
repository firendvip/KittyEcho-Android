package com.wordtaker.keyboard.wordtaker.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ParaformerWorkNetwork {
    Unmetered,
    Connected,
}

internal interface ParaformerModelStateStore {
    fun load(): ParaformerLifecycleSnapshot
    fun save(snapshot: ParaformerLifecycleSnapshot)
    fun clear()
    fun observe(observer: (ParaformerLifecycleSnapshot) -> Unit) = Unit
}

internal interface ParaformerWorkScheduler {
    fun enqueueUnique(network: ParaformerWorkNetwork)
    fun pauseUnique()
    fun cancelUnique()
}

internal interface ParaformerPartialStore {
    fun deleteAll()
}

/** Process-wide facade shared by the launcher settings, IME settings, and toolbar. */
internal class ParaformerModelManager(
    private val modelReady: () -> Boolean,
    private val stateStore: ParaformerModelStateStore,
    private val workScheduler: ParaformerWorkScheduler,
    private val partialStore: ParaformerPartialStore,
) {
    private var lifecycle = ParaformerModelLifecycle(stateStore.load())
    private val mutableState = MutableStateFlow(lifecycle.state)
    val state: StateFlow<ParaformerLifecycleState> = mutableState.asStateFlow()

    init {
        stateStore.observe { restored ->
            synchronized(this) {
                lifecycle = ParaformerModelLifecycle(restored)
                mutableState.value = lifecycle.state
            }
        }
    }

    @Synchronized
    fun onVoiceRequested(): ParaformerLifecycleAction {
        val ready = modelReady()
        val action = lifecycle.onVoiceRequested(ready)
        if (ready) lifecycle.onInitializationSucceeded()
        publish()
        return action
    }

    @Synchronized
    fun confirmWifi(): ParaformerLifecycleAction = confirm(lifecycle.confirmWifi())

    @Synchronized
    fun confirmMobile(): ParaformerLifecycleAction = confirm(lifecycle.confirmMobile())

    private fun confirm(action: ParaformerLifecycleAction): ParaformerLifecycleAction {
        publish()
        when (action) {
            ParaformerLifecycleAction.EnqueueWifi ->
                workScheduler.enqueueUnique(ParaformerWorkNetwork.Unmetered)
            ParaformerLifecycleAction.EnqueueConnected ->
                workScheduler.enqueueUnique(ParaformerWorkNetwork.Connected)
            else -> Unit
        }
        return action
    }

    @Synchronized
    fun onWorkerProgress(downloadedBytes: Long) {
        lifecycle.onDownloadProgress(downloadedBytes, lifecycle.state.mobileConfirmed)
        publish()
    }

    @Synchronized
    fun onNetworkChanged(connected: Boolean, metered: Boolean) {
        if (lifecycle.state.phase !in ACTIVE_DOWNLOAD_PHASES) return
        when (
            ParaformerDownloadPolicy.networkDecision(
                connected = connected,
                metered = metered,
                mobileConfirmed = lifecycle.state.mobileConfirmed,
            )
        ) {
            ParaformerNetworkDecision.Allow -> Unit
            ParaformerNetworkDecision.PauseOffline -> pause(ParaformerPauseReason.Offline)
            ParaformerNetworkDecision.PauseMetered -> pause(ParaformerPauseReason.MeteredNetwork)
        }
    }

    @Synchronized
    fun pause(reason: ParaformerPauseReason = ParaformerPauseReason.User) {
        lifecycle.pause(reason)
        workScheduler.pauseUnique()
        publish()
    }

    @Synchronized
    fun resume(): ParaformerLifecycleAction = confirm(lifecycle.resumeAction())

    @Synchronized
    fun retry(): ParaformerLifecycleAction = confirm(lifecycle.retryAction())

    @Synchronized
    fun refreshReadiness() {
        if (modelReady() && lifecycle.state.phase != ParaformerModelPhase.Ready) {
            lifecycle.onInitializationSucceeded()
            publish()
        }
    }

    @Synchronized
    fun cancel() {
        workScheduler.cancelUnique()
        lifecycle.cancel()
        stateStore.clear()
        mutableState.value = lifecycle.state
        partialStore.deleteAll()
    }

    @Synchronized
    fun onVerifying() {
        lifecycle.onVerifying()
        publish()
    }

    @Synchronized
    fun onInstalling() {
        lifecycle.onInstalling()
        publish()
    }

    @Synchronized
    fun onInstalled() {
        lifecycle.onDownloadInstalled()
        publish()
    }

    @Synchronized
    fun onInitialized() {
        lifecycle.onInitializationSucceeded()
        publish()
    }

    @Synchronized
    fun onFailure(failure: ParaformerModelFailure) {
        lifecycle.onFailure(failure)
        publish()
    }

    fun consumePendingVoiceStart(): Boolean = lifecycle.consumePendingVoiceStart()

    private fun publish() {
        val snapshot = lifecycle.snapshot()
        stateStore.save(snapshot)
        mutableState.value = lifecycle.state
    }

    private companion object {
        val ACTIVE_DOWNLOAD_PHASES = setOf(
            ParaformerModelPhase.Queued,
            ParaformerModelPhase.Downloading,
            ParaformerModelPhase.Verifying,
            ParaformerModelPhase.Installing,
        )
    }
}
