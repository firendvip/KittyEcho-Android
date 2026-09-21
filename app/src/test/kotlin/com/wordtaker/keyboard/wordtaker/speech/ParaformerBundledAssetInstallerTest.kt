package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ParaformerBundledAssetInstallerTest : FunSpec({

    test("valid existing private model is reused and stale staging is removed") {
        val ops = RecordingBundledInstallOps(finalUsable = true)

        ParaformerBundledAssetInstaller(ops).ensureInstalled() shouldBe
            ParaformerAtomicInstallResult.AlreadyUsable

        ops.operations shouldContainExactly listOf(
            "deleteObsoleteDownloadState",
            "finalInstallUsable",
            "deleteFailedStaging",
        )
    }

    test("missing or corrupt private model is rebuilt from both frozen assets atomically") {
        val ops = RecordingBundledInstallOps()

        ParaformerBundledAssetInstaller(ops).ensureInstalled() shouldBe
            ParaformerAtomicInstallResult.Installed

        ops.operations shouldContainExactly listOf(
            "deleteObsoleteDownloadState",
            "finalInstallUsable",
            "resetForBundledInstall",
            "writeBundledAsset:${ParaformerModelContract.MODEL_FILENAME}",
            "writeBundledAsset:${ParaformerModelContract.TOKENS_FILENAME}",
            "checkCancelled",
            "finalInstallUsable",
            "locationFacts",
            "artifactFacts:${ParaformerModelContract.MODEL_FILENAME}",
            "artifactFacts:${ParaformerModelContract.TOKENS_FILENAME}",
            "checkCancelled",
            "chmodDirectory0700",
            "chmodFile0600:${ParaformerModelContract.MODEL_FILENAME}",
            "fsyncFile:${ParaformerModelContract.MODEL_FILENAME}",
            "chmodFile0600:${ParaformerModelContract.TOKENS_FILENAME}",
            "fsyncFile:${ParaformerModelContract.TOKENS_FILENAME}",
            "writeReceipt",
            "chmodFile0600:${ParaformerInstallReceipt.FILENAME}",
            "fsyncFile:${ParaformerInstallReceipt.FILENAME}",
            "fsyncStagingDirectory",
            "checkCancelled",
            "atomicRenameStagingToFinal",
            "fsyncNoBackupRoot",
        )
    }

    test("asset copy failure removes staging and never publishes a final directory") {
        val ops = RecordingBundledInstallOps(failOnAsset = ParaformerModelContract.TOKENS_FILENAME)

        shouldThrow<ParaformerAttemptException> {
            ParaformerBundledAssetInstaller(ops).ensureInstalled()
        }.failure shouldBe ParaformerAttemptFailure.Integrity

        ops.operations.last() shouldBe "deleteFailedStaging"
        ("atomicRenameStagingToFinal" in ops.operations) shouldBe false
    }
})

private class RecordingBundledInstallOps(
    private val finalUsable: Boolean = false,
    private val failOnAsset: String? = null,
) : ParaformerBundledInstallOps {
    val operations = mutableListOf<String>()

    override fun deleteObsoleteDownloadState() {
        operations += "deleteObsoleteDownloadState"
    }

    override fun resetForBundledInstall() {
        operations += "resetForBundledInstall"
    }

    override fun deleteFailedStaging() {
        operations += "deleteFailedStaging"
    }

    override fun writeBundledAsset(filename: String, expectedBytes: Long, expectedSha256: String) {
        operations += "writeBundledAsset:$filename"
        if (filename == failOnAsset) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
        }
    }

    override fun checkCancelled() {
        operations += "checkCancelled"
    }

    override fun finalInstallUsable(): Boolean {
        operations += "finalInstallUsable"
        return finalUsable
    }

    override fun locationFacts(): ParaformerInstallLocationFacts {
        operations += "locationFacts"
        return ParaformerInstallLocationFacts(
            stagingDirectChild = true,
            finalDirectChild = true,
            stagingRegularDirectory = true,
            stagingSymbolicLink = false,
            sameFileSystem = true,
        )
    }

    override fun artifactFacts(filename: String): ParaformerArtifactFacts {
        operations += "artifactFacts:$filename"
        return when (filename) {
            ParaformerModelContract.MODEL_FILENAME -> ParaformerArtifactFacts(
                filename,
                ParaformerModelContract.MODEL_BYTES,
                ParaformerModelContract.MODEL_SHA256,
                regularFile = true,
                symbolicLink = false,
            )
            ParaformerModelContract.TOKENS_FILENAME -> ParaformerArtifactFacts(
                filename,
                ParaformerModelContract.TOKENS_BYTES,
                ParaformerModelContract.TOKENS_SHA256,
                regularFile = true,
                symbolicLink = false,
            )
            else -> error("unexpected artifact")
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
        receipt.requireFrozenContract()
        operations += "writeReceipt"
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
