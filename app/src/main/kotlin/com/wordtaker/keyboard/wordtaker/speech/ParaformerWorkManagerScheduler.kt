package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal class ParaformerWorkManagerScheduler(context: Context) : ParaformerWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueueUnique(network: ParaformerWorkNetwork) {
        ParaformerDownloadStopSignal.clear()
        val requiredNetwork = when (network) {
            ParaformerWorkNetwork.Unmetered -> NetworkType.UNMETERED
            // AndroidX treats PARTIAL connectivity as unsatisfied before the worker can run.
            // Explicit mobile consent is therefore enforced at the worker boundary instead.
            ParaformerWorkNetwork.Connected -> NetworkType.NOT_REQUIRED
        }
        val request = OneTimeWorkRequestBuilder<ParaformerModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(requiredNetwork)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    ParaformerModelDownloadWorker.KEY_ALLOW_METERED to
                        (network == ParaformerWorkNetwork.Connected),
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun pauseUnique() {
        // WorkManager has no mutable pause primitive. Cancellation stops the worker while
        // the staging bytes remain intact; resume enqueues the same unique name again.
        ParaformerDownloadStopSignal.request()
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    override fun cancelUnique() {
        ParaformerDownloadStopSignal.request()
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "paraformer-model-download"
    }
}

internal object ParaformerDownloadStopSignal {
    @Volatile
    private var requested = false

    fun request() {
        requested = true
    }

    fun clear() {
        requested = false
    }

    fun isRequested(): Boolean = requested
}
