package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ParaformerSecureInstallPolicyTest : FunSpec({

    test("receipt round trip binds every frozen artifact and attribution field") {
        val receipt = ParaformerInstallReceipt.frozenContract()
        val encoded = receipt.encode()

        ParaformerInstallReceipt.decode(encoded) shouldBe receipt
        receipt.requireFrozenContract()
        encoded shouldContain ParaformerModelContract.MODEL_ID
        encoded shouldContain ParaformerModelContract.UPSTREAM_MODEL_ID
        encoded shouldContain ParaformerModelContract.UPSTREAM_VERSION
        encoded shouldContain ParaformerModelContract.CONVERSION_REVISION
        encoded shouldContain ParaformerModelContract.ARTIFACT_REVISION
        encoded shouldContain ParaformerModelContract.RUNTIME_VERSION
        encoded shouldContain ParaformerModelContract.MODEL_FILENAME
        encoded shouldContain ParaformerModelContract.MODEL_SHA256
        encoded shouldContain ParaformerModelContract.TOKENS_FILENAME
        encoded shouldContain ParaformerModelContract.TOKENS_SHA256
    }

    test("tampered receipt never validates as an installed model") {
        listOf(
            ParaformerInstallReceipt.frozenContract().copy(modelId = "other"),
            ParaformerInstallReceipt.frozenContract().copy(artifactRevision = "other"),
            ParaformerInstallReceipt.frozenContract().copy(runtimeVersion = "other"),
            ParaformerInstallReceipt.frozenContract().copy(
                artifacts = ParaformerInstallReceipt.frozenContract().artifacts.mapIndexed { index, artifact ->
                    if (index == 0) artifact.copy(sizeBytes = artifact.sizeBytes - 1L) else artifact
                },
            ),
        ).forEach { receipt ->
            shouldThrow<ParaformerAttemptException> {
                receipt.requireFrozenContract()
            }.failure shouldBe ParaformerAttemptFailure.Integrity
        }
    }

    test("secure install fsyncs fixed files and receipt before one atomic directory rename") {
        val ops = RecordingAtomicInstallOps()

        ParaformerSecureInstaller(ops).install() shouldBe ParaformerAtomicInstallResult.Installed

        ops.operations shouldContainExactly listOf(
            "finalUsable",
            "locationFacts",
            "artifactFacts:${ParaformerModelContract.MODEL_FILENAME}",
            "artifactFacts:${ParaformerModelContract.TOKENS_FILENAME}",
            "chmodDirectory0700",
            "chmodFile0600:${ParaformerModelContract.MODEL_FILENAME}",
            "fsyncFile:${ParaformerModelContract.MODEL_FILENAME}",
            "chmodFile0600:${ParaformerModelContract.TOKENS_FILENAME}",
            "fsyncFile:${ParaformerModelContract.TOKENS_FILENAME}",
            "writeReceipt",
            "chmodFile0600:${ParaformerInstallReceipt.FILENAME}",
            "fsyncFile:${ParaformerInstallReceipt.FILENAME}",
            "fsyncStagingDirectory",
            "atomicRenameStagingToFinal",
            "fsyncNoBackupRoot",
        )
        ops.writtenReceipt?.requireFrozenContract()
    }

    test("usable final install is never overwritten") {
        val ops = RecordingAtomicInstallOps(finalUsable = true)

        ParaformerSecureInstaller(ops).install() shouldBe ParaformerAtomicInstallResult.AlreadyUsable

        ops.operations shouldContainExactly listOf("finalUsable")
    }

    test("traversal symlink hardlink nonregular cross-filesystem and bad digest stop before rename") {
        val badLocations = listOf(
            validLocation().copy(stagingDirectChild = false),
            validLocation().copy(finalDirectChild = false),
            validLocation().copy(stagingRegularDirectory = false),
            validLocation().copy(stagingSymbolicLink = true),
            validLocation().copy(sameFileSystem = false),
        )
        badLocations.forEach { location ->
            val ops = RecordingAtomicInstallOps(location = location)
            shouldThrow<ParaformerAttemptException> {
                ParaformerSecureInstaller(ops).install()
            }
            ("atomicRenameStagingToFinal" in ops.operations) shouldBe false
        }

        val validModel = frozenModelFacts()
        val badModels = listOf(
            validModel.copy(filename = "../${ParaformerModelContract.MODEL_FILENAME}"),
            validModel.copy(regularFile = false),
            validModel.copy(symbolicLink = true),
            validModel.copy(linkCount = 2L),
            validModel.copy(sizeBytes = validModel.sizeBytes - 1L),
            validModel.copy(sha256 = "00"),
        )
        badModels.forEach { model ->
            val ops = RecordingAtomicInstallOps(modelFacts = model)
            shouldThrow<ParaformerAttemptException> {
                ParaformerSecureInstaller(ops).install()
            }.failure shouldBe ParaformerAttemptFailure.Integrity
            ("atomicRenameStagingToFinal" in ops.operations) shouldBe false
        }
    }
})

private class RecordingAtomicInstallOps(
    private val finalUsable: Boolean = false,
    private val location: ParaformerInstallLocationFacts = validLocation(),
    private val modelFacts: ParaformerArtifactFacts = frozenModelFacts(),
    private val tokensFacts: ParaformerArtifactFacts = frozenTokensFacts(),
) : ParaformerAtomicInstallOps {
    val operations = mutableListOf<String>()
    var writtenReceipt: ParaformerInstallReceipt? = null

    override fun finalInstallUsable(): Boolean {
        operations += "finalUsable"
        return finalUsable
    }

    override fun locationFacts(): ParaformerInstallLocationFacts {
        operations += "locationFacts"
        return location
    }

    override fun artifactFacts(filename: String): ParaformerArtifactFacts {
        operations += "artifactFacts:$filename"
        return when (filename) {
            ParaformerModelContract.MODEL_FILENAME -> modelFacts
            ParaformerModelContract.TOKENS_FILENAME -> tokensFacts
            else -> error("unexpected artifact $filename")
        }
    }

    override fun chmodDirectory0700() {
        operations += "chmodDirectory0700"
    }

    override fun chmodFile0600(filename: String) {
        operations += "chmodFile0600:$filename"
    }

    override fun fsyncFile(filename: String) {
        operations += "fsyncFile:$filename"
    }

    override fun writeReceipt(receipt: ParaformerInstallReceipt) {
        operations += "writeReceipt"
        writtenReceipt = receipt
    }

    override fun fsyncStagingDirectory() {
        operations += "fsyncStagingDirectory"
    }

    override fun atomicRenameStagingToFinal() {
        operations += "atomicRenameStagingToFinal"
    }

    override fun fsyncNoBackupRoot() {
        operations += "fsyncNoBackupRoot"
    }
}

private fun validLocation() = ParaformerInstallLocationFacts(
    stagingDirectChild = true,
    finalDirectChild = true,
    stagingRegularDirectory = true,
    stagingSymbolicLink = false,
    sameFileSystem = true,
)

private fun frozenModelFacts() = ParaformerArtifactFacts(
    filename = ParaformerModelContract.MODEL_FILENAME,
    sizeBytes = ParaformerModelContract.MODEL_BYTES,
    sha256 = ParaformerModelContract.MODEL_SHA256,
    regularFile = true,
    symbolicLink = false,
    linkCount = 1L,
)

private fun frozenTokensFacts() = ParaformerArtifactFacts(
    filename = ParaformerModelContract.TOKENS_FILENAME,
    sizeBytes = ParaformerModelContract.TOKENS_BYTES,
    sha256 = ParaformerModelContract.TOKENS_SHA256,
    regularFile = true,
    symbolicLink = false,
    linkCount = 1L,
)
