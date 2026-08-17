package com.wordtaker.keyboard.wordtaker.speech

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Removes only the exact legacy Zipformer directory after a proven Paraformer recognition. */
internal class ParaformerLegacyModelCleaner(context: Context) {
    private val appContext = context.applicationContext
    private val attempted = AtomicBoolean(false)

    fun afterSuccessfulRecognition(result: AsrResult) {
        if (result.text.isBlank() || !attempted.compareAndSet(false, true)) return
        runCatching {
            val verified = ParaformerPrivateModelStore(appContext).validateAndResolve()
            if (
                verified.artifacts.size != 2 ||
                !ParaformerLegacyCleanupPolicy.mayDelete(
                    paraformerVerified = true,
                    initialized = true,
                    successfulRecognitionCount = 1,
                )
            ) {
                return@runCatching
            }
            val filesRoot = appContext.filesDir.absoluteFile
            val legacyRoot = File(filesRoot, LEGACY_DIRECTORY).absoluteFile
            if (
                legacyRoot.parentFile?.absoluteFile != filesRoot ||
                legacyRoot.name != LEGACY_DIRECTORY
            ) {
                return@runCatching
            }
            deleteFixedEntryNoFollow(legacyRoot)
            fsyncDirectory(filesRoot)
        }
    }

    private companion object {
        const val LEGACY_DIRECTORY = "zipformer-zh"
    }
}
