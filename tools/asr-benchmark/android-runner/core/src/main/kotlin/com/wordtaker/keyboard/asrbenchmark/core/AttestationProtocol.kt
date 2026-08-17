package com.wordtaker.keyboard.asrbenchmark.core

import java.util.Base64

data class RunnerContractBindings(
    val benchmarkEvidenceSha256: String,
    val publicPlanSha256: String,
    val registrySha256: String,
    val normalizationSha256: String,
    val selectionConfigSha256: String,
    val runnerBuildSha256: String,
    val apkSha256: String,
    val appSigningCertSha256: String,
    val deviceIdentityCommitmentSha256: String,
) {
    init {
        listOf(
            benchmarkEvidenceSha256,
            publicPlanSha256,
            registrySha256,
            normalizationSha256,
            selectionConfigSha256,
            runnerBuildSha256,
            apkSha256,
            appSigningCertSha256,
            deviceIdentityCommitmentSha256,
        ).forEach { Hashing.requireSha256(it, "runner contract binding") }
        if (deviceIdentityCommitmentSha256 == "0".repeat(64)) {
            throw BenchmarkContractException("device identity commitment is empty")
        }
    }

    fun toDocument(): Map<String, String> = linkedMapOf(
        "benchmark_evidence_sha256" to benchmarkEvidenceSha256,
        "public_plan_sha256" to publicPlanSha256,
        "registry_sha256" to registrySha256,
        "normalization_sha256" to normalizationSha256,
        "selection_config_sha256" to selectionConfigSha256,
        "runner_build_sha256" to runnerBuildSha256,
        "apk_sha256" to apkSha256,
        "app_signing_cert_sha256" to appSigningCertSha256,
        "device_identity_commitment_sha256" to deviceIdentityCommitmentSha256,
    )
}

data class AttestationSignature(
    val signatureDer: ByteArray,
    val certificateChainDer: List<ByteArray>,
    val localSecurityLevelClaim: String,
) {
    init {
        if (
            signatureDer.isEmpty() ||
            certificateChainDer.isEmpty() ||
            certificateChainDer.any { it.isEmpty() } ||
            localSecurityLevelClaim !in setOf(
                "software",
                "trusted_environment",
                "strongbox",
                "unknown",
            )
        ) {
            throw BenchmarkContractException("attestation signature evidence is invalid")
        }
    }
}

fun interface AttestationSignatureProvider {
    fun sign(
        attestationChallengeSha256: String,
        message: ByteArray,
    ): AttestationSignature
}

object DeviceOutputDocuments {
    fun accuracy(
        result: BenchmarkRunResult,
        decoderPlanSha256: String,
    ): Map<String, Any?> {
        Hashing.requireSha256(decoderPlanSha256, "decoder plan hash")
        return linkedMapOf(
            "schema_version" to "1.0",
            "run_id" to result.runId,
            "model_alias" to result.modelAlias,
            "decoder_contract_id" to "first-layer-raw-v1",
            "pcm_contract_id" to "pcm16k-mono-s16le-v1",
            "input_transform_id" to "canonical-pcm-direct-v1",
            "decoder_plan_sha256" to decoderPlanSha256,
            "reference_accessed" to false,
            "predictions" to result.predictions.map { prediction ->
                linkedMapOf(
                    "clip_id" to prediction.clipId,
                    "pcm_sha256" to prediction.pcmSha256,
                    "pcm_payload_sha256" to prediction.pcmPayloadSha256,
                    "hypothesis" to prediction.transcript,
                    "status" to prediction.status.name.lowercase(),
                    "error_code" to prediction.errorCode,
                )
            },
        )
    }

    fun engineering(
        result: BenchmarkRunResult,
        decoderPlanSha256: String,
        accuracyDocument: Map<String, Any?>,
        bindings: RunnerContractBindings,
        stabilityProof: StabilityProof,
    ): Map<String, Any?> {
        Hashing.requireSha256(decoderPlanSha256, "decoder plan hash")
        if (
            accuracyDocument["run_id"] != result.runId ||
            accuracyDocument["model_alias"] != result.modelAlias
        ) {
            throw BenchmarkContractException("accuracy document differs from runner result")
        }
        val telemetry = telemetryDocument(stabilityProof)
        val stabilityPeakRss = stabilityProof.samples.maxOf { it.rssBytes }
        val stabilityPeakPss = stabilityProof.samples.maxOf { it.pssBytes }
        @Suppress("UNCHECKED_CAST")
        val artifacts = result.engineeringDocument["artifact_measurements"]
            as? List<Map<String, Any?>>
            ?: throw BenchmarkContractException("runner artifact measurements are missing")
        @Suppress("UNCHECKED_CAST")
        val clipMeasurements = result.engineeringDocument["clip_measurements"]
            as? List<Map<String, Any?>>
            ?: throw BenchmarkContractException("runner clip measurements are missing")
        return linkedMapOf(
            "schema_version" to "2.0",
            "run_id" to result.runId,
            "dataset_id" to result.datasetId,
            "model_alias" to result.modelAlias,
            "decoder_plan_sha256" to decoderPlanSha256,
            "accuracy_output_sha256" to CanonicalJson.sha256(accuracyDocument),
            "clock_source" to "android_elapsed_realtime_nanos",
            "trusted_runner_status" to "phase_b_external_observer_required",
            "bindings" to bindings.toDocument(),
            "pcm_set_sha256" to result.pcmSetSha256,
            "artifact_measurements" to artifacts,
            "artifact_set_sha256" to result.artifactSetSha256,
            "clip_measurements" to clipMeasurements,
            "runtime" to linkedMapOf(
                "total_resource_bytes" to result.runtime.totalResourceBytes,
                "cold_latency_ns" to result.runtime.coldLatencyNanos,
                "warm_latency_ns" to result.runtime.warmLatencyNanos,
                "decode_probe_peak_rss_bytes" to
                    result.runtime.peakRssBytes,
                "decode_probe_peak_pss_bytes" to
                    result.runtime.peakPssBytes,
                "peak_rss_bytes" to maxOf(
                    result.runtime.peakRssBytes,
                    stabilityPeakRss,
                ),
                "peak_pss_bytes" to maxOf(
                    result.runtime.peakPssBytes,
                    stabilityPeakPss,
                ),
                "oom_count" to result.runtime.oomCount,
                "crash_count" to result.runtime.crashCount,
                "success_count" to result.runtime.successCount,
            ),
            "telemetry" to telemetry,
        )
    }

    private fun telemetryDocument(proof: StabilityProof): Map<String, Any?> {
        if (proof.samples.size < 2) {
            throw BenchmarkContractException("telemetry needs at least two samples")
        }
        val withoutSummary = linkedMapOf<String, Any?>(
            "sample_interval_ms" to proof.sampleIntervalNanos / 1_000_000L,
            "duration_ns" to (
                proof.samples.last().monotonicNanos -
                    proof.samples.first().monotonicNanos
                ),
            "samples" to proof.samples.map { sample ->
                linkedMapOf(
                    "monotonic_ns" to sample.monotonicNanos,
                    "rss_bytes" to sample.rssBytes,
                    "pss_bytes" to sample.pssBytes,
                    "thermal_status" to sample.thermalStatus,
                    "heartbeat_index" to sample.heartbeatIndex,
                    "runner_alive" to sample.runnerAlive,
                    "decoder_active" to sample.decoderActive,
                    "active_clip_id" to sample.activeClipId,
                    "active_decode_progress" to sample.activeDecodeProgress,
                    "completed_clip_count" to sample.completedClipCount,
                    "completed_loops" to sample.completedLoops,
                    "completed_clips_in_current_loop" to
                        sample.completedClipsInCurrentLoop,
                    "successful_loops" to sample.successfulLoops,
                    "last_verified_clip_id" to sample.lastVerifiedClipId,
                )
            },
            "loop_count" to proof.loopCount,
            "successful_loop_count" to proof.successfulLoopCount,
            "total_decoded_clip_count" to proof.totalDecodedClipCount,
            "loop_proofs" to proof.loopProofs.map { loop ->
                linkedMapOf(
                    "loop_index" to loop.loopIndex,
                    "monotonic_start_ns" to loop.monotonicStartNanos,
                    "monotonic_end_ns" to loop.monotonicEndNanos,
                    "ordered_clip_ids_sha256" to loop.orderedClipIdsSha256,
                    "clip_count" to loop.clipCount,
                    "cumulative_decoded_clip_count" to loop.cumulativeDecodedClipCount,
                    "successful" to loop.successful,
                )
            },
        )
        return linkedMapOf<String, Any?>().apply {
            putAll(withoutSummary)
            put("summary_sha256", CanonicalJson.sha256(withoutSummary))
        }
    }
}

object AndroidAttestationEnvelopeFactory {
    fun create(
        result: BenchmarkRunResult,
        decoderPlanReceiptCommitSha256: String,
        decoderPlanSha256: String,
        decoderPlanRawSha256: String,
        decoderPlanIdSha256: String,
        accuracyDocument: Map<String, Any?>,
        engineeringDocument: Map<String, Any?>,
        bindings: RunnerContractBindings,
        protocolProfile: String,
        hostChallenge: ByteArray,
        physicalDeviceClaim: Boolean,
        emulatorClaim: Boolean,
        signer: AttestationSignatureProvider,
    ): Map<String, Any?> {
        if (protocolProfile !in setOf(
                "development_fixture",
                "exploration",
                "production_confirmation",
            )
        ) {
            throw BenchmarkContractException("attestation protocol profile is invalid")
        }
        if (
            hostChallenge.size != 32 ||
            hostChallenge.all { it == 0.toByte() } ||
            physicalDeviceClaim == emulatorClaim
        ) {
            throw BenchmarkContractException("attestation host/device claims are invalid")
        }
        val commitments = linkedMapOf(
            "phase_a_decoder_plan_receipt_commit_sha256" to
                Hashing.requireSha256(
                    decoderPlanReceiptCommitSha256,
                    "decoder receipt commit hash",
                ),
            "benchmark_evidence_sha256" to bindings.benchmarkEvidenceSha256,
            "public_plan_sha256" to bindings.publicPlanSha256,
            "decoder_plan_sha256" to Hashing.requireSha256(
                decoderPlanSha256,
                "decoder plan hash",
            ),
            "decoder_plan_raw_sha256" to Hashing.requireSha256(
                decoderPlanRawSha256,
                "raw decoder plan hash",
            ),
            "decoder_plan_id_sha256" to Hashing.requireSha256(
                decoderPlanIdSha256,
                "decoder plan ID hash",
            ),
            "accuracy_payload_sha256" to CanonicalJson.sha256(accuracyDocument),
            "engineering_payload_sha256" to CanonicalJson.sha256(engineeringDocument),
            "registry_sha256" to bindings.registrySha256,
            "normalization_sha256" to bindings.normalizationSha256,
            "selection_config_sha256" to bindings.selectionConfigSha256,
            "runner_build_sha256" to bindings.runnerBuildSha256,
            "apk_sha256" to bindings.apkSha256,
            "app_signing_cert_sha256" to bindings.appSigningCertSha256,
            "device_identity_commitment_sha256" to
                bindings.deviceIdentityCommitmentSha256,
            "pcm_set_sha256" to result.pcmSetSha256,
            "artifact_set_sha256" to result.artifactSetSha256,
            "telemetry_summary_sha256" to (
                (
                    engineeringDocument["telemetry"] as? Map<*, *>
                    )?.get("summary_sha256") as? String
                    ?: throw BenchmarkContractException(
                        "telemetry summary hash is missing",
                    )
                ),
        )
        val attestationChallengeSha256 = Hashing.sha256(
            CHALLENGE_DOMAIN +
                hostChallenge +
                CanonicalJson.encode(commitments).encodeToByteArray(),
        )
        val claims = linkedMapOf<String, Any?>(
            "physical_device" to physicalDeviceClaim,
            "emulator" to emulatorClaim,
            "local_key_security_level" to "unknown",
        )
        val unsignedPayload = linkedMapOf<String, Any?>(
            "schema_version" to "1.0",
            "run_id" to result.runId,
            "model_alias" to result.modelAlias,
            "protocol_profile" to protocolProfile,
            "commitments" to commitments,
            "host_challenge_sha256" to Hashing.sha256(hostChallenge),
            "attestation_challenge_sha256" to attestationChallengeSha256,
            "runtime_claims" to claims,
        )
        val signedPayload = LinkedHashMap(unsignedPayload)
        val finalSignature = signer.sign(
            attestationChallengeSha256,
            attestationMessageBytes(signedPayload),
        )
        return linkedMapOf(
            "schema_version" to "1.0",
            "attestation_format" to "android-keystore-key-attestation-v1",
            "signed_payload" to signedPayload,
            "signature_algorithm" to "SHA256withECDSA",
            "signature_base64" to Base64.getEncoder().encodeToString(
                finalSignature.signatureDer,
            ),
            "certificate_chain_der_base64" to
                finalSignature.certificateChainDer.map {
                    Base64.getEncoder().encodeToString(it)
                },
        )
    }

    fun attestationMessageBytes(signedPayload: Map<String, Any?>): ByteArray =
        MESSAGE_DOMAIN + CanonicalJson.encode(signedPayload).encodeToByteArray()

    private val MESSAGE_DOMAIN =
        "kittyecho-asr-attestation-envelope-v1\u0000".encodeToByteArray()
    private val CHALLENGE_DOMAIN =
        "kittyecho-asr-keystore-challenge-v1\u0000".encodeToByteArray()
}
