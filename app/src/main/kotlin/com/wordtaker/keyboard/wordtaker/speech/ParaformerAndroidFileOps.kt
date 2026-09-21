package com.wordtaker.keyboard.wordtaker.speech

import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

internal fun readSecureSmallFile(file: File, maxBytes: Int): String {
    val before = Os.lstat(file.absolutePath)
    if (
        !OsConstants.S_ISREG(before.st_mode) ||
        OsConstants.S_ISLNK(before.st_mode) ||
        before.st_nlink != 1L ||
        before.st_size !in 0..maxBytes.toLong()
    ) {
        throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
    }
    val descriptor = Os.open(
        file.absolutePath,
        OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
        0,
    )
    val bytes = FileInputStream(descriptor).use { input ->
        val output = ByteArrayOutputStream(minOf(maxBytes, 4096))
        val buffer = ByteArray(minOf(maxBytes + 1, 4096))
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    val after = Os.lstat(file.absolutePath)
    if (
        before.st_dev != after.st_dev ||
        before.st_ino != after.st_ino ||
        before.st_size != after.st_size ||
        before.st_mtime != after.st_mtime
    ) {
        throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
    }
    return bytes.toString(Charsets.UTF_8)
}

internal fun fsyncDirectory(directory: File) {
    val descriptor = Os.open(
        directory.absolutePath,
        OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
        0,
    )
    try {
        val stat = Os.fstat(descriptor)
        if (!OsConstants.S_ISDIR(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
        }
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

internal fun deleteFixedEntryNoFollow(file: File) {
    val stat = runCatching { Os.lstat(file.absolutePath) }.getOrNull() ?: return
    if (OsConstants.S_ISDIR(stat.st_mode) && !OsConstants.S_ISLNK(stat.st_mode)) {
        val children = file.listFiles()
            ?: throw ParaformerAttemptException(ParaformerAttemptFailure.Storage)
        children.forEach(::deleteFixedEntryNoFollow)
    }
    Os.remove(file.absolutePath)
}
