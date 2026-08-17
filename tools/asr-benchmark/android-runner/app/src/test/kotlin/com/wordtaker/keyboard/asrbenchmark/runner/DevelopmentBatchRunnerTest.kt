package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.ArtifactFile
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.DecoderClip
import com.wordtaker.keyboard.asrbenchmark.core.DecoderPlan
import com.wordtaker.keyboard.asrbenchmark.core.DecoderResult
import com.wordtaker.keyboard.asrbenchmark.core.DecoderStatus
import com.wordtaker.keyboard.asrbenchmark.core.FileIdentity
import com.wordtaker.keyboard.asrbenchmark.core.Hashing
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import com.wordtaker.keyboard.asrbenchmark.core.MonotonicClock
import com.wordtaker.keyboard.asrbenchmark.core.RunnerPreparedDecoderAdapter
import com.wordtaker.keyboard.asrbenchmark.core.SecureFileAccess
import com.wordtaker.keyboard.asrbenchmark.core.SyntheticWav
import com.wordtaker.keyboard.asrbenchmark.core.TrustedContentAddressedOutputStore
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DevelopmentBatchRunnerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun preparesOnceDecodesEveryClipOnceAndPreservesRawFirstLayerOutput() {
        val fixture = fixture()
        val adapter = RecordingBatchAdapter()

        val result = DevelopmentBatchRunner(
            secureFiles = fixture.files,
            clock = IncrementingClock(),
        ).run(
            evidence = fixture.evidence,
            artifacts = listOf(ArtifactFile("weights", "weights")),
            adapter = adapter,
        )

        assertEquals(1, adapter.prepareCount)
        assertEquals(96, adapter.decodeCount)
        assertEquals(2, fixture.files.readCount("weights"))
        fixture.evidence.plan.clips.forEach { clip ->
            assertEquals(2, fixture.files.readCount(clip.pathToken))
        }
        assertEquals(96L, result.successCount)
        assertEquals(0L, result.failureCount)
        assertEquals(
            setOf(
                "schema_version",
                "run_id",
                "dataset_id",
                "model_alias",
                "decoder_contract_id",
                "pcm_contract_id",
                "input_transform_id",
                "snapshot_fingerprint_sha256",
                "development_decoder_plan_sha256",
                "reference_accessed",
                "predictions",
                "eligibility",
            ),
            result.document.keys,
        )
        @Suppress("UNCHECKED_CAST")
        val predictions = result.document["predictions"] as List<Map<String, Any?>>
        assertEquals(96, predictions.size)
        assertEquals(" raw-1 ", predictions.first()["hypothesis"])
        assertEquals(" raw-96 ", predictions.last()["hypothesis"])
        assertEquals(
            listOf(
                "clip_000000000000",
                "clip_000000000001",
            ),
            predictions.take(2).map { it["clip_id"] },
        )
        assertTrue(predictions.all { it["status"] == "ok" })
        assertTrue(predictions.all { (it["stop_to_final_ns"] as Long) >= 0L })
        @Suppress("UNCHECKED_CAST")
        val eligibility = result.document["eligibility"] as Map<String, Any?>
        assertEquals(
            mapOf(
                "development_only" to true,
                "emulator_only" to true,
                "formal_eligible" to false,
                "product_decision_eligible" to false,
                "production_eligible" to false,
            ),
            eligibility,
        )
        assertFalse(deepStrings(result.document).any { it.startsWith("/host/") })
        assertFalse(result.document.toString().contains("reference_text"))
    }

    @Test
    fun preparationFailureAbortsWithoutFabricatingClipPredictions() {
        val fixture = fixture()
        val adapter = RecordingBatchAdapter(
            prepareFailure = IllegalStateException("/private/model path"),
        )

        val error = assertThrows(BenchmarkContractException::class.java) {
            DevelopmentBatchRunner(
                secureFiles = fixture.files,
                clock = IncrementingClock(),
            ).run(
                evidence = fixture.evidence,
                artifacts = listOf(ArtifactFile("weights", "weights")),
                adapter = adapter,
            )
        }

        assertEquals(1, adapter.prepareCount)
        assertEquals(0, adapter.decodeCount)
        assertFalse(error.message.orEmpty().contains("/private/"))
    }

    @Test
    fun privateContentAddressedOutputIs0600AndBundleContainsOnlySafeSummary() {
        val fixture = fixture()
        val result = DevelopmentBatchRunner(
            secureFiles = fixture.files,
            clock = IncrementingClock(),
        ).run(
            evidence = fixture.evidence,
            artifacts = listOf(ArtifactFile("weights", "weights")),
            adapter = RecordingBatchAdapter(),
        )
        val privateRoot = temporary.newFolder("private-output").toPath()
        Files.setPosixFilePermissions(
            privateRoot,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val stored = TrustedContentAddressedOutputStore.openDevelopment(
            privateRoot,
            RUN_ID,
        ).use { output ->
            output.publish("paraformer-dev-batch", result.document)
        }
        val outputPath = privateRoot
            .resolve(TrustedContentAddressedOutputStore.ROOT_NAME)
            .resolve(RUN_ID)
            .resolve(stored.fileName)

        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
            Files.getPosixFilePermissions(outputPath),
        )
        assertEquals(stored.sha256, Hashing.sha256(Files.readAllBytes(outputPath)))
        assertTrue(
            Files.readAllBytes(outputPath).decodeToString().contains(" raw-1 "),
        )

        val bundle = DevelopmentBatchBundleContract.fields(stored, result)
        assertEquals(
            setOf(
                "batch_file",
                "batch_sha256",
                "success_count",
                "failure_count",
                "formal_eligible",
                "product_decision_eligible",
                "production_eligible",
            ),
            bundle.keys,
        )
        assertEquals(false, bundle["formal_eligible"])
        assertEquals(false, bundle["product_decision_eligible"])
        assertEquals(false, bundle["production_eligible"])
        assertFalse(deepStrings(bundle).any { it.contains(" raw-") })
        assertFalse(deepStrings(bundle).any { it.contains(privateRoot.toString()) })
    }

    private fun fixture(): BatchFixture {
        val pcmBytes = SyntheticWav.pcm16Mono(16_000, 160)
        val measuredFiles = linkedMapOf<String, MeasuredFile>()
        val basenames = linkedMapOf<String, String>()
        val clips = List(96) { index ->
            val clipId = "clip_${index.toString(16).padStart(12, '0')}"
            val pathToken = "pcm-${index.toString().padStart(4, '0')}"
            val measured = measured(pathToken, pcmBytes, index + 100L, true)
            val pcmFacts = requireNotNull(measured.pcm)
            measuredFiles[pathToken] = measured
            basenames[clipId] = "$clipId.wav"
            DecoderClip(
                clipId = clipId,
                pathToken = pathToken,
                wavSha256 = measured.sha256,
                pcmPayloadSha256 = pcmFacts.payloadSha256,
                pcmPayloadBytes = pcmFacts.payloadBytes,
            )
        }
        measuredFiles["weights"] = measured(
            "weights",
            byteArrayOf(1, 2, 3),
            1L,
            false,
        )
        return BatchFixture(
            evidence = DevelopmentDecoderPlanEvidence(
                plan = DecoderPlan(
                    planId = "plan_000000000001",
                    runId = RUN_ID,
                    datasetId = "dataset_2b92d245d55a",
                    modelAlias = "M001",
                    clips = clips,
                ),
                snapshotFingerprintSha256 = "f".repeat(64),
                canonicalSha256 = "e".repeat(64),
                pcmBasenamesByClipId = basenames,
            ),
            files = FakeBatchSecureFiles(measuredFiles),
        )
    }

    private fun measured(
        token: String,
        bytes: ByteArray,
        inode: Long,
        pcm: Boolean,
    ): MeasuredFile = MeasuredFile.fromBytes(
        pathToken = token,
        bytes = bytes,
        identity = FileIdentity(
            device = 1L,
            inode = inode,
            sizeBytes = bytes.size.toLong(),
            mode = 0x8000,
            linkCount = 1L,
        ),
        requireCanonicalPcm = pcm,
    )

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
    }
}

private data class BatchFixture(
    val evidence: DevelopmentDecoderPlanEvidence,
    val files: FakeBatchSecureFiles,
)

private class FakeBatchSecureFiles(
    private val files: Map<String, MeasuredFile>,
) : SecureFileAccess {
    private val reads = mutableMapOf<String, Int>()

    override fun read(pathToken: String, requireCanonicalPcm: Boolean): MeasuredFile {
        reads[pathToken] = (reads[pathToken] ?: 0) + 1
        val measured = files.getValue(pathToken)
        check((measured.pcm != null) == requireCanonicalPcm)
        return measured
    }

    fun readCount(pathToken: String): Int = reads[pathToken] ?: 0
}

private class IncrementingClock : MonotonicClock {
    private var next = 0L

    override fun nowNanos(): Long = next++
}

private class RecordingBatchAdapter(
    private val prepareFailure: Exception? = null,
) : RunnerPreparedDecoderAdapter {
    var prepareCount = 0
    var decodeCount = 0

    override fun prepare(artifactsByRole: Map<String, MeasuredFile>) {
        prepareCount += 1
        prepareFailure?.let { throw it }
    }

    override fun decode(canonicalPcmWav: ByteArray): DecoderResult {
        decodeCount += 1
        return DecoderResult(
            transcript = " raw-$decodeCount ",
            status = DecoderStatus.OK,
            errorCode = null,
        )
    }
}
