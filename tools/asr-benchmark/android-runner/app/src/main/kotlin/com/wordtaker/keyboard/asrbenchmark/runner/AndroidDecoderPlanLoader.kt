package com.wordtaker.keyboard.asrbenchmark.runner

import android.system.Os
import android.system.OsConstants
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.CanonicalJson
import com.wordtaker.keyboard.asrbenchmark.core.DecoderClip
import com.wordtaker.keyboard.asrbenchmark.core.DecoderPlan
import com.wordtaker.keyboard.asrbenchmark.core.Hashing
import com.wordtaker.keyboard.asrbenchmark.core.StrictCanonicalJson
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class AndroidDecoderPlanEvidence(
    val plan: DecoderPlan,
    val canonicalDocument: Map<String, Any?>,
    val planIdSha256: String,
    val canonicalSha256: String,
    val rawFileSha256: String,
    val publicPlanSha256: String,
    val manifestSha256: String,
    val pathBindings: Map<String, String>,
)

/**
 * Loads the exact Phase A decoder-plan payload through one safe read-only FD.
 *
 * Device-local PCM paths are supplied separately and never enter the signed
 * accuracy or engineering documents. Each local path is bound to a plan clip
 * only through the runner-verified complete WAV and payload hashes.
 */
object AndroidDecoderPlanLoader {
    fun load(
        decoderPlanPath: String,
        pcmPathsByClipId: Map<String, String>,
    ): AndroidDecoderPlanEvidence {
        val raw = readSafeFile(decoderPlanPath)
        val decoded = StrictCanonicalJson.decodeObject(raw.bytes)
        if (decoded.rawSha256 != raw.sha256) {
            throw BenchmarkContractException(
                "decoder-plan raw digest differs across safe-FD validation",
            )
        }
        val document = decoded.document
        requireExactKeys(
            document,
            setOf(
                "schema_version",
                "plan_id",
                "run_id",
                "dataset_id",
                "model_alias",
                "pcm_contract_id",
                "decoder_contract_id",
                "input_transform_id",
                "public_plan_sha256",
                "manifest_sha256",
                "clips",
            ),
            "decoder plan",
        )
        val planId = requireString(document["plan_id"], "decoder plan_id")
        val identityDocument = LinkedHashMap(document)
        identityDocument.remove("plan_id")
        val expectedPlanId = "plan_${CanonicalJson.sha256(identityDocument).take(12)}"
        if (planId != expectedPlanId) {
            throw BenchmarkContractException(
                "Android decoder plan_id differs from its semantic document",
            )
        }
        if (
            document["schema_version"] != "1.0" ||
            document["pcm_contract_id"] != "pcm16k-mono-s16le-v1" ||
            document["decoder_contract_id"] != "first-layer-raw-v1" ||
            document["input_transform_id"] != "canonical-pcm-direct-v1"
        ) {
            throw BenchmarkContractException(
                "Android decoder plan contracts are invalid",
            )
        }
        val publicPlanSha256 = requireString(
            document["public_plan_sha256"],
            "decoder public-plan hash",
        )
        val manifestSha256 = requireString(
            document["manifest_sha256"],
            "decoder manifest hash",
        )
        Hashing.requireSha256(publicPlanSha256, "decoder public-plan hash")
        Hashing.requireSha256(manifestSha256, "decoder manifest hash")
        val clipDocuments = document["clips"] as? List<*>
            ?: throw BenchmarkContractException("decoder clips must be an array")
        if (clipDocuments.isEmpty()) {
            throw BenchmarkContractException("decoder plan must contain clips")
        }
        val bindings = linkedMapOf<String, String>()
        val clips = clipDocuments.mapIndexed { index, value ->
            val clip = value as? Map<*, *>
                ?: throw BenchmarkContractException(
                    "decoder clip[$index] must be an object",
                )
            val normalized = clip.entries.associate { entry ->
                val key = entry.key as? String
                    ?: throw BenchmarkContractException(
                        "decoder clip key must be a string",
                    )
                key to entry.value
            }
            requireExactKeys(
                normalized,
                setOf(
                    "clip_id",
                    "audio_path",
                    "wav_file_sha256",
                    "pcm_payload_sha256",
                    "pcm_payload_bytes",
                ),
                "decoder clip[$index]",
            )
            val clipId = requireString(normalized["clip_id"], "clip_id")
            val committedPath = requireString(
                normalized["audio_path"],
                "decoder audio path",
            )
            if (!committedPath.startsWith("/")) {
                throw BenchmarkContractException(
                    "decoder audio path must be absolute",
                )
            }
            val localPath = pcmPathsByClipId[clipId]
                ?: throw BenchmarkContractException(
                    "device PCM binding is missing for $clipId",
                )
            val pathToken = "pcm-${index.toString().padStart(4, '0')}"
            bindings[pathToken] = localPath
            DecoderClip(
                clipId = clipId,
                pathToken = pathToken,
                wavSha256 = requireString(
                    normalized["wav_file_sha256"],
                    "decoder WAV hash",
                ),
                pcmPayloadSha256 = requireString(
                    normalized["pcm_payload_sha256"],
                    "decoder PCM payload hash",
                ),
                pcmPayloadBytes = requireLong(
                    normalized["pcm_payload_bytes"],
                    "decoder PCM payload bytes",
                ),
            )
        }
        if (pcmPathsByClipId.keys != clips.map { it.clipId }.toSet()) {
            throw BenchmarkContractException(
                "device PCM bindings differ from the exact decoder clip set",
            )
        }
        val plan = DecoderPlan(
            planId = planId,
            runId = requireString(document["run_id"], "decoder run_id"),
            datasetId = requireString(document["dataset_id"], "decoder dataset_id"),
            modelAlias = requireString(document["model_alias"], "decoder alias"),
            clips = clips,
        )
        return AndroidDecoderPlanEvidence(
            plan = plan,
            canonicalDocument = document,
            planIdSha256 = Hashing.sha256(planId.encodeToByteArray()),
            canonicalSha256 = decoded.canonicalSha256,
            rawFileSha256 = raw.sha256,
            publicPlanSha256 = publicPlanSha256,
            manifestSha256 = manifestSha256,
            pathBindings = bindings,
        )
    }

    private data class SafeBytes(
        val bytes: ByteArray,
        val sha256: String,
    )

    private fun readSafeFile(path: String): SafeBytes {
        if (!path.startsWith("/")) {
            throw BenchmarkContractException(
                "decoder-plan path must be device absolute",
            )
        }
        val descriptor = try {
            Os.open(
                path,
                OsConstants.O_RDONLY or
                    OsConstants.O_CLOEXEC or
                    OsConstants.O_NOFOLLOW,
                0,
            )
        } catch (error: Exception) {
            throw BenchmarkContractException(
                "decoder plan cannot be opened safely: ${error.javaClass.simpleName}",
            )
        }
        try {
            val before = Os.fstat(descriptor)
            if (
                !OsConstants.S_ISREG(before.st_mode) ||
                before.st_nlink != 1L ||
                before.st_size <= 0L ||
                before.st_size > MAX_PLAN_BYTES
            ) {
                throw BenchmarkContractException(
                    "decoder plan must bind one bounded regular-file inode",
                )
            }
            val output = ByteArrayOutputStream(before.st_size.toInt())
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = Os.read(descriptor, buffer, 0, buffer.size)
                if (count == 0) {
                    break
                }
                if (count < 0) {
                    throw BenchmarkContractException(
                        "decoder-plan safe-FD read failed",
                    )
                }
                total += count
                if (total > before.st_size || total > MAX_PLAN_BYTES) {
                    throw BenchmarkContractException(
                        "decoder plan changed size while being read",
                    )
                }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
            }
            val after = Os.fstat(descriptor)
            if (
                before.st_dev != after.st_dev ||
                before.st_ino != after.st_ino ||
                before.st_size != after.st_size ||
                before.st_mode != after.st_mode ||
                before.st_nlink != after.st_nlink ||
                total != after.st_size
            ) {
                throw BenchmarkContractException(
                    "decoder-plan identity changed during safe-FD read",
                )
            }
            return SafeBytes(
                bytes = output.toByteArray(),
                sha256 = digest.digest().joinToString("") {
                    "%02x".format(it)
                },
            )
        } finally {
            try {
                Os.close(descriptor)
            } catch (_: Exception) {
                // A failed close never turns a failed measurement into success.
            }
        }
    }

    private fun requireExactKeys(
        value: Map<String, Any?>,
        expected: Set<String>,
        context: String,
    ) {
        if (value.keys != expected) {
            throw BenchmarkContractException("$context fields are invalid")
        }
    }

    private fun requireString(value: Any?, context: String): String =
        (value as? String)?.takeIf { it.isNotEmpty() }
            ?: throw BenchmarkContractException("$context must be a string")

    private fun requireLong(value: Any?, context: String): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw BenchmarkContractException(
            "$context must be a non-negative integer",
        )
    }.also {
        if (it < 0) {
            throw BenchmarkContractException(
                "$context must be a non-negative integer",
            )
        }
    }

    private const val MAX_PLAN_BYTES = 16L * 1024L * 1024L
}
