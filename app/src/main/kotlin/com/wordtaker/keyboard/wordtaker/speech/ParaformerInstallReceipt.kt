package com.wordtaker.keyboard.wordtaker.speech

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ParaformerReceiptArtifact(
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
internal data class ParaformerInstallReceipt(
    val formatVersion: Int,
    val modelId: String,
    val upstreamModelId: String,
    val upstreamVersion: String,
    val conversionRevision: String,
    val artifactRevision: String,
    val runtimeVersion: String,
    val artifacts: List<ParaformerReceiptArtifact>,
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    fun requireFrozenContract() {
        if (this != frozenContract()) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity)
        }
    }

    companion object {
        const val FILENAME = "receipt.json"
        private const val FORMAT_VERSION = 1
        private val JSON = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
        }

        fun frozenContract() = ParaformerInstallReceipt(
            formatVersion = FORMAT_VERSION,
            modelId = ParaformerModelContract.MODEL_ID,
            upstreamModelId = ParaformerModelContract.UPSTREAM_MODEL_ID,
            upstreamVersion = ParaformerModelContract.UPSTREAM_VERSION,
            conversionRevision = ParaformerModelContract.CONVERSION_REVISION,
            artifactRevision = ParaformerModelContract.ARTIFACT_REVISION,
            runtimeVersion = ParaformerModelContract.RUNTIME_VERSION,
            artifacts = listOf(
                ParaformerReceiptArtifact(
                    filename = ParaformerModelContract.MODEL_FILENAME,
                    sizeBytes = ParaformerModelContract.MODEL_BYTES,
                    sha256 = ParaformerModelContract.MODEL_SHA256,
                ),
                ParaformerReceiptArtifact(
                    filename = ParaformerModelContract.TOKENS_FILENAME,
                    sizeBytes = ParaformerModelContract.TOKENS_BYTES,
                    sha256 = ParaformerModelContract.TOKENS_SHA256,
                ),
            ),
        )

        fun decode(encoded: String): ParaformerInstallReceipt = try {
            JSON.decodeFromString(serializer(), encoded).also { it.requireFrozenContract() }
        } catch (error: ParaformerAttemptException) {
            throw error
        } catch (error: Throwable) {
            throw ParaformerAttemptException(ParaformerAttemptFailure.Integrity, error)
        }
    }
}
