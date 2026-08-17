package com.wordtaker.keyboard.asrbenchmark.runner

import android.content.Context
import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.TrustedContentAddressedOutputStore

data class StoredAndroidBundle(
    val accuracyFileName: String,
    val accuracySha256: String,
    val engineeringFileName: String,
    val engineeringSha256: String,
    val attestationFileName: String,
    val attestationSha256: String,
)

/**
 * Publishes device output below Context.noBackupFilesDir only.
 *
 * Callers provide an anonymous run_id, never an output path. The core store
 * validates both runner-owned child components without following links, holds
 * the run-directory FD, and revalidates the fixed component identities around
 * every leaf operation. Android exposes no guaranteed openat/renameat Java API,
 * so this path hardening is not claimed to resist an arbitrary same-UID race.
 *
 * These blobs are intentionally not host-valid receipts. After ADB pull, the
 * host verifier must validate the signed bundle and create repository-external
 * Phase A-style payload/receipt transactions before scoring.
 */
class AndroidBundleStore(
    context: Context,
    private val runId: String,
) {
    private val trustedAppPrivateRoot =
        context.noBackupFilesDir.toPath()

    fun store(bundle: AndroidBenchmarkBundle): StoredAndroidBundle {
        requireRunBinding(bundle)
        return TrustedContentAddressedOutputStore.open(
            trustedAppPrivateRoot,
            runId,
        ).use { output ->
            val accuracy = output.publish(
                "accuracy",
                bundle.accuracyDocument,
            )
            val engineering = output.publish(
                "engineering",
                bundle.engineeringDocument,
            )
            val attestation = output.publish(
                "attestation",
                bundle.attestationEnvelope,
            )
            StoredAndroidBundle(
                accuracyFileName = accuracy.fileName,
                accuracySha256 = accuracy.sha256,
                engineeringFileName = engineering.fileName,
                engineeringSha256 = engineering.sha256,
                attestationFileName = attestation.fileName,
                attestationSha256 = attestation.sha256,
            )
        }
    }

    private fun requireRunBinding(bundle: AndroidBenchmarkBundle) {
        val accuracyRunId = bundle.accuracyDocument["run_id"]
        val engineeringRunId = bundle.engineeringDocument["run_id"]
        val signedPayload = bundle.attestationEnvelope["signed_payload"]
            as? Map<*, *>
            ?: throw BenchmarkContractException(
                "Android attestation signed payload is missing",
            )
        if (
            accuracyRunId != runId ||
            engineeringRunId != runId ||
            signedPayload["run_id"] != runId
        ) {
            throw BenchmarkContractException(
                "Android output bundle differs from its private run directory",
            )
        }
    }
}
