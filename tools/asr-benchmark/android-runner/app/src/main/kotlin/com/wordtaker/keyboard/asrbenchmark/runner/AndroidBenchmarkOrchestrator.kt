package com.wordtaker.keyboard.asrbenchmark.runner

import android.content.Context
import com.wordtaker.keyboard.asrbenchmark.core.AndroidAttestationEnvelopeFactory
import com.wordtaker.keyboard.asrbenchmark.core.ArtifactFile
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkRunResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderAdapter
import com.wordtaker.keyboard.asrbenchmark.core.DecodeProgressObserver
import com.wordtaker.keyboard.asrbenchmark.core.DeviceOutputDocuments
import com.wordtaker.keyboard.asrbenchmark.core.Hashing
import com.wordtaker.keyboard.asrbenchmark.core.RunnerContractBindings
import com.wordtaker.keyboard.asrbenchmark.core.StabilityPolicy
import com.wordtaker.keyboard.asrbenchmark.core.TrustedBenchmarkRunner

data class AndroidBenchmarkRequest(
    val decoderPlanPath: String,
    val decoderPlanReceiptCommitSha256: String,
    val pcmPathsByClipId: Map<String, String>,
    val artifactPathsByRole: Map<String, String>,
    val benchmarkEvidenceSha256: String,
    val registrySha256: String,
    val normalizationSha256: String,
    val selectionConfigSha256: String,
    val protocolProfile: String,
    val hostChallenge: ByteArray,
    val stabilityPolicy: StabilityPolicy,
) {
    init {
        Hashing.requireSha256(
            decoderPlanReceiptCommitSha256,
            "decoder-plan receipt commit",
        )
        listOf(
            benchmarkEvidenceSha256,
            registrySha256,
            normalizationSha256,
            selectionConfigSha256,
        ).forEach {
            Hashing.requireSha256(it, "Android benchmark binding")
        }
        if (
            protocolProfile !in setOf(
                "development_fixture",
                "exploration",
                "production_confirmation",
            ) ||
            hostChallenge.size != 32 ||
            hostChallenge.all { it == 0.toByte() } ||
            artifactPathsByRole.isEmpty()
        ) {
            throw BenchmarkContractException(
                "Android benchmark request is invalid",
            )
        }
        if (
            protocolProfile != "development_fixture" &&
            stabilityPolicy != StabilityPolicy.formal()
        ) {
            throw BenchmarkContractException(
                "formal Android runs must use the frozen ten-minute policy",
            )
        }
    }
}

data class AndroidBenchmarkBundle(
    val accuracyDocument: Map<String, Any?>,
    val engineeringDocument: Map<String, Any?>,
    val attestationEnvelope: Map<String, Any?>,
)

/**
 * Owns every measurement that an adapter is forbidden to report.
 *
 * AndroidKeyStore authenticates the key and signed commitments only. The host
 * therefore keeps every output at formal_eligible=false until the separate ADB
 * observer contract is complete.
 */
class AndroidBenchmarkOrchestrator(
    private val context: Context,
) {
    fun run(
        request: AndroidBenchmarkRequest,
        adapter: DecoderAdapter,
    ): AndroidBenchmarkBundle = runWithBoundAdapter(
        adapter = adapter,
        artifactPathsByRole = request.artifactPathsByRole,
    ) {
        runMeasured(request, adapter)
    }

    private fun runMeasured(
        request: AndroidBenchmarkRequest,
        adapter: DecoderAdapter,
    ): AndroidBenchmarkBundle {
        val planEvidence = AndroidDecoderPlanLoader.load(
            request.decoderPlanPath,
            request.pcmPathsByClipId,
        )
        val artifactBindings = request.artifactPathsByRole.entries
            .sortedBy { it.key }
            .mapIndexed { index, entry ->
                val pathToken = "artifact-${index.toString().padStart(2, '0')}"
                Triple(entry.key, pathToken, entry.value)
            }
        val allPaths = linkedMapOf<String, String>().apply {
            putAll(planEvidence.pathBindings)
            artifactBindings.forEach { (_, pathToken, path) -> put(pathToken, path) }
        }
        val secureFiles = AndroidSecureFileAccess(allPaths)
        val resourceProbe = AndroidRuntimeResourceProbe(context)
        val runner = TrustedBenchmarkRunner(
            secureFiles = secureFiles,
            clock = AndroidElapsedRealtimeClock,
            resourceProbe = resourceProbe,
        )
        val artifacts = artifactBindings.map { (role, token, _path) ->
            ArtifactFile(role, token)
        }
        val (primaryResult, stabilityProof) = runContinuousStability(
            runner = runner,
            planEvidence = planEvidence,
            artifacts = artifacts,
            adapter = adapter,
            resourceProbe = resourceProbe,
            policy = request.stabilityPolicy,
        )
        val build = AndroidBuildEvidenceCollector.collect(context)
        val bindings = RunnerContractBindings(
            benchmarkEvidenceSha256 = request.benchmarkEvidenceSha256,
            publicPlanSha256 = planEvidence.publicPlanSha256,
            registrySha256 = request.registrySha256,
            normalizationSha256 = request.normalizationSha256,
            selectionConfigSha256 = request.selectionConfigSha256,
            runnerBuildSha256 = build.runnerBuildSha256,
            apkSha256 = build.apkSha256,
            appSigningCertSha256 = build.appSigningCertSha256,
            deviceIdentityCommitmentSha256 =
                build.deviceIdentityCommitmentSha256,
        )
        val accuracy = DeviceOutputDocuments.accuracy(
            primaryResult,
            planEvidence.canonicalSha256,
        )
        val engineering = DeviceOutputDocuments.engineering(
            primaryResult,
            planEvidence.canonicalSha256,
            accuracy,
            bindings,
            stabilityProof,
        )
        val requiredSecurity = if (
            request.protocolProfile == "development_fixture"
        ) {
            RequiredKeystoreSecurity.DEVELOPMENT
        } else {
            RequiredKeystoreSecurity.HARDWARE_TEE_OR_STRONGBOX
        }
        val envelope = AndroidAttestationEnvelopeFactory.create(
            result = primaryResult,
            decoderPlanReceiptCommitSha256 =
                request.decoderPlanReceiptCommitSha256,
            decoderPlanSha256 = planEvidence.canonicalSha256,
            decoderPlanRawSha256 = planEvidence.rawFileSha256,
            decoderPlanIdSha256 = planEvidence.planIdSha256,
            accuracyDocument = accuracy,
            engineeringDocument = engineering,
            bindings = bindings,
            protocolProfile = request.protocolProfile,
            hostChallenge = request.hostChallenge,
            physicalDeviceClaim = build.physicalDeviceClaim,
            emulatorClaim = build.emulatorClaim,
            signer = AndroidKeystoreSignatureProvider(
                runId = primaryResult.runId,
                requiredSecurity = requiredSecurity,
            ),
        )
        return AndroidBenchmarkBundle(
            accuracyDocument = accuracy,
            engineeringDocument = engineering,
            attestationEnvelope = envelope,
        )
    }

    private fun runContinuousStability(
        runner: TrustedBenchmarkRunner,
        planEvidence: AndroidDecoderPlanEvidence,
        artifacts: List<ArtifactFile>,
        adapter: DecoderAdapter,
        resourceProbe: AndroidRuntimeResourceProbe,
        policy: StabilityPolicy,
    ): Pair<BenchmarkRunResult, com.wordtaker.keyboard.asrbenchmark.core.StabilityProof> {
        val collector = AndroidStabilityTelemetryCollector(
            orderedClipIds = planEvidence.plan.clips.map { it.clipId },
            clock = AndroidElapsedRealtimeClock,
            resourceProbe = resourceProbe,
            policy = policy,
        )
        var finished = false
        try {
            collector.start()
            val startNanos = AndroidElapsedRealtimeClock.nowNanos()
            val targetDuration = Math.addExact(
                policy.minimumDurationNanos,
                Math.addExact(
                    policy.sampleIntervalNanos,
                    policy.absoluteJitterNanos,
                ),
            )
            var loopIndex = 0
            var primary: BenchmarkRunResult? = null
            do {
                loopIndex += 1
                val loopStart = AndroidElapsedRealtimeClock.nowNanos()
                val result = runner.decode(
                    planEvidence.plan,
                    artifacts,
                    adapter,
                    object : DecodeProgressObserver {
                        override fun onDecodeStarted(clipId: String) {
                            collector.markDecodeStarted(clipId)
                        }

                        override fun onVerifiedDecode(
                            clipId: String,
                            status: com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus,
                        ) {
                            collector.markDecodeCompleted(clipId, status)
                        }
                    },
                )
                if (result.predictions.any { it.errorCode != null }) {
                    throw BenchmarkContractException(
                        "stability loop contains an unsuccessful decode",
                    )
                }
                val loopEnd = AndroidElapsedRealtimeClock.nowNanos()
                collector.markLoopCompleted(
                    loopIndex = loopIndex,
                    monotonicStartNanos = loopStart,
                    monotonicEndNanos = loopEnd,
                    successful = true,
                )
                if (primary == null) {
                    primary = result
                }
            } while (
                loopIndex < policy.minimumCompleteLoops ||
                AndroidElapsedRealtimeClock.nowNanos() - startNanos <
                targetDuration
            )
            val proof = collector.finish()
            finished = true
            return requireNotNull(primary) to proof
        } finally {
            if (!finished) {
                collector.close()
            }
        }
    }
}
