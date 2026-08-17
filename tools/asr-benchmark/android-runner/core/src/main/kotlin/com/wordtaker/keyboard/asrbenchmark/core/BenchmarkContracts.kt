package com.wordtaker.keyboard.asrbenchmark.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sin

open class BenchmarkContractException(message: String) : IllegalArgumentException(message)

data class FileIdentity(
    val device: Long,
    val inode: Long,
    val sizeBytes: Long,
    val mode: Int,
    val linkCount: Long,
) {
    init {
        if (device < 0 || inode < 0 || sizeBytes < 0 || mode < 0 || linkCount < 0) {
            throw BenchmarkContractException("file identity values must be non-negative")
        }
        if (mode and FILE_TYPE_MASK != REGULAR_FILE_TYPE) {
            throw BenchmarkContractException("benchmark input must be a regular file")
        }
        if (linkCount != 1L) {
            throw BenchmarkContractException("benchmark input must bind exactly one inode link")
        }
    }

    companion object {
        private const val FILE_TYPE_MASK = 0xF000
        private const val REGULAR_FILE_TYPE = 0x8000
    }
}

data class PcmFacts(
    val sampleRateHz: Int,
    val channels: Int,
    val sampleWidthBits: Int,
    val payloadSha256: String,
    val payloadBytes: Long,
)

data class MeasuredFile(
    val pathToken: String,
    private val contentBytes: ByteArray?,
    val identity: FileIdentity,
    val sha256: String,
    val pcm: PcmFacts?,
) {
    val bytes: ByteArray
        get() = contentBytes?.copyOf()
            ?: throw BenchmarkContractException(
                "streamed artifact bytes are intentionally not retained",
            )

    init {
        if (pathToken.isBlank() || pathToken.contains('/') || pathToken.contains('\\')) {
            throw BenchmarkContractException("file path token must be opaque and path-free")
        }
        if (contentBytes != null && identity.sizeBytes != contentBytes.size.toLong()) {
            throw BenchmarkContractException("safe-FD size differs from bytes read")
        }
        Hashing.requireSha256(sha256, "file sha256")
    }

    companion object {
        fun fromBytes(
            pathToken: String,
            bytes: ByteArray,
            identity: FileIdentity,
            requireCanonicalPcm: Boolean,
        ): MeasuredFile {
            val immutableCopy = bytes.copyOf()
            val pcm = if (requireCanonicalPcm) {
                CanonicalPcmWav.inspect(immutableCopy)
            } else {
                null
            }
            return MeasuredFile(
                pathToken = pathToken,
                contentBytes = immutableCopy,
                identity = identity,
                sha256 = Hashing.sha256(immutableCopy),
                pcm = pcm,
            )
        }

        fun fromDigest(
            pathToken: String,
            identity: FileIdentity,
            sha256: String,
        ): MeasuredFile = MeasuredFile(
            pathToken = pathToken,
            contentBytes = null,
            identity = identity,
            sha256 = Hashing.requireSha256(sha256, "streamed file sha256"),
            pcm = null,
        )
    }

    fun retainedBytesOrNull(): ByteArray? = contentBytes?.copyOf()
}

interface SecureFileAccess {
    /**
     * Implementations must O_NOFOLLOW-open a read-only FD, derive identity from
     * fstat on that same FD, and read/hash the complete file from that FD.
     */
    fun read(pathToken: String, requireCanonicalPcm: Boolean): MeasuredFile
}

data class DecoderClip(
    val clipId: String,
    val pathToken: String,
    val wavSha256: String,
    val pcmPayloadSha256: String,
    val pcmPayloadBytes: Long,
) {
    init {
        if (!CLIP_ID.matches(clipId)) {
            throw BenchmarkContractException("clip_id is not anonymous")
        }
        if (pathToken.isBlank() || pathToken.contains('/') || pathToken.contains('\\')) {
            throw BenchmarkContractException("decoder clip path token is invalid")
        }
        Hashing.requireSha256(wavSha256, "clip WAV sha256")
        Hashing.requireSha256(pcmPayloadSha256, "clip PCM payload sha256")
        if (pcmPayloadBytes < 0) {
            throw BenchmarkContractException("clip PCM payload size must be non-negative")
        }
    }
}

data class DecoderPlan(
    val planId: String,
    val runId: String,
    val datasetId: String,
    val modelAlias: String,
    val clips: List<DecoderClip>,
) {
    init {
        if (!PLAN_ID.matches(planId)) {
            throw BenchmarkContractException("decoder plan_id is invalid")
        }
        if (!RUN_ID.matches(runId)) {
            throw BenchmarkContractException("run_id is invalid")
        }
        if (!DATASET_ID.matches(datasetId)) {
            throw BenchmarkContractException("dataset_id is invalid")
        }
        if (!MODEL_ALIAS.matches(modelAlias)) {
            throw BenchmarkContractException("model alias is invalid")
        }
        if (clips.isEmpty()) {
            throw BenchmarkContractException("decoder plan must contain clips")
        }
        if (clips.map { it.clipId }.distinct().size != clips.size) {
            throw BenchmarkContractException("decoder plan contains duplicate clip_id")
        }
    }

    companion object {
        private val PLAN_ID = Regex("^plan_[0-9a-f]{12}$")
        private val RUN_ID = Regex("^run_[0-9a-f]{12}$")
        private val DATASET_ID = Regex("^dataset_[0-9a-f]{12}$")
        private val MODEL_ALIAS = Regex("^M[0-9]{3}$")
    }
}

data class ArtifactFile(
    val componentRole: String,
    val pathToken: String,
) {
    init {
        if (componentRole !in COMPONENT_ROLES) {
            throw BenchmarkContractException("artifact component role is invalid")
        }
        if (pathToken.isBlank() || pathToken.contains('/') || pathToken.contains('\\')) {
            throw BenchmarkContractException("artifact path token is invalid")
        }
    }

    companion object {
        private val COMPONENT_ROLES = setOf(
            "weights",
            "conversion",
            "runtime",
            "tokenizer",
        )
    }
}

enum class DecoderStatus {
    OK,
    ERROR,
}

data class DecoderResult(
    val transcript: String,
    val status: DecoderStatus,
    val errorCode: String?,
) {
    init {
        if (transcript.length > 100_000) {
            throw BenchmarkContractException("decoder transcript exceeds the scoring limit")
        }
        if (errorCode !in setOf(null, "oom", "crash", "decode_error")) {
            throw BenchmarkContractException("decoder error code is invalid")
        }
        if ((status == DecoderStatus.OK) != (errorCode == null)) {
            throw BenchmarkContractException("decoder status and error code conflict")
        }
    }
}

fun interface DecoderAdapter {
    /**
     * The runner owns and measures the input. Adapters receive a disposable
     * byte copy and may return only transcript and decoder status.
     */
    fun decode(canonicalPcmWav: ByteArray): DecoderResult
}

interface RunnerPreparedDecoderAdapter : DecoderAdapter {
    /**
     * Called by the runner after it safe-FD measured every frozen artifact.
     * The adapter may consume these measurements but cannot add to or replace
     * runner-owned telemetry.
     */
    fun prepare(artifactsByRole: Map<String, MeasuredFile>)
}

fun interface DecodeProgressObserver {
    /**
     * Called immediately before the adapter receives the runner-owned PCM
     * copy. It identifies the exact frozen-order clip currently in flight.
     */
    fun onDecodeStarted(clipId: String) {
        // Completion remains the single abstract method so Kotlin SAM callers
        // cannot accidentally replace the verified-completion callback.
    }

    /**
     * Called only after the adapter returned and the runner revalidated the
     * exact PCM and artifact identities/content for this decode.
     */
    fun onVerifiedDecode(clipId: String, status: DecoderStatus)
}

fun interface MonotonicClock {
    fun nowNanos(): Long
}

data class ResourceSample(
    val rssBytes: Long,
    val pssBytes: Long,
    val thermalStatus: Int,
    val swapBytes: Long? = null,
) {
    init {
        if (
            rssBytes < 0 ||
            pssBytes < 0 ||
            thermalStatus !in 0..6 ||
            swapBytes?.let { it < 0L } == true
        ) {
            throw BenchmarkContractException("runtime resource sample is invalid")
        }
    }
}

fun interface ResourceProbe {
    fun sample(): ResourceSample
}

object Hashing {
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    fun requireSha256(value: String, context: String): String {
        if (!SHA256.matches(value)) {
            throw BenchmarkContractException("$context must be a lowercase sha256")
        }
        return value
    }
}

object CanonicalPcmWav {
    fun inspect(bytes: ByteArray): PcmFacts {
        if (bytes.size < 44 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") {
            throw BenchmarkContractException("PCM input must be a RIFF/WAVE file")
        }
        val declaredRiffSize = littleEndianInt(bytes, 4).toLong() and 0xffffffffL
        if (declaredRiffSize + 8L != bytes.size.toLong()) {
            throw BenchmarkContractException("WAV RIFF size differs from safe-FD bytes")
        }
        var offset = 12
        var formatFound = false
        var dataPayload: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val chunkId = ascii(bytes, offset, 4)
            val chunkSize = littleEndianInt(bytes, offset + 4)
            if (chunkSize < 0) {
                throw BenchmarkContractException("WAV chunk size exceeds the local limit")
            }
            val contentStart = offset + 8
            val contentEnd = contentStart.toLong() + chunkSize.toLong()
            if (contentEnd > bytes.size.toLong()) {
                throw BenchmarkContractException("WAV chunk exceeds the file")
            }
            when (chunkId) {
                "fmt " -> {
                    if (formatFound || chunkSize < 16) {
                        throw BenchmarkContractException("WAV must contain one PCM fmt chunk")
                    }
                    val audioFormat = littleEndianShort(bytes, contentStart)
                    val channels = littleEndianShort(bytes, contentStart + 2)
                    val sampleRate = littleEndianInt(bytes, contentStart + 4)
                    val byteRate = littleEndianInt(bytes, contentStart + 8)
                    val blockAlign = littleEndianShort(bytes, contentStart + 12)
                    val bitsPerSample = littleEndianShort(bytes, contentStart + 14)
                    if (
                        audioFormat != 1 ||
                        channels != 1 ||
                        sampleRate != 16_000 ||
                        byteRate != 32_000 ||
                        blockAlign != 2 ||
                        bitsPerSample != 16
                    ) {
                        throw BenchmarkContractException(
                            "WAV must be 16kHz mono signed PCM16 little-endian",
                        )
                    }
                    formatFound = true
                }
                "data" -> {
                    if (dataPayload != null || chunkSize % 2 != 0) {
                        throw BenchmarkContractException("WAV must contain one aligned data chunk")
                    }
                    dataPayload = bytes.copyOfRange(contentStart, contentEnd.toInt())
                }
            }
            offset = contentEnd.toInt() + (chunkSize and 1)
        }
        if (!formatFound || dataPayload == null || offset != bytes.size) {
            throw BenchmarkContractException("WAV PCM container is incomplete")
        }
        return PcmFacts(
            sampleRateHz = 16_000,
            channels = 1,
            sampleWidthBits = 16,
            payloadSha256 = Hashing.sha256(dataPayload),
            payloadBytes = dataPayload.size.toLong(),
        )
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}

object SyntheticWav {
    /** Creates a deterministic, non-speech sine fixture for contract tests. */
    fun pcm16Mono(sampleRate: Int, frameCount: Int): ByteArray {
        if (sampleRate != 16_000 || frameCount <= 0) {
            throw BenchmarkContractException("synthetic WAV parameters are invalid")
        }
        val payload = ByteBuffer.allocate(frameCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount) { index ->
            val sample = (1_000.0 * sin(2.0 * Math.PI * 440.0 * index / sampleRate)).toInt()
            payload.putShort(sample.toShort())
        }
        val payloadBytes = payload.array()
        val output = ByteBuffer.allocate(44 + payloadBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".encodeToByteArray())
        output.putInt(36 + payloadBytes.size)
        output.put("WAVE".encodeToByteArray())
        output.put("fmt ".encodeToByteArray())
        output.putInt(16)
        output.putShort(1)
        output.putShort(1)
        output.putInt(sampleRate)
        output.putInt(sampleRate * 2)
        output.putShort(2)
        output.putShort(16)
        output.put("data".encodeToByteArray())
        output.putInt(payloadBytes.size)
        output.put(payloadBytes)
        return output.array()
    }
}

private val CLIP_ID = Regex("^clip_[0-9a-f]{12}$")
