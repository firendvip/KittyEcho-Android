package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the SenseVoice ASR model into internal storage on first launch, so the
 * APK itself stays small. Two files land in `context.filesDir/sensevoice/`:
 *   - tokens.txt        (~310 KB, downloaded first because it is tiny)
 *   - model.int8.onnx   (~228 MB)
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

    const val DIR_NAME = "sensevoice"
    const val MODEL_NAME = "model.int8.onnx"
    const val TOKENS_NAME = "tokens.txt"

    // A valid SenseVoice int8 model is ~228 MB; require >100 MB so a truncated or
    // bogus file is never treated as "downloaded".
    private const val MIN_MODEL_BYTES = 100L * 1024L * 1024L

    // Primary mirror (hf-mirror, China-friendly), then the official Hugging Face host.
    private const val MIRROR_BASE =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
    private const val HF_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            // 228 MB model: a 60s read timeout trips on transient slow-downs. 300s is
            // conservative headroom against network jitter (no resume support yet).
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Directory the model lives in: `<filesDir>/sensevoice/`. */
    fun modelDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** Absolute path to the model file. */
    fun modelFile(context: Context): File = File(modelDir(context), MODEL_NAME)

    /** Absolute path to the tokens file. */
    fun tokensFile(context: Context): File = File(modelDir(context), TOKENS_NAME)

    /**
     * True only when both files exist AND the model is plausibly complete
     * (>100 MB). Exception-safe — any failure reading the filesystem returns false.
     */
    fun isDownloaded(context: Context): Boolean {
        return try {
            val model = modelFile(context)
            val tokens = tokensFile(context)
            model.exists() && model.length() > MIN_MODEL_BYTES &&
                tokens.exists() && tokens.length() > 0L
        } catch (t: Throwable) {
            Log.e(TAG, "isDownloaded check failed", t)
            false
        }
    }

    /**
     * Downloads tokens.txt then model.int8.onnx into internal storage.
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

        // Small file first so an obvious failure (no network) surfaces fast.
        downloadFile(TOKENS_NAME, tokensFile(context), onProgress)
        downloadFile(MODEL_NAME, modelFile(context), onProgress)
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
