package com.wordtaker.keyboard.wordtaker.speech

import android.app.job.JobScheduler
import android.content.Context
import android.content.res.AssetManager
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal interface ParaformerBundledInstallOps : ParaformerAtomicInstallOps {
    fun deleteObsoleteDownloadState()
    fun resetForBundledInstall()
    fun deleteFailedStaging()
    fun writeBundledAsset(filename: String, expectedBytes: Long, expectedSha256: String)
}

/** Installs the frozen APK assets into the validated no-backup model directory. */
internal class ParaformerBundledAssetInstaller(
    private val ops: ParaformerBundledInstallOps,
) {
    constructor(context: Context) : this(ParaformerAndroidBundledInstallOps(context))

    @Synchronized
    fun ensureInstalled(): ParaformerAtomicInstallResult {
        ops.deleteObsoleteDownloadState()
        if (ops.finalInstallUsable()) {
            ops.deleteFailedStaging()
            return ParaformerAtomicInstallResult.AlreadyUsable
        }
        ops.resetForBundledInstall()
        return try {
            ops.writeBundledAsset(
                ParaformerModelContract.MODEL_FILENAME,
                ParaformerModelContract.MODEL_BYTES,
                ParaformerModelContract.MODEL_SHA256,
            )
            ops.writeBundledAsset(
                ParaformerModelContract.TOKENS_FILENAME,
                ParaformerModelContract.TOKENS_BYTES,
                ParaformerModelContract.TOKENS_SHA256,
            )
            ParaformerSecureInstaller(ops).install()
        } catch (error: Throwable) {
            runCatching { ops.deleteFailedStaging() }
            throw error
        }
    }
}

/** Android fd-based implementation; every destination open rejects final-component links. */
internal class ParaformerAndroidBundledInstallOps(
    private val context: Context,
) : ParaformerBundledInstallOps {
    private val delegate = ParaformerAndroidInstallOps(context)
    private val noBackupRoot = context.noBackupFilesDir.absoluteFile

    override fun checkCancelled() = delegate.checkCancelled()
    override fun finalInstallUsable(): Boolean = delegate.finalInstallUsable()
    override fun locationFacts(): ParaformerInstallLocationFacts = delegate.locationFacts()
    override fun artifactFacts(filename: String): ParaformerArtifactFacts =
        delegate.artifactFacts(filename)
    override fun chmodDirectory0700() = delegate.chmodDirectory0700()
    override fun chmodFile0600(filename: String) = delegate.chmodFile0600(filename)
    override fun fsyncFile(filename: String) = delegate.fsyncFile(filename)
    override fun writeReceipt(receipt: ParaformerInstallReceipt) = delegate.writeReceipt(receipt)
    override fun fsyncStagingDirectory() = delegate.fsyncStagingDirectory()
    override fun atomicRenameStagingToFinal() = delegate.atomicRenameStagingToFinal()
    override fun fsyncNoBackupRoot() = delegate.fsyncNoBackupRoot()

    override fun deleteObsoleteDownloadState() {
        listOf(
            ".paraformer-download-state.json",
            ".paraformer-download-state.json.tmp",
        ).forEach { name -> deleteFixedEntryNoFollow(File(noBackupRoot, name)) }
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler?.allPendingJobs.orEmpty()
                .filter { job ->
                    job.service.packageName == context.packageName &&
                        job.service.className == LEGACY_WORK_MANAGER_JOB_SERVICE
                }
                .forEach { job -> scheduler?.cancel(job.id) }
        }
        fsyncDirectory(noBackupRoot)
    }

    override fun resetForBundledInstall() {
        deleteFixedEntryNoFollow(ParaformerStoragePaths.stagingRoot(context))
        deleteFixedEntryNoFollow(ParaformerStoragePaths.finalRoot(context))
        val required = ParaformerModelContract.TOTAL_BYTES + INSTALL_RESERVE_BYTES
        if (StatFs(noBackupRoot.absolutePath).availableBytes < required) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Storage)
        }
        delegate.ensureStagingDirectory()
    }

    override fun deleteFailedStaging() {
        deleteFixedEntryNoFollow(ParaformerStoragePaths.stagingRoot(context))
        fsyncDirectory(noBackupRoot)
    }

    override fun writeBundledAsset(
        filename: String,
        expectedBytes: Long,
        expectedSha256: String,
    ) {
        requireArtifactContract(filename, expectedBytes, expectedSha256)
        val stagingRoot = delegate.ensureStagingDirectory()
        val destination = File(stagingRoot, filename)
        deleteFixedEntryNoFollow(destination)
        val input = try {
            context.assets.open(
                "$ASSET_DIRECTORY/$filename",
                AssetManager.ACCESS_STREAMING,
            )
        } catch (error: Throwable) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity, error)
        }
        val descriptor = try {
            Os.open(
                destination.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                FILE_MODE_0600,
            )
        } catch (error: Throwable) {
            runCatching { input.close() }
            throw ParaformerAttemptException(ParaformerAttemptFailure.Storage, error)
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            input.use {
                FileOutputStream(descriptor).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > expectedBytes) {
                            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (
                copied != expectedBytes ||
                !digest.digest().toHex().equals(expectedSha256, ignoreCase = true)
            ) {
                throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
            }
            Os.chmod(destination.absolutePath, FILE_MODE_0600)
            fsyncFixedFile(destination)
            fsyncDirectory(stagingRoot)
        } catch (error: ParaformerAttemptException) {
            throw error
        } catch (error: Throwable) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Storage, error)
        }
    }

    private fun requireArtifactContract(filename: String, bytes: Long, sha256: String) {
        val valid = when (filename) {
            ParaformerModelContract.MODEL_FILENAME ->
                bytes == ParaformerModelContract.MODEL_BYTES &&
                    sha256 == ParaformerModelContract.MODEL_SHA256
            ParaformerModelContract.TOKENS_FILENAME ->
                bytes == ParaformerModelContract.TOKENS_BYTES &&
                    sha256 == ParaformerModelContract.TOKENS_SHA256
            else -> false
        }
        if (!valid) throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val ASSET_DIRECTORY = "models/paraformer"
        const val COPY_BUFFER_BYTES = 1024 * 1024
        const val INSTALL_RESERVE_BYTES = 128L * 1024L * 1024L
        const val FILE_MODE_0600 = 0x180
        const val LEGACY_WORK_MANAGER_JOB_SERVICE =
            "androidx.work.impl.background.systemjob.SystemJobService"
    }
}
