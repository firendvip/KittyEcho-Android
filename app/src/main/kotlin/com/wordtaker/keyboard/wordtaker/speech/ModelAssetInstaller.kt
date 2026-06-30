package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Installs the SenseVoice ASR model bundled inside the APK (`assets/models/sensevoice/`)
 * into internal storage on first launch, so the keyboard works fully offline with no
 * network download. The two files land in the same place [ModelDownloader] expects:
 *   - tokens.txt        -> [ModelDownloader.tokensFile]
 *   - model.int8.onnx   -> [ModelDownloader.modelFile]
 *
 * Idempotent: a `.installed_v1` marker inside the model dir, combined with
 * [ModelDownloader.isDownloaded], short-circuits any recopy on later launches.
 *
 * Fully silent and crash-proof: every failure is logged and swallowed — this NEVER
 * throws and NEVER shows UI. Run off the main thread.
 */
object ModelAssetInstaller {

    private const val TAG = "ModelAssetInstaller"

    private const val ASSET_DIR = "models/sensevoice"
    private const val MARKER_NAME = ".installed_v1"
    private const val COPY_BUFFER_BYTES = 8 * 1024

    /**
     * Ensures the bundled model is present in internal storage. No-ops when already
     * installed. Silent on any error.
     */
    fun ensureInstalled(context: Context) {
        try {
            val dir = ModelDownloader.modelDir(context)
            val marker = File(dir, MARKER_NAME)

            if (marker.exists() && ModelDownloader.isDownloaded(context)) {
                return // Already installed; nothing to do.
            }

            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "无法创建模型目录: ${dir.absolutePath}")
                return
            }

            copyAsset(context, ModelDownloader.TOKENS_NAME, ModelDownloader.tokensFile(context))
            copyAsset(context, ModelDownloader.MODEL_NAME, ModelDownloader.modelFile(context))

            // Both files copied successfully -> drop the marker.
            marker.createNewFile()
            Log.i(TAG, "模型已从 assets 安装到 ${dir.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "ensureInstalled failed", t)
        }
    }

    /**
     * Streams `assets/models/sensevoice/<name>` to a sibling `.part` temp file, then
     * atomically renames it onto [dest]. Throws on failure (caught by [ensureInstalled]).
     */
    private fun copyAsset(context: Context, name: String, dest: File) {
        val partFile = File(dest.parentFile, dest.name + ".part")
        if (partFile.exists()) partFile.delete()

        context.assets.open("$ASSET_DIR/$name").use { input ->
            partFile.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var read = input.read(buffer)
                while (read >= 0) {
                    output.write(buffer, 0, read)
                    read = input.read(buffer)
                }
                output.flush()
            }
        }

        if (dest.exists()) dest.delete()
        if (!partFile.renameTo(dest)) {
            partFile.delete()
            throw java.io.IOException("重命名失败: ${partFile.name} -> ${dest.name}")
        }
    }
}
