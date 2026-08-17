package com.wordtaker.keyboard.asrbenchmark.runner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.CanonicalJson
import com.wordtaker.keyboard.asrbenchmark.core.Hashing
import com.wordtaker.keyboard.asrbenchmark.core.MonotonicClock
import com.wordtaker.keyboard.asrbenchmark.core.ResourceProbe
import com.wordtaker.keyboard.asrbenchmark.core.ResourceSample
import java.io.File

object AndroidElapsedRealtimeClock : MonotonicClock {
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

class AndroidRuntimeResourceProbe(
    context: Context,
) : ResourceProbe {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    override fun sample(): ResourceSample {
        val procStatus = readProcStatusMemory()
        val pssBytes = Debug.getPss() * 1024L
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            powerManager?.currentThermalStatus ?: 0
        } else {
            0
        }
        return ResourceSample(
            rssBytes = procStatus.rssBytes,
            pssBytes = pssBytes,
            thermalStatus = thermal,
            swapBytes = procStatus.swapBytes,
        )
    }

    private fun readProcStatusMemory(): ParsedProcStatusMemory =
        File("/proc/self/status").useLines { lines ->
            ProcStatusMemoryParser.parse(lines)
        }
}

internal data class ParsedProcStatusMemory(
    val rssBytes: Long,
    val swapBytes: Long?,
)

internal object ProcStatusMemoryParser {
    fun parse(lines: Sequence<String>): ParsedProcStatusMemory {
        val fields = lines.filter {
            it.startsWith("VmRSS:") || it.startsWith("VmSwap:")
        }.associate { line ->
            line.substringBefore(':') to line.substringAfter(':')
        }
        val rss = fields["VmRSS"]
            ?: throw BenchmarkContractException("kernel RSS telemetry is unavailable")
        return ParsedProcStatusMemory(
            rssBytes = parseKibibytes(rss),
            swapBytes = fields["VmSwap"]?.let(::parseKibibytes),
        )
    }

    private fun parseKibibytes(value: String): Long {
        val kibibytes = value
            .trim()
            .substringBefore(' ')
            .toLongOrNull()
        if (
            kibibytes == null ||
            kibibytes < 0L ||
            kibibytes > Long.MAX_VALUE / 1024L
        ) {
            throw BenchmarkContractException("kernel memory telemetry is malformed")
        }
        return kibibytes * 1024L
    }
}

data class AndroidBuildEvidence(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val apkSha256: String,
    val appSigningCertSha256: String,
    val runnerBuildSha256: String,
    val deviceIdentityCommitmentSha256: String,
    val physicalDeviceClaim: Boolean,
    val emulatorClaim: Boolean,
)

object AndroidBuildEvidenceCollector {
    fun collect(context: Context): AndroidBuildEvidence {
        val packageName = context.packageName
        if (
            packageName != "com.wordtaker.keyboard.asrbenchmark.runner" &&
            packageName != "com.wordtaker.keyboard.asrbenchmark.runner.development"
        ) {
            throw BenchmarkContractException("benchmark runner package identity is invalid")
        }
        val packageManager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }
        val signers = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        if (signers.size != 1) {
            throw BenchmarkContractException(
                "benchmark runner must have exactly one current signing certificate",
            )
        }
        val signingCertSha256 = Hashing.sha256(signers.single().toByteArray())
        val sourcePath = context.applicationInfo.sourceDir
            ?: throw BenchmarkContractException("runner APK path is unavailable")
        val apkMeasurement = AndroidSecureFileAccess(
            mapOf("runner-apk" to sourcePath),
        ).read("runner-apk", requireCanonicalPcm = false)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val versionName = packageInfo.versionName.orEmpty()
        if (versionName.isBlank()) {
            throw BenchmarkContractException("runner version identity is unavailable")
        }
        val emulator = isEmulator()
        val deviceCommitment = CanonicalJson.sha256(
            linkedMapOf(
                "brand" to Build.BRAND,
                "device" to Build.DEVICE,
                "fingerprint" to Build.FINGERPRINT,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "product" to Build.PRODUCT,
                "sdk_int" to Build.VERSION.SDK_INT,
                "supported_abis" to Build.SUPPORTED_ABIS.toList(),
            ),
        )
        val runnerBuildSha256 = CanonicalJson.sha256(
            linkedMapOf(
                "package_name" to packageName,
                "version_name" to versionName,
                "version_code" to versionCode,
                "apk_sha256" to apkMeasurement.sha256,
                "app_signing_cert_sha256" to signingCertSha256,
            ),
        )
        if (
            listOf(
                apkMeasurement.sha256,
                signingCertSha256,
                runnerBuildSha256,
                deviceCommitment,
            ).any { it == "0".repeat(64) }
        ) {
            throw BenchmarkContractException("runner identity commitment is empty")
        }
        return AndroidBuildEvidence(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            apkSha256 = apkMeasurement.sha256,
            appSigningCertSha256 = signingCertSha256,
            runnerBuildSha256 = runnerBuildSha256,
            deviceIdentityCommitmentSha256 = deviceCommitment,
            physicalDeviceClaim = !emulator,
            emulatorClaim = emulator,
        )
    }

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("sdk_gphone") ||
            model.contains("emulator") ||
            product.contains("sdk")
    }
}
