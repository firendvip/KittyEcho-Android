package com.wordtaker.keyboard.asrbenchmark.core

data class AccuracyPrediction(
    val clipId: String,
    val pcmSha256: String,
    val pcmPayloadSha256: String,
    val transcript: String,
    val status: DecoderStatus,
    val errorCode: String?,
)

data class ClipMeasurement(
    val clipId: String,
    val wavSha256Before: String,
    val wavSha256After: String,
    val pcmPayloadSha256Before: String,
    val pcmPayloadSha256After: String,
    val pcmPayloadBytes: Long,
    val identityBefore: FileIdentity,
    val identityAfter: FileIdentity,
    val monotonicStopNanos: Long,
    val monotonicFinalNanos: Long,
    val stopToFinalNanos: Long,
    val runState: String,
    val status: DecoderStatus,
    val errorCode: String?,
)

data class RuntimeMeasurements(
    val totalResourceBytes: Long,
    val coldLatencyNanos: List<Long>,
    val warmLatencyNanos: List<Long>,
    val peakRssBytes: Long,
    val peakPssBytes: Long,
    val maximumThermalStatus: Int,
    val oomCount: Int,
    val crashCount: Int,
    val successCount: Int,
)

data class BenchmarkRunResult(
    val runId: String,
    val datasetId: String,
    val modelAlias: String,
    val predictions: List<AccuracyPrediction>,
    val measurements: List<ClipMeasurement>,
    val runtime: RuntimeMeasurements,
    val pcmSetSha256: String,
    val artifactSetSha256: String,
    val engineeringDocument: Map<String, Any?>,
)

class TrustedBenchmarkRunner(
    private val secureFiles: SecureFileAccess,
    private val clock: MonotonicClock,
    private val resourceProbe: ResourceProbe,
) {
    fun decode(
        plan: DecoderPlan,
        artifacts: List<ArtifactFile>,
        adapter: DecoderAdapter,
        progressObserver: DecodeProgressObserver? = null,
    ): BenchmarkRunResult {
        if (artifacts.isEmpty()) {
            throw BenchmarkContractException("runner requires measured model resources")
        }
        if (artifacts.map { it.componentRole }.distinct().size != artifacts.size) {
            throw BenchmarkContractException("artifact component roles must be unique")
        }
        val frozenArtifacts = artifacts.associateWith {
            secureFiles.read(it.pathToken, requireCanonicalPcm = false)
        }
        var peakRss = 0L
        var peakPss = 0L
        var maximumThermal = 0
        fun collectResources() {
            val sample = resourceProbe.sample()
            peakRss = maxOf(peakRss, sample.rssBytes)
            peakPss = maxOf(peakPss, sample.pssBytes)
            maximumThermal = maxOf(maximumThermal, sample.thermalStatus)
        }

        val predictions = mutableListOf<AccuracyPrediction>()
        val clipMeasurements = mutableListOf<ClipMeasurement>()
        val artifactsByRole = frozenArtifacts.entries.associate {
            it.key.componentRole to it.value
        }
        var adapterPrepared = false
        plan.clips.forEachIndexed { index, clip ->
            val before = secureFiles.read(clip.pathToken, requireCanonicalPcm = true)
            verifyPcmCommitment(clip, before)
            collectResources()
            val stopNanos = clock.nowNanos()
            progressObserver?.onDecodeStarted(clip.clipId)
            val decoderResult = try {
                if (!adapterPrepared && adapter is RunnerPreparedDecoderAdapter) {
                    adapter.prepare(artifactsByRole)
                    adapterPrepared = true
                }
                adapter.decode(before.bytes.copyOf())
            } catch (_: OutOfMemoryError) {
                DecoderResult("", DecoderStatus.ERROR, "oom")
            } catch (_: Exception) {
                DecoderResult("", DecoderStatus.ERROR, "crash")
            }
            val finalNanos = clock.nowNanos()
            if (finalNanos < stopNanos) {
                throw BenchmarkContractException("monotonic clock moved backwards")
            }
            collectResources()
            val after = secureFiles.read(clip.pathToken, requireCanonicalPcm = true)
            verifyPcmCommitment(clip, after)
            verifyUnchanged(before, after, "canonical PCM")
            frozenArtifacts.forEach { (artifact, frozen) ->
                val current = secureFiles.read(
                    artifact.pathToken,
                    requireCanonicalPcm = false,
                )
                verifyUnchanged(frozen, current, "artifact ${artifact.componentRole}")
            }
            progressObserver?.onVerifiedDecode(clip.clipId, decoderResult.status)
            predictions += AccuracyPrediction(
                clipId = clip.clipId,
                pcmSha256 = before.sha256,
                pcmPayloadSha256 = requireNotNull(before.pcm).payloadSha256,
                transcript = decoderResult.transcript,
                status = decoderResult.status,
                errorCode = decoderResult.errorCode,
            )
            clipMeasurements += ClipMeasurement(
                clipId = clip.clipId,
                wavSha256Before = before.sha256,
                wavSha256After = after.sha256,
                pcmPayloadSha256Before = before.pcm.payloadSha256,
                pcmPayloadSha256After = requireNotNull(after.pcm).payloadSha256,
                pcmPayloadBytes = before.pcm.payloadBytes,
                identityBefore = before.identity,
                identityAfter = after.identity,
                monotonicStopNanos = stopNanos,
                monotonicFinalNanos = finalNanos,
                stopToFinalNanos = finalNanos - stopNanos,
                runState = if (index == 0) "cold" else "warm",
                status = decoderResult.status,
                errorCode = decoderResult.errorCode,
            )
        }
        if (predictions.map { it.clipId } != plan.clips.map { it.clipId }) {
            throw BenchmarkContractException("runner output order differs from decoder plan")
        }
        val artifactDocument = frozenArtifacts.map { (artifact, measured) ->
            linkedMapOf<String, Any?>(
                "component_role" to artifact.componentRole,
                "sha256" to measured.sha256,
                "size_bytes" to measured.identity.sizeBytes,
                "st_dev" to measured.identity.device,
                "st_ino" to measured.identity.inode,
                "mode" to measured.identity.mode,
                "nlink" to measured.identity.linkCount,
            )
        }
        val pcmSetDocument = plan.clips.map { clip ->
            linkedMapOf<String, Any?>(
                "clip_id" to clip.clipId,
                "wav" to clip.wavSha256,
                "payload" to clip.pcmPayloadSha256,
            )
        }
        val runtime = RuntimeMeasurements(
            totalResourceBytes = frozenArtifacts.values.sumOf { it.identity.sizeBytes },
            coldLatencyNanos = clipMeasurements
                .filter { it.runState == "cold" }
                .map { it.stopToFinalNanos },
            warmLatencyNanos = clipMeasurements
                .filter { it.runState == "warm" }
                .map { it.stopToFinalNanos },
            peakRssBytes = peakRss,
            peakPssBytes = peakPss,
            maximumThermalStatus = maximumThermal,
            oomCount = clipMeasurements.count { it.errorCode == "oom" },
            crashCount = clipMeasurements.count { it.errorCode == "crash" },
            successCount = clipMeasurements.count { it.status == DecoderStatus.OK },
        )
        val artifactSetSha256 = CanonicalJson.sha256(artifactDocument)
        val pcmSetSha256 = CanonicalJson.sha256(pcmSetDocument)
        return BenchmarkRunResult(
            runId = plan.runId,
            datasetId = plan.datasetId,
            modelAlias = plan.modelAlias,
            predictions = predictions.toList(),
            measurements = clipMeasurements.toList(),
            runtime = runtime,
            pcmSetSha256 = pcmSetSha256,
            artifactSetSha256 = artifactSetSha256,
            engineeringDocument = engineeringDocument(
                plan,
                clipMeasurements,
                runtime,
                artifactDocument,
                artifactSetSha256,
                pcmSetSha256,
            ),
        )
    }

    private fun verifyPcmCommitment(clip: DecoderClip, measured: MeasuredFile) {
        val pcm = measured.pcm
            ?: throw BenchmarkContractException("runner PCM measurement is missing")
        if (
            measured.sha256 != clip.wavSha256 ||
            pcm.payloadSha256 != clip.pcmPayloadSha256 ||
            pcm.payloadBytes != clip.pcmPayloadBytes
        ) {
            throw BenchmarkContractException("runner PCM differs from decoder-plan commitment")
        }
    }

    private fun verifyUnchanged(
        before: MeasuredFile,
        after: MeasuredFile,
        context: String,
    ) {
        if (
            before.pathToken != after.pathToken ||
            before.identity != after.identity ||
            before.sha256 != after.sha256 ||
            !retainedBytesMatch(before, after)
        ) {
            throw BenchmarkContractException("$context identity or content changed during decode")
        }
    }

    private fun retainedBytesMatch(before: MeasuredFile, after: MeasuredFile): Boolean {
        val beforeBytes = before.retainedBytesOrNull()
        val afterBytes = after.retainedBytesOrNull()
        return when {
            beforeBytes == null && afterBytes == null -> true
            beforeBytes == null || afterBytes == null -> false
            else -> beforeBytes.contentEquals(afterBytes)
        }
    }

    private fun engineeringDocument(
        plan: DecoderPlan,
        measurements: List<ClipMeasurement>,
        runtime: RuntimeMeasurements,
        artifacts: List<Map<String, Any?>>,
        artifactSetSha256: String,
        pcmSetSha256: String,
    ): Map<String, Any?> = linkedMapOf(
        "schema_version" to "2.0",
        "run_id" to plan.runId,
        "dataset_id" to plan.datasetId,
        "model_alias" to plan.modelAlias,
        "clock_source" to "android_elapsed_realtime_nanos",
        "trusted_runner_status" to "phase_b_external_observer_required",
        "pcm_set_sha256" to pcmSetSha256,
        "artifact_measurements" to artifacts,
        "artifact_set_sha256" to artifactSetSha256,
        "clip_measurements" to measurements.map { measurement ->
            linkedMapOf<String, Any?>(
                "clip_id" to measurement.clipId,
                "wav_file_sha256_before" to measurement.wavSha256Before,
                "wav_file_sha256_after" to measurement.wavSha256After,
                "pcm_payload_sha256_before" to measurement.pcmPayloadSha256Before,
                "pcm_payload_sha256_after" to measurement.pcmPayloadSha256After,
                "pcm_payload_bytes" to measurement.pcmPayloadBytes,
                "st_dev_before" to measurement.identityBefore.device,
                "st_dev_after" to measurement.identityAfter.device,
                "st_ino_before" to measurement.identityBefore.inode,
                "st_ino_after" to measurement.identityAfter.inode,
                "mode_before" to measurement.identityBefore.mode,
                "mode_after" to measurement.identityAfter.mode,
                "nlink_before" to measurement.identityBefore.linkCount,
                "nlink_after" to measurement.identityAfter.linkCount,
                "monotonic_stop_ns" to measurement.monotonicStopNanos,
                "monotonic_final_ns" to measurement.monotonicFinalNanos,
                "stop_to_final_ns" to measurement.stopToFinalNanos,
                "run_state" to measurement.runState,
                "status" to measurement.status.name.lowercase(),
                "error_code" to measurement.errorCode,
            )
        },
        "runtime" to linkedMapOf(
            "total_resource_bytes" to runtime.totalResourceBytes,
            "cold_latency_ns" to runtime.coldLatencyNanos,
            "warm_latency_ns" to runtime.warmLatencyNanos,
            "peak_rss_bytes" to runtime.peakRssBytes,
            "peak_pss_bytes" to runtime.peakPssBytes,
            "oom_count" to runtime.oomCount,
            "crash_count" to runtime.crashCount,
            "success_count" to runtime.successCount,
        ),
    )
}

object CanonicalJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> finiteNumber(value.toDouble())
        is Double -> finiteNumber(value)
        is String -> encodeString(value)
        is Map<*, *> -> {
            val entries = value.entries.map { entry ->
                val key = entry.key as? String
                    ?: throw BenchmarkContractException("canonical JSON map key must be a string")
                key to entry.value
            }.sortedBy { it.first }
            entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
                "${encodeString(it.first)}:${encode(it.second)}"
            }
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
            encode(it)
        }
        else -> throw BenchmarkContractException("value is not canonical JSON")
    }

    fun sha256(value: Any?): String = Hashing.sha256(encode(value).encodeToByteArray())

    private fun finiteNumber(value: Double): String {
        if (!value.isFinite()) {
            throw BenchmarkContractException("canonical JSON number must be finite")
        }
        return value.toString()
    }

    private fun encodeString(value: String): String {
        val output = StringBuilder("\"")
        value.forEach { character ->
            when (character) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\b' -> output.append("\\b")
                '\u000c' -> output.append("\\f")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        output.append("\\u%04x".format(character.code))
                    } else {
                        output.append(character)
                    }
                }
            }
        }
        return output.append('"').toString()
    }
}
