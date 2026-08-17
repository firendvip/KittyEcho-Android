package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.CanonicalJson
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DevelopmentDecoderPlanContractTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun acceptsOnlyTheFrozenDevelopmentEmulatorDev96Shape() {
        val evidence = DevelopmentDecoderPlanContract.parse(
            rawPlanBytes = encode(planDocument()),
            runId = RUN_ID,
            modelAlias = MODEL_ALIAS,
        )

        assertEquals(96, evidence.plan.clips.size)
        assertEquals(DATASET_ID, evidence.plan.datasetId)
        assertEquals(RUN_ID, evidence.plan.runId)
        assertEquals(MODEL_ALIAS, evidence.plan.modelAlias)
        assertEquals(SNAPSHOT_SHA256, evidence.snapshotFingerprintSha256)
        assertEquals(64, evidence.canonicalSha256.length)
        assertEquals(96, evidence.pcmBasenamesByClipId.values.toSet().size)
        assertTrue(
            evidence.pcmBasenamesByClipId.all { (clipId, basename) ->
                basename == "$clipId.wav"
            },
        )
        assertFalse(
            deepStrings(evidence.plan).any {
                it.startsWith("/host/")
            },
        )
    }

    @Test
    fun topLevelTargetAndEligibilityFieldsAreExactAndFailClosed() {
        val attacks = listOf<(MutableMap<String, Any?>) -> Unit>(
            { it["schema_version"] = "1.0" },
            { it["pcm_contract_id"] = "pcm48k" },
            { it["input_transform_id"] = "model-specific" },
            { it["dataset_id"] = "minds14" },
            { it["snapshot_fingerprint_sha256"] = "0" },
            { it["references"] = emptyList<Any>() },
            { it.remove("target") },
            {
                target(it)["logical_device"] = "physical-phone"
            },
            {
                target(it)["physical_device"] = true
            },
            {
                target(it)["platform"] = "desktop"
            },
            {
                eligibility(it)["development_only"] = false
            },
            {
                eligibility(it)["emulator_only"] = false
            },
            {
                eligibility(it)["formal_eligible"] = true
            },
            {
                eligibility(it)["product_decision_eligible"] = true
            },
            {
                eligibility(it)["production_eligible"] = true
            },
            {
                eligibility(it)["extra"] = false
            },
        )

        attacks.forEach { mutate ->
            val document = planDocument()
            mutate(document)
            assertThrows(BenchmarkContractException::class.java) {
                DevelopmentDecoderPlanContract.parse(
                    encode(document),
                    RUN_ID,
                    MODEL_ALIAS,
                )
            }
        }
    }

    @Test
    fun clipsMustBeExactlyNinetySixAnonymousUniqueAndAnswerFree() {
        val attacks = listOf<(MutableMap<String, Any?>) -> Unit>(
            { clips(it).removeAt(95) },
            { clips(it) += clip(96) },
            {
                clips(it)[1]["clip_id"] = clips(it)[0].getValue("clip_id")
            },
            {
                clips(it)[1]["audio_path"] =
                    clips(it)[0].getValue("audio_path")
            },
            { clips(it)[0]["clip_id"] = "speaker_alice" },
            { clips(it)[0]["audio_path"] = "relative/clip.wav" },
            { clips(it)[0]["audio_path"] = "/host/private/../escape.wav" },
            { clips(it)[0]["wav_file_sha256"] = "0" },
            { clips(it)[0]["pcm_payload_sha256"] = "A".repeat(64) },
            { clips(it)[0]["pcm_payload_bytes"] = -1L },
            { clips(it)[0]["reference"] = "private answer" },
            { clips(it)[0]["transcription"] = "private answer" },
            { clips(it)[0].remove("pcm_payload_bytes") },
        )

        attacks.forEach { mutate ->
            val document = planDocument()
            mutate(document)
            assertThrows(BenchmarkContractException::class.java) {
                DevelopmentDecoderPlanContract.parse(
                    encode(document),
                    RUN_ID,
                    MODEL_ALIAS,
                )
            }
        }
    }

    @Test
    fun strictCanonicalJsonRejectsDuplicateKeysAndNonCanonicalBytes() {
        val canonical = encode(planDocument())
        val duplicateSchema = canonical.decodeToString().replaceFirst(
            "{",
            "{\"schema_version\":\"development-emulator-decoder-plan-v1\",",
        ).encodeToByteArray()

        listOf(
            duplicateSchema,
            canonical + byteArrayOf(' '.code.toByte()),
        ).forEach { invalid ->
            assertThrows(BenchmarkContractException::class.java) {
                DevelopmentDecoderPlanContract.parse(
                    invalid,
                    RUN_ID,
                    MODEL_ALIAS,
                )
            }
        }
    }

    @Test
    fun hostFrozenSnapshotAndPlanCommitmentsRejectWholePlanReplacement() {
        val originalDocument = planDocument()
        val original = DevelopmentDecoderPlanContract.parse(
            encode(originalDocument),
            RUN_ID,
            MODEL_ALIAS,
        )
        DevelopmentDecoderPlanContract.requireExpectedCommitments(
            original,
            expectedSnapshotFingerprintSha256 = SNAPSHOT_SHA256,
            expectedDecoderPlanSha256 = original.canonicalSha256,
        )

        listOf(
            "0".repeat(64) to original.canonicalSha256,
            SNAPSHOT_SHA256 to "0".repeat(64),
        ).forEach { (snapshot, plan) ->
            assertThrows(BenchmarkContractException::class.java) {
                DevelopmentDecoderPlanContract.requireExpectedCommitments(
                    original,
                    expectedSnapshotFingerprintSha256 = snapshot,
                    expectedDecoderPlanSha256 = plan,
                )
            }
        }

        val replacementDocument = planDocument().apply {
            clips(this)[0]["pcm_payload_bytes"] = 64_000L
        }
        val replacement = DevelopmentDecoderPlanContract.parse(
            encode(replacementDocument),
            RUN_ID,
            MODEL_ALIAS,
        )
        assertThrows(BenchmarkContractException::class.java) {
            DevelopmentDecoderPlanContract.requireExpectedCommitments(
                replacement,
                expectedSnapshotFingerprintSha256 = SNAPSHOT_SHA256,
                expectedDecoderPlanSha256 = original.canonicalSha256,
            )
        }
    }

    @Test
    fun privateLayoutUsesOnlyExactDirectBasenamesAndRejectsEscapeOrSymlink() {
        val evidence = DevelopmentDecoderPlanContract.parse(
            encode(planDocument()),
            RUN_ID,
            MODEL_ALIAS,
        )
        val trustedRoot = temporary.newFolder("batch-input").toPath()
        val planPath = Files.createFile(
            trustedRoot.resolve("decoder-plan.dev.json"),
        )
        val pcmRoot = Files.createDirectory(trustedRoot.resolve("pcm"))
        evidence.pcmBasenamesByClipId.values.forEach { basename ->
            Files.createFile(pcmRoot.resolve(basename))
        }

        val inputs = DevelopmentBatchPrivateInputContract.bind(
            trustedRoot = trustedRoot,
            decoderPlanPath = planPath,
            pcmRoot = pcmRoot,
            evidence = evidence,
        )

        assertEquals(96, inputs.pcmPathsByToken.size)
        assertTrue(inputs.pcmPathsByToken.values.all { it.parent == pcmRoot })
        assertFalse(inputs.pcmPathsByToken.values.any { it.toString().contains("/host/") })

        assertThrows(BenchmarkContractException::class.java) {
            DevelopmentBatchPrivateInputContract.bind(
                trustedRoot,
                planPath,
                temporary.newFolder("escape").toPath(),
                evidence,
            )
        }

        val first = inputs.pcmPathsByToken.values.first()
        val outside = Files.createFile(temporary.root.toPath().resolve("outside.wav"))
        Files.delete(first)
        Files.createSymbolicLink(first, outside)
        assertThrows(BenchmarkContractException::class.java) {
            DevelopmentBatchPrivateInputContract.bind(
                trustedRoot,
                planPath,
                pcmRoot,
                evidence,
            )
        }
    }

    @Test
    fun privateLayoutRejectsMissingAndAdditionalPcmChildren() {
        val evidence = DevelopmentDecoderPlanContract.parse(
            encode(planDocument()),
            RUN_ID,
            MODEL_ALIAS,
        )
        val trustedRoot = temporary.newFolder("exact-input").toPath()
        val planPath = Files.createFile(
            trustedRoot.resolve("decoder-plan.dev.json"),
        )
        val pcmRoot = Files.createDirectory(trustedRoot.resolve("pcm"))
        val expected = evidence.pcmBasenamesByClipId.values.toList()
        expected.dropLast(1).forEach { Files.createFile(pcmRoot.resolve(it)) }

        assertThrows(BenchmarkContractException::class.java) {
            DevelopmentBatchPrivateInputContract.bind(
                trustedRoot,
                planPath,
                pcmRoot,
                evidence,
            )
        }

        Files.createFile(pcmRoot.resolve(expected.last()))
        Files.createFile(pcmRoot.resolve("unexpected.wav"))
        assertThrows(BenchmarkContractException::class.java) {
            DevelopmentBatchPrivateInputContract.bind(
                trustedRoot,
                planPath,
                pcmRoot,
                evidence,
            )
        }
    }

    private fun planDocument(): MutableMap<String, Any?> = linkedMapOf(
        "schema_version" to "development-emulator-decoder-plan-v1",
        "dataset_id" to DATASET_ID,
        "snapshot_fingerprint_sha256" to SNAPSHOT_SHA256,
        "pcm_contract_id" to "pcm16k-mono-s16le-v1",
        "input_transform_id" to "canonical-pcm-direct-v1",
        "target" to linkedMapOf<String, Any?>(
            "logical_device" to "wt_real",
            "physical_device" to false,
            "platform" to "android",
        ),
        "eligibility" to linkedMapOf<String, Any?>(
            "development_only" to true,
            "emulator_only" to true,
            "formal_eligible" to false,
            "product_decision_eligible" to false,
            "production_eligible" to false,
        ),
        "clips" to MutableList(96, ::clip),
    )

    private fun clip(index: Int): MutableMap<String, Any?> {
        val clipId = "clip_${index.toString(16).padStart(12, '0')}"
        return linkedMapOf(
            "clip_id" to clipId,
            "audio_path" to "/host/frozen/$clipId.wav",
            "wav_file_sha256" to (index + 1).toString(16).padStart(64, '0'),
            "pcm_payload_sha256" to (index + 101).toString(16).padStart(64, '0'),
            "pcm_payload_bytes" to 32_000L + index,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun target(document: MutableMap<String, Any?>) =
        document.getValue("target") as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun eligibility(document: MutableMap<String, Any?>) =
        document.getValue("eligibility") as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun clips(document: MutableMap<String, Any?>) =
        document.getValue("clips") as MutableList<MutableMap<String, Any?>>

    private fun encode(document: Map<String, Any?>): ByteArray =
        (CanonicalJson.encode(document) + "\n").encodeToByteArray()

    private fun deepStrings(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is Map<*, *> -> value.entries.flatMap {
            deepStrings(it.key) + deepStrings(it.value)
        }
        is Iterable<*> -> value.flatMap(::deepStrings)
        else -> emptyList()
    }

    companion object {
        private const val RUN_ID = "run_000000000001"
        private const val MODEL_ALIAS = "M001"
        private const val DATASET_ID = "dataset_2b92d245d55a"
        private val SNAPSHOT_SHA256 = "f".repeat(64)
    }
}
