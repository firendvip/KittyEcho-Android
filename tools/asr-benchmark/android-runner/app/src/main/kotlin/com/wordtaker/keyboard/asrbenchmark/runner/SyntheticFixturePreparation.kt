package com.wordtaker.keyboard.asrbenchmark.runner

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.CanonicalJson
import com.wordtaker.keyboard.asrbenchmark.core.CanonicalPcmWav
import com.wordtaker.keyboard.asrbenchmark.core.Hashing
import com.wordtaker.keyboard.asrbenchmark.core.SyntheticWav
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class SyntheticFixtureFiles(
    val decoderPlanFileName: String,
    val decoderPlanId: String,
    val decoderPlanCanonicalSha256: String,
    val decoderPlanRawSha256: String,
    val pcmFileName: String,
    val pcmSha256: String,
    val pcmPayloadSha256: String,
    val artifactFileName: String,
    val artifactSha256: String,
)

/**
 * Creates deterministic non-speech files for the development-only device loop.
 *
 * It never reads user audio. Files persist until explicit operator cleanup and
 * are stored only under the benchmark APK's no-backup private directory.
 */
class SyntheticFixturePreparation(
    private val context: Context,
) {
    fun prepare(
        runId: String,
        datasetId: String,
        modelAlias: String,
        publicPlanSha256: String,
        manifestSha256: String,
    ): SyntheticFixtureFiles {
        Hashing.requireSha256(publicPlanSha256, "synthetic public-plan hash")
        Hashing.requireSha256(manifestSha256, "synthetic manifest hash")
        val directory = fixtureDirectory(context)
        prepareDirectory(directory)
        val wavBytes = SyntheticWav.pcm16Mono(
            sampleRate = 16_000,
            frameCount = 16_000,
        )
        val pcm = CanonicalPcmWav.inspect(wavBytes)
        val pcmFile = publish(directory, "pcm", wavBytes)
        val artifactBytes =
            "kittyecho-phase-b-synthetic-decoder-v1".encodeToByteArray()
        val artifactFile = publish(directory, "artifact", artifactBytes)
        val planWithoutId = linkedMapOf<String, Any?>(
            "schema_version" to "1.0",
            "run_id" to runId,
            "dataset_id" to datasetId,
            "model_alias" to modelAlias,
            "pcm_contract_id" to "pcm16k-mono-s16le-v1",
            "decoder_contract_id" to "first-layer-raw-v1",
            "input_transform_id" to "canonical-pcm-direct-v1",
            "public_plan_sha256" to publicPlanSha256,
            "manifest_sha256" to manifestSha256,
            "clips" to listOf(
                linkedMapOf(
                    "clip_id" to SYNTHETIC_CLIP_ID,
                    "audio_path" to pcmFile.absolutePath,
                    "wav_file_sha256" to Hashing.sha256(wavBytes),
                    "pcm_payload_sha256" to pcm.payloadSha256,
                    "pcm_payload_bytes" to pcm.payloadBytes,
                ),
            ),
        )
        val planId = "plan_${CanonicalJson.sha256(planWithoutId).take(12)}"
        val plan = LinkedHashMap(planWithoutId).apply {
            put("plan_id", planId)
        }
        val planBytes = (CanonicalJson.encode(plan) + "\n").encodeToByteArray()
        val planFile = publish(directory, "decoder-plan", planBytes)
        return SyntheticFixtureFiles(
            decoderPlanFileName = planFile.name,
            decoderPlanId = planId,
            decoderPlanCanonicalSha256 = CanonicalJson.sha256(plan),
            decoderPlanRawSha256 = Hashing.sha256(planBytes),
            pcmFileName = pcmFile.name,
            pcmSha256 = Hashing.sha256(wavBytes),
            pcmPayloadSha256 = pcm.payloadSha256,
            artifactFileName = artifactFile.name,
            artifactSha256 = Hashing.sha256(artifactBytes),
        )
    }

    private fun publish(
        directory: File,
        kind: String,
        bytes: ByteArray,
    ): File {
        val digest = Hashing.sha256(bytes)
        val target = File(directory, "$kind-$digest.bin")
        if (target.exists()) {
            val measured = AndroidSecureFileAccess(
                mapOf("fixture" to target.absolutePath),
            ).read("fixture", requireCanonicalPcm = false)
            if (measured.sha256 != digest) {
                throw BenchmarkContractException(
                    "existing synthetic fixture differs from its digest",
                )
            }
            return target
        }
        val temporary = File.createTempFile(".$kind-", ".tmp", directory)
        var published = false
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
                Os.fchmod(output.fd, READ_ONLY_MODE)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
                published = true
            } catch (_: FileAlreadyExistsException) {
                // A concurrent preparation may have published the same digest.
            }
            fsyncDirectory(directory)
            val measured = AndroidSecureFileAccess(
                mapOf("fixture" to target.absolutePath),
            ).read("fixture", requireCanonicalPcm = false)
            if (measured.sha256 != digest) {
                throw BenchmarkContractException(
                    "published synthetic fixture digest differs",
                )
            }
            return target
        } finally {
            if (!published && temporary.exists() && !temporary.delete()) {
                throw BenchmarkContractException(
                    "private synthetic fixture temp cannot be cleaned",
                )
            }
        }
    }

    companion object {
        const val SYNTHETIC_CLIP_ID = "clip_000000000001"
        private const val READ_ONLY_MODE = 0x100
        private const val PRIVATE_DIRECTORY_MODE = 0x1C0

        fun fixtureDirectory(context: Context): File =
            File(context.noBackupFilesDir, "asr-benchmark-synthetic")

        fun resolveFixtureFile(context: Context, fileName: String): File {
            if (
                fileName.isBlank() ||
                fileName.contains('/') ||
                fileName.contains('\\') ||
                fileName == "." ||
                fileName == ".."
            ) {
                throw BenchmarkContractException(
                    "synthetic fixture filename must be opaque",
                )
            }
            val directory = fixtureDirectory(context).canonicalFile
            val candidate = File(directory, fileName)
            if (candidate.parentFile?.canonicalFile != directory) {
                throw BenchmarkContractException(
                    "synthetic fixture filename escaped its directory",
                )
            }
            return candidate
        }

        private fun prepareDirectory(directory: File) {
            if (!directory.exists() && !directory.mkdirs()) {
                throw BenchmarkContractException(
                    "synthetic fixture directory cannot be created",
                )
            }
            if (!OsConstants.S_ISDIR(Os.stat(directory.absolutePath).st_mode)) {
                throw BenchmarkContractException(
                    "synthetic fixture store must be a directory",
                )
            }
            Os.chmod(directory.absolutePath, PRIVATE_DIRECTORY_MODE)
        }

        private fun fsyncDirectory(directory: File) {
            val descriptor = Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
                0,
            )
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                    throw BenchmarkContractException(
                        "synthetic fixture store changed before directory fsync",
                    )
                }
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }
}
