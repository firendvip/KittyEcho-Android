package com.wordtaker.keyboard.asrbenchmark.runner

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.wordtaker.keyboard.asrbenchmark.core.ArtifactFile
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import com.wordtaker.keyboard.asrbenchmark.core.StabilityPolicy
import com.wordtaker.keyboard.asrbenchmark.core.TrustedContentAddressedOutputStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Development-only two-stage fixture loop.
 *
 * `prepare` creates non-speech fixture files and a decoder-plan payload. The
 * host commits that plan through the Phase A receipt protocol. `run` accepts
 * the resulting receipt commitment plus a fresh host challenge, executes the
 * measured loop, and emits raw content-addressed blobs for host verification.
 *
 * Instrumentation/debug execution can never establish formal eligibility.
 */
class SyntheticBenchmarkInstrumentation : Instrumentation() {
    private lateinit var commandArguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        commandArguments = arguments ?: Bundle.EMPTY
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val requestedMode = commandArguments.getString("mode")
        val developmentBatch = requestedMode in setOf(
            "paraformer-dev-batch",
            "zipformer-dev-batch",
        )
        try {
            when (required("mode")) {
                "prepare" -> prepare(result)
                "run" -> runFixture(result)
                "paraformer-smoke" -> runParaformerSmoke(result)
                "zipformer-smoke" -> runZipformerSmoke(result)
                "firered-smoke" -> runFireRedSmoke(result)
                "funasr-nano-smoke" -> runFunAsrNanoSmoke(result)
                "paraformer-stability" -> runParaformerStability(result)
                "zipformer-stability" -> runZipformerStability(result)
                "funasr-nano-stability" -> runFunAsrNanoStability(result)
                "paraformer-dev-batch" -> runDevelopmentBatch(result)
                "zipformer-dev-batch" -> runDevelopmentBatch(result)
                else -> throw BenchmarkContractException(
                    "synthetic instrumentation mode is invalid",
                )
            }
            result.putBoolean("formal_eligible", false)
            result.putBoolean("product_decision_eligible", false)
            if (!developmentBatch) {
                result.putBoolean("adversarial_same_uid_resistant", false)
            }
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString(
                "error_code",
                if (developmentBatch) {
                    "development_batch_error"
                } else {
                    error.javaClass.simpleName.take(64)
                },
            )
            result.putBoolean("formal_eligible", false)
            result.putBoolean("product_decision_eligible", false)
            if (developmentBatch) {
                result.putBoolean("production_eligible", false)
            } else {
                result.putBoolean("adversarial_same_uid_resistant", false)
                result.putString("external_process_observer", "required")
            }
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun runParaformerSmoke(result: Bundle) {
        val command = ParaformerInstrumentationCommand.parse(
            stringArguments(),
        )
        val trustedRoot = targetContext.noBackupFilesDir.toPath()
            .resolve(PARAFORMER_INPUT_ROOT)
            .resolve(command.runId)
        val inputs = ParaformerPrivateInputContract.validate(
            trustedRoot = trustedRoot,
            command = command,
        )
        val fileAccess = AndroidSecureFileAccess(
            mapOf(
                "weights" to inputs.model.toString(),
                "tokenizer" to inputs.tokens.toString(),
                "pcm" to inputs.pcm.toString(),
            ),
        )
        val weights = fileAccess.read("weights", requireCanonicalPcm = false)
        val tokenizer = fileAccess.read("tokenizer", requireCanonicalPcm = false)
        val pcm = fileAccess.read("pcm", requireCanonicalPcm = true)
        val artifacts = listOf(
            AsrSmokeArtifact(
                role = "weights",
                sizeBytes = weights.identity.sizeBytes,
                sha256 = weights.sha256,
            ),
            AsrSmokeArtifact(
                role = "tokenizer",
                sizeBytes = tokenizer.identity.sizeBytes,
                sha256 = tokenizer.sha256,
            ),
            AsrSmokeArtifact(
                role = "pcm",
                sizeBytes = pcm.identity.sizeBytes,
                sha256 = pcm.sha256,
            ),
        )
        val adapter = ParaformerDecoderAdapter(
            OfflineParaformerModelConfig.devicePrivate(
                modelPath = inputs.model.toString(),
                tokensPath = inputs.tokens.toString(),
            ),
        )
        val smoke = executeSmoke(
            command.runId,
            command.cancellationPhase,
            artifacts,
            adapter,
            mapOf(
                "weights" to weights,
                "tokenizer" to tokenizer,
            ),
            pcm.bytes,
        )
        publishSmoke(
            result = result,
            kind = "paraformer-smoke",
            runId = command.runId,
            cancellationPhase = command.cancellationPhase,
            smoke = smoke,
        )
    }

    private fun runZipformerSmoke(result: Bundle) {
        val command = ZipformerInstrumentationCommand.parse(stringArguments())
        val trustedRoot = targetContext.noBackupFilesDir.toPath()
            .resolve(ZIPFORMER_INPUT_ROOT)
            .resolve(command.runId)
        val inputs = ZipformerPrivateInputContract.validate(trustedRoot, command)
        val fileAccess = AndroidSecureFileAccess(
            mapOf(
                "encoder" to inputs.encoder.toString(),
                "decoder" to inputs.decoder.toString(),
                "joiner" to inputs.joiner.toString(),
                "tokenizer" to inputs.tokens.toString(),
                "pcm" to inputs.pcm.toString(),
            ),
        )
        val encoder = fileAccess.read("encoder", requireCanonicalPcm = false)
        val decoder = fileAccess.read("decoder", requireCanonicalPcm = false)
        val joiner = fileAccess.read("joiner", requireCanonicalPcm = false)
        val tokenizer = fileAccess.read("tokenizer", requireCanonicalPcm = false)
        val pcm = fileAccess.read("pcm", requireCanonicalPcm = true)
        val artifacts = listOf(
            AsrSmokeArtifact("encoder", encoder.identity.sizeBytes, encoder.sha256),
            AsrSmokeArtifact("decoder", decoder.identity.sizeBytes, decoder.sha256),
            AsrSmokeArtifact("joiner", joiner.identity.sizeBytes, joiner.sha256),
            AsrSmokeArtifact(
                "tokenizer",
                tokenizer.identity.sizeBytes,
                tokenizer.sha256,
            ),
            AsrSmokeArtifact("pcm", pcm.identity.sizeBytes, pcm.sha256),
        )
        val adapter = ZipformerDecoderAdapter(
            ZipformerModelConfig.devicePrivate(
                encoderPath = inputs.encoder.toString(),
                decoderPath = inputs.decoder.toString(),
                joinerPath = inputs.joiner.toString(),
                tokensPath = inputs.tokens.toString(),
            ),
        )
        val smoke = executeSmoke(
            runId = command.runId,
            cancellationPhase = command.cancellationPhase,
            artifacts = artifacts,
            adapter = adapter,
            preparedArtifacts = mapOf(
                "encoder" to encoder,
                "decoder" to decoder,
                "joiner" to joiner,
                "tokenizer" to tokenizer,
            ),
            pcm = pcm.bytes,
        )
        publishSmoke(
            result = result,
            kind = "zipformer-smoke",
            runId = command.runId,
            cancellationPhase = command.cancellationPhase,
            smoke = smoke,
        )
    }

    private fun runFireRedSmoke(result: Bundle) {
        val command = FireRedInstrumentationCommand.parse(stringArguments())
        val trustedRoot = targetContext.noBackupFilesDir.toPath()
            .resolve(FIRERED_INPUT_ROOT)
            .resolve(command.runId)
        val inputs = FireRedPrivateInputContract.validate(trustedRoot, command)
        val fileAccess = AndroidSecureFileAccess(
            mapOf(
                "encoder" to inputs.encoder.toString(),
                "decoder" to inputs.decoder.toString(),
                "tokenizer" to inputs.tokens.toString(),
                "pcm" to inputs.pcm.toString(),
            ),
        )
        val encoder = fileAccess.read("encoder", requireCanonicalPcm = false)
        val decoder = fileAccess.read("decoder", requireCanonicalPcm = false)
        val tokenizer = fileAccess.read("tokenizer", requireCanonicalPcm = false)
        val pcm = fileAccess.read("pcm", requireCanonicalPcm = true)
        val artifacts = listOf(
            AsrSmokeArtifact("encoder", encoder.identity.sizeBytes, encoder.sha256),
            AsrSmokeArtifact("decoder", decoder.identity.sizeBytes, decoder.sha256),
            AsrSmokeArtifact(
                "tokenizer",
                tokenizer.identity.sizeBytes,
                tokenizer.sha256,
            ),
            AsrSmokeArtifact("pcm", pcm.identity.sizeBytes, pcm.sha256),
        )
        val adapter = FireRedDecoderAdapter(
            OfflineFireRedModelConfig.devicePrivate(
                encoderPath = inputs.encoder.toString(),
                decoderPath = inputs.decoder.toString(),
                tokensPath = inputs.tokens.toString(),
            ),
        )
        val smoke = executeSmoke(
            runId = command.runId,
            cancellationPhase = command.cancellationPhase,
            artifacts = artifacts,
            adapter = adapter,
            preparedArtifacts = mapOf(
                "encoder" to encoder,
                "decoder" to decoder,
                "tokenizer" to tokenizer,
            ),
            pcm = pcm.bytes,
        )
        publishSmoke(
            result = result,
            kind = "firered-smoke",
            runId = command.runId,
            cancellationPhase = command.cancellationPhase,
            smoke = smoke,
        )
    }

    private fun runFunAsrNanoSmoke(result: Bundle) {
        val command = FunAsrNanoInstrumentationCommand.parse(stringArguments())
        val trustedRoot = targetContext.noBackupFilesDir.toPath()
            .resolve(FUNASR_NANO_INPUT_ROOT)
            .resolve(command.runId)
        val inputs = FunAsrNanoPrivateInputContract.validate(trustedRoot, command)
        val fileAccess = AndroidSecureFileAccess(
            mapOf(
                "embedding" to inputs.embedding.toString(),
                "encoder_adaptor" to inputs.encoderAdaptor.toString(),
                "llm" to inputs.llm.toString(),
                "tokenizer_json" to inputs.tokenizer.toString(),
                "vocab" to inputs.vocab.toString(),
                "merges" to inputs.merges.toString(),
                "pcm" to inputs.pcm.toString(),
            ),
        )
        val embedding = fileAccess.read("embedding", requireCanonicalPcm = false)
        val encoderAdaptor = fileAccess.read(
            "encoder_adaptor",
            requireCanonicalPcm = false,
        )
        val llm = fileAccess.read("llm", requireCanonicalPcm = false)
        val tokenizer = fileAccess.read(
            "tokenizer_json",
            requireCanonicalPcm = false,
        )
        val vocab = fileAccess.read("vocab", requireCanonicalPcm = false)
        val merges = fileAccess.read("merges", requireCanonicalPcm = false)
        val pcm = fileAccess.read("pcm", requireCanonicalPcm = true)
        val artifacts = listOf(
            AsrSmokeArtifact(
                "embedding",
                embedding.identity.sizeBytes,
                embedding.sha256,
            ),
            AsrSmokeArtifact(
                "encoder_adaptor",
                encoderAdaptor.identity.sizeBytes,
                encoderAdaptor.sha256,
            ),
            AsrSmokeArtifact("llm", llm.identity.sizeBytes, llm.sha256),
            AsrSmokeArtifact(
                "tokenizer_json",
                tokenizer.identity.sizeBytes,
                tokenizer.sha256,
            ),
            AsrSmokeArtifact("vocab", vocab.identity.sizeBytes, vocab.sha256),
            AsrSmokeArtifact("merges", merges.identity.sizeBytes, merges.sha256),
            AsrSmokeArtifact("pcm", pcm.identity.sizeBytes, pcm.sha256),
        )
        val adapter = FunAsrNanoDecoderAdapter(
            OfflineFunAsrNanoModelConfig.devicePrivate(
                embeddingPath = inputs.embedding.toString(),
                encoderAdaptorPath = inputs.encoderAdaptor.toString(),
                llmPath = inputs.llm.toString(),
                tokenizerDirectoryPath = inputs.tokenizerDirectory.toString(),
            ),
        )
        val smoke = executeSmoke(
            runId = command.runId,
            cancellationPhase = command.cancellationPhase,
            artifacts = artifacts,
            adapter = adapter,
            preparedArtifacts = mapOf(
                "embedding" to embedding,
                "encoder_adaptor" to encoderAdaptor,
                "llm" to llm,
                "tokenizer_json" to tokenizer,
                "vocab" to vocab,
                "merges" to merges,
            ),
            pcm = pcm.bytes,
        )
        publishSmoke(
            result = result,
            kind = "funasr-nano-smoke",
            runId = command.runId,
            cancellationPhase = command.cancellationPhase,
            smoke = smoke,
        )
    }

    private fun runParaformerStability(result: Bundle) {
        val stability = ParaformerStabilityInstrumentationCommand.parse(
            stringArguments(),
        )
        val command = stability.smokeCommand
        val trustedRoot = targetContext.noBackupFilesDir.toPath()
            .resolve(PARAFORMER_INPUT_ROOT)
            .resolve(command.runId)
        val inputs = ParaformerPrivateInputContract.validate(trustedRoot, command)
        val paths = mapOf(
            "weights" to inputs.model.toString(),
            "tokenizer" to inputs.tokens.toString(),
        )
        val fileAccess = AndroidSecureFileAccess(
            paths + ("pcm" to inputs.pcm.toString()),
        )
        val artifacts = paths.keys.associateWith { role ->
            fileAccess.read(role, requireCanonicalPcm = false)
        }
        val pcm = fileAccess.read("pcm", requireCanonicalPcm = true)
        val adapter = ParaformerDecoderAdapter(
            OfflineParaformerModelConfig.devicePrivate(
                modelPath = inputs.model.toString(),
                tokensPath = inputs.tokens.toString(),
            ),
        )
        val continuous = executeContinuousStability(
            duration = stability.duration,
            cancellationPhase = command.cancellationPhase,
            pathsByRole = paths,
            artifactsByRole = artifacts,
            pcm = pcm,
            adapter = adapter,
        )
        publishContinuousStability(
            result = result,
            kind = "paraformer-stability",
            runId = command.runId,
            continuous = continuous,
        )
    }

    private fun runZipformerStability(result: Bundle) {
        val stability = ZipformerStabilityInstrumentationCommand.parse(
            stringArguments(),
        )
        val command = stability.smokeCommand
        val trustedRoot = targetContext.noBackupFilesDir.toPath()
            .resolve(ZIPFORMER_INPUT_ROOT)
            .resolve(command.runId)
        val inputs = ZipformerPrivateInputContract.validate(trustedRoot, command)
        val paths = mapOf(
            "encoder" to inputs.encoder.toString(),
            "decoder" to inputs.decoder.toString(),
            "joiner" to inputs.joiner.toString(),
            "tokenizer" to inputs.tokens.toString(),
        )
        val fileAccess = AndroidSecureFileAccess(
            paths + ("pcm" to inputs.pcm.toString()),
        )
        val artifacts = paths.keys.associateWith { role ->
            fileAccess.read(role, requireCanonicalPcm = false)
        }
        val pcm = fileAccess.read("pcm", requireCanonicalPcm = true)
        val adapter = ZipformerDecoderAdapter(
            ZipformerModelConfig.devicePrivate(
                encoderPath = inputs.encoder.toString(),
                decoderPath = inputs.decoder.toString(),
                joinerPath = inputs.joiner.toString(),
                tokensPath = inputs.tokens.toString(),
            ),
        )
        val continuous = executeContinuousStability(
            duration = stability.duration,
            cancellationPhase = command.cancellationPhase,
            pathsByRole = paths,
            artifactsByRole = artifacts,
            pcm = pcm,
            adapter = adapter,
        )
        publishContinuousStability(
            result = result,
            kind = "zipformer-stability",
            runId = command.runId,
            continuous = continuous,
        )
    }

    private fun runFunAsrNanoStability(result: Bundle) {
        val stability = FunAsrNanoStabilityInstrumentationCommand.parse(
            stringArguments(),
        )
        val command = stability.smokeCommand
        val trustedRoot = targetContext.noBackupFilesDir.toPath()
            .resolve(FUNASR_NANO_INPUT_ROOT)
            .resolve(command.runId)
        val inputs = FunAsrNanoPrivateInputContract.validate(trustedRoot, command)
        val paths = mapOf(
            "embedding" to inputs.embedding.toString(),
            "encoder_adaptor" to inputs.encoderAdaptor.toString(),
            "llm" to inputs.llm.toString(),
            "tokenizer_json" to inputs.tokenizer.toString(),
            "vocab" to inputs.vocab.toString(),
            "merges" to inputs.merges.toString(),
        )
        val fileAccess = AndroidSecureFileAccess(
            paths + ("pcm" to inputs.pcm.toString()),
        )
        val artifacts = paths.keys.associateWith { role ->
            fileAccess.read(role, requireCanonicalPcm = false)
        }
        val pcm = fileAccess.read("pcm", requireCanonicalPcm = true)
        val adapter = FunAsrNanoDecoderAdapter(
            OfflineFunAsrNanoModelConfig.devicePrivate(
                embeddingPath = inputs.embedding.toString(),
                encoderAdaptorPath = inputs.encoderAdaptor.toString(),
                llmPath = inputs.llm.toString(),
                tokenizerDirectoryPath = inputs.tokenizerDirectory.toString(),
            ),
        )
        val continuous = executeContinuousStability(
            duration = stability.duration,
            cancellationPhase = command.cancellationPhase,
            pathsByRole = paths,
            artifactsByRole = artifacts,
            pcm = pcm,
            adapter = adapter,
        )
        publishContinuousStability(
            result = result,
            kind = "funasr-nano-stability",
            runId = command.runId,
            continuous = continuous,
        )
    }

    private fun runDevelopmentBatch(result: Bundle) {
        val command = DevelopmentBatchInstrumentationCommand.parse(
            stringArguments(),
        )
        val stagingRoot = targetContext.noBackupFilesDir.toPath()
            .resolve(DEVELOPMENT_BATCH_INPUT_ROOT)
            .resolve(command.runId)
        val decoderPlanPath = java.io.File(command.decoderPlanPath).toPath()
        val pcmRoot = java.io.File(command.pcmRootPath).toPath()
        DevelopmentBatchPrivateInputContract.requireStagingLayout(
            trustedRoot = stagingRoot,
            decoderPlanPath = decoderPlanPath,
            pcmRoot = pcmRoot,
        )
        val evidence = AndroidDevelopmentBatchPlanLoader.load(
            decoderPlanPath = command.decoderPlanPath,
            runId = command.runId,
            modelAlias = command.modelAlias,
            expectedSnapshotFingerprintSha256 =
                command.expectedSnapshotFingerprintSha256,
            expectedDecoderPlanSha256 = command.expectedDecoderPlanSha256,
        )
        val privateInputs = DevelopmentBatchPrivateInputContract.bind(
            trustedRoot = stagingRoot,
            decoderPlanPath = decoderPlanPath,
            pcmRoot = pcmRoot,
            evidence = evidence,
        )
        val modelPaths = validateDevelopmentModelPaths(command)
        val fileAccess = AndroidSecureFileAccess(
            modelPaths + privateInputs.pcmPathsByToken.mapValues {
                it.value.toString()
            },
        )
        val adapter: CancellablePreparedDecoderAdapter
        val artifacts: List<ArtifactFile>
        val adapterRoles: Map<String, String>
        when (command.candidate) {
            DevelopmentBatchCandidate.PARAFORMER -> {
                adapter = ParaformerDecoderAdapter(
                    OfflineParaformerModelConfig.devicePrivate(
                        modelPath = modelPaths.getValue("weights"),
                        tokensPath = modelPaths.getValue("tokenizer"),
                    ),
                )
                artifacts = listOf(
                    ArtifactFile("weights", "weights"),
                    ArtifactFile("tokenizer", "tokenizer"),
                )
                adapterRoles = mapOf(
                    "weights" to "weights",
                    "tokenizer" to "tokenizer",
                )
            }
            DevelopmentBatchCandidate.ZIPFORMER -> {
                adapter = ZipformerDecoderAdapter(
                    ZipformerModelConfig.devicePrivate(
                        encoderPath = modelPaths.getValue("encoder"),
                        decoderPath = modelPaths.getValue("decoder"),
                        joinerPath = modelPaths.getValue("joiner"),
                        tokensPath = modelPaths.getValue("tokenizer"),
                    ),
                )
                artifacts = listOf(
                    ArtifactFile("weights", "encoder"),
                    ArtifactFile("conversion", "decoder"),
                    ArtifactFile("runtime", "joiner"),
                    ArtifactFile("tokenizer", "tokenizer"),
                )
                adapterRoles = mapOf(
                    "weights" to "encoder",
                    "conversion" to "decoder",
                    "runtime" to "joiner",
                    "tokenizer" to "tokenizer",
                )
            }
        }
        val batch = runWithBoundAdapter(
            adapter = adapter,
            artifactPathsByRole = modelPaths,
        ) {
            DevelopmentBatchRunner(
                secureFiles = fileAccess,
                clock = AndroidElapsedRealtimeClock,
            ).run(
                evidence = evidence,
                artifacts = artifacts,
                adapter = adapter,
                adapterArtifactRoles = adapterRoles,
            )
        }
        val kind = when (command.candidate) {
            DevelopmentBatchCandidate.PARAFORMER -> "paraformer-dev-batch"
            DevelopmentBatchCandidate.ZIPFORMER -> "zipformer-dev-batch"
        }
        val stored = TrustedContentAddressedOutputStore.openDevelopment(
            targetContext.noBackupFilesDir.toPath(),
            command.runId,
        ).use { output -> output.publish(kind, batch.document) }
        val fields = DevelopmentBatchBundleContract.fields(stored, batch)
        result.putString("batch_file", fields.getValue("batch_file") as String)
        result.putString("batch_sha256", fields.getValue("batch_sha256") as String)
        result.putLong("success_count", fields.getValue("success_count") as Long)
        result.putLong("failure_count", fields.getValue("failure_count") as Long)
        result.putBoolean("formal_eligible", false)
        result.putBoolean("product_decision_eligible", false)
        result.putBoolean("production_eligible", false)
    }

    private fun validateDevelopmentModelPaths(
        command: DevelopmentBatchInstrumentationCommand,
    ): Map<String, String> {
        val (rootName, expectedNames) = when (command.candidate) {
            DevelopmentBatchCandidate.PARAFORMER ->
                PARAFORMER_INPUT_ROOT to mapOf(
                    "weights" to "model.int8.onnx",
                    "tokenizer" to "tokens.txt",
                )
            DevelopmentBatchCandidate.ZIPFORMER ->
                ZIPFORMER_INPUT_ROOT to mapOf(
                    "encoder" to "encoder.int8.onnx",
                    "decoder" to "decoder.int8.onnx",
                    "joiner" to "joiner.int8.onnx",
                    "tokenizer" to "tokens.txt",
                )
        }
        val trustedRoot = targetContext.noBackupFilesDir.toPath()
            .resolve(rootName)
            .resolve(command.runId)
        if (
            java.nio.file.Files.isSymbolicLink(trustedRoot.parent) ||
            !java.nio.file.Files.isDirectory(
                trustedRoot.parent,
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
        ) {
            throw BenchmarkContractException(
                "development batch model root is invalid",
            )
        }
        val validated = expectedNames.mapValues { (role, filename) ->
            val supplied = command.modelPathsByRole[role]
                ?: throw BenchmarkContractException(
                    "development batch model role is missing",
                )
            ParaformerPrivateInputContract.requireDirectRegularFile(
                trustedRoot = trustedRoot,
                candidate = java.io.File(supplied).toPath(),
                expectedFileName = filename,
            ).toString()
        }
        if (validated.values.toSet().size != validated.size) {
            throw BenchmarkContractException(
                "development batch model files must be distinct",
            )
        }
        return validated
    }

    private fun executeContinuousStability(
        duration: ContinuousStabilityDuration,
        cancellationPhase: ParaformerCancellationPhase,
        pathsByRole: Map<String, String>,
        artifactsByRole: Map<String, MeasuredFile>,
        pcm: MeasuredFile,
        adapter: CancellablePreparedDecoderAdapter,
    ): ContinuousStabilityResult = runWithBoundAdapter(
        adapter = adapter,
        artifactPathsByRole = pathsByRole,
    ) {
        val probe = AndroidRuntimeResourceProbe(targetContext)
        ContinuousStabilityRunner(AndroidElapsedRealtimeClock).run(
            duration = duration,
            cancellationPhase = cancellationPhase,
            artifactsByRole = artifactsByRole,
            canonicalPcm = pcm,
            adapter = adapter,
            telemetry = AndroidStabilityTelemetryCollector(
                orderedClipIds = listOf(ContinuousStabilityRunner.CLIP_ID),
                clock = AndroidElapsedRealtimeClock,
                resourceProbe = probe,
                policy = duration.policy,
            ),
        )
    }

    private fun publishContinuousStability(
        result: Bundle,
        kind: String,
        runId: String,
        continuous: ContinuousStabilityResult,
    ) {
        val stored = TrustedContentAddressedOutputStore.open(
            targetContext.noBackupFilesDir.toPath(),
            runId,
        ).use { output -> output.publish(kind, continuous.document) }
        result.putString("stability_file", stored.fileName)
        result.putString("stability_sha256", stored.sha256)
        result.putString("stability_status", continuous.document["status"] as String)
        result.putString(
            "failure_phase",
            continuous.document["failure_phase"] as String,
        )
        result.putString(
            "failure_code",
            continuous.document["failure_code"] as String,
        )
        result.putLong("success_count", continuous.document["success_count"] as Long)
        result.putLong("failure_count", continuous.document["failure_count"] as Long)
        result.putLong("duration_ns", continuous.document["duration_ns"] as Long)
        result.putString("external_process_observer", "required")
    }

    private fun executeSmoke(
        runId: String,
        cancellationPhase: ParaformerCancellationPhase,
        artifacts: List<AsrSmokeArtifact>,
        adapter: CancellablePreparedDecoderAdapter,
        preparedArtifacts: Map<String, MeasuredFile>,
        pcm: ByteArray,
    ): AsrSmokeResult = try {
        if (cancellationPhase == ParaformerCancellationPhase.BEFORE) {
            adapter.cancel()
        }
        adapter.prepare(preparedArtifacts)
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val decoded = when (cancellationPhase) {
            ParaformerCancellationPhase.DURING ->
                decodeWithConcurrentCancellation(adapter, pcm)
            else -> adapter.decode(pcm)
        }
        if (cancellationPhase == ParaformerCancellationPhase.AFTER) {
            adapter.cancel()
        }
        AsrSmokeOutcomePolicy.fromDecoderResult(
            runId = runId,
            decoderResult = decoded,
            stopToFinalNanos = SystemClock.elapsedRealtimeNanos() - startedAt,
            cancellationPhase = cancellationPhase,
            artifacts = artifacts,
        )
    } catch (error: Exception) {
        AsrSmokeResult.failure(
            runId = runId,
            exceptionCode = when (error) {
                is TimeoutException -> "decode_timeout"
                is BenchmarkContractException -> "contract_error"
                else -> "runtime_error"
            },
            cancellationPhase = cancellationPhase,
            artifacts = artifacts,
        )
    } finally {
        adapter.close()
    }

    private fun publishSmoke(
        result: Bundle,
        kind: String,
        runId: String,
        cancellationPhase: ParaformerCancellationPhase,
        smoke: AsrSmokeResult,
    ) {
        val stored = TrustedContentAddressedOutputStore.open(
            targetContext.noBackupFilesDir.toPath(),
            runId,
        ).use { output -> output.publish(kind, smoke.document) }
        result.putString("smoke_file", stored.fileName)
        result.putString("smoke_sha256", stored.sha256)
        result.putString("smoke_status", smoke.document["status"] as String)
        result.putBoolean(
            "output_nonempty",
            smoke.document["output_nonempty"] as Boolean,
        )
        result.putString("cancellation_phase", cancellationPhase.wireValue)
        result.putString("external_process_observer", "required")
    }

    private fun decodeWithConcurrentCancellation(
        adapter: CancellablePreparedDecoderAdapter,
        pcm: ByteArray,
    ): DecoderResult {
        val enteredDecode = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "asr-smoke-decode").apply {
                isDaemon = true
            }
        }
        return try {
            val pending = executor.submit<DecoderResult> {
                enteredDecode.countDown()
                adapter.decode(pcm)
            }
            if (!enteredDecode.await(5L, TimeUnit.SECONDS)) {
                throw TimeoutException("decode thread did not start")
            }
            SystemClock.sleep(10L)
            adapter.cancel()
            pending.get(60L, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(60L, TimeUnit.SECONDS)
        }
    }

    private fun prepare(result: Bundle) {
        val files = SyntheticFixturePreparation(targetContext).prepare(
            runId = required("run_id"),
            datasetId = required("dataset_id"),
            modelAlias = required("model_alias"),
            publicPlanSha256 = requiredSha256("public_plan_sha256"),
            manifestSha256 = requiredSha256("manifest_sha256"),
        )
        result.putString("decoder_plan_file", files.decoderPlanFileName)
        result.putString("decoder_plan_id", files.decoderPlanId)
        result.putString(
            "decoder_plan_sha256",
            files.decoderPlanCanonicalSha256,
        )
        result.putString(
            "decoder_plan_raw_sha256",
            files.decoderPlanRawSha256,
        )
        result.putString("pcm_file", files.pcmFileName)
        result.putString("pcm_sha256", files.pcmSha256)
        result.putString("pcm_payload_sha256", files.pcmPayloadSha256)
        result.putString("artifact_file", files.artifactFileName)
        result.putString("artifact_sha256", files.artifactSha256)
    }

    private fun runFixture(result: Bundle) {
        val decoderPlan = SyntheticFixturePreparation.resolveFixtureFile(
            targetContext,
            required("decoder_plan_file"),
        )
        val pcm = SyntheticFixturePreparation.resolveFixtureFile(
            targetContext,
            required("pcm_file"),
        )
        val artifact = SyntheticFixturePreparation.resolveFixtureFile(
            targetContext,
            required("artifact_file"),
        )
        val request = AndroidBenchmarkRequest(
            decoderPlanPath = decoderPlan.absolutePath,
            decoderPlanReceiptCommitSha256 =
                requiredSha256("decoder_plan_receipt_commit_sha256"),
            pcmPathsByClipId = mapOf(
                SyntheticFixturePreparation.SYNTHETIC_CLIP_ID to
                    pcm.absolutePath,
            ),
            artifactPathsByRole = mapOf("weights" to artifact.absolutePath),
            benchmarkEvidenceSha256 =
                requiredSha256("benchmark_evidence_sha256"),
            registrySha256 = requiredSha256("registry_sha256"),
            normalizationSha256 = requiredSha256("normalization_sha256"),
            selectionConfigSha256 =
                requiredSha256("selection_config_sha256"),
            protocolProfile = "development_fixture",
            hostChallenge = requiredHex("host_challenge_hex", 32),
            stabilityPolicy = StabilityPolicy.development(
                minimumDurationNanos = 10_000_000_000L,
            ),
        )
        val bundle = AndroidBenchmarkOrchestrator(targetContext).run(
            request,
            SyntheticDecoderAdapter(),
        )
        val stored = AndroidBundleStore(
            targetContext,
            required("run_id"),
        ).store(bundle)
        result.putString("accuracy_file", stored.accuracyFileName)
        result.putString("accuracy_sha256", stored.accuracySha256)
        result.putString("engineering_file", stored.engineeringFileName)
        result.putString("engineering_sha256", stored.engineeringSha256)
        result.putString("attestation_file", stored.attestationFileName)
        result.putString("attestation_sha256", stored.attestationSha256)
        result.putString("assurance_level", "development_signed")
    }

    private fun required(name: String): String =
        commandArguments.getString(name)
            ?.takeIf { it.isNotBlank() }
            ?: throw BenchmarkContractException(
                "synthetic instrumentation argument is missing",
            )

    private fun requiredSha256(name: String): String {
        val value = required(name)
        if (!Regex("^[0-9a-f]{64}$").matches(value)) {
            throw BenchmarkContractException(
                "synthetic instrumentation SHA-256 is invalid",
            )
        }
        return value
    }

    private fun requiredHex(name: String, bytes: Int): ByteArray {
        val value = required(name)
        if (
            value.length != bytes * 2 ||
            !Regex("^[0-9a-f]+$").matches(value)
        ) {
            throw BenchmarkContractException(
                "synthetic instrumentation challenge is invalid",
            )
        }
        return value.chunked(2).map {
            it.toInt(16).toByte()
        }.toByteArray().also {
            if (it.all { byte -> byte == 0.toByte() }) {
                throw BenchmarkContractException(
                    "synthetic instrumentation challenge is empty",
                )
            }
        }
    }

    private fun stringArguments(): List<Pair<String, String>> =
        commandArguments.keySet().sorted().map { key ->
            val value = commandArguments.getString(key)
                ?: throw BenchmarkContractException(
                    "instrumentation argument type is invalid",
                )
            key to value
        }

    companion object {
        private const val PARAFORMER_INPUT_ROOT =
            "kittyecho-asr-paraformer-smoke-input-v1"
        private const val ZIPFORMER_INPUT_ROOT =
            "kittyecho-asr-zipformer-smoke-input-v1"
        private const val FIRERED_INPUT_ROOT =
            "kittyecho-asr-firered-smoke-input-v1"
        private const val FUNASR_NANO_INPUT_ROOT =
            "kittyecho-asr-funasr-nano-smoke-input-v1"
        private const val DEVELOPMENT_BATCH_INPUT_ROOT =
            "kittyecho-asr-development-batch-input-v1"
    }
}
