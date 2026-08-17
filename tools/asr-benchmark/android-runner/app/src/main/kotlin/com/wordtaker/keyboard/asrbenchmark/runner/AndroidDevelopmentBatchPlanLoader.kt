package com.wordtaker.keyboard.asrbenchmark.runner

import android.system.Os
import android.system.OsConstants
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import java.io.ByteArrayOutputStream
import java.io.File

/** Reads the private development plan through one no-follow read-only FD. */
object AndroidDevelopmentBatchPlanLoader {
    fun load(
        decoderPlanPath: String,
        runId: String,
        modelAlias: String,
        expectedSnapshotFingerprintSha256: String,
        expectedDecoderPlanSha256: String,
    ): DevelopmentDecoderPlanEvidence {
        if (!File(decoderPlanPath).isAbsolute) {
            throw BenchmarkContractException(
                "development decoder plan path must be absolute",
            )
        }
        val descriptor = try {
            Os.open(
                decoderPlanPath,
                OsConstants.O_RDONLY or
                    OsConstants.O_CLOEXEC or
                    OsConstants.O_NOFOLLOW,
                0,
            )
        } catch (_: Exception) {
            throw BenchmarkContractException(
                "development decoder plan cannot be opened safely",
            )
        }
        try {
            val before = Os.fstat(descriptor)
            if (
                !OsConstants.S_ISREG(before.st_mode) ||
                before.st_nlink != 1L ||
                before.st_size <= 0L ||
                before.st_size > MAX_PLAN_BYTES
            ) {
                throw BenchmarkContractException(
                    "development decoder plan must bind one bounded regular file",
                )
            }
            val output = ByteArrayOutputStream(before.st_size.toInt())
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = Os.read(descriptor, buffer, 0, buffer.size)
                if (count == 0) break
                if (count < 0) {
                    throw BenchmarkContractException(
                        "development decoder plan safe read failed",
                    )
                }
                total = Math.addExact(total, count.toLong())
                if (total > before.st_size || total > MAX_PLAN_BYTES) {
                    throw BenchmarkContractException(
                        "development decoder plan changed size while read",
                    )
                }
                output.write(buffer, 0, count)
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
                    "development decoder plan identity changed while read",
                )
            }
            val evidence = DevelopmentDecoderPlanContract.parse(
                rawPlanBytes = output.toByteArray(),
                runId = runId,
                modelAlias = modelAlias,
            )
            DevelopmentDecoderPlanContract.requireExpectedCommitments(
                evidence = evidence,
                expectedSnapshotFingerprintSha256 =
                    expectedSnapshotFingerprintSha256,
                expectedDecoderPlanSha256 = expectedDecoderPlanSha256,
            )
            return evidence
        } finally {
            try {
                Os.close(descriptor)
            } catch (_: Exception) {
                // Any earlier failure already makes the plan unusable.
            }
        }
    }

    private const val MAX_PLAN_BYTES = 16L * 1024L * 1024L
}
