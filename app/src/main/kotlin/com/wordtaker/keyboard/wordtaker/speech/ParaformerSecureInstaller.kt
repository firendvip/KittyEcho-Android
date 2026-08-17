package com.wordtaker.keyboard.wordtaker.speech

internal data class ParaformerInstallLocationFacts(
    val stagingDirectChild: Boolean,
    val finalDirectChild: Boolean,
    val stagingRegularDirectory: Boolean,
    val stagingSymbolicLink: Boolean,
    val sameFileSystem: Boolean,
)

internal interface ParaformerAtomicInstallOps {
    fun checkCancelled() = Unit
    fun finalInstallUsable(): Boolean
    fun locationFacts(): ParaformerInstallLocationFacts
    fun artifactFacts(filename: String): ParaformerArtifactFacts
    fun chmodDirectory0700()
    fun chmodFile0600(filename: String)
    fun fsyncFile(filename: String)
    fun writeReceipt(receipt: ParaformerInstallReceipt)
    fun fsyncStagingDirectory()
    fun atomicRenameStagingToFinal()
    fun fsyncNoBackupRoot()
}

internal enum class ParaformerAtomicInstallResult {
    Installed,
    AlreadyUsable,
}

/** Validates the complete staging tree before the only operation that makes it live. */
internal class ParaformerSecureInstaller(
    private val ops: ParaformerAtomicInstallOps,
) {
    @Synchronized
    fun install(): ParaformerAtomicInstallResult {
        ops.checkCancelled()
        if (ops.finalInstallUsable()) return ParaformerAtomicInstallResult.AlreadyUsable

        val location = ops.locationFacts()
        if (
            !location.stagingDirectChild ||
            !location.finalDirectChild ||
            !location.stagingRegularDirectory ||
            location.stagingSymbolicLink ||
            !location.sameFileSystem
        ) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
        }

        val artifacts = listOf(
            ops.artifactFacts(ParaformerModelContract.MODEL_FILENAME),
            ops.artifactFacts(ParaformerModelContract.TOKENS_FILENAME),
        )
        try {
            ParaformerModelContract.validate(artifacts)
        } catch (error: AsrFailureException) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity, error)
        }
        ops.checkCancelled()

        ops.chmodDirectory0700()
        artifacts.forEach { artifact ->
            ops.chmodFile0600(artifact.filename)
            ops.fsyncFile(artifact.filename)
        }
        val receipt = ParaformerInstallReceipt.frozenContract().also {
            it.requireFrozenContract()
        }
        ops.writeReceipt(receipt)
        ops.chmodFile0600(ParaformerInstallReceipt.FILENAME)
        ops.fsyncFile(ParaformerInstallReceipt.FILENAME)
        ops.fsyncStagingDirectory()
        ops.checkCancelled()
        ops.atomicRenameStagingToFinal()
        ops.fsyncNoBackupRoot()
        return ParaformerAtomicInstallResult.Installed
    }
}
