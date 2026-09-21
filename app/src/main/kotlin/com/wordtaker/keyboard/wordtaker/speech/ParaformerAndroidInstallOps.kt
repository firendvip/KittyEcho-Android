package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal object ParaformerStoragePaths {
    const val STAGING_DIRECTORY = ".paraformer-staging-${ParaformerModelContract.ARTIFACT_REVISION}"

    fun stagingRoot(context: Context): File =
        File(context.noBackupFilesDir, STAGING_DIRECTORY).absoluteFile

    fun finalRoot(context: Context): File =
        File(context.noBackupFilesDir, ParaformerModelContract.PRIVATE_DIRECTORY).absoluteFile
}

/** Android syscall adapter for [ParaformerSecureInstaller]. */
internal class ParaformerAndroidInstallOps(private val context: Context) : ParaformerAtomicInstallOps {
    private val noBackupRoot = context.noBackupFilesDir.absoluteFile
    private val stagingRoot = ParaformerStoragePaths.stagingRoot(context)
    private val finalRoot = ParaformerStoragePaths.finalRoot(context)

    override fun checkCancelled() = Unit

    fun ensureStagingDirectory(): File {
        requireDirectChildren()
        if (!stagingRoot.exists()) {
            if (!stagingRoot.mkdir()) {
                throw ParaformerAttemptException(ParaformerAttemptFailure.Storage)
            }
        }
        val stat = Os.lstat(stagingRoot.absolutePath)
        if (!OsConstants.S_ISDIR(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
        }
        Os.chmod(stagingRoot.absolutePath, DIRECTORY_MODE_0700)
        fsyncDirectory(noBackupRoot)
        return stagingRoot
    }

    override fun finalInstallUsable(): Boolean = runCatching {
        ParaformerPrivateModelStore(context).validateAndResolve()
    }.isSuccess

    override fun locationFacts(): ParaformerInstallLocationFacts {
        requireDirectChildren()
        val rootStat = Os.lstat(noBackupRoot.absolutePath)
        val stagingStat = Os.lstat(stagingRoot.absolutePath)
        return ParaformerInstallLocationFacts(
            stagingDirectChild = stagingRoot.parentFile?.absoluteFile == noBackupRoot,
            finalDirectChild = finalRoot.parentFile?.absoluteFile == noBackupRoot,
            stagingRegularDirectory = OsConstants.S_ISDIR(stagingStat.st_mode),
            stagingSymbolicLink = OsConstants.S_ISLNK(stagingStat.st_mode),
            sameFileSystem = rootStat.st_dev == stagingStat.st_dev,
        )
    }

    override fun artifactFacts(filename: String): ParaformerArtifactFacts =
        measureFixedArtifact(File(stagingRoot, requireArtifactFilename(filename)))

    override fun chmodDirectory0700() {
        Os.chmod(stagingRoot.absolutePath, DIRECTORY_MODE_0700)
    }

    override fun chmodFile0600(filename: String) {
        val fixed = when (filename) {
            ParaformerInstallReceipt.FILENAME -> filename
            else -> requireArtifactFilename(filename)
        }
        Os.chmod(File(stagingRoot, fixed).absolutePath, FILE_MODE_0600)
    }

    override fun fsyncFile(filename: String) {
        val fixed = when (filename) {
            ParaformerInstallReceipt.FILENAME -> filename
            else -> requireArtifactFilename(filename)
        }
        fsyncFixedFile(File(stagingRoot, fixed))
    }

    override fun writeReceipt(receipt: ParaformerInstallReceipt) {
        receipt.requireFrozenContract()
        val receiptFile = File(stagingRoot, ParaformerInstallReceipt.FILENAME)
        val temporary = File(stagingRoot, "${ParaformerInstallReceipt.FILENAME}.tmp")
        deleteFixedEntryNoFollow(temporary)
        val payload = receipt.encode().toByteArray(Charsets.UTF_8)
        val descriptor = Os.open(
            temporary.absolutePath,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            FILE_MODE_0600,
        )
        FileOutputStream(descriptor).use { output ->
            output.write(payload)
            output.flush()
            output.fd.sync()
        }
        Os.chmod(temporary.absolutePath, FILE_MODE_0600)
        deleteFixedEntryNoFollow(receiptFile)
        Os.rename(temporary.absolutePath, receiptFile.absolutePath)
    }

    override fun fsyncStagingDirectory() {
        fsyncDirectory(stagingRoot)
    }

    override fun atomicRenameStagingToFinal() {
        requireDirectChildren()
        if (finalInstallUsable()) return
        deleteFixedEntryNoFollow(finalRoot)
        Os.rename(stagingRoot.absolutePath, finalRoot.absolutePath)
    }

    override fun fsyncNoBackupRoot() {
        fsyncDirectory(noBackupRoot)
    }

    private fun requireDirectChildren() {
        if (
            !noBackupRoot.isDirectory ||
            stagingRoot.parentFile?.absoluteFile != noBackupRoot ||
            finalRoot.parentFile?.absoluteFile != noBackupRoot ||
            stagingRoot.name != ParaformerStoragePaths.STAGING_DIRECTORY ||
            finalRoot.name != ParaformerModelContract.PRIVATE_DIRECTORY
        ) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
        }
    }

    private fun requireArtifactFilename(filename: String): String = when (filename) {
        ParaformerModelContract.MODEL_FILENAME,
        ParaformerModelContract.TOKENS_FILENAME,
        -> filename
        else -> throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
    }

    private companion object {
        const val FILE_MODE_0600 = 0x180
        const val DIRECTORY_MODE_0700 = 0x1C0
    }
}

internal fun measureFixedArtifact(file: File): ParaformerArtifactFacts {
    val descriptor = try {
        Os.open(
            file.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0,
        )
    } catch (error: Throwable) {
        throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity, error)
    }
    try {
        val before = Os.fstat(descriptor)
        if (!OsConstants.S_ISREG(before.st_mode) || before.st_nlink != 1L) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = Os.read(descriptor, buffer, 0, buffer.size)
            if (count == 0) break
            digest.update(buffer, 0, count)
        }
        val after = Os.fstat(descriptor)
        if (
            before.st_dev != after.st_dev ||
            before.st_ino != after.st_ino ||
            before.st_size != after.st_size ||
            before.st_mtime != after.st_mtime
        ) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
        }
        return ParaformerArtifactFacts(
            filename = file.name,
            sizeBytes = after.st_size,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            regularFile = true,
            symbolicLink = false,
            linkCount = after.st_nlink,
            device = after.st_dev,
            inode = after.st_ino,
            modifiedSeconds = after.st_mtime,
        )
    } finally {
        runCatching { Os.close(descriptor) }
    }
}

internal fun fsyncFixedFile(file: File) {
    val descriptor = Os.open(
        file.absolutePath,
        OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
        0,
    )
    try {
        val stat = Os.fstat(descriptor)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
        }
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}
