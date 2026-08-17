package com.wordtaker.keyboard.asrbenchmark.runner

import android.system.Os
import android.system.OsConstants
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.FileIdentity
import com.wordtaker.keyboard.asrbenchmark.core.Hashing
import com.wordtaker.keyboard.asrbenchmark.core.MeasuredFile
import com.wordtaker.keyboard.asrbenchmark.core.SecureFileAccess
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class AndroidSecureFileAccess(
    private val pathBindings: Map<String, String>,
) : SecureFileAccess {
    init {
        if (
            pathBindings.isEmpty() ||
            pathBindings.keys.any {
                it.isBlank() || it.contains('/') || it.contains('\\')
            } ||
            pathBindings.values.any { !File(it).isAbsolute }
        ) {
            throw BenchmarkContractException(
                "Android safe-file bindings must map opaque tokens to absolute paths",
            )
        }
    }

    override fun read(pathToken: String, requireCanonicalPcm: Boolean): MeasuredFile {
        val path = pathBindings[pathToken]
            ?: throw BenchmarkContractException("Android safe-file token is unknown")
        val descriptor = try {
            Os.open(
                path,
                OsConstants.O_RDONLY or
                    OsConstants.O_CLOEXEC or
                    OsConstants.O_NOFOLLOW,
                0,
            )
        } catch (error: Exception) {
            throw BenchmarkContractException(
                "Android benchmark file cannot be opened safely: ${error.javaClass.simpleName}",
            )
        }
        try {
            val before = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_nlink != 1L) {
                throw BenchmarkContractException(
                    "Android benchmark file must bind one regular-file inode",
                )
            }
            if (before.st_size < 0L || before.st_size > MAX_FILE_BYTES) {
                throw BenchmarkContractException("Android benchmark file size is invalid")
            }
            if (requireCanonicalPcm && before.st_size > MAX_PCM_BYTES) {
                throw BenchmarkContractException("canonical PCM exceeds the runner limit")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val retained = if (requireCanonicalPcm) {
                ByteArrayOutputStream(before.st_size.toInt())
            } else {
                null
            }
            val buffer = ByteArray(1024 * 1024)
            var total = 0L
            while (true) {
                val count = Os.read(descriptor, buffer, 0, buffer.size)
                if (count == 0) {
                    break
                }
                if (count < 0) {
                    throw BenchmarkContractException("Android safe-FD read failed")
                }
                total += count
                if (total > before.st_size || total > MAX_FILE_BYTES) {
                    throw BenchmarkContractException(
                        "Android benchmark file changed size while being read",
                    )
                }
                digest.update(buffer, 0, count)
                retained?.write(buffer, 0, count)
            }
            val after = Os.fstat(descriptor)
            if (
                before.st_dev != after.st_dev ||
                before.st_ino != after.st_ino ||
                before.st_size != after.st_size ||
                before.st_mode != after.st_mode ||
                before.st_nlink != after.st_nlink ||
                total != after.st_size
            ) {
                throw BenchmarkContractException(
                    "Android benchmark file identity changed during safe-FD read",
                )
            }
            val identity = FileIdentity(
                device = after.st_dev,
                inode = after.st_ino,
                sizeBytes = after.st_size,
                mode = after.st_mode,
                linkCount = after.st_nlink,
            )
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            return if (retained != null) {
                val bytes = retained.toByteArray()
                val measured = MeasuredFile.fromBytes(
                    pathToken = pathToken,
                    bytes = bytes,
                    identity = identity,
                    requireCanonicalPcm = true,
                )
                if (measured.sha256 != sha256) {
                    throw BenchmarkContractException(
                        "Android safe-FD PCM digest differs from retained bytes",
                    )
                }
                measured
            } else {
                MeasuredFile.fromDigest(
                    pathToken = pathToken,
                    identity = identity,
                    sha256 = Hashing.requireSha256(
                        sha256,
                        "Android safe-FD file digest",
                    ),
                )
            }
        } finally {
            try {
                Os.close(descriptor)
            } catch (_: Exception) {
                // The measured result is already unusable if an earlier exception escaped.
            }
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_PCM_BYTES = 256L * 1024L * 1024L
    }
}
