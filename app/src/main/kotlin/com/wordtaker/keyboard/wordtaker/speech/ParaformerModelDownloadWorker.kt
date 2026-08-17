package com.wordtaker.keyboard.wordtaker.speech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

internal class ParaformerModelDownloadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val stateStore = ParaformerAndroidModelStateStore(appContext)
    private val allowMetered = inputData.getBoolean(KEY_ALLOW_METERED, false)

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo(stateStore.load().downloadedBytes))
        val initial = stateStore.load()
        val mobileConfirmed = initial.mobileConfirmed || allowMetered
        return try {
            pauseReason(mobileConfirmed)?.let { reason ->
                savePaused(initial, reason, workRemainsEnqueued = true)
                return Result.retry()
            }
            val job = currentCoroutineContext()[Job]
            var lastPersisted = initial.downloadedBytes
            val downloader = ParaformerArtifactDownloader(applicationContext, HTTP_CLIENT)
            withContext(Dispatchers.IO) {
                downloader.downloadAll(
                    onProgress = { downloaded ->
                        if (
                            downloaded == ParaformerModelContract.TOTAL_BYTES ||
                            downloaded - lastPersisted >= PROGRESS_PERSIST_INTERVAL_BYTES
                        ) {
                            lastPersisted = downloaded
                            saveDownloading(downloaded, mobileConfirmed)
                            setProgressAsync(workDataOf(KEY_PROGRESS_BYTES to downloaded))
                            setForegroundAsync(foregroundInfo(downloaded))
                        }
                    },
                    shouldCancel = {
                        isStopped || job?.isActive != true || ParaformerDownloadStopSignal.isRequested()
                    },
                    pauseReason = { pauseReason(mobileConfirmed) },
                )
            }

            savePhase(ParaformerModelPhase.Verifying, mobileConfirmed)
            val installOps = ParaformerAndroidInstallOps(applicationContext)
            // The installer re-hashes both exact artifacts immediately before installation.
            savePhase(ParaformerModelPhase.Installing, mobileConfirmed)
            ParaformerSecureInstaller(installOps).install()

            savePhase(ParaformerModelPhase.Initializing, mobileConfirmed)
            AppGraph.init(applicationContext)
            val engine = AppGraph.speechEngine
            val initializationFailure = engine.prepareInstalledModel()
            if (initializationFailure != null || !engine.isReady()) {
                throw initializationFailure ?: AsrInitializationException()
            }
            stateStore.save(
                ParaformerLifecycleSnapshot(
                    phase = ParaformerModelPhase.Ready,
                    downloadedBytes = ParaformerModelContract.TOTAL_BYTES,
                    mobileConfirmed = mobileConfirmed,
                    workEnqueued = false,
                ),
            )
            Result.success()
        } catch (paused: ParaformerDownloadPausedException) {
            savePaused(stateStore.load(), paused.reason, workRemainsEnqueued = true)
            Result.retry()
        } catch (cancelled: CancellationException) {
            reconcileCancelledWork()
            throw cancelled
        } catch (error: ParaformerAttemptException) {
            logHttpDiagnostics(error)
            if (error.failure == ParaformerAttemptFailure.Cancelled) {
                reconcileCancelledWork()
                Result.failure()
            } else {
                saveFailure(mapFailure(error.failure), mobileConfirmed)
                Result.failure()
            }
        } catch (error: AsrOutOfMemoryException) {
            saveFailure(ParaformerModelFailure.Memory, mobileConfirmed)
            Result.failure()
        } catch (error: AsrFailureException) {
            saveFailure(ParaformerModelFailure.Initialization, mobileConfirmed)
            Result.failure()
        } catch (error: OutOfMemoryError) {
            saveFailure(ParaformerModelFailure.Memory, mobileConfirmed)
            Result.failure()
        } catch (error: Throwable) {
            saveFailure(ParaformerModelFailure.Initialization, mobileConfirmed)
            Result.failure()
        }
    }

    private fun reconcileCancelledWork() {
        val snapshot = stateStore.load()
        if (snapshot.phase in ACTIVE_PHASES) {
            val reason = pauseReason(snapshot.mobileConfirmed) ?: ParaformerPauseReason.User
            savePaused(snapshot, reason, workRemainsEnqueued = reason != ParaformerPauseReason.User)
        }
    }

    private fun saveDownloading(downloaded: Long, mobileConfirmed: Boolean) {
        stateStore.save(
            ParaformerLifecycleSnapshot(
                phase = ParaformerModelPhase.Downloading,
                downloadedBytes = downloaded.coerceIn(0L, ParaformerModelContract.TOTAL_BYTES),
                mobileConfirmed = mobileConfirmed,
                workEnqueued = true,
            ),
        )
    }

    private fun savePhase(phase: ParaformerModelPhase, mobileConfirmed: Boolean) {
        stateStore.save(
            stateStore.load().copy(
                phase = phase,
                mobileConfirmed = mobileConfirmed,
                pauseReason = null,
                workEnqueued = true,
                failure = null,
            ),
        )
    }

    private fun savePaused(
        snapshot: ParaformerLifecycleSnapshot,
        reason: ParaformerPauseReason,
        workRemainsEnqueued: Boolean,
    ) {
        stateStore.save(
            snapshot.copy(
                phase = ParaformerModelPhase.Paused,
                pauseReason = reason,
                workEnqueued = workRemainsEnqueued,
                failure = null,
            ),
        )
    }

    private fun saveFailure(failure: ParaformerModelFailure, mobileConfirmed: Boolean) {
        stateStore.save(
            stateStore.load().copy(
                phase = ParaformerModelPhase.Error,
                mobileConfirmed = mobileConfirmed,
                pauseReason = null,
                workEnqueued = false,
                failure = failure,
            ),
        )
    }

    private fun logHttpDiagnostics(error: ParaformerAttemptException) {
        error.safeDiagnosticSummary()?.let { Log.w(HTTP_DIAGNOSTIC_TAG, it) }
        error.suppressed.forEach { suppressed ->
            (suppressed as? ParaformerAttemptException)
                ?.safeDiagnosticSummary()
                ?.let { Log.w(HTTP_DIAGNOSTIC_TAG, it) }
        }
    }

    private fun pauseReason(mobileConfirmed: Boolean): ParaformerPauseReason? {
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return ParaformerPauseReason.Offline
        val network = connectivity.activeNetwork ?: return ParaformerPauseReason.Offline
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return ParaformerPauseReason.Offline
        val hasInternet =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return ParaformerPauseReason.Offline
        val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return if (metered && !mobileConfirmed) ParaformerPauseReason.MeteredNetwork else null
    }

    private fun foregroundInfo(downloadedBytes: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "语音模型下载",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val progress = if (ParaformerModelContract.TOTAL_BYTES == 0L) 0 else
            ((downloadedBytes * 100L) / ParaformerModelContract.TOTAL_BYTES)
                .coerceIn(0L, 100L).toInt()
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand_cat)
            .setContentTitle("正在下载弦外小猫语音模型")
            .setContentText("$progress% · 仅下载公开模型文件")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun mapFailure(failure: ParaformerAttemptFailure): ParaformerModelFailure = when (failure) {
        ParaformerAttemptFailure.Storage -> ParaformerModelFailure.Storage
        ParaformerAttemptFailure.Integrity -> ParaformerModelFailure.Integrity
        ParaformerAttemptFailure.Protocol -> ParaformerModelFailure.Protocol
        ParaformerAttemptFailure.Network,
        ParaformerAttemptFailure.Timeout,
        is ParaformerAttemptFailure.Http,
        -> ParaformerModelFailure.Network
        ParaformerAttemptFailure.Cancelled -> ParaformerModelFailure.Network
    }

    companion object {
        const val KEY_ALLOW_METERED = "allow_metered"
        const val KEY_PROGRESS_BYTES = "progress_bytes"
        private const val NOTIFICATION_CHANNEL_ID = "paraformer_model_download"
        private const val HTTP_DIAGNOSTIC_TAG = "ParaformerHttp"
        private const val NOTIFICATION_ID = 7042
        private const val PROGRESS_PERSIST_INTERVAL_BYTES = 1024L * 1024L
        private val ACTIVE_PHASES = setOf(
            ParaformerModelPhase.Queued,
            ParaformerModelPhase.Downloading,
            ParaformerModelPhase.Verifying,
            ParaformerModelPhase.Installing,
            ParaformerModelPhase.Initializing,
        )
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(30L, TimeUnit.SECONDS)
            .readTimeout(5L, TimeUnit.MINUTES)
            .writeTimeout(30L, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
