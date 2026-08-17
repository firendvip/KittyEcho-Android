package com.wordtaker.keyboard.wordtaker.speech

internal enum class ParaformerModelPhase {
    Uninstalled,
    AwaitingConfirmation,
    Queued,
    Downloading,
    Paused,
    Verifying,
    Installing,
    Initializing,
    Ready,
    Error,
}

internal enum class ParaformerPauseReason {
    User,
    Offline,
    MeteredNetwork,
}

internal enum class ParaformerModelFailure {
    Storage,
    Network,
    Protocol,
    Integrity,
    Memory,
    Initialization,
}

internal enum class ParaformerLifecycleAction {
    None,
    ShowConfirmation,
    StartVoice,
    EnqueueWifi,
    EnqueueConnected,
}

internal data class ParaformerLifecycleSnapshot(
    val phase: ParaformerModelPhase = ParaformerModelPhase.Uninstalled,
    val downloadedBytes: Long = 0L,
    val mobileConfirmed: Boolean = false,
    val pauseReason: ParaformerPauseReason? = null,
    val workEnqueued: Boolean = false,
    val failure: ParaformerModelFailure? = null,
)

internal data class ParaformerLifecycleState(
    val phase: ParaformerModelPhase,
    val downloadedBytes: Long,
    val totalBytes: Long = ParaformerModelContract.TOTAL_BYTES,
    val mobileConfirmed: Boolean,
    val pauseReason: ParaformerPauseReason?,
    val failure: ParaformerModelFailure? = null,
)

/** Pure, persistence-friendly lifecycle used by WorkManager and both settings surfaces. */
internal class ParaformerModelLifecycle(
    snapshot: ParaformerLifecycleSnapshot = ParaformerLifecycleSnapshot(),
) {
    private var current = snapshot

    val state: ParaformerLifecycleState
        get() = ParaformerLifecycleState(
            phase = current.phase,
            downloadedBytes = current.downloadedBytes,
            mobileConfirmed = current.mobileConfirmed,
            pauseReason = current.pauseReason,
            failure = current.failure,
        )

    fun snapshot(): ParaformerLifecycleSnapshot = current

    fun onVoiceRequested(modelReady: Boolean): ParaformerLifecycleAction {
        if (modelReady) return ParaformerLifecycleAction.StartVoice
        requestConfirmation()
        return ParaformerLifecycleAction.ShowConfirmation
    }

    fun requestConfirmation() {
        if (current.phase == ParaformerModelPhase.Uninstalled) {
            current = current.copy(phase = ParaformerModelPhase.AwaitingConfirmation)
        }
    }

    fun confirmWifi(): ParaformerLifecycleAction = enqueue(mobileConfirmed = false)

    fun confirmMobile(): ParaformerLifecycleAction = enqueue(mobileConfirmed = true)

    private fun enqueue(mobileConfirmed: Boolean): ParaformerLifecycleAction {
        if (current.workEnqueued || current.phase != ParaformerModelPhase.AwaitingConfirmation) {
            return ParaformerLifecycleAction.None
        }
        current = current.copy(
            phase = ParaformerModelPhase.Queued,
            mobileConfirmed = mobileConfirmed,
            pauseReason = null,
            workEnqueued = true,
            failure = null,
        )
        return if (mobileConfirmed) {
            ParaformerLifecycleAction.EnqueueConnected
        } else {
            ParaformerLifecycleAction.EnqueueWifi
        }
    }

    fun onDownloadProgress(downloadedBytes: Long, mobileConfirmed: Boolean) {
        current = current.copy(
            phase = ParaformerModelPhase.Downloading,
            downloadedBytes = downloadedBytes.coerceIn(0L, ParaformerModelContract.TOTAL_BYTES),
            mobileConfirmed = mobileConfirmed,
            pauseReason = null,
            workEnqueued = true,
            failure = null,
        )
    }

    fun pause(reason: ParaformerPauseReason) {
        current = current.copy(
            phase = ParaformerModelPhase.Paused,
            pauseReason = reason,
            workEnqueued = false,
        )
    }

    fun resumeAction(): ParaformerLifecycleAction {
        if (current.phase != ParaformerModelPhase.Paused || current.workEnqueued) {
            return ParaformerLifecycleAction.None
        }
        current = current.copy(
            phase = ParaformerModelPhase.Queued,
            pauseReason = null,
            workEnqueued = true,
            failure = null,
        )
        return if (current.mobileConfirmed) {
            ParaformerLifecycleAction.EnqueueConnected
        } else {
            ParaformerLifecycleAction.EnqueueWifi
        }
    }

    fun cancel() {
        current = ParaformerLifecycleSnapshot()
    }

    fun onDownloadInstalled() {
        current = current.copy(
            phase = ParaformerModelPhase.Initializing,
            downloadedBytes = ParaformerModelContract.TOTAL_BYTES,
            pauseReason = null,
            workEnqueued = false,
            failure = null,
        )
    }

    fun onInitializationSucceeded() {
        current = current.copy(
            phase = ParaformerModelPhase.Ready,
            failure = null,
            workEnqueued = false,
        )
    }

    fun onVerifying() {
        current = current.copy(
            phase = ParaformerModelPhase.Verifying,
            pauseReason = null,
            failure = null,
            workEnqueued = true,
        )
    }

    fun onInstalling() {
        current = current.copy(
            phase = ParaformerModelPhase.Installing,
            pauseReason = null,
            failure = null,
            workEnqueued = true,
        )
    }

    fun onFailure(failure: ParaformerModelFailure) {
        current = current.copy(
            phase = ParaformerModelPhase.Error,
            pauseReason = null,
            workEnqueued = false,
            failure = failure,
        )
    }

    fun retryAction(): ParaformerLifecycleAction {
        if (current.phase != ParaformerModelPhase.Error || current.workEnqueued) {
            return ParaformerLifecycleAction.None
        }
        current = current.copy(
            phase = ParaformerModelPhase.Queued,
            pauseReason = null,
            workEnqueued = true,
            failure = null,
        )
        return if (current.mobileConfirmed) {
            ParaformerLifecycleAction.EnqueueConnected
        } else {
            ParaformerLifecycleAction.EnqueueWifi
        }
    }

    /** Download completion is never remembered as a deferred microphone action. */
    fun consumePendingVoiceStart(): Boolean = false
}

internal object ParaformerLegacyCleanupPolicy {
    fun mayDelete(
        paraformerVerified: Boolean,
        initialized: Boolean,
        successfulRecognitionCount: Int,
    ): Boolean = paraformerVerified && initialized && successfulRecognitionCount > 0
}
