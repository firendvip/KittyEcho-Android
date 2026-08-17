package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineCandidateInstrumentationContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fireRedCommandAndPrivateFilesRequireOneExactFlatLayout() {
        val root = temporaryFolder.newFolder("fire").toPath()
        val encoder = create(root, "encoder.int8.onnx")
        val decoder = create(root, "decoder.int8.onnx")
        val tokens = create(root, "tokens.txt")
        val pcm = create(root, "synthetic-smoke.wav")
        val entries = fireEntries(root)

        val command = FireRedInstrumentationCommand.parse(entries)
        val validated = FireRedPrivateInputContract.validate(root, command)

        assertEquals("run_000000000001", command.runId)
        assertEquals("dataset_000000000001", command.datasetId)
        assertEquals("M001", command.modelAlias)
        assertEquals(encoder, validated.encoder)
        assertEquals(decoder, validated.decoder)
        assertEquals(tokens, validated.tokens)
        assertEquals(pcm, validated.pcm)
        assertEquals(ParaformerCancellationPhase.NONE, command.cancellationPhase)
    }

    @Test
    fun funAsrCommandBindsThreeModelsAndExactTokenizerDirectory() {
        val root = temporaryFolder.newFolder("nano").toPath()
        val embedding = create(root, "embedding.int8.onnx")
        val adaptor = create(root, "encoder_adaptor.int8.onnx")
        val llm = create(root, "llm.int8.onnx")
        val tokenizerDirectory = Files.createDirectory(root.resolve("Qwen3-0.6B"))
        val tokenizer = create(tokenizerDirectory, "tokenizer.json")
        val vocab = create(tokenizerDirectory, "vocab.json")
        val merges = create(tokenizerDirectory, "merges.txt")
        val pcm = create(root, "synthetic-smoke.wav")

        val command = FunAsrNanoInstrumentationCommand.parse(nanoEntries(root))
        val validated = FunAsrNanoPrivateInputContract.validate(root, command)

        assertEquals(embedding, validated.embedding)
        assertEquals(adaptor, validated.encoderAdaptor)
        assertEquals(llm, validated.llm)
        assertEquals(tokenizerDirectory, validated.tokenizerDirectory)
        assertEquals(tokenizer, validated.tokenizer)
        assertEquals(vocab, validated.vocab)
        assertEquals(merges, validated.merges)
        assertEquals(pcm, validated.pcm)
    }

    @Test
    fun commandsRejectMissingDuplicateExtraWrongModeAndUnsafeValues() {
        val root = temporaryFolder.newFolder("arguments").toPath()
        createFireLayout(root)
        val valid = fireEntries(root)

        listOf(
            valid.filterNot { it.first == "pcm_path" },
            valid + ("encoder_path" to root.resolve("encoder.int8.onnx").toString()),
            valid + ("host_sha256" to "0".repeat(64)),
            valid.map { if (it.first == "mode") it.first to "paraformer-smoke" else it },
            valid.map { if (it.first == "run_id") it.first to "bad" else it },
            valid.map { if (it.first == "model_alias") it.first to " " else it },
            valid.map { if (it.first == "mode") it.first to "firered-smoke\u0000" else it },
            valid.map {
                if (it.first == "tokens_path") it.first to "bad\u0000path" else it
            },
        ).forEach { invalid ->
            assertThrows(BenchmarkContractException::class.java) {
                FireRedInstrumentationCommand.parse(invalid)
            }
        }
    }

    @Test
    fun commandsAcceptEveryDeclaredCancellationPhase() {
        val root = temporaryFolder.newFolder("phases").toPath()
        createFireLayout(root)

        ParaformerCancellationPhase.entries.forEach { phase ->
            val command = FireRedInstrumentationCommand.parse(
                fireEntries(root).map {
                    if (it.first == "cancel_phase") {
                        it.first to phase.wireValue
                    } else {
                        it
                    }
                },
            )
            assertEquals(phase, command.cancellationPhase)
        }
    }

    @Test
    fun privateContractsRejectTraversalWrongNamesMissingFilesAndSymlinks() {
        val fireRoot = temporaryFolder.newFolder("fire-invalid").toPath()
        createFireLayout(fireRoot)
        val linkedEncoder = fireRoot.resolve("linked-encoder.int8.onnx")
        Files.createSymbolicLink(linkedEncoder, fireRoot.resolve("encoder.int8.onnx"))
        val badFire = fireEntries(fireRoot).map {
            if (it.first == "encoder_path") it.first to linkedEncoder.toString() else it
        }
        assertThrows(BenchmarkContractException::class.java) {
            FireRedPrivateInputContract.validate(
                fireRoot,
                FireRedInstrumentationCommand.parse(badFire),
            )
        }

        val nanoRoot = temporaryFolder.newFolder("nano-invalid").toPath()
        create(nanoRoot, "embedding.int8.onnx")
        create(nanoRoot, "encoder_adaptor.int8.onnx")
        create(nanoRoot, "llm.int8.onnx")
        val wrongDirectory = Files.createDirectory(nanoRoot.resolve("tokenizer"))
        create(wrongDirectory, "tokenizer.json")
        create(wrongDirectory, "vocab.json")
        create(wrongDirectory, "merges.txt")
        create(nanoRoot, "synthetic-smoke.wav")
        val wrongDirectoryEntries = nanoEntries(
            nanoRoot,
            tokenizerDirectory = wrongDirectory,
        )
        assertThrows(BenchmarkContractException::class.java) {
            FunAsrNanoPrivateInputContract.validate(
                nanoRoot,
                FunAsrNanoInstrumentationCommand.parse(wrongDirectoryEntries),
            )
        }

        val validDirectory = Files.createDirectory(nanoRoot.resolve("Qwen3-0.6B"))
        create(validDirectory, "tokenizer.json")
        create(validDirectory, "vocab.json")
        assertThrows(BenchmarkContractException::class.java) {
            FunAsrNanoPrivateInputContract.validate(
                nanoRoot,
                FunAsrNanoInstrumentationCommand.parse(
                    nanoEntries(nanoRoot, validDirectory),
                ),
            )
        }
    }

    @Test
    fun exactBasenameSymlinksLinkedInodesAndSymlinkRootsAreRejected() {
        val linkedFileRoot = temporaryFolder.newFolder("linked-file").toPath()
        val outside = temporaryFolder.newFolder("outside").toPath()
        val outsideEncoder = create(outside, "encoder.int8.onnx")
        Files.createSymbolicLink(
            linkedFileRoot.resolve("encoder.int8.onnx"),
            outsideEncoder,
        )
        create(linkedFileRoot, "decoder.int8.onnx")
        create(linkedFileRoot, "tokens.txt")
        create(linkedFileRoot, "synthetic-smoke.wav")
        assertThrows(BenchmarkContractException::class.java) {
            FireRedPrivateInputContract.validate(
                linkedFileRoot,
                FireRedInstrumentationCommand.parse(fireEntries(linkedFileRoot)),
            )
        }

        val linkedInodeRoot = temporaryFolder.newFolder("linked-inode").toPath()
        val encoder = create(linkedInodeRoot, "encoder.int8.onnx")
        Files.createLink(linkedInodeRoot.resolve("decoder.int8.onnx"), encoder)
        create(linkedInodeRoot, "tokens.txt")
        create(linkedInodeRoot, "synthetic-smoke.wav")
        assertThrows(BenchmarkContractException::class.java) {
            FireRedPrivateInputContract.validate(
                linkedInodeRoot,
                FireRedInstrumentationCommand.parse(fireEntries(linkedInodeRoot)),
            )
        }

        val realRoot = temporaryFolder.newFolder("real-root").toPath()
        createFireLayout(realRoot)
        val linkedRoot = temporaryFolder.root.toPath().resolve("fire-root-link")
        Files.createSymbolicLink(linkedRoot, realRoot)
        assertThrows(BenchmarkContractException::class.java) {
            FireRedPrivateInputContract.validate(
                linkedRoot,
                FireRedInstrumentationCommand.parse(fireEntries(linkedRoot)),
            )
        }
    }

    @Test
    fun tokenizerDirectoryRequiresExactlyThreeDirectDistinctRegularFiles() {
        val root = temporaryFolder.newFolder("nano-exact").toPath()
        val embedding = create(root, "embedding.int8.onnx")
        create(root, "encoder_adaptor.int8.onnx")
        create(root, "llm.int8.onnx")
        create(root, "synthetic-smoke.wav")
        val tokenizerDirectory = Files.createDirectory(root.resolve("Qwen3-0.6B"))
        Files.createLink(tokenizerDirectory.resolve("tokenizer.json"), embedding)
        create(tokenizerDirectory, "vocab.json")
        create(tokenizerDirectory, "merges.txt")
        assertThrows(BenchmarkContractException::class.java) {
            FunAsrNanoPrivateInputContract.validate(
                root,
                FunAsrNanoInstrumentationCommand.parse(nanoEntries(root)),
            )
        }

        Files.delete(tokenizerDirectory.resolve("tokenizer.json"))
        create(tokenizerDirectory, "tokenizer.json")
        create(tokenizerDirectory, "unexpected.json")
        assertThrows(BenchmarkContractException::class.java) {
            FunAsrNanoPrivateInputContract.validate(
                root,
                FunAsrNanoInstrumentationCommand.parse(nanoEntries(root)),
            )
        }
    }

    @Test
    fun tokenizerDirectoryAndExactChildrenCannotBeSymbolicLinks() {
        val linkedDirectoryRoot = temporaryFolder.newFolder("nano-dir-link").toPath()
        create(linkedDirectoryRoot, "embedding.int8.onnx")
        create(linkedDirectoryRoot, "encoder_adaptor.int8.onnx")
        create(linkedDirectoryRoot, "llm.int8.onnx")
        create(linkedDirectoryRoot, "synthetic-smoke.wav")
        val externalDirectory = temporaryFolder.newFolder("Qwen3-external").toPath()
        create(externalDirectory, "tokenizer.json")
        create(externalDirectory, "vocab.json")
        create(externalDirectory, "merges.txt")
        Files.createSymbolicLink(
            linkedDirectoryRoot.resolve("Qwen3-0.6B"),
            externalDirectory,
        )
        assertThrows(BenchmarkContractException::class.java) {
            FunAsrNanoPrivateInputContract.validate(
                linkedDirectoryRoot,
                FunAsrNanoInstrumentationCommand.parse(
                    nanoEntries(linkedDirectoryRoot),
                ),
            )
        }

        val linkedChildRoot = temporaryFolder.newFolder("nano-child-link").toPath()
        create(linkedChildRoot, "embedding.int8.onnx")
        create(linkedChildRoot, "encoder_adaptor.int8.onnx")
        create(linkedChildRoot, "llm.int8.onnx")
        create(linkedChildRoot, "synthetic-smoke.wav")
        val tokenizerDirectory = Files.createDirectory(
            linkedChildRoot.resolve("Qwen3-0.6B"),
        )
        val externalTokenizer = create(
            temporaryFolder.newFolder("tokenizer-external").toPath(),
            "tokenizer.json",
        )
        Files.createSymbolicLink(
            tokenizerDirectory.resolve("tokenizer.json"),
            externalTokenizer,
        )
        create(tokenizerDirectory, "vocab.json")
        create(tokenizerDirectory, "merges.txt")
        assertThrows(BenchmarkContractException::class.java) {
            FunAsrNanoPrivateInputContract.validate(
                linkedChildRoot,
                FunAsrNanoInstrumentationCommand.parse(
                    nanoEntries(linkedChildRoot),
                ),
            )
        }
    }

    @Test
    fun contractErrorsNeverRevealPrivatePaths() {
        val root = temporaryFolder.newFolder("private-path").toPath()
        createFireLayout(root)
        val command = FireRedInstrumentationCommand.parse(
            fireEntries(root).map {
                if (it.first == "encoder_path") {
                    it.first to root.resolve("secret-model.onnx").toString()
                } else {
                    it
                }
            },
        )

        val error = assertThrows(BenchmarkContractException::class.java) {
            FireRedPrivateInputContract.validate(root, command)
        }
        assertFalse(error.message.orEmpty().contains(root.toString()))
        assertFalse(error.message.orEmpty().contains("secret-model.onnx"))
    }

    @Test
    fun deviceConfigsMeasureOnlyStagedPrivateFilesButKeepRuntimeCommitment() {
        val fire = OfflineFireRedModelConfig.devicePrivate(
            encoderPath = "/private/encoder.int8.onnx",
            decoderPath = "/private/decoder.int8.onnx",
            tokensPath = "/private/tokens.txt",
        )
        val nano = OfflineFunAsrNanoModelConfig.devicePrivate(
            embeddingPath = "/private/embedding.int8.onnx",
            encoderAdaptorPath = "/private/encoder_adaptor.int8.onnx",
            llmPath = "/private/llm.int8.onnx",
            tokenizerDirectoryPath = "/private/Qwen3-0.6B",
        )

        assertEquals(setOf("encoder", "decoder", "tokenizer"), fire.expectations().keys)
        assertEquals(
            setOf(
                "embedding",
                "encoder_adaptor",
                "llm",
                "tokenizer_json",
                "vocab",
                "merges",
            ),
            nano.expectations().keys,
        )
        assertEquals(null, fire.runtimeAarPath)
        assertEquals(null, nano.runtimeAarPath)
        assertEquals(
            OfflineFireRedModelConfig.RUNTIME_RECEIPT_COMMIT_SHA256,
            fire.receiptCommitSha256ByRole.getValue("runtime"),
        )
        assertEquals(
            OfflineFunAsrNanoModelConfig.RUNTIME_RECEIPT_COMMIT_SHA256,
            nano.receiptCommitSha256ByRole.getValue("runtime"),
        )
        assertEquals(false, fire.expectations().containsKey("runtime"))
        assertEquals(false, fire.pathsByRole().containsKey("runtime"))
        assertEquals(false, nano.expectations().containsKey("runtime"))
        assertEquals(false, nano.pathsByRole().containsKey("runtime"))
    }

    private fun fireEntries(root: Path): List<Pair<String, String>> = listOf(
        "mode" to "firered-smoke",
        "run_id" to "run_000000000001",
        "dataset_id" to "dataset_000000000001",
        "model_alias" to "M001",
        "encoder_path" to root.resolve("encoder.int8.onnx").toString(),
        "decoder_path" to root.resolve("decoder.int8.onnx").toString(),
        "tokens_path" to root.resolve("tokens.txt").toString(),
        "pcm_path" to root.resolve("synthetic-smoke.wav").toString(),
        "cancel_phase" to "none",
    )

    private fun nanoEntries(
        root: Path,
        tokenizerDirectory: Path = root.resolve("Qwen3-0.6B"),
    ): List<Pair<String, String>> = listOf(
        "mode" to "funasr-nano-smoke",
        "run_id" to "run_000000000001",
        "dataset_id" to "dataset_000000000001",
        "model_alias" to "M001",
        "embedding_path" to root.resolve("embedding.int8.onnx").toString(),
        "encoder_adaptor_path" to root.resolve("encoder_adaptor.int8.onnx").toString(),
        "llm_path" to root.resolve("llm.int8.onnx").toString(),
        "tokenizer_directory_path" to tokenizerDirectory.toString(),
        "pcm_path" to root.resolve("synthetic-smoke.wav").toString(),
        "cancel_phase" to "none",
    )

    private fun createFireLayout(root: Path) {
        create(root, "encoder.int8.onnx")
        create(root, "decoder.int8.onnx")
        create(root, "tokens.txt")
        create(root, "synthetic-smoke.wav")
    }

    private fun create(root: Path, filename: String): Path =
        Files.write(root.resolve(filename), byteArrayOf(1))
}
