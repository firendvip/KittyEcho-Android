package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.CanonicalJson
import com.wordtaker.keyboard.asrbenchmark.core.DecoderClip
import com.wordtaker.keyboard.asrbenchmark.core.DecoderPlan
import com.wordtaker.keyboard.asrbenchmark.core.Hashing
import com.wordtaker.keyboard.asrbenchmark.core.StrictCanonicalJson
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Development-emulator-only MInDS-14 batch surface. */
enum class DevelopmentBatchCandidate {
    PARAFORMER,
    ZIPFORMER,
}

data class DevelopmentBatchInstrumentationCommand(
    val candidate: DevelopmentBatchCandidate,
    val runId: String,
    val modelAlias: String,
    val decoderPlanPath: String,
    val pcmRootPath: String,
    val expectedSnapshotFingerprintSha256: String,
    val expectedDecoderPlanSha256: String,
    val modelPathsByRole: Map<String, String>,
) {
    companion object {
        fun parse(
            entries: List<Pair<String, String>>,
        ): DevelopmentBatchInstrumentationCommand {
            val modeEntries = entries.filter { it.first == MODE }
            if (modeEntries.size != 1) {
                invalid("development batch mode is invalid")
            }
            val candidate = when (modeEntries.single().second) {
                PARAFORMER_MODE -> DevelopmentBatchCandidate.PARAFORMER
                ZIPFORMER_MODE -> DevelopmentBatchCandidate.ZIPFORMER
                else -> invalid("development batch candidate is invalid")
            }
            val requiredKeys = when (candidate) {
                DevelopmentBatchCandidate.PARAFORMER -> PARAFORMER_KEYS
                DevelopmentBatchCandidate.ZIPFORMER -> ZIPFORMER_KEYS
            }
            if (
                entries.size != requiredKeys.size ||
                entries.map { it.first }.toSet() != requiredKeys ||
                entries.any {
                    it.second.isBlank() ||
                        it.second.length > MAX_ARGUMENT_LENGTH ||
                        it.second.indexOf('\u0000') >= 0
                }
            ) {
                invalid("development batch arguments must be exact and unique")
            }
            val values = entries.toMap()
            val runId = values.getValue("run_id")
            val modelAlias = values.getValue("model_alias")
            if (!RUN_ID.matches(runId) || !MODEL_ALIAS.matches(modelAlias)) {
                invalid("development batch identity is invalid")
            }
            val expectedSnapshot = values.getValue(
                "expected_snapshot_fingerprint_sha256",
            )
            val expectedPlan = values.getValue("expected_decoder_plan_sha256")
            Hashing.requireSha256(
                expectedSnapshot,
                "expected development snapshot fingerprint",
            )
            Hashing.requireSha256(
                expectedPlan,
                "expected development decoder plan sha256",
            )
            val pathKeys = requiredKeys - setOf(
                MODE,
                "run_id",
                "model_alias",
                "expected_snapshot_fingerprint_sha256",
                "expected_decoder_plan_sha256",
            )
            if (pathKeys.any { !File(values.getValue(it)).isAbsolute }) {
                invalid("development batch paths must be absolute")
            }
            val modelPaths = when (candidate) {
                DevelopmentBatchCandidate.PARAFORMER -> linkedMapOf(
                    "weights" to values.getValue("model_path"),
                    "tokenizer" to values.getValue("tokens_path"),
                )
                DevelopmentBatchCandidate.ZIPFORMER -> linkedMapOf(
                    "encoder" to values.getValue("encoder_path"),
                    "decoder" to values.getValue("decoder_path"),
                    "joiner" to values.getValue("joiner_path"),
                    "tokenizer" to values.getValue("tokens_path"),
                )
            }
            return DevelopmentBatchInstrumentationCommand(
                candidate = candidate,
                runId = runId,
                modelAlias = modelAlias,
                decoderPlanPath = values.getValue("decoder_plan_path"),
                pcmRootPath = values.getValue("pcm_root_path"),
                expectedSnapshotFingerprintSha256 = expectedSnapshot,
                expectedDecoderPlanSha256 = expectedPlan,
                modelPathsByRole = modelPaths,
            )
        }

        private const val MODE = "mode"
        private const val PARAFORMER_MODE = "paraformer-dev-batch"
        private const val ZIPFORMER_MODE = "zipformer-dev-batch"
        private const val MAX_ARGUMENT_LENGTH = 4_096
        private val RUN_ID = Regex("^run_[0-9a-f]{12}$")
        private val MODEL_ALIAS = Regex("^M[0-9]{3}$")
        private val COMMON_KEYS = setOf(
            MODE,
            "run_id",
            "model_alias",
            "tokens_path",
            "decoder_plan_path",
            "pcm_root_path",
            "expected_snapshot_fingerprint_sha256",
            "expected_decoder_plan_sha256",
        )
        private val PARAFORMER_KEYS = COMMON_KEYS + "model_path"
        private val ZIPFORMER_KEYS = COMMON_KEYS + setOf(
            "encoder_path",
            "decoder_path",
            "joiner_path",
        )
    }
}

data class DevelopmentDecoderPlanEvidence(
    val plan: DecoderPlan,
    val snapshotFingerprintSha256: String,
    val canonicalSha256: String,
    val pcmBasenamesByClipId: Map<String, String>,
)

object DevelopmentDecoderPlanContract {
    fun parse(
        rawPlanBytes: ByteArray,
        runId: String,
        modelAlias: String,
    ): DevelopmentDecoderPlanEvidence {
        val decoded = StrictCanonicalJson.decodeObject(rawPlanBytes)
        val document = decoded.document
        requireExactKeys(document, TOP_LEVEL_KEYS, "development decoder plan")
        if (
            document["schema_version"] != SCHEMA_VERSION ||
            document["pcm_contract_id"] != PCM_CONTRACT_ID ||
            document["input_transform_id"] != INPUT_TRANSFORM_ID
        ) {
            invalid("development decoder plan contracts are invalid")
        }
        val datasetId = requireString(document["dataset_id"], "dataset_id")
        val snapshotSha256 = requireString(
            document["snapshot_fingerprint_sha256"],
            "snapshot fingerprint",
        )
        Hashing.requireSha256(snapshotSha256, "snapshot fingerprint")
        requireExactObject(
            document["target"],
            linkedMapOf(
                "logical_device" to "wt_real",
                "physical_device" to false,
                "platform" to "android",
            ),
            "development decoder target",
        )
        requireExactObject(
            document["eligibility"],
            linkedMapOf(
                "development_only" to true,
                "emulator_only" to true,
                "formal_eligible" to false,
                "product_decision_eligible" to false,
                "production_eligible" to false,
            ),
            "development decoder eligibility",
        )
        val rawClips = document["clips"] as? List<*>
            ?: invalid("development decoder clips must be an array")
        if (rawClips.size != EXPECTED_CLIP_COUNT) {
            invalid("development decoder plan must contain exactly 96 clips")
        }
        val basenames = linkedMapOf<String, String>()
        val clips = rawClips.mapIndexed { index, rawClip ->
            val clip = stringKeyedObject(
                rawClip,
                "development decoder clip[$index]",
            )
            requireExactKeys(clip, CLIP_KEYS, "development decoder clip[$index]")
            val clipId = requireString(clip["clip_id"], "clip_id")
            if (!CLIP_ID.matches(clipId) || basenames.containsKey(clipId)) {
                invalid("development decoder clip_id is invalid or duplicated")
            }
            val hostPath = requireString(clip["audio_path"], "audio_path")
            val hostFile = File(hostPath).toPath()
            val expectedBasename = "$clipId.wav"
            if (
                !hostFile.isAbsolute ||
                hostFile.normalize() != hostFile ||
                hostPath.indexOf('\\') >= 0 ||
                hostPath.indexOf('\u0000') >= 0 ||
                hostFile.fileName?.toString() != expectedBasename
            ) {
                invalid("development decoder audio path is invalid")
            }
            if (expectedBasename in basenames.values) {
                invalid("development decoder PCM basename is duplicated")
            }
            basenames[clipId] = expectedBasename
            DecoderClip(
                clipId = clipId,
                pathToken = "pcm-${index.toString().padStart(4, '0')}",
                wavSha256 = requireString(
                    clip["wav_file_sha256"],
                    "WAV sha256",
                ),
                pcmPayloadSha256 = requireString(
                    clip["pcm_payload_sha256"],
                    "PCM payload sha256",
                ),
                pcmPayloadBytes = requirePositiveLong(
                    clip["pcm_payload_bytes"],
                    "PCM payload bytes",
                ),
            )
        }
        val semanticSha256 = CanonicalJson.sha256(document)
        return DevelopmentDecoderPlanEvidence(
            plan = DecoderPlan(
                planId = "plan_${semanticSha256.take(12)}",
                runId = runId,
                datasetId = datasetId,
                modelAlias = modelAlias,
                clips = clips,
            ),
            snapshotFingerprintSha256 = snapshotSha256,
            canonicalSha256 = decoded.canonicalSha256,
            pcmBasenamesByClipId = basenames,
        )
    }

    fun requireExpectedCommitments(
        evidence: DevelopmentDecoderPlanEvidence,
        expectedSnapshotFingerprintSha256: String,
        expectedDecoderPlanSha256: String,
    ) {
        Hashing.requireSha256(
            expectedSnapshotFingerprintSha256,
            "expected development snapshot fingerprint",
        )
        Hashing.requireSha256(
            expectedDecoderPlanSha256,
            "expected development decoder plan sha256",
        )
        if (
            evidence.snapshotFingerprintSha256 !=
            expectedSnapshotFingerprintSha256 ||
            evidence.canonicalSha256 != expectedDecoderPlanSha256
        ) {
            invalid("development decoder plan differs from host commitments")
        }
    }

    private const val SCHEMA_VERSION =
        "development-emulator-decoder-plan-v1"
    private const val PCM_CONTRACT_ID = "pcm16k-mono-s16le-v1"
    private const val INPUT_TRANSFORM_ID = "canonical-pcm-direct-v1"
    private const val EXPECTED_CLIP_COUNT = 96
    private val CLIP_ID = Regex("^clip_[0-9a-f]{12}$")
    private val TOP_LEVEL_KEYS = setOf(
        "schema_version",
        "dataset_id",
        "snapshot_fingerprint_sha256",
        "pcm_contract_id",
        "input_transform_id",
        "target",
        "eligibility",
        "clips",
    )
    private val CLIP_KEYS = setOf(
        "clip_id",
        "audio_path",
        "wav_file_sha256",
        "pcm_payload_sha256",
        "pcm_payload_bytes",
    )
}

data class DevelopmentBatchPrivateInputs(
    val decoderPlan: Path,
    val pcmRoot: Path,
    val pcmPathsByToken: Map<String, Path>,
)

object DevelopmentBatchPrivateInputContract {
    fun requireStagingLayout(
        trustedRoot: Path,
        decoderPlanPath: Path,
        pcmRoot: Path,
    ) {
        if (
            !trustedRoot.isAbsolute ||
            !decoderPlanPath.isAbsolute ||
            !pcmRoot.isAbsolute ||
            trustedRoot.normalize() != trustedRoot ||
            decoderPlanPath.normalize() != decoderPlanPath ||
            pcmRoot.normalize() != pcmRoot ||
            decoderPlanPath != trustedRoot.resolve(PLAN_NAME) ||
            pcmRoot != trustedRoot.resolve(PCM_DIRECTORY) ||
            Files.isSymbolicLink(trustedRoot.parent) ||
            !Files.isDirectory(
                trustedRoot.parent,
                LinkOption.NOFOLLOW_LINKS,
            ) ||
            Files.isSymbolicLink(trustedRoot) ||
            !Files.isDirectory(trustedRoot, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(decoderPlanPath) ||
            !Files.isRegularFile(decoderPlanPath, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(pcmRoot) ||
            !Files.isDirectory(pcmRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            invalid("development batch private root layout is invalid")
        }
    }

    fun bind(
        trustedRoot: Path,
        decoderPlanPath: Path,
        pcmRoot: Path,
        evidence: DevelopmentDecoderPlanEvidence,
    ): DevelopmentBatchPrivateInputs {
        requireStagingLayout(trustedRoot, decoderPlanPath, pcmRoot)
        val expectedNames = evidence.pcmBasenamesByClipId.values.toSet()
        val actualNames = try {
            linkedSetOf<String>().also { names ->
                Files.newDirectoryStream(pcmRoot).use { children ->
                    children.forEach { names += it.fileName.toString() }
                }
            }
        } catch (_: Exception) {
            invalid("development batch PCM directory cannot be inspected safely")
        }
        if (actualNames != expectedNames) {
            invalid("development batch PCM children differ from the frozen plan")
        }
        val bindings = linkedMapOf<String, Path>()
        evidence.plan.clips.forEach { clip ->
            val basename = evidence.pcmBasenamesByClipId[clip.clipId]
                ?: invalid("development batch PCM basename is missing")
            val path = pcmRoot.resolve(basename)
            if (
                path.normalize() != path ||
                path.parent != pcmRoot ||
                Files.isSymbolicLink(path) ||
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            ) {
                invalid("development batch PCM must be a direct regular file")
            }
            bindings[clip.pathToken] = path
        }
        if (bindings.values.toSet().size != evidence.plan.clips.size) {
            invalid("development batch PCM bindings are duplicated")
        }
        return DevelopmentBatchPrivateInputs(
            decoderPlan = decoderPlanPath,
            pcmRoot = pcmRoot,
            pcmPathsByToken = bindings,
        )
    }

    private const val PLAN_NAME = "decoder-plan.dev.json"
    private const val PCM_DIRECTORY = "pcm"
}

private fun requireExactObject(
    value: Any?,
    expected: Map<String, Any?>,
    context: String,
) {
    val actual = stringKeyedObject(value, context)
    if (actual != expected || actual.keys != expected.keys) {
        invalid("$context fields are invalid")
    }
}

private fun stringKeyedObject(value: Any?, context: String): Map<String, Any?> {
    val raw = value as? Map<*, *> ?: invalid("$context must be an object")
    return raw.entries.associate { entry ->
        val key = entry.key as? String ?: invalid("$context key is invalid")
        key to entry.value
    }
}

private fun requireExactKeys(
    value: Map<String, Any?>,
    expected: Set<String>,
    context: String,
) {
    if (value.keys != expected) {
        invalid("$context fields are invalid")
    }
}

private fun requireString(value: Any?, context: String): String =
    (value as? String)?.takeIf { it.isNotEmpty() }
        ?: invalid("$context must be a non-empty string")

private fun requirePositiveLong(value: Any?, context: String): Long = when (value) {
    is Long -> value
    is Int -> value.toLong()
    else -> invalid("$context must be a positive integer")
}.also {
    if (it <= 0L) {
        invalid("$context must be a positive integer")
    }
}

private fun invalid(message: String): Nothing =
    throw BenchmarkContractException(message)
