package com.wordtaker.keyboard.asrbenchmark.core

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedBenchmarkRunnerTest {
    private val pcmBytes = SyntheticWav.pcm16Mono(sampleRate = 16_000, frameCount = 160)
    private val pcmMeasurement = MeasuredFile.fromBytes(
        pathToken = "clip-a",
        bytes = pcmBytes,
        identity = FileIdentity(
            device = 1,
            inode = 2,
            sizeBytes = pcmBytes.size.toLong(),
            mode = 0x81A0,
            linkCount = 1,
        ),
        requireCanonicalPcm = true,
    )
    private val artifactBytes = "synthetic-model".encodeToByteArray()
    private val artifactMeasurement = MeasuredFile.fromBytes(
        pathToken = "weights",
        bytes = artifactBytes,
        identity = FileIdentity(
            device = 1,
            inode = 3,
            sizeBytes = artifactBytes.size.toLong(),
            mode = 0x81A0,
            linkCount = 1,
        ),
        requireCanonicalPcm = false,
    )

    @Test
    fun `runner owns canonical pcm ordering timing and resource measurements`() {
        val files = FakeSecureFiles(
            mutableMapOf(
                "clip-a" to pcmMeasurement,
                "weights" to artifactMeasurement,
            ),
        )
        val clock = SequenceClock(1_000_000_000L, 1_125_000_000L)
        val adapter = FabricatingAdapter()
        val startedProgress = mutableListOf<String>()
        val verifiedProgress = mutableListOf<Pair<String, DecoderStatus>>()
        val runner = TrustedBenchmarkRunner(
            secureFiles = files,
            clock = clock,
            resourceProbe = SequenceResourceProbe(
                ResourceSample(rssBytes = 10, pssBytes = 9, thermalStatus = 0),
                ResourceSample(rssBytes = 20, pssBytes = 18, thermalStatus = 1),
            ),
        )

        val result = runner.decode(
            plan = DecoderPlan(
                planId = "plan_0123456789ab",
                runId = "run_0123456789ab",
                datasetId = "dataset_0123456789ab",
                modelAlias = "M001",
                clips = listOf(
                    DecoderClip(
                        clipId = "clip_0123456789ab",
                        pathToken = "clip-a",
                        wavSha256 = pcmMeasurement.sha256,
                        pcmPayloadSha256 = pcmMeasurement.pcm!!.payloadSha256,
                        pcmPayloadBytes = requireNotNull(pcmMeasurement.pcm)
                            .payloadBytes,
                    ),
                ),
            ),
            artifacts = listOf(ArtifactFile("weights", "weights")),
            adapter = adapter,
            progressObserver = object : DecodeProgressObserver {
                override fun onDecodeStarted(clipId: String) {
                    startedProgress += clipId
                }

                override fun onVerifiedDecode(
                    clipId: String,
                    status: DecoderStatus,
                ) {
                    verifiedProgress += clipId to status
                }
            },
        )

        assertEquals(listOf("clip_0123456789ab"), result.predictions.map { it.clipId })
        assertEquals(125_000_000L, result.measurements.single().stopToFinalNanos)
        assertEquals(20, result.runtime.peakRssBytes)
        assertEquals(18, result.runtime.peakPssBytes)
        assertEquals(artifactBytes.size.toLong(), result.runtime.totalResourceBytes)
        assertEquals(1, adapter.calls)
        assertEquals(
            listOf("clip_0123456789ab" to DecoderStatus.OK),
            verifiedProgress,
        )
        assertEquals(listOf("clip_0123456789ab"), startedProgress)
        assertFalse(result.engineeringDocument.toString().contains("adapterLatency"))
        assertFalse(result.engineeringDocument.toString().contains("adapterRss"))
    }

    @Test
    fun `runner prepares an artifact-bound adapter once before decoding clips`() {
        val secondPcm = MeasuredFile.fromBytes(
            pathToken = "clip-b",
            bytes = pcmBytes,
            identity = pcmMeasurement.identity.copy(inode = 4),
            requireCanonicalPcm = true,
        )
        val files = FakeSecureFiles(
            mutableMapOf(
                "clip-a" to pcmMeasurement,
                "clip-b" to secondPcm,
                "weights" to artifactMeasurement,
            ),
        )
        val adapter = object : RunnerPreparedDecoderAdapter {
            var prepareCalls = 0
            var decodeCalls = 0
            var prepared: Map<String, MeasuredFile>? = null

            override fun prepare(artifactsByRole: Map<String, MeasuredFile>) {
                prepareCalls += 1
                prepared = artifactsByRole
            }

            override fun decode(canonicalPcmWav: ByteArray): DecoderResult {
                decodeCalls += 1
                return DecoderResult("synthetic", DecoderStatus.OK, null)
            }
        }
        val plan = DecoderPlan(
            planId = "plan_0123456789ab",
            runId = "run_0123456789ab",
            datasetId = "dataset_0123456789ab",
            modelAlias = "M001",
            clips = listOf(
                DecoderClip(
                    clipId = "clip_0123456789ab",
                    pathToken = "clip-a",
                    wavSha256 = pcmMeasurement.sha256,
                    pcmPayloadSha256 = requireNotNull(pcmMeasurement.pcm)
                        .payloadSha256,
                    pcmPayloadBytes = requireNotNull(pcmMeasurement.pcm)
                        .payloadBytes,
                ),
                DecoderClip(
                    clipId = "clip_abcdefabcdef",
                    pathToken = "clip-b",
                    wavSha256 = secondPcm.sha256,
                    pcmPayloadSha256 = requireNotNull(secondPcm.pcm)
                        .payloadSha256,
                    pcmPayloadBytes = requireNotNull(secondPcm.pcm)
                        .payloadBytes,
                ),
            ),
        )

        TrustedBenchmarkRunner(
            secureFiles = files,
            clock = SequenceClock(1, 2, 3, 4),
            resourceProbe = SequenceResourceProbe(ResourceSample(1, 1, 0)),
        ).decode(
            plan,
            listOf(ArtifactFile("weights", "weights")),
            adapter,
        )

        assertEquals(1, adapter.prepareCalls)
        assertEquals(2, adapter.decodeCalls)
        assertEquals(
            artifactMeasurement.sha256,
            adapter.prepared?.get("weights")?.sha256,
        )
    }

    @Test
    fun `runner maps preparation failure to crash without invoking decoder`() {
        val adapter = object : RunnerPreparedDecoderAdapter {
            var decodeCalls = 0

            override fun prepare(artifactsByRole: Map<String, MeasuredFile>) {
                throw BenchmarkContractException("synthetic preparation failure")
            }

            override fun decode(canonicalPcmWav: ByteArray): DecoderResult {
                decodeCalls += 1
                return DecoderResult("must not run", DecoderStatus.OK, null)
            }
        }

        val result = runner().decode(
            singleClipPlan(),
            listOf(ArtifactFile("weights", "weights")),
            adapter,
        )

        assertEquals(0, adapter.decodeCalls)
        assertEquals(DecoderStatus.ERROR, result.predictions.single().status)
        assertEquals("crash", result.predictions.single().errorCode)
        assertEquals(1, result.runtime.crashCount)
    }

    @Test
    fun `duplicate clip and changed order are rejected before decoding`() {
        val clip = DecoderClip(
            clipId = "clip_0123456789ab",
            pathToken = "clip-a",
            wavSha256 = pcmMeasurement.sha256,
            pcmPayloadSha256 = pcmMeasurement.pcm!!.payloadSha256,
            pcmPayloadBytes = requireNotNull(pcmMeasurement.pcm).payloadBytes,
        )
        assertThrows(BenchmarkContractException::class.java) {
            DecoderPlan(
                planId = "plan_0123456789ab",
                runId = "run_0123456789ab",
                datasetId = "dataset_0123456789ab",
                modelAlias = "M001",
                clips = listOf(clip, clip),
            )
        }
    }

    @Test
    fun `benchmark contract value objects fail closed at every public boundary`() {
        val regularIdentity = FileIdentity(
            device = 1,
            inode = 2,
            sizeBytes = artifactBytes.size.toLong(),
            mode = 0x81A0,
            linkCount = 1,
        )
        assertThrows(BenchmarkContractException::class.java) {
            FileIdentity(-1, 2, 1, 0x81A0, 1)
        }
        assertThrows(BenchmarkContractException::class.java) {
            FileIdentity(1, 2, 1, 0x41A0, 1)
        }
        assertThrows(BenchmarkContractException::class.java) {
            FileIdentity(1, 2, 1, 0x81A0, 2)
        }
        assertThrows(BenchmarkContractException::class.java) {
            MeasuredFile(
                pathToken = "../artifact",
                contentBytes = artifactBytes,
                identity = regularIdentity,
                sha256 = Hashing.sha256(artifactBytes),
                pcm = null,
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            MeasuredFile(
                pathToken = "artifact",
                contentBytes = artifactBytes + 0,
                identity = regularIdentity,
                sha256 = Hashing.sha256(artifactBytes),
                pcm = null,
            )
        }
        val streamed = MeasuredFile.fromDigest(
            pathToken = "artifact",
            identity = regularIdentity,
            sha256 = Hashing.sha256(artifactBytes),
        )
        assertNull(streamed.retainedBytesOrNull())
        assertThrows(BenchmarkContractException::class.java) {
            streamed.bytes
        }

        fun decoderClip(
            clipId: String = "clip_0123456789ab",
            pathToken: String = "clip-a",
            payloadBytes: Long = pcmMeasurement.pcm!!.payloadBytes,
        ) = DecoderClip(
            clipId = clipId,
            pathToken = pathToken,
            wavSha256 = pcmMeasurement.sha256,
            pcmPayloadSha256 = pcmMeasurement.pcm!!.payloadSha256,
            pcmPayloadBytes = payloadBytes,
        )
        assertThrows(BenchmarkContractException::class.java) {
            decoderClip(clipId = "speaker-name")
        }
        assertThrows(BenchmarkContractException::class.java) {
            decoderClip(pathToken = "/tmp/clip.wav")
        }
        assertThrows(BenchmarkContractException::class.java) {
            decoderClip(payloadBytes = -1)
        }

        val validClip = decoderClip()
        listOf(
            { DecoderPlan("bad", "run_0123456789ab", "dataset_0123456789ab", "M001", listOf(validClip)) },
            { DecoderPlan("plan_0123456789ab", "bad", "dataset_0123456789ab", "M001", listOf(validClip)) },
            { DecoderPlan("plan_0123456789ab", "run_0123456789ab", "bad", "M001", listOf(validClip)) },
            { DecoderPlan("plan_0123456789ab", "run_0123456789ab", "dataset_0123456789ab", "model", listOf(validClip)) },
            { DecoderPlan("plan_0123456789ab", "run_0123456789ab", "dataset_0123456789ab", "M001", emptyList()) },
        ).forEach { factory ->
            assertThrows(BenchmarkContractException::class.java) {
                factory()
            }
        }
        assertThrows(BenchmarkContractException::class.java) {
            ArtifactFile("unknown", "artifact")
        }
        assertThrows(BenchmarkContractException::class.java) {
            ArtifactFile("weights", "../artifact")
        }
        assertThrows(BenchmarkContractException::class.java) {
            DecoderResult("x".repeat(100_001), DecoderStatus.OK, null)
        }
        assertThrows(BenchmarkContractException::class.java) {
            DecoderResult("", DecoderStatus.ERROR, "unknown")
        }
        assertThrows(BenchmarkContractException::class.java) {
            DecoderResult("", DecoderStatus.OK, "decode_error")
        }
        assertThrows(BenchmarkContractException::class.java) {
            ResourceSample(-1, 0, 0)
        }
        assertThrows(BenchmarkContractException::class.java) {
            ResourceSample(0, 0, 0, swapBytes = -1L)
        }
        assertThrows(BenchmarkContractException::class.java) {
            Hashing.requireSha256("not-a-digest", "test")
        }
        assertThrows(BenchmarkContractException::class.java) {
            SyntheticWav.pcm16Mono(sampleRate = 8_000, frameCount = 1)
        }

        var verified = false
        val observer = DecodeProgressObserver { _, _ -> verified = true }
        observer.onDecodeStarted("clip_0123456789ab")
        observer.onVerifiedDecode(
            "clip_0123456789ab",
            DecoderStatus.OK,
        )
        assertTrue(verified)
    }

    @Test
    fun `same content inode replacement during decode fails closed`() {
        val files = FakeSecureFiles(
            mutableMapOf(
                "clip-a" to pcmMeasurement,
                "weights" to artifactMeasurement,
            ),
        )
        val runner = TrustedBenchmarkRunner(
            secureFiles = files,
            clock = SequenceClock(1, 2),
            resourceProbe = SequenceResourceProbe(ResourceSample(1, 1, 0)),
        )
        val adapter = object : DecoderAdapter {
            override fun decode(canonicalPcmWav: ByteArray): DecoderResult {
                files.values["clip-a"] = pcmMeasurement.copy(
                    identity = pcmMeasurement.identity.copy(inode = 99),
                )
                return DecoderResult("ok", DecoderStatus.OK, null)
            }
        }
        var progressCallbacks = 0

        assertThrows(BenchmarkContractException::class.java) {
            runner.decode(
                singleClipPlan(),
                listOf(ArtifactFile("weights", "weights")),
                adapter,
                DecodeProgressObserver { _, _ -> progressCallbacks += 1 },
            )
        }
        assertEquals(0, progressCallbacks)
    }

    @Test
    fun `pcm or artifact content replacement during decode fails closed`() {
        for (target in listOf("clip-a", "weights")) {
            val files = FakeSecureFiles(
                mutableMapOf(
                    "clip-a" to pcmMeasurement,
                    "weights" to artifactMeasurement,
                ),
            )
            val runner = TrustedBenchmarkRunner(
                secureFiles = files,
                clock = SequenceClock(1, 2),
                resourceProbe = SequenceResourceProbe(ResourceSample(1, 1, 0)),
            )
            val adapter = object : DecoderAdapter {
                override fun decode(canonicalPcmWav: ByteArray): DecoderResult {
                    val original = requireNotNull(files.values[target])
                    val changed = original.bytes.copyOf()
                    changed[changed.lastIndex] = (changed.last() + 1).toByte()
                    files.values[target] = MeasuredFile.fromBytes(
                        target,
                        changed,
                        original.identity,
                        requireCanonicalPcm = target == "clip-a",
                    )
                    return DecoderResult("ok", DecoderStatus.OK, null)
                }
            }
            var progressCallbacks = 0

            assertThrows(BenchmarkContractException::class.java) {
                runner.decode(
                    singleClipPlan(),
                    listOf(ArtifactFile("weights", "weights")),
                    adapter,
                    DecodeProgressObserver { _, _ -> progressCallbacks += 1 },
                )
            }
            assertEquals(0, progressCallbacks)
        }
    }

    @Test
    fun `adapter receives a copy and cannot self report trusted metrics`() {
        val files = FakeSecureFiles(
            mutableMapOf(
                "clip-a" to pcmMeasurement,
                "weights" to artifactMeasurement,
            ),
        )
        val adapter = object : DecoderAdapter {
            val adapterLatency = 1L
            val adapterRss = 1L

            override fun decode(canonicalPcmWav: ByteArray): DecoderResult {
                canonicalPcmWav.fill(0)
                return DecoderResult("synthetic", DecoderStatus.OK, null)
            }
        }
        val result = TrustedBenchmarkRunner(
            secureFiles = files,
            clock = SequenceClock(10, 110),
            resourceProbe = SequenceResourceProbe(ResourceSample(50, 40, 0)),
        ).decode(
            singleClipPlan(),
            listOf(ArtifactFile("weights", "weights")),
            adapter,
        )

        assertEquals(100, result.measurements.single().stopToFinalNanos)
        assertEquals(50, result.runtime.peakRssBytes)
        assertEquals(pcmMeasurement.sha256, files.values["clip-a"]!!.sha256)
    }

    @Test
    fun `stability proof accepts bounded jitter and long decode progress`() {
        val proof = StabilityProofFixture.valid(
            intervalNanos = 5_000_000_000L,
            timestamps = listOf(0L, 5_000_000_001L, 10_100_000_000L),
            completedClips = listOf(0, 0, 2),
            activeProgress = listOf(0, 0, 2),
        )

        val validated = StabilityProofValidator.validate(
            proof,
            orderedClipIds = listOf("clip_0123456789ab"),
            policy = StabilityPolicy.development(minimumDurationNanos = 10_000_000_000L),
        )

        assertTrue(validated)
    }

    @Test
    fun `stability proof rejects idle heartbeat and incomplete loops`() {
        val idle = StabilityProofFixture.valid(
            intervalNanos = 5_000_000_000L,
            timestamps = listOf(0L, 5_000_000_000L, 10_000_000_000L),
            completedClips = listOf(0, 1, 2),
            activeProgress = listOf(0, 0, 0),
        )
        assertThrows(BenchmarkContractException::class.java) {
            StabilityProofValidator.validate(
                idle,
                listOf("clip_0123456789ab"),
                StabilityPolicy.development(10_000_000_000L),
            )
        }

        val oneLoop = idle.copy(
            samples = idle.samples.mapIndexed { index, sample ->
                sample.copy(
                    activeDecodeProgress = index + 1L,
                    completedLoops = if (index == 2) 1 else 0,
                    successfulLoops = if (index == 2) 1 else 0,
                    completedClipCount = if (index == 2) 1 else 0,
                )
            },
            loopProofs = idle.loopProofs.take(1),
            loopCount = 1,
            successfulLoopCount = 1,
            totalDecodedClipCount = 1,
        )
        assertThrows(BenchmarkContractException::class.java) {
            StabilityProofValidator.validate(
                oneLoop,
                listOf("clip_0123456789ab"),
                StabilityPolicy.development(10_000_000_000L),
            )
        }
    }

    @Test
    fun `stability progress advances only on runner verified decode completion`() {
        val firstClip = "clip_0123456789ab"
        val secondClip = "clip_abcdefabcdef"
        val ledger = StabilityProgressLedger(listOf(firstClip, secondClip))

        val initial = ledger.snapshot()
        assertFalse(initial.decoderActive)
        assertEquals(0, ledger.snapshot().activeDecodeProgress)
        assertEquals(0, initial.completedClipsInCurrentLoop)
        assertNull(initial.activeClipId)
        assertNull(initial.lastVerifiedClipId)
        assertThrows(BenchmarkContractException::class.java) {
            ledger.markLoopCompleted(1)
        }

        ledger.markDecodeStarted(firstClip)
        assertEquals(firstClip, ledger.snapshot().activeClipId)
        ledger.markDecodeCompleted(firstClip, DecoderStatus.OK)
        assertEquals(1, ledger.snapshot().activeDecodeProgress)
        assertEquals(1, ledger.snapshot().completedClipsInCurrentLoop)
        assertEquals(firstClip, ledger.snapshot().lastVerifiedClipId)
        ledger.markDecodeStarted(secondClip)
        ledger.markDecodeCompleted(secondClip, DecoderStatus.OK)
        ledger.markLoopCompleted(1)
        assertEquals(1, ledger.snapshot().completedLoops)
        assertEquals(0, ledger.snapshot().completedClipsInCurrentLoop)

        ledger.markDecodeStarted(firstClip)
        ledger.markDecodeCompleted(firstClip, DecoderStatus.OK)
        ledger.markDecodeStarted(secondClip)
        ledger.markDecodeCompleted(secondClip, DecoderStatus.OK)
        ledger.markLoopCompleted(2)
        assertEquals(4, ledger.snapshot().activeDecodeProgress)
        assertEquals(4, ledger.snapshot().completedClipCount)
        assertEquals(2, ledger.snapshot().successfulLoops)
    }

    @Test
    fun `stability ledger rejects skipped repeated and mismatched loop clips`() {
        val firstClip = "clip_0123456789ab"
        val secondClip = "clip_abcdefabcdef"

        assertThrows(BenchmarkContractException::class.java) {
            StabilityProgressLedger(emptyList())
        }
        assertThrows(BenchmarkContractException::class.java) {
            StabilityProgressLedger(listOf(firstClip, firstClip))
        }
        assertThrows(BenchmarkContractException::class.java) {
            StabilityProgressLedger(listOf(firstClip, secondClip)).apply {
                markDecodeStarted(secondClip)
            }
        }
        assertThrows(BenchmarkContractException::class.java) {
            StabilityProgressLedger(listOf(firstClip, secondClip)).apply {
                markDecodeStarted(firstClip)
                markDecodeCompleted(firstClip, DecoderStatus.OK)
                markDecodeStarted(firstClip)
            }
        }
        assertThrows(BenchmarkContractException::class.java) {
            StabilityProgressLedger(listOf(firstClip, secondClip)).apply {
                markDecodeStarted(firstClip)
                markDecodeCompleted(firstClip, DecoderStatus.ERROR)
            }
        }
        assertThrows(BenchmarkContractException::class.java) {
            StabilityProgressLedger(listOf(firstClip, secondClip)).apply {
                markDecodeStarted(firstClip)
                markDecodeCompleted(firstClip, DecoderStatus.OK)
                markLoopCompleted(1)
            }
        }
        val cancelled = StabilityProgressLedger(listOf(firstClip)).apply {
            markDecodeStarted(firstClip)
            finish()
        }
        assertFalse(cancelled.snapshot().decoderActive)
        assertNull(cancelled.snapshot().activeClipId)
    }

    @Test
    fun `stability validator rejects every forged sample and loop boundary`() {
        val clipId = "clip_0123456789ab"
        val order = listOf(clipId)
        val policy = StabilityPolicy.development(10_000_000_000L)
        val valid = StabilityProofFixture.valid(
            intervalNanos = 5_000_000_000L,
            timestamps = listOf(0L, 5_000_000_000L, 10_000_000_000L),
            completedClips = listOf(0, 1, 2),
            activeProgress = listOf(0, 1, 2),
        )
        fun rejects(
            proof: StabilityProof = valid,
            clips: List<String> = order,
            selectedPolicy: StabilityPolicy = policy,
        ) {
            assertThrows(BenchmarkContractException::class.java) {
                StabilityProofValidator.validate(
                    proof,
                    clips,
                    selectedPolicy,
                )
            }
        }

        rejects(clips = emptyList())
        rejects(clips = listOf(clipId, clipId))
        rejects(proof = valid.copy(sampleIntervalNanos = 1L))
        rejects(proof = valid.copy(samples = valid.samples.take(1)))
        rejects(proof = valid.copy(loopCount = 1))
        rejects(
            proof = valid.copy(
                loopProofs = valid.loopProofs.mapIndexed { index, loop ->
                    if (index == 0) loop.copy(clipCount = 2) else loop
                },
            ),
        )
        rejects(
            proof = valid.copy(
                samples = valid.samples.mapIndexed { index, sample ->
                    if (index == 1) {
                        sample.copy(monotonicNanos = 6_000_000_000L)
                    } else {
                        sample
                    }
                },
            ),
        )
        rejects(
            proof = valid.copy(
                samples = valid.samples.mapIndexed { index, sample ->
                    if (index == 1) sample.copy(rssBytes = -1L) else sample
                },
            ),
        )
        rejects(
            proof = valid.copy(
                samples = valid.samples.mapIndexed { index, sample ->
                    if (index == 1) sample.copy(swapBytes = -1L) else sample
                },
            ),
        )
        rejects(
            proof = valid.copy(
                samples = valid.samples.mapIndexed { index, sample ->
                    if (index == 1) {
                        sample.copy(completedClipsInCurrentLoop = 2)
                    } else {
                        sample
                    }
                },
            ),
        )
        rejects(
            proof = valid.copy(
                samples = valid.samples.mapIndexed { index, sample ->
                    if (index == 1) {
                        sample.copy(
                            activeDecodeProgress = 2,
                            completedClipCount = 2,
                        )
                    } else {
                        sample
                    }
                },
            ),
        )
        rejects(
            proof = valid.copy(
                samples = valid.samples.mapIndexed { index, sample ->
                    if (index == 1) {
                        sample.copy(lastVerifiedClipId = null)
                    } else {
                        sample
                    }
                },
            ),
        )
        rejects(
            proof = valid.copy(
                samples = valid.samples.mapIndexed { index, sample ->
                    if (index == 1) {
                        sample.copy(activeClipId = null)
                    } else {
                        sample
                    }
                },
            ),
        )
        rejects(
            proof = valid.copy(
                samples = valid.samples.mapIndexed { index, sample ->
                    if (index == 0) {
                        sample.copy(
                            decoderActive = true,
                            activeClipId = clipId,
                        )
                    } else {
                        sample
                    }
                },
            ),
        )
        rejects(
            proof = valid.copy(
                samples = valid.samples.mapIndexed { index, sample ->
                    if (index == 1) {
                        sample.copy(
                            decoderActive = false,
                            activeClipId = null,
                            activeDecodeProgress = 0,
                            completedClipCount = 0,
                            completedLoops = 0,
                            completedClipsInCurrentLoop = 0,
                            successfulLoops = 0,
                            lastVerifiedClipId = null,
                        )
                    } else {
                        sample
                    }
                },
            ),
        )
        rejects(
            proof = valid.copy(
                samples = valid.samples.mapIndexed { index, sample ->
                    if (index == 1) {
                        sample.copy(
                            decoderActive = false,
                            activeClipId = null,
                            completedLoops = 0,
                            completedClipsInCurrentLoop = 1,
                            successfulLoops = 0,
                        )
                    } else {
                        sample
                    }
                },
            ),
        )
        rejects(
            selectedPolicy = StabilityPolicy.development(
                11_000_000_000L,
            ),
        )
        rejects(proof = valid.copy(totalDecodedClipCount = 3))
    }

    @Test
    fun `stability policy rejects impossible contracts`() {
        listOf(
            { StabilityPolicy(0, 1, 2, 0, 0) },
            { StabilityPolicy(1, 0, 2, 0, 0) },
            { StabilityPolicy(1, 1, 1, 0, 0) },
            { StabilityPolicy(1, 1, 2, -1, 0) },
            { StabilityPolicy(1, 1, 2, 0, 101) },
        ).forEach { factory ->
            assertThrows(BenchmarkContractException::class.java) {
                factory()
            }
        }
    }

    @Test
    fun `strict canonical decoder json accepts every supported value and escape`() {
        val document = linkedMapOf<String, Any?>(
            "array" to listOf<Any?>(
                "value",
                7L,
                true,
                false,
                null,
                mapOf("nested" to listOf(1L, 2L)),
            ),
            "empty_array" to emptyList<Any?>(),
            "false" to false,
            "integer" to Long.MIN_VALUE,
            "maximum" to Long.MAX_VALUE,
            "nested" to emptyMap<String, Any?>(),
            "null" to null,
            "string" to "\"\\\b\u000c\n\r\t雪",
            "true" to true,
            "zero" to 0L,
        )
        val encoded = (CanonicalJson.encode(document) + "\n").encodeToByteArray()
        val decoded = StrictCanonicalJson.decodeObject(encoded)

        assertEquals(document, decoded.document)
        assertEquals(Hashing.sha256(encoded), decoded.rawSha256)
        assertEquals(CanonicalJson.sha256(document), decoded.canonicalSha256)
    }

    @Test
    fun `strict canonical decoder json rejects malformed syntax and number boundaries`() {
        val malformed = listOf(
            byteArrayOf(0xff.toByte()),
            byteArrayOf(),
            "\n".encodeToByteArray(),
            "[]\n".encodeToByteArray(),
            "\"root\"\n".encodeToByteArray(),
            "true\n".encodeToByteArray(),
            "false\n".encodeToByteArray(),
            "null\n".encodeToByteArray(),
            "1\n".encodeToByteArray(),
            "{".encodeToByteArray(),
            "{]\n".encodeToByteArray(),
            "{\"a\":".encodeToByteArray(),
            "{\"a\" 1}\n".encodeToByteArray(),
            "{\"a\":1 \"b\":2}\n".encodeToByteArray(),
            "{\"a\":1,}\n".encodeToByteArray(),
            "{\"a\":[1 2]}\n".encodeToByteArray(),
            "{\"a\":[1,]}\n".encodeToByteArray(),
            "{\"a\":[1,".encodeToByteArray(),
            "{\"a\":[}\n".encodeToByteArray(),
            "{\"a\":\"unterminated}\n".encodeToByteArray(),
            "{\"a\":\"line\nbreak\"}\n".encodeToByteArray(),
            "{\"a\":\"\\".encodeToByteArray(),
            "{\"a\":\"\\}\n".encodeToByteArray(),
            "{\"a\":\"\\x\"}\n".encodeToByteArray(),
            "{\"a\":\"\\u12\"}\n".encodeToByteArray(),
            "{\"a\":\"\\u12xz\"}\n".encodeToByteArray(),
            "{\"a\":-}\n".encodeToByteArray(),
            "{\"a\":-x}\n".encodeToByteArray(),
            "{\"a\":01}\n".encodeToByteArray(),
            "{\"a\":1.0}\n".encodeToByteArray(),
            "{\"a\":1e2}\n".encodeToByteArray(),
            "{\"a\":1E2}\n".encodeToByteArray(),
            "{\"a\":9223372036854775808}\n".encodeToByteArray(),
            "{\"a\":-9223372036854775809}\n".encodeToByteArray(),
            "{\"a\":truX}\n".encodeToByteArray(),
            "{\"a\":falsX}\n".encodeToByteArray(),
            "{\"a\":nulX}\n".encodeToByteArray(),
        )
        malformed.forEach { bytes ->
            assertThrows(BenchmarkContractException::class.java) {
                StrictCanonicalJson.decodeObject(bytes)
            }
        }
    }

    @Test
    fun `strict canonical decoder json rejects duplicate keys noncanonical escapes and byte tail`() {
        assertThrows(BenchmarkContractException::class.java) {
            StrictCanonicalJson.decodeObject(
                "{\"run_id\":\"one\",\"run_id\":\"two\"}\n"
                    .encodeToByteArray(),
            )
        }
        assertThrows(BenchmarkContractException::class.java) {
            StrictCanonicalJson.decodeObject(
                "{\"run_id\":\"one\"}\n ".encodeToByteArray(),
            )
        }
        for (encoded in listOf(
            "{\"value\":\"\\/\"}\n",
            "{\"value\":\"\\u96ea\"}\n",
            "{\"value\":-0}\n",
            "{ \"value\":1}\n",
            "\t{\"value\":[true,false,null]}\r\n",
        )) {
            assertThrows(BenchmarkContractException::class.java) {
                StrictCanonicalJson.decodeObject(encoded.encodeToByteArray())
            }
        }
    }

    @Test
    fun `trusted output store rejects direct and intermediate symlinks on real filesystem`() {
        val intermediateRoot = Files.createTempDirectory(
            "kittyecho-output-intermediate-",
        )
        val outside = Files.createTempDirectory("kittyecho-output-outside-")
        Files.createSymbolicLink(
            intermediateRoot.resolve(TrustedContentAddressedOutputStore.ROOT_NAME),
            outside,
        )
        assertThrows(BenchmarkContractException::class.java) {
            TrustedContentAddressedOutputStore.open(
                intermediateRoot,
                "run_0123456789ab",
            )
        }

        val directRoot = Files.createTempDirectory("kittyecho-output-direct-")
        val storeRoot = directRoot.resolve(
            TrustedContentAddressedOutputStore.ROOT_NAME,
        )
        Files.createDirectory(storeRoot)
        Files.createSymbolicLink(
            storeRoot.resolve("run_0123456789ab"),
            outside,
        )
        assertThrows(BenchmarkContractException::class.java) {
            TrustedContentAddressedOutputStore.open(
                directRoot,
                "run_0123456789ab",
            )
        }
    }

    @Test
    fun `trusted output store detects replacement after validation without writing attacker path`() {
        val trustedRoot = Files.createTempDirectory("kittyecho-output-race-")
        val attackerDirectory = Files.createTempDirectory(
            "kittyecho-output-attacker-",
        )
        val displaced = trustedRoot.resolve("displaced-run")
        val store = TrustedContentAddressedOutputStore.openForTesting(
            trustedRoot,
            "run_0123456789ab",
        ) { runDirectory ->
            Files.move(
                runDirectory,
                displaced,
                StandardCopyOption.ATOMIC_MOVE,
            )
            Files.createSymbolicLink(runDirectory, attackerDirectory)
        }

        store.use {
            assertThrows(BenchmarkContractException::class.java) {
                it.publish("accuracy", mapOf("value" to 1L))
            }
        }
        Files.newDirectoryStream(attackerDirectory).use {
            assertFalse(it.iterator().hasNext())
        }
    }

    @Test
    fun `trusted output store binds published inode and rejects same content replacement`() {
        val trustedRoot = Files.createTempDirectory("kittyecho-output-binding-")
        TrustedContentAddressedOutputStore.open(
            trustedRoot,
            "run_0123456789ab",
        ).use { store ->
            val published = store.publish(
                "accuracy",
                mapOf("value" to 1L),
            )
            assertTrue(
                store.verifyPublished(
                    published.fileName,
                    published.sha256,
                ),
            )
            val target = store.runDirectory.resolve(published.fileName)
            val replacement = store.runDirectory.resolve("replacement.json")
            Files.write(replacement, Files.readAllBytes(target))
            Files.setPosixFilePermissions(
                replacement,
                setOf(PosixFilePermission.OWNER_READ),
            )
            Files.move(
                replacement,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            assertThrows(BenchmarkContractException::class.java) {
                store.verifyPublished(
                    published.fileName,
                    published.sha256,
                )
            }
            assertThrows(BenchmarkContractException::class.java) {
                store.publish("accuracy", mapOf("value" to 1L))
            }
        }
    }

    @Test
    fun `trusted output store reuses only matching immutable content`() {
        val trustedRoot = Files.createTempDirectory("kittyecho-output-reuse-")
        val runId = "run_0123456789ab"
        val document = mapOf("value" to 1L)
        val first = TrustedContentAddressedOutputStore.open(
            trustedRoot,
            runId,
        ).use { store ->
            store.publish("accuracy", document).also {
                assertTrue(store.verifyPublished(it.fileName, it.sha256))
                assertEquals(it, store.publish("accuracy", document))
            }
        }

        TrustedContentAddressedOutputStore.open(
            trustedRoot,
            runId,
        ).use { store ->
            val reused = store.publish("accuracy", document)
            assertEquals(first, reused)
            assertTrue(store.verifyPublished(reused.fileName, reused.sha256))
        }
    }

    @Test
    fun `trusted output store rejects invalid roots identifiers and leaf state`() {
        assertThrows(BenchmarkContractException::class.java) {
            TrustedContentAddressedOutputStore.open(
                Files.createTempDirectory("kittyecho-output-missing-")
                    .resolve("absent"),
                "run_0123456789ab",
            )
        }
        val invalidRoot = Files.createTempFile("kittyecho-output-file-", ".tmp")
        assertThrows(BenchmarkContractException::class.java) {
            TrustedContentAddressedOutputStore.open(
                invalidRoot,
                "run_0123456789ab",
            )
        }

        val permissiveRoot = Files.createTempDirectory(
            "kittyecho-output-permissions-",
        )
        Files.setPosixFilePermissions(
            permissiveRoot,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
            ),
        )
        assertThrows(BenchmarkContractException::class.java) {
            TrustedContentAddressedOutputStore.open(
                permissiveRoot,
                "run_0123456789ab",
            )
        }

        val readOnlyRoot = Files.createTempDirectory(
            "kittyecho-output-read-only-",
        )
        Files.setPosixFilePermissions(
            readOnlyRoot,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        assertThrows(BenchmarkContractException::class.java) {
            TrustedContentAddressedOutputStore.open(
                readOnlyRoot,
                "run_0123456789ab",
            )
        }

        val unreadableRunRoot = Files.createTempDirectory(
            "kittyecho-output-unreadable-run-",
        )
        val unreadableStoreRoot = unreadableRunRoot.resolve(
            TrustedContentAddressedOutputStore.ROOT_NAME,
        )
        Files.createDirectory(unreadableStoreRoot)
        val unreadableRun = unreadableStoreRoot.resolve(
            "run_0123456789ab",
        )
        Files.createDirectory(unreadableRun)
        Files.setPosixFilePermissions(unreadableRun, emptySet())
        assertThrows(BenchmarkContractException::class.java) {
            TrustedContentAddressedOutputStore.open(
                unreadableRunRoot,
                "run_0123456789ab",
            )
        }

        val trustedRoot = Files.createTempDirectory("kittyecho-output-invalid-")
        assertThrows(BenchmarkContractException::class.java) {
            TrustedContentAddressedOutputStore.open(trustedRoot, "../escape")
        }
        TrustedContentAddressedOutputStore.open(
            trustedRoot,
            "run_0123456789ab",
        ).use { store ->
            assertThrows(BenchmarkContractException::class.java) {
                store.publish("x", emptyMap())
            }
            assertThrows(BenchmarkContractException::class.java) {
                store.verifyPublished("missing.json", "0".repeat(64))
            }
            assertThrows(BenchmarkContractException::class.java) {
                store.verifyPublished("../escape.json", "0".repeat(64))
            }
        }

        val blockedRoot = Files.createTempDirectory("kittyecho-output-blocked-")
        Files.write(
            blockedRoot.resolve(TrustedContentAddressedOutputStore.ROOT_NAME),
            byteArrayOf(1),
        )
        assertThrows(BenchmarkContractException::class.java) {
            TrustedContentAddressedOutputStore.open(
                blockedRoot,
                "run_0123456789ab",
            )
        }
    }

    @Test
    fun `trusted output store rejects symlink permission and closed-store tampering`() {
        val trustedRoot = Files.createTempDirectory("kittyecho-output-tamper-")
        val store = TrustedContentAddressedOutputStore.open(
            trustedRoot,
            "run_0123456789ab",
        )
        val published = store.publish("accuracy", mapOf("value" to 1L))
        val target = store.runDirectory.resolve(published.fileName)
        Files.setPosixFilePermissions(
            target,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        )
        assertThrows(BenchmarkContractException::class.java) {
            store.verifyPublished(published.fileName, published.sha256)
        }
        store.close()
        store.close()
        assertThrows(BenchmarkContractException::class.java) {
            store.publish("accuracy", mapOf("value" to 2L))
        }

        val contentRoot = Files.createTempDirectory(
            "kittyecho-output-content-",
        )
        TrustedContentAddressedOutputStore.open(
            contentRoot,
            "run_0123456789ab",
        ).use { contentStore ->
            val content = contentStore.publish(
                "accuracy",
                mapOf("value" to 4L),
            )
            val contentPath = contentStore.runDirectory.resolve(
                content.fileName,
            )
            Files.setPosixFilePermissions(
                contentPath,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
            Files.write(contentPath, "{}\n".encodeToByteArray())
            Files.setPosixFilePermissions(
                contentPath,
                setOf(PosixFilePermission.OWNER_READ),
            )
            assertThrows(BenchmarkContractException::class.java) {
                contentStore.verifyPublished(
                    content.fileName,
                    content.sha256,
                )
            }
        }

        val symlinkRoot = Files.createTempDirectory(
            "kittyecho-output-leaf-link-",
        )
        val outside = Files.createTempFile("kittyecho-output-leaf-", ".json")
        TrustedContentAddressedOutputStore.open(
            symlinkRoot,
            "run_0123456789ab",
        ).use { linkedStore ->
            val document = mapOf("value" to 3L)
            val encoded = (
                CanonicalJson.encode(document) + "\n"
                ).encodeToByteArray()
            val digest = Hashing.sha256(encoded)
            Files.createSymbolicLink(
                linkedStore.runDirectory.resolve("accuracy-$digest.json"),
                outside,
            )
            assertThrows(BenchmarkContractException::class.java) {
                linkedStore.publish("accuracy", document)
            }
        }

        val directoryMutationRoot = Files.createTempDirectory(
            "kittyecho-output-directory-mutation-",
        )
        TrustedContentAddressedOutputStore.open(
            directoryMutationRoot,
            "run_0123456789ab",
        ).use { mutatedStore ->
            Files.setPosixFilePermissions(
                mutatedStore.runDirectory,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            assertThrows(BenchmarkContractException::class.java) {
                mutatedStore.publish("accuracy", mapOf("value" to 5L))
            }
        }
    }

    @Test
    fun `formal stability policy freezes ten minutes two loops and jitter`() {
        val policy = StabilityPolicy.formal()

        assertEquals(600_000_000_000L, policy.minimumDurationNanos)
        assertEquals(2, policy.minimumCompleteLoops)
        assertEquals(250_000_000L, policy.absoluteJitterNanos)
        assertEquals(5, policy.relativeJitterPercent)
    }

    @Test
    fun `development emulator policy has isolated fixed scheduling tolerance`() {
        val emulator = StabilityPolicy.developmentEmulator600()
        val formal = StabilityPolicy.formal()
        val emulatorJitter = StabilityProofFixture.valid(
            intervalNanos = 5_000_000_000L,
            timestamps = listOf(
                0L,
                6_244_947_956L,
                11_244_947_956L,
            ),
            completedClips = listOf(0, 1, 2),
            activeProgress = listOf(0, 1, 2),
        )

        assertEquals(600_000_000_000L, emulator.minimumDurationNanos)
        assertEquals(5_000_000_000L, emulator.sampleIntervalNanos)
        assertEquals(2, emulator.minimumCompleteLoops)
        assertEquals(1_250_000_000L, emulator.absoluteJitterNanos)
        assertEquals(25, emulator.relativeJitterPercent)
        assertTrue(
            StabilityProofValidator.validate(
                emulatorJitter,
                listOf("clip_0123456789ab"),
                emulator.copy(minimumDurationNanos = 10_000_000_000L),
            ),
        )
        assertThrows(BenchmarkContractException::class.java) {
            StabilityProofValidator.validate(
                emulatorJitter,
                listOf("clip_0123456789ab"),
                formal.copy(minimumDurationNanos = 10_000_000_000L),
            )
        }
    }

    @Test
    fun `synthetic output separates accuracy engineering and signed commitments`() {
        val result = TrustedBenchmarkRunner(
            secureFiles = FakeSecureFiles(
                mutableMapOf(
                    "clip-a" to pcmMeasurement,
                    "weights" to artifactMeasurement,
                ),
            ),
            clock = SequenceClock(1_000, 2_000),
            resourceProbe = SequenceResourceProbe(ResourceSample(50, 40, 0)),
        ).decode(
            singleClipPlan(),
            listOf(ArtifactFile("weights", "weights")),
            FabricatingAdapter(),
        )
        val decoderPlanSha256 = "8".repeat(64)
        val accuracy = DeviceOutputDocuments.accuracy(result, decoderPlanSha256)
        val stability = StabilityProofFixture.valid(
            intervalNanos = 5_000_000_000L,
            timestamps = listOf(0, 5_000_000_000L, 10_000_000_000L),
            completedClips = listOf(0, 1, 2),
            activeProgress = listOf(0, 1, 2),
        )
        val bindings = RunnerContractBindings(
            benchmarkEvidenceSha256 = "a".repeat(64),
            publicPlanSha256 = "b".repeat(64),
            registrySha256 = "1".repeat(64),
            normalizationSha256 = "2".repeat(64),
            selectionConfigSha256 = "3".repeat(64),
            runnerBuildSha256 = "4".repeat(64),
            apkSha256 = "5".repeat(64),
            appSigningCertSha256 = "6".repeat(64),
            deviceIdentityCommitmentSha256 = "7".repeat(64),
        )
        val engineering = DeviceOutputDocuments.engineering(
            result,
            decoderPlanSha256,
            accuracy,
            bindings,
            stability,
        )
        val runtime = engineering["runtime"] as Map<*, *>
        assertEquals(50L, runtime["decode_probe_peak_rss_bytes"])
        assertEquals(40L, runtime["decode_probe_peak_pss_bytes"])
        assertEquals(102L, runtime["peak_rss_bytes"])
        assertEquals(92L, runtime["peak_pss_bytes"])
        var signedMessages = 0
        val envelope = AndroidAttestationEnvelopeFactory.create(
            result = result,
            decoderPlanReceiptCommitSha256 = "9".repeat(64),
            decoderPlanSha256 = decoderPlanSha256,
            decoderPlanRawSha256 = "a".repeat(64),
            decoderPlanIdSha256 = "b".repeat(64),
            accuracyDocument = accuracy,
            engineeringDocument = engineering,
            bindings = bindings,
            protocolProfile = "development_fixture",
            hostChallenge = ByteArray(32) { 0x42.toByte() },
            physicalDeviceClaim = false,
            emulatorClaim = true,
            signer = AttestationSignatureProvider { challenge, message ->
                signedMessages += 1
                assertEquals(64, challenge.length)
                val prefix = "kittyecho-asr-attestation-envelope-v1\u0000"
                    .encodeToByteArray()
                assertTrue(
                    message.copyOfRange(0, prefix.size).contentEquals(prefix),
                )
                AttestationSignature(
                    signatureDer = byteArrayOf(1, 2, 3),
                    certificateChainDer = listOf(byteArrayOf(4, 5, 6)),
                    localSecurityLevelClaim = "software",
                )
            },
        )

        assertFalse(engineering.toString().contains("hypothesis"))
        assertFalse(engineering.toString().contains("transcript"))
        assertFalse(engineering.toString().contains("filename"))
        assertFalse(engineering.toString().contains("path"))
        assertTrue(accuracy.toString().contains("hypothesis"))
        assertEquals(1, signedMessages)
        assertEquals(
            false,
            (envelope["signed_payload"] as Map<*, *>)["runtime_claims"]
                .let { it as Map<*, *> }["physical_device"],
        )
        assertFalse(envelope.containsKey("formal_eligible"))
        assertFalse(envelope.containsKey("product_decision_eligible"))
        val commitments = (
            envelope["signed_payload"] as Map<*, *>
            )["commitments"] as Map<*, *>
        assertEquals("a".repeat(64), commitments["decoder_plan_raw_sha256"])
        assertEquals("b".repeat(64), commitments["decoder_plan_id_sha256"])
    }

    private fun runner(): TrustedBenchmarkRunner = TrustedBenchmarkRunner(
        secureFiles = FakeSecureFiles(
            mutableMapOf(
                "clip-a" to pcmMeasurement,
                "weights" to artifactMeasurement,
            ),
        ),
        clock = SequenceClock(1, 2),
        resourceProbe = SequenceResourceProbe(ResourceSample(1, 1, 0)),
    )

    private fun singleClipPlan(): DecoderPlan = DecoderPlan(
        planId = "plan_0123456789ab",
        runId = "run_0123456789ab",
        datasetId = "dataset_0123456789ab",
        modelAlias = "M001",
        clips = listOf(
            DecoderClip(
                clipId = "clip_0123456789ab",
                pathToken = "clip-a",
                wavSha256 = pcmMeasurement.sha256,
                pcmPayloadSha256 = pcmMeasurement.pcm!!.payloadSha256,
                pcmPayloadBytes = requireNotNull(pcmMeasurement.pcm)
                    .payloadBytes,
            ),
        ),
    )
}

private class FakeSecureFiles(
    val values: MutableMap<String, MeasuredFile>,
) : SecureFileAccess {
    override fun read(pathToken: String, requireCanonicalPcm: Boolean): MeasuredFile {
        val value = requireNotNull(values[pathToken])
        return MeasuredFile.fromBytes(
            pathToken,
            value.bytes.copyOf(),
            value.identity,
            requireCanonicalPcm,
        )
    }
}

private class SequenceClock(vararg values: Long) : MonotonicClock {
    private val sequence = values.toMutableList()

    override fun nowNanos(): Long {
        check(sequence.isNotEmpty())
        return sequence.removeAt(0)
    }
}

private class SequenceResourceProbe(
    vararg samples: ResourceSample,
) : ResourceProbe {
    private val sequence = samples.toMutableList()
    private var last = samples.last()

    override fun sample(): ResourceSample {
        if (sequence.isNotEmpty()) {
            last = sequence.removeAt(0)
        }
        return last
    }
}

private class FabricatingAdapter : DecoderAdapter {
    var calls = 0
    val adapterLatency = 1L
    val adapterRss = 1L

    override fun decode(canonicalPcmWav: ByteArray): DecoderResult {
        calls += 1
        return DecoderResult("synthetic", DecoderStatus.OK, null)
    }
}

private object StabilityProofFixture {
    fun valid(
        intervalNanos: Long,
        timestamps: List<Long>,
        completedClips: List<Int>,
        activeProgress: List<Int>,
    ): StabilityProof {
        require(timestamps.size == 3)
        require(completedClips.size == 3)
        require(activeProgress.size == 3)
        val firstLoopEnd = if (completedClips[1] >= 1) {
            timestamps[1]
        } else {
            timestamps[2] - 50_000_000L
        }
        val secondLoopStart = maxOf(firstLoopEnd, timestamps[1] + 1)
        val loops = listOf(
            StabilityLoopProof(
                loopIndex = 1,
                monotonicStartNanos = timestamps[0],
                monotonicEndNanos = firstLoopEnd,
                orderedClipIdsSha256 = CanonicalJson.sha256(
                    listOf("clip_0123456789ab"),
                ),
                clipCount = 1,
                cumulativeDecodedClipCount = 1,
                successful = true,
            ),
            StabilityLoopProof(
                loopIndex = 2,
                monotonicStartNanos = secondLoopStart,
                monotonicEndNanos = timestamps[2],
                orderedClipIdsSha256 = CanonicalJson.sha256(
                    listOf("clip_0123456789ab"),
                ),
                clipCount = 1,
                cumulativeDecodedClipCount = 2,
                successful = true,
            ),
        )
        return StabilityProof(
            sampleIntervalNanos = intervalNanos,
            samples = timestamps.mapIndexed { index, timestamp ->
                val completeLoops = when {
                    timestamp >= loops[1].monotonicEndNanos -> 2
                    timestamp >= loops[0].monotonicEndNanos -> 1
                    else -> 0
                }
                StabilitySample(
                    monotonicNanos = timestamp,
                    rssBytes = 100L + index,
                    pssBytes = 90L + index,
                    thermalStatus = 0,
                    heartbeatIndex = index,
                    runnerAlive = true,
                    decoderActive = index > 0,
                    activeClipId = if (index > 0) {
                        "clip_0123456789ab"
                    } else {
                        null
                    },
                    activeDecodeProgress = activeProgress[index].toLong(),
                    completedClipCount = completedClips[index],
                    completedLoops = completeLoops,
                    completedClipsInCurrentLoop =
                        completedClips[index] - completeLoops,
                    successfulLoops = completeLoops,
                    lastVerifiedClipId = if (completedClips[index] > 0) {
                        "clip_0123456789ab"
                    } else {
                        null
                    },
                )
            },
            loopProofs = loops,
            loopCount = 2,
            successfulLoopCount = 2,
            totalDecodedClipCount = 2,
        )
    }
}
