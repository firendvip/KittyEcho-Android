package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.ArtifactFile
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.Hashing
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import com.wordtaker.keyboard.asrbenchmark.core.MonotonicClock
import com.wordtaker.keyboard.asrbenchmark.core.RunnerPreparedDecoderAdapter
import com.wordtaker.keyboard.asrbenchmark.core.SecureFileAccess
import com.wordtaker.keyboard.asrbenchmark.core.StoredContentAddressedJson

data class DevelopmentBatchResult(
    val document: Map<String, Any?>,
    val successCount: Long,
    val failureCount: Long,
)

/**
 * Runs one development-emulator decode for every frozen dev96 clip.
 * The existing trusted runner owns safe-FD PCM/artifact measurement and
 * before/after identity verification. This wrapper adds no text transform.
 */
class DevelopmentBatchRunner(
    private val secureFiles: SecureFileAccess,
    private val clock: MonotonicClock,
) {
    fun run(
        evidence: DevelopmentDecoderPlanEvidence,
        artifacts: List<ArtifactFile>,
        adapter: RunnerPreparedDecoderAdapter,
        adapterArtifactRoles: Map<String, String> = artifacts.associate {
            it.componentRole to it.componentRole
        },
    ): DevelopmentBatchResult {
        requireEvidence(evidence)
        if (
            adapterArtifactRoles.keys !=
            artifacts.map { it.componentRole }.toSet() ||
            adapterArtifactRoles.values.any { it.isBlank() } ||
            adapterArtifactRoles.values.toSet().size != artifacts.size
        ) {
            throw BenchmarkContractException(
                "development batch adapter artifact roles are invalid",
            )
        }
        if (
            artifacts.isEmpty() ||
            artifacts.map { it.componentRole }.toSet().size != artifacts.size
        ) {
            throw BenchmarkContractException(
                "development batch artifacts must be non-empty and unique",
            )
        }
        val frozenArtifacts = artifacts.associateWith { artifact ->
            secureFiles.read(artifact.pathToken, requireCanonicalPcm = false)
        }
        val preparedArtifacts = frozenArtifacts.entries.associate { (artifact, measured) ->
            adapterArtifactRoles.getValue(artifact.componentRole) to measured
        }
        try {
            adapter.prepare(preparedArtifacts)
        } catch (_: OutOfMemoryError) {
            throw BenchmarkContractException(
                "development batch model preparation failed",
            )
        } catch (_: Exception) {
            throw BenchmarkContractException(
                "development batch model preparation failed",
            )
        }
        val predictions = evidence.plan.clips.map { clip ->
            val before = secureFiles.read(
                clip.pathToken,
                requireCanonicalPcm = true,
            )
            verifyPcmCommitment(clip, before)
            val stopNanos = clock.nowNanos()
            val decoded = try {
                adapter.decode(before.bytes)
            } catch (_: OutOfMemoryError) {
                DecoderResult("", DecoderStatus.ERROR, "oom")
            } catch (_: Exception) {
                DecoderResult("", DecoderStatus.ERROR, "crash")
            }
            val finalNanos = clock.nowNanos()
            if (finalNanos < stopNanos) {
                throw BenchmarkContractException(
                    "development batch monotonic clock moved backwards",
                )
            }
            val after = secureFiles.read(
                clip.pathToken,
                requireCanonicalPcm = true,
            )
            verifyPcmCommitment(clip, after)
            verifyUnchanged(before, after, "development batch PCM")
            linkedMapOf<String, Any?>(
                "clip_id" to clip.clipId,
                "pcm_sha256" to before.sha256,
                "pcm_payload_sha256" to requireNotNull(before.pcm).payloadSha256,
                "hypothesis" to decoded.transcript,
                "status" to decoded.status.name.lowercase(),
                "error_code" to decoded.errorCode,
                "stop_to_final_ns" to finalNanos - stopNanos,
            )
        }
        frozenArtifacts.forEach { (artifact, frozen) ->
            val after = secureFiles.read(
                artifact.pathToken,
                requireCanonicalPcm = false,
            )
            verifyUnchanged(
                frozen,
                after,
                "development batch model artifact",
            )
        }
        val successCount = predictions.count {
            it["status"] == "ok"
        }.toLong()
        val failureCount = Math.subtractExact(
            EXPECTED_CLIP_COUNT.toLong(),
            successCount,
        )
        val document = linkedMapOf<String, Any?>(
            "schema_version" to "development-emulator-model-output-v1",
            "run_id" to evidence.plan.runId,
            "dataset_id" to evidence.plan.datasetId,
            "model_alias" to evidence.plan.modelAlias,
            "decoder_contract_id" to "first-layer-raw-v1",
            "pcm_contract_id" to "pcm16k-mono-s16le-v1",
            "input_transform_id" to "canonical-pcm-direct-v1",
            "snapshot_fingerprint_sha256" to
                evidence.snapshotFingerprintSha256,
            "development_decoder_plan_sha256" to evidence.canonicalSha256,
            "reference_accessed" to false,
            "predictions" to predictions,
            "eligibility" to eligibility(),
        )
        return DevelopmentBatchResult(
            document = document,
            successCount = successCount,
            failureCount = failureCount,
        )
    }

    private fun requireEvidence(evidence: DevelopmentDecoderPlanEvidence) {
        Hashing.requireSha256(
            evidence.snapshotFingerprintSha256,
            "development snapshot fingerprint",
        )
        Hashing.requireSha256(
            evidence.canonicalSha256,
            "development decoder plan sha256",
        )
        if (
            evidence.plan.clips.size != EXPECTED_CLIP_COUNT ||
            evidence.pcmBasenamesByClipId.keys !=
            evidence.plan.clips.map { it.clipId }.toSet() ||
            evidence.pcmBasenamesByClipId.values.toSet().size !=
            EXPECTED_CLIP_COUNT ||
            evidence.pcmBasenamesByClipId.any { (clipId, basename) ->
                basename != "$clipId.wav"
            }
        ) {
            throw BenchmarkContractException(
                "development batch evidence is not the exact dev96 contract",
            )
        }
    }

    private fun verifyPcmCommitment(
        clip: com.wordtaker.keyboard.asrbenchmark.core.DecoderClip,
        measured: MeasuredFile,
    ) {
        val pcm = measured.pcm ?: throw BenchmarkContractException(
            "development batch PCM measurement is missing",
        )
        if (
            measured.sha256 != clip.wavSha256 ||
            pcm.payloadSha256 != clip.pcmPayloadSha256 ||
            pcm.payloadBytes != clip.pcmPayloadBytes
        ) {
            throw BenchmarkContractException(
                "development batch PCM differs from the frozen plan",
            )
        }
    }

    private fun verifyUnchanged(
        before: MeasuredFile,
        after: MeasuredFile,
        context: String,
    ) {
        val beforeBytes = before.retainedBytesOrNull()
        val afterBytes = after.retainedBytesOrNull()
        val retainedBytesMatch = when {
            beforeBytes == null && afterBytes == null -> true
            beforeBytes == null || afterBytes == null -> false
            else -> beforeBytes.contentEquals(afterBytes)
        }
        if (
            before.pathToken != after.pathToken ||
            before.identity != after.identity ||
            before.sha256 != after.sha256 ||
            !retainedBytesMatch
        ) {
            throw BenchmarkContractException("$context identity or content changed")
        }
    }

    companion object {
        const val EXPECTED_CLIP_COUNT = 96

        fun eligibility(): Map<String, Boolean> = linkedMapOf(
            "development_only" to true,
            "emulator_only" to true,
            "formal_eligible" to false,
            "product_decision_eligible" to false,
            "production_eligible" to false,
        )
    }
}

object DevelopmentBatchBundleContract {
    fun fields(
        stored: StoredContentAddressedJson,
        result: DevelopmentBatchResult,
    ): Map<String, Any?> {
        Hashing.requireSha256(stored.sha256, "development batch output sha256")
        if (
            stored.fileName.isBlank() ||
            stored.fileName.contains('/') ||
            stored.fileName.contains('\\') ||
            !stored.fileName.endsWith("-${stored.sha256}.json") ||
            result.successCount < 0L ||
            result.failureCount < 0L ||
            result.successCount + result.failureCount !=
            DevelopmentBatchRunner.EXPECTED_CLIP_COUNT.toLong()
        ) {
            throw BenchmarkContractException(
                "development batch Bundle summary is invalid",
            )
        }
        return linkedMapOf(
            "batch_file" to stored.fileName,
            "batch_sha256" to stored.sha256,
            "success_count" to result.successCount,
            "failure_count" to result.failureCount,
            "formal_eligible" to false,
            "product_decision_eligible" to false,
            "production_eligible" to false,
        )
    }
}
