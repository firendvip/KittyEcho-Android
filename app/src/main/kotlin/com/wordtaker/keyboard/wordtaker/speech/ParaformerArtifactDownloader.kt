package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

internal class ParaformerDownloadPausedException(
    val reason: ParaformerPauseReason,
) : RuntimeException("Paraformer download paused: $reason")

internal data class ParaformerFrozenArtifact(
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal class ParaformerArtifactDownloader(
    context: Context,
    private val client: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val installOps = ParaformerAndroidInstallOps(appContext)
    private val stagingRoot: File
        get() = installOps.ensureStagingDirectory()

    fun downloadAll(
        onProgress: (Long) -> Unit,
        shouldCancel: () -> Boolean,
        pauseReason: () -> ParaformerPauseReason?,
    ) {
        if (installOps.finalInstallUsable()) {
            onProgress(ParaformerModelContract.TOTAL_BYTES)
            return
        }
        val reusable = ARTIFACTS.sumOf(::reusableBytes)
        val required = ParaformerDownloadPolicy.requiredFreeBytes(
            expectedBytes = ParaformerModelContract.TOTAL_BYTES,
            resumableBytes = reusable,
        )
        if (StatFs(appContext.noBackupFilesDir.absolutePath).availableBytes < required) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Storage)
        }

        var completedBefore = 0L
        ARTIFACTS.forEach { artifact ->
            pauseReason()?.let { throw ParaformerDownloadPausedException(it) }
            if (validatedStagingArtifact(artifact)) {
                completedBefore += artifact.sizeBytes
                onProgress(completedBefore)
                return@forEach
            }
            downloadOne(
                artifact = artifact,
                completedBefore = completedBefore,
                onProgress = onProgress,
                shouldCancel = shouldCancel,
                pauseReason = pauseReason,
            )
            completedBefore += artifact.sizeBytes
            onProgress(completedBefore)
        }
    }

    private fun downloadOne(
        artifact: ParaformerFrozenArtifact,
        completedBefore: Long,
        onProgress: (Long) -> Unit,
        shouldCancel: () -> Boolean,
        pauseReason: () -> ParaformerPauseReason?,
    ) {
        val resume = loadResume(artifact)
        val preferredSource = resume?.source ?: ParaformerArtifactSource.Official
        val initialOffset = resume?.offset ?: 0L
        try {
            if (preferredSource == ParaformerArtifactSource.Mirror) {
                attemptWithResumeRecovery(
                    artifact = artifact,
                    source = preferredSource,
                    initialOffset = initialOffset,
                    etag = resume?.etag,
                    completedBefore = completedBefore,
                    onProgress = onProgress,
                    shouldCancel = shouldCancel,
                    pauseReason = pauseReason,
                )
            } else {
                ParaformerDownloadPolicy.runWithMirrorFallback(initialOffset) { source, offset ->
                    if (source == ParaformerArtifactSource.Mirror) resetPartial(artifact)
                    attemptWithResumeRecovery(
                        artifact = artifact,
                        source = source,
                        initialOffset = offset,
                        etag = if (source == ParaformerArtifactSource.Official) resume?.etag else null,
                        completedBefore = completedBefore,
                        onProgress = onProgress,
                        shouldCancel = shouldCancel,
                        pauseReason = pauseReason,
                    )
                }
            }
        } catch (error: ParaformerAttemptException) {
            if (
                error.failure == ParaformerAttemptFailure.Protocol ||
                error.failure == ParaformerAttemptFailure.Integrity
            ) {
                resetPartial(artifact)
            }
            throw error
        }
    }

    private fun attemptWithResumeRecovery(
        artifact: ParaformerFrozenArtifact,
        source: ParaformerArtifactSource,
        initialOffset: Long,
        etag: String?,
        completedBefore: Long,
        onProgress: (Long) -> Unit,
        shouldCancel: () -> Boolean,
        pauseReason: () -> ParaformerPauseReason?,
    ) {
        ParaformerDownloadPolicy.runResumeWithSingleFreshRetry(
            initialOffset = initialOffset,
            resetPartial = { resetPartial(artifact) },
        ) { offset ->
            attempt(
                artifact = artifact,
                source = source,
                offset = offset,
                etag = etag.takeIf { offset > 0L },
                completedBefore = completedBefore,
                onProgress = onProgress,
                shouldCancel = shouldCancel,
                pauseReason = pauseReason,
            )
        }
    }

    private fun attempt(
        artifact: ParaformerFrozenArtifact,
        source: ParaformerArtifactSource,
        offset: Long,
        etag: String?,
        completedBefore: Long,
        onProgress: (Long) -> Unit,
        shouldCancel: () -> Boolean,
        pauseReason: () -> ParaformerPauseReason?,
    ) {
        if (offset == artifact.sizeBytes) {
            finalizePartial(artifact)
            return
        }
        val base = when (source) {
            ParaformerArtifactSource.Official -> ParaformerModelContract.OFFICIAL_BASE_URL
            ParaformerArtifactSource.Mirror -> ParaformerModelContract.MIRROR_BASE_URL
        }
        val request = ParaformerDownloadRequestFactory.create(
            url = "$base/${artifact.filename}".toHttpUrl(),
            offset = offset,
            etag = etag,
        )
        val part = partFile(artifact)
        val descriptor = openPartForWrite(part, offset)
        try {
            FileOutputStream(descriptor).use { output ->
                ParaformerHttpTransfer(client).transfer(
                    request = request,
                    expectedTotal = artifact.sizeBytes,
                    offset = offset,
                    expectedEtag = etag,
                    output = output,
                    onResponseMetadata = { frozenEtag ->
                        saveResume(
                            artifact,
                            ParaformerResumeMetadata(source, frozenEtag, offset),
                        )
                    },
                    onProgress = { currentArtifactBytes ->
                        pauseReason()?.let { throw ParaformerDownloadPausedException(it) }
                        onProgress(completedBefore + currentArtifactBytes)
                    },
                    shouldCancel = shouldCancel,
                )
                output.flush()
                output.fd.sync()
            }
        } catch (error: ParaformerDownloadPausedException) {
            throw error
        } catch (error: ParaformerAttemptException) {
            throw error
        } catch (error: IOException) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Storage, error)
        }
        finalizePartial(artifact)
    }

    private fun finalizePartial(artifact: ParaformerFrozenArtifact) {
        val part = partFile(artifact)
        val facts = measureFixedArtifact(part)
        if (
            facts.sizeBytes != artifact.sizeBytes ||
            !facts.sha256.equals(artifact.sha256, ignoreCase = true)
        ) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
        }
        Os.chmod(part.absolutePath, FILE_MODE_0600)
        fsyncFixedFile(part)
        val final = File(stagingRoot, artifact.filename)
        deleteFixedEntryNoFollow(final)
        Os.rename(part.absolutePath, final.absolutePath)
        deleteFixedEntryNoFollow(resumeFile(artifact))
        fsyncDirectory(stagingRoot)
    }

    private fun validatedStagingArtifact(artifact: ParaformerFrozenArtifact): Boolean {
        val file = File(stagingRoot, artifact.filename)
        if (!file.exists()) return false
        return runCatching {
            val facts = measureFixedArtifact(file)
            facts.sizeBytes == artifact.sizeBytes &&
                facts.sha256.equals(artifact.sha256, ignoreCase = true)
        }.getOrDefault(false).also { valid ->
            if (!valid) deleteFixedEntryNoFollow(file)
        }
    }

    private fun reusableBytes(artifact: ParaformerFrozenArtifact): Long {
        if (validatedStagingArtifact(artifact)) return artifact.sizeBytes
        return loadResume(artifact)?.offset ?: 0L
    }

    private fun loadResume(artifact: ParaformerFrozenArtifact): ParaformerResumeMetadata? {
        val part = partFile(artifact)
        val metadata = resumeFile(artifact)
        if (!part.exists() || !metadata.exists()) {
            if (part.exists() || metadata.exists()) resetPartial(artifact)
            return null
        }
        return runCatching {
            val stat = Os.lstat(part.absolutePath)
            if (
                !OsConstants.S_ISREG(stat.st_mode) ||
                OsConstants.S_ISLNK(stat.st_mode) ||
                stat.st_nlink != 1L ||
                stat.st_size !in 0..artifact.sizeBytes
            ) {
                throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
            }
            val json = JSONObject(readSecureSmallFile(metadata, MAX_RESUME_METADATA_BYTES))
            check(json.getInt("format") == RESUME_FORMAT)
            val source = ParaformerArtifactSource.valueOf(json.getString("source"))
            val frozenEtag = json.getString("etag")
            if (frozenEtag.isBlank() || frozenEtag.length > MAX_ETAG_CHARS) {
                throw ParaformerAttemptException(ParaformerAttemptFailure.Protocol)
            }
            ParaformerResumeMetadata(source, frozenEtag, stat.st_size)
        }.getOrElse {
            resetPartial(artifact)
            null
        }
    }

    private fun saveResume(
        artifact: ParaformerFrozenArtifact,
        metadata: ParaformerResumeMetadata,
    ) {
        val payload = JSONObject()
            .put("format", RESUME_FORMAT)
            .put("source", metadata.source.name)
            .put("etag", metadata.etag)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val destination = resumeFile(artifact)
        val temporary = File(stagingRoot, "${destination.name}.tmp")
        deleteFixedEntryNoFollow(temporary)
        val descriptor = try {
            Os.open(
                temporary.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                FILE_MODE_0600,
            )
        } catch (error: Throwable) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Storage, error)
        }
        try {
            FileOutputStream(descriptor).use { output ->
                output.write(payload)
                output.flush()
                output.fd.sync()
            }
            Os.chmod(temporary.absolutePath, FILE_MODE_0600)
            deleteFixedEntryNoFollow(destination)
            Os.rename(temporary.absolutePath, destination.absolutePath)
            fsyncDirectory(stagingRoot)
        } catch (error: ParaformerAttemptException) {
            throw error
        } catch (error: Throwable) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Storage, error)
        }
    }

    private fun openPartForWrite(part: File, offset: Long) = try {
        if (offset == 0L) {
            deleteFixedEntryNoFollow(part)
            Os.open(
                part.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                FILE_MODE_0600,
            )
        } else {
            val descriptor = Os.open(
                part.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_APPEND or OsConstants.O_CLOEXEC or
                    OsConstants.O_NOFOLLOW,
                0,
            )
            val stat = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L || stat.st_size != offset) {
                Os.close(descriptor)
                throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
            }
            descriptor
        }
    } catch (error: ParaformerAttemptException) {
        throw error
    } catch (error: Throwable) {
        throw ParaformerAttemptException(ParaformerAttemptFailure.Storage, error)
    }

    private fun resetPartial(artifact: ParaformerFrozenArtifact) {
        deleteFixedEntryNoFollow(partFile(artifact))
        deleteFixedEntryNoFollow(resumeFile(artifact))
        fsyncDirectory(stagingRoot)
    }

    private fun partFile(artifact: ParaformerFrozenArtifact) =
        File(stagingRoot, "${artifact.filename}.part")

    private fun resumeFile(artifact: ParaformerFrozenArtifact) =
        File(stagingRoot, "${artifact.filename}.resume.json")

    private data class ParaformerResumeMetadata(
        val source: ParaformerArtifactSource,
        val etag: String,
        val offset: Long,
    )

    private companion object {
        const val RESUME_FORMAT = 1
        const val MAX_RESUME_METADATA_BYTES = 4 * 1024
        const val MAX_ETAG_CHARS = 512
        const val FILE_MODE_0600 = 0x180
        val ARTIFACTS = listOf(
            ParaformerFrozenArtifact(
                filename = ParaformerModelContract.TOKENS_FILENAME,
                sizeBytes = ParaformerModelContract.TOKENS_BYTES,
                sha256 = ParaformerModelContract.TOKENS_SHA256,
            ),
            ParaformerFrozenArtifact(
                filename = ParaformerModelContract.MODEL_FILENAME,
                sizeBytes = ParaformerModelContract.MODEL_BYTES,
                sha256 = ParaformerModelContract.MODEL_SHA256,
            ),
        )
    }
}
