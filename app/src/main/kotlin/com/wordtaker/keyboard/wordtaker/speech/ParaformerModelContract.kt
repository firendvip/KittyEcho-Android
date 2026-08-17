package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest

data class ParaformerArtifactFacts(
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
    val regularFile: Boolean,
    val symbolicLink: Boolean,
    val linkCount: Long = 1,
    val device: Long = 0,
    val inode: Long = 0,
    val modifiedSeconds: Long = 0,
)

object ParaformerModelContract {
    const val MODEL_ID = "paraformer_int8"
    const val UPSTREAM_MODEL_ID =
        "iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch"
    const val UPSTREAM_VERSION = "v2.0.4"
    const val CONVERSION_REVISION = "bbf29cf22ede51f541c052af8f8e77fc54c76e21"
    const val ARTIFACT_REVISION = "fe3e2bbfa0a0d3789b653c4b6cf3f87a5dbf2b94"
    const val RUNTIME_VERSION = "sherpa-onnx-1.13.3"
    /** Recognition attribution must identify the exact deployed bytes, not the converter. */
    const val MODEL_REVISION = ARTIFACT_REVISION
    const val PRIVATE_DIRECTORY = "asr-paraformer-int8-$MODEL_REVISION"
    const val MODEL_FILENAME = "model.int8.onnx"
    const val TOKENS_FILENAME = "tokens.txt"
    const val MODEL_BYTES = 223_385_835L
    const val MODEL_SHA256 =
        "9ada9127ca5b82320385ac12340eb8b05dee64fd45cf8cf593ec693826ec2fd7"
    const val TOKENS_BYTES = 75_756L
    const val TOKENS_SHA256 =
        "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"
    const val TOTAL_BYTES = MODEL_BYTES + TOKENS_BYTES

    const val OFFICIAL_BASE_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28/resolve/fe3e2bbfa0a0d3789b653c4b6cf3f87a5dbf2b94"
    const val MIRROR_BASE_URL =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28/resolve/fe3e2bbfa0a0d3789b653c4b6cf3f87a5dbf2b94"

    fun validate(artifacts: List<ParaformerArtifactFacts>) {
        val byName = artifacts.associateBy { it.filename }
        if (
            artifacts.size != 2 ||
            byName[MODEL_FILENAME] == null ||
            byName[TOKENS_FILENAME] == null
        ) {
            throw AsrModelMissingException()
        }
        requireExact(byName.getValue(MODEL_FILENAME), MODEL_BYTES, MODEL_SHA256)
        requireExact(byName.getValue(TOKENS_FILENAME), TOKENS_BYTES, TOKENS_SHA256)
    }

    private fun requireExact(
        facts: ParaformerArtifactFacts,
        expectedBytes: Long,
        expectedSha256: String,
    ) {
        if (
            !facts.regularFile ||
            facts.symbolicLink ||
            facts.linkCount != 1L ||
            facts.sizeBytes != expectedBytes ||
            !facts.sha256.equals(expectedSha256, ignoreCase = true)
        ) {
            throw AsrModelCorruptException()
        }
    }
}

internal data class ValidatedParaformerFiles(
    val modelPath: String,
    val tokensPath: String,
    val artifacts: List<ParaformerArtifactFacts>,
)

/** Opens only fixed, regular, non-symlink files under this app's no-backup directory. */
internal class ParaformerPrivateModelStore(context: Context) {
    private val noBackupRoot = context.noBackupFilesDir.absoluteFile
    private val modelRoot = File(noBackupRoot, ParaformerModelContract.PRIVATE_DIRECTORY)

    fun validateAndResolve(): ValidatedParaformerFiles {
        validateRoot()
        val model = File(modelRoot, ParaformerModelContract.MODEL_FILENAME)
        val tokens = File(modelRoot, ParaformerModelContract.TOKENS_FILENAME)
        if (!model.exists() || !tokens.exists()) throw AsrModelMissingException()
        val artifacts = listOf(measure(model), measure(tokens))
        ParaformerModelContract.validate(artifacts)
        validateOrMigrateReceipt(model, tokens)
        return ValidatedParaformerFiles(
            modelPath = model.absolutePath,
            tokensPath = tokens.absolutePath,
            artifacts = artifacts,
        )
    }

    private fun validateOrMigrateReceipt(model: File, tokens: File) {
        val receipt = File(modelRoot, ParaformerInstallReceipt.FILENAME)
        if (!receipt.exists()) {
            // Upgrade migration: exact frozen bytes from the pre-receipt Paraformer build can be
            // adopted offline. The two artifacts were already opened O_NOFOLLOW and fully hashed.
            Os.chmod(modelRoot.absolutePath, DIRECTORY_MODE_0700)
            Os.chmod(model.absolutePath, FILE_MODE_0600)
            Os.chmod(tokens.absolutePath, FILE_MODE_0600)
            writeReceipt(receipt)
        }
        try {
            ParaformerInstallReceipt.decode(
                readSecureSmallFile(receipt, MAX_RECEIPT_BYTES),
            ).requireFrozenContract()
            Os.chmod(receipt.absolutePath, FILE_MODE_0600)
        } catch (error: Throwable) {
            throw AsrModelCorruptException(error)
        }
    }

    private fun writeReceipt(receipt: File) {
        val temporary = File(modelRoot, "${ParaformerInstallReceipt.FILENAME}.tmp")
        try {
            deleteFixedEntryNoFollow(temporary)
            val descriptor = Os.open(
                temporary.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                FILE_MODE_0600,
            )
            FileOutputStream(descriptor).use { output ->
                output.write(ParaformerInstallReceipt.frozenContract().encode().toByteArray())
                output.flush()
                output.fd.sync()
            }
            Os.chmod(temporary.absolutePath, FILE_MODE_0600)
            Os.rename(temporary.absolutePath, receipt.absolutePath)
            fsyncDirectory(modelRoot)
        } catch (error: Throwable) {
            runCatching { deleteFixedEntryNoFollow(temporary) }
            throw AsrModelCorruptException(error)
        }
    }

    private fun validateRoot() {
        if (!modelRoot.exists()) throw AsrModelMissingException()
        try {
            val expectedParent = noBackupRoot.canonicalFile
            if (
                modelRoot.parentFile?.canonicalFile != expectedParent ||
                !modelRoot.isDirectory
            ) {
                throw AsrModelCorruptException()
            }
            // Android may expose the same trusted app-data root through /data/user/0
            // and a different canonical alias. lstat the direct child itself so a
            // model-directory symlink is still rejected without rejecting that alias.
            val stat = Os.lstat(modelRoot.absolutePath)
            if (!OsConstants.S_ISDIR(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) {
                throw AsrModelCorruptException()
            }
        } catch (error: AsrFailureException) {
            throw error
        } catch (error: Throwable) {
            throw AsrModelCorruptException(error)
        }
    }

    private fun measure(file: File): ParaformerArtifactFacts {
        val descriptor = try {
            Os.open(
                file.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            )
        } catch (error: FileNotFoundException) {
            throw AsrModelMissingException()
        } catch (error: Throwable) {
            throw AsrModelCorruptException(error)
        }
        try {
            val before = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_nlink != 1L) {
                throw AsrModelCorruptException()
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_BYTES)
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
                throw AsrModelCorruptException()
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
        } catch (error: AsrFailureException) {
            throw error
        } catch (error: Throwable) {
            throw AsrModelCorruptException(error)
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }

    private companion object {
        const val HASH_BUFFER_BYTES = 1024 * 1024
        const val MAX_RECEIPT_BYTES = 16 * 1024
        const val FILE_MODE_0600 = 0x180
        const val DIRECTORY_MODE_0700 = 0x1C0
    }
}
