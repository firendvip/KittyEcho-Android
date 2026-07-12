package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network fallback for the streaming Zipformer ASR model (the primary path is
 * [ModelAssetInstaller], which copies the bundled assets — this downloader only
 * matters if the bundled install somehow failed). Four files land in
 * `context.filesDir/zipformer-zh/`:
 *   - tokens.txt          (~19 KB, downloaded first because it is tiny)
 *   - decoder.int8.onnx   (~1.3 MB)
 *   - joiner.int8.onnx    (~1.0 MB)
 *   - encoder.int8.onnx   (~70 MB)
 *
 * Each file streams to a `.part` temp file with progress callbacks, then is renamed
 * to its final name only on full success. Any failure throws and cleans up the
 * partial file, so a half-finished download never looks "ready".
 *
 * Everything is exception-safe and validates inputs/responses at the boundary
 * (HTTP status, response body presence). Run downloads off the main thread.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    const val DIR_NAME = "zipformer-zh"
    const val ENCODER_NAME = "encoder.int8.onnx"
    const val DECODER_NAME = "decoder.int8.onnx"
    const val JOINER_NAME = "joiner.int8.onnx"
    const val TOKENS_NAME = "tokens.txt"

    // A valid int8 streaming encoder is ~70 MB; require >30 MB so a truncated or
    // bogus file is never treated as "downloaded". Decoder/joiner are >1 MB each.
    private const val MIN_ENCODER_BYTES = 30L * 1024L * 1024L
    private const val MIN_SMALL_ONNX_BYTES = 100L * 1024L

    // Upstream file names inside the sherpa-onnx model repo (renamed locally to the
    // short canonical names above).
    private const val UPSTREAM_ENCODER = "encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx"
    private const val UPSTREAM_DECODER = "decoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx"
    private const val UPSTREAM_JOINER = "joiner-epoch-20-avg-1-chunk-16-left-128.int8.onnx"
    private const val UPSTREAM_TOKENS = "tokens.txt"

    // Primary mirror (hf-mirror, China-friendly), then the official Hugging Face host.
    private const val MIRROR_BASE =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12/resolve/main"
    private const val HF_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12/resolve/main"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            // 70 MB encoder: a 60s read timeout trips on transient slow-downs. 300s is
            // conservative headroom against network jitter (no resume support yet).
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Directory the model lives in: `<filesDir>/zipformer-zh/`. */
    fun modelDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** Absolute path to the streaming encoder. */
    fun encoderFile(context: Context): File = File(modelDir(context), ENCODER_NAME)

    /** Absolute path to the streaming decoder. */
    fun decoderFile(context: Context): File = File(modelDir(context), DECODER_NAME)

    /** Absolute path to the streaming joiner. */
    fun joinerFile(context: Context): File = File(modelDir(context), JOINER_NAME)

    /** Absolute path to the tokens file. */
    fun tokensFile(context: Context): File = File(modelDir(context), TOKENS_NAME)

    /**
     * True only when all four files exist AND are plausibly complete.
     * Exception-safe — any failure reading the filesystem returns false.
     */
    fun isDownloaded(context: Context): Boolean {
        return try {
            val encoder = encoderFile(context)
            val decoder = decoderFile(context)
            val joiner = joinerFile(context)
            val tokens = tokensFile(context)
            encoder.exists() && encoder.length() > MIN_ENCODER_BYTES &&
                decoder.exists() && decoder.length() > MIN_SMALL_ONNX_BYTES &&
                joiner.exists() && joiner.length() > MIN_SMALL_ONNX_BYTES &&
                tokens.exists() && tokens.length() > 0L
        } catch (t: Throwable) {
            Log.e(TAG, "isDownloaded check failed", t)
            false
        }
    }

    /**
     * Downloads tokens, decoder, joiner, then the big encoder into internal storage.
     *
     * @param onProgress invoked with (downloadedBytes, totalBytes) for the *current*
     *        file; totalBytes is the file's Content-Length, or -1 if unknown.
     * @throws IOException on any network/HTTP/IO failure (partial files are cleaned up).
     */
    @Throws(IOException::class)
    fun download(context: Context, onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit) {
        val dir = modelDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建模型目录: ${dir.absolutePath}")
        }

        // Small files first so an obvious failure (no network) surfaces fast.
        downloadFile(UPSTREAM_TOKENS, tokensFile(context), onProgress)
        downloadFile(UPSTREAM_DECODER, decoderFile(context), onProgress)
        downloadFile(UPSTREAM_JOINER, joinerFile(context), onProgress)
        downloadFile(UPSTREAM_ENCODER, encoderFile(context), onProgress)
    }

    /**
     * Streams a single file to `<dest>.part`, then renames it to `dest` on success.
     * Tries the mirror first, then the official host. Throws on total failure.
     */
    @Throws(IOException::class)
    private fun downloadFile(
        fileName: String,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val urls = listOf("$MIRROR_BASE/$fileName", "$HF_BASE/$fileName")
        var lastError: IOException? = null

        for (url in urls) {
            try {
                streamToPartFile(url, dest, onProgress)
                return // success
            } catch (e: IOException) {
                Log.w(TAG, "download from $url failed, trying next source", e)
                lastError = e
            }
        }
        throw lastError ?: IOException("下载失败: $fileName")
    }

    /** Performs the actual streamed GET into `<dest>.part` and renames on success. */
    @Throws(IOException::class)
    private fun streamToPartFile(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val partFile = File(dest.parentFile, dest.name + ".part")
        // Drop any stale partial from a previous failed attempt.
        if (partFile.exists()) partFile.delete()

        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} for $url")
                }
                val body = response.body ?: throw IOException("空响应体: $url")
                val total = body.contentLength() // -1 if unknown

                body.byteStream().use { input ->
                    partFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        // Emit an initial 0% BEFORE the first read so progress is ordered.
                        onProgress(0L, total)
                        var read = input.read(buffer)
                        while (read >= 0) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                            read = input.read(buffer)
                        }
                        output.flush()
                    }
                }
            }

            // Atomic-ish finalize: replace any existing dest, then rename.
            if (dest.exists()) dest.delete()
            if (!partFile.renameTo(dest)) {
                throw IOException("重命名失败: ${partFile.name} -> ${dest.name}")
            }
        } catch (t: Throwable) {
            // Clean up the partial file on ANY failure so isDownloaded stays honest.
            try {
                if (partFile.exists()) partFile.delete()
            } catch (_: Throwable) {
            }
            if (t is IOException) throw t
            throw IOException(t.message ?: t.javaClass.simpleName, t)
        }
    }
}
