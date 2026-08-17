package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONObject

/** Crash-safe process state stored beside the staging tree, outside Android backup. */
internal class ParaformerAndroidModelStateStore(context: Context) : ParaformerModelStateStore {
    private val noBackupRoot = context.noBackupFilesDir.absoluteFile
    private val stateFile = File(noBackupRoot, STATE_FILENAME)
    private val temporaryFile = File(noBackupRoot, "$STATE_FILENAME.tmp")

    override fun load(): ParaformerLifecycleSnapshot {
        if (!stateFile.exists()) return ParaformerLifecycleSnapshot()
        return runCatching {
            decode(readSecureSmallFile(stateFile, MAX_STATE_BYTES))
        }.getOrElse { ParaformerLifecycleSnapshot() }
    }

    @Synchronized
    override fun save(snapshot: ParaformerLifecycleSnapshot) {
        ensureDirectRoot()
        deleteFixedEntryNoFollow(temporaryFile)
        val payload = encode(snapshot).toByteArray(Charsets.UTF_8)
        check(payload.size <= MAX_STATE_BYTES)
        val descriptor = Os.open(
            temporaryFile.absolutePath,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            FILE_MODE_0600,
        )
        FileOutputStream(descriptor).use { output ->
            output.write(payload)
            output.flush()
            output.fd.sync()
        }
        Os.chmod(temporaryFile.absolutePath, FILE_MODE_0600)
        Os.rename(temporaryFile.absolutePath, stateFile.absolutePath)
        fsyncDirectory(noBackupRoot)
        observers.forEach { it(snapshot) }
    }

    @Synchronized
    override fun clear() {
        deleteFixedEntryNoFollow(temporaryFile)
        deleteFixedEntryNoFollow(stateFile)
        fsyncDirectory(noBackupRoot)
        val empty = ParaformerLifecycleSnapshot()
        observers.forEach { it(empty) }
    }

    override fun observe(observer: (ParaformerLifecycleSnapshot) -> Unit) {
        observers += observer
    }

    private fun ensureDirectRoot() {
        check(noBackupRoot.isDirectory)
        check(stateFile.parentFile?.absoluteFile == noBackupRoot)
        check(temporaryFile.parentFile?.absoluteFile == noBackupRoot)
    }

    private fun encode(snapshot: ParaformerLifecycleSnapshot): String = JSONObject()
        .put("format", STATE_FORMAT)
        .put("phase", snapshot.phase.name)
        .put("downloadedBytes", snapshot.downloadedBytes)
        .put("mobileConfirmed", snapshot.mobileConfirmed)
        .put("pauseReason", snapshot.pauseReason?.name)
        .put("workEnqueued", snapshot.workEnqueued)
        .put("failure", snapshot.failure?.name)
        .toString()

    private fun decode(encoded: String): ParaformerLifecycleSnapshot {
        val json = JSONObject(encoded)
        check(json.getInt("format") == STATE_FORMAT)
        val phase = ParaformerModelPhase.valueOf(json.getString("phase"))
        val pauseReason = json.optionalEnum<ParaformerPauseReason>("pauseReason")
        val failure = json.optionalEnum<ParaformerModelFailure>("failure")
        return ParaformerLifecycleSnapshot(
            phase = phase,
            downloadedBytes = json.getLong("downloadedBytes")
                .coerceIn(0L, ParaformerModelContract.TOTAL_BYTES),
            mobileConfirmed = json.getBoolean("mobileConfirmed"),
            pauseReason = pauseReason,
            workEnqueued = json.getBoolean("workEnqueued"),
            failure = failure,
        )
    }

    private companion object {
        const val STATE_FILENAME = ".paraformer-download-state.json"
        const val STATE_FORMAT = 1
        const val MAX_STATE_BYTES = 16 * 1024
        const val FILE_MODE_0600 = 0x180
        val observers = CopyOnWriteArraySet<(ParaformerLifecycleSnapshot) -> Unit>()
    }
}

private inline fun <reified T : Enum<T>> JSONObject.optionalEnum(name: String): T? {
    if (!has(name) || isNull(name)) return null
    return enumValueOf<T>(getString(name))
}

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
    if (bytes.size > maxBytes) {
        throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
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
    // android.system.Os exposes POSIX remove(3), which removes an empty directory or the
    // link itself and never follows a final symlink component.
    Os.remove(file.absolutePath)
}
