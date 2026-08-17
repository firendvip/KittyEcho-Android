package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class ParaformerAndroidDistributionSourceContractTest : FunSpec({

    test("WorkManager is unique foreground dataSync work with frozen network constraints") {
        val root = paraformerProjectRoot()
        val catalog = File(root, "gradle/libs.versions.toml").readText()
        val appBuild = File(root, "app/build.gradle.kts").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val worker = paraformerProductionSource("wordtaker/speech/ParaformerModelDownloadWorker.kt")
        val scheduler = paraformerProductionSource("wordtaker/speech/ParaformerWorkManagerScheduler.kt")

        catalog shouldContain "androidx-work = \"2.11.2\""
        catalog shouldContain "androidx-work-runtime"
        appBuild shouldContain "implementation(libs.androidx.work.runtime)"
        manifest shouldContain "android.permission.FOREGROUND_SERVICE"
        manifest shouldContain "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
        manifest shouldContain "androidx.work.impl.foreground.SystemForegroundService"
        manifest shouldContain "android:foregroundServiceType=\"dataSync\""
        worker shouldContain "CoroutineWorker"
        worker shouldContain "setForeground"
        worker shouldContain "FOREGROUND_SERVICE_TYPE_DATA_SYNC"
        scheduler shouldContain "enqueueUniqueWork"
        scheduler shouldContain "ExistingWorkPolicy.KEEP"
        scheduler shouldContain "UNIQUE_WORK_NAME"
        scheduler shouldContain "NetworkType.UNMETERED"
        scheduler shouldContain "NetworkType.NOT_REQUIRED"
        scheduler shouldNotContain "NetworkType.CONNECTED"
        scheduler shouldContain "BackoffPolicy.EXPONENTIAL"
        scheduler shouldContain "30L"
        worker shouldContain "NET_CAPABILITY_INTERNET"
        worker shouldContain "NET_CAPABILITY_NOT_METERED"
        worker shouldContain "metered && !mobileConfirmed"
        worker shouldContain "return Result.retry()"
    }

    test("state persistence and staging stay under the app noBackup boundary") {
        val stateStore = paraformerProductionSource("wordtaker/speech/ParaformerAndroidModelStateStore.kt")
        val fileOps = paraformerProductionSource("wordtaker/speech/ParaformerAndroidInstallOps.kt")

        stateStore shouldContain "noBackupFilesDir"
        fileOps shouldContain "noBackupFilesDir"
        fileOps shouldContain "O_NOFOLLOW"
        fileOps shouldContain "st_nlink"
        fileOps shouldContain "Os.chmod"
        fileOps shouldContain "Os.fsync"
        fileOps shouldContain "Os.rename"
        fileOps shouldContain "0600"
        fileOps shouldContain "0700"
    }

    test("production downloader uses the tested fallback policy") {
        val downloader = paraformerProductionSource("wordtaker/speech/ParaformerArtifactDownloader.kt")

        downloader shouldContain "ParaformerDownloadPolicy.runWithMirrorFallback"
    }

    test("redirect allowlist contains only the exact AWS CDN hostname") {
        val transfer = paraformerProductionSource("wordtaker/speech/ParaformerHttpTransfer.kt")

        transfer shouldContain "\"us.aws.cdn.hf.co\""
        transfer shouldNotContain "*.hf.co"
    }

    test("resume rejection clears the partial and retries fresh on the same host only once") {
        val downloader = paraformerProductionSource("wordtaker/speech/ParaformerArtifactDownloader.kt")
        val policy = paraformerProductionSource("wordtaker/speech/ParaformerDownloadPolicy.kt")

        downloader shouldContain "ParaformerDownloadPolicy.runResumeWithSingleFreshRetry"
        policy shouldContain "MAX_SAME_HOST_FRESH_RETRIES = 1"
    }

    test("transport interruption retains resume metadata while clean failures clear it") {
        val downloader = paraformerProductionSource("wordtaker/speech/ParaformerArtifactDownloader.kt")

        downloader shouldContain "onResponseMetadata = { frozenEtag ->"
        downloader shouldContain "saveResume("
        downloader shouldContain "error.failure == ParaformerAttemptFailure.Protocol ||"
        downloader shouldContain "error.failure == ParaformerAttemptFailure.Integrity"
        downloader shouldContain "resetPartial(artifact)"
    }

    test("HTTP protocol diagnostics stay distinct from byte integrity and are safely logged") {
        val worker = paraformerProductionSource("wordtaker/speech/ParaformerModelDownloadWorker.kt")

        worker shouldContain "ParaformerAttemptFailure.Protocol -> ParaformerModelFailure.Protocol"
        worker shouldContain "safeDiagnosticSummary()"
        worker shouldContain "HTTP_DIAGNOSTIC_TAG = \"ParaformerHttp\""
        worker shouldContain "Log.w"
    }

    test("all model states and failures have shared full and compact presentations") {
        val phaseStates = listOf(
            ParaformerModelPhase.Uninstalled,
            ParaformerModelPhase.AwaitingConfirmation,
            ParaformerModelPhase.Queued,
            ParaformerModelPhase.Downloading,
            ParaformerModelPhase.Paused,
            ParaformerModelPhase.Verifying,
            ParaformerModelPhase.Installing,
            ParaformerModelPhase.Initializing,
            ParaformerModelPhase.Ready,
        )
        phaseStates.forEach { phase ->
            ParaformerModelPresentation.forState(
                ParaformerLifecycleState(
                    phase = phase,
                    downloadedBytes = 1L,
                    mobileConfirmed = false,
                    pauseReason = null,
                ),
            ).status.isNotBlank() shouldBe true
        }
        listOf(
            ParaformerModelFailure.Storage,
            ParaformerModelFailure.Network,
            ParaformerModelFailure.Protocol,
            ParaformerModelFailure.Integrity,
            ParaformerModelFailure.Memory,
        ).forEach { failure ->
            ParaformerModelPresentation.forFailure(failure).status.isNotBlank() shouldBe true
        }

        ParaformerCompactStatusPolicy.shouldShow(
            candidatesOwnToolbar = true,
            phase = ParaformerModelPhase.Downloading,
        ) shouldBe false
        ParaformerCompactStatusPolicy.shouldShow(
            candidatesOwnToolbar = false,
            phase = ParaformerModelPhase.Downloading,
        ) shouldBe true
        ParaformerCompactStatusPolicy.shouldShow(
            candidatesOwnToolbar = false,
            phase = ParaformerModelPhase.Ready,
        ) shouldBe false
    }
})

private fun paraformerProductionSource(relativePath: String): String =
    File(paraformerProjectRoot(), "app/src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()

private fun paraformerProjectRoot(): File {
    var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    repeat(8) {
        if (File(current, "app/src/main").isDirectory) return current
        current = current.parentFile ?: error("Could not locate project root")
    }
    error("Could not locate project root")
}
