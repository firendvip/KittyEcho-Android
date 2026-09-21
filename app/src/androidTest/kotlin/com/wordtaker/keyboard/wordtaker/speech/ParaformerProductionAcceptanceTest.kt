package com.wordtaker.keyboard.wordtaker.speech

import android.content.res.AssetManager
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Private emulator-only acceptance gates; no model or transcript leaves app-private storage. */
@RunWith(AndroidJUnit4::class)
class ParaformerProductionAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val modelRoot = File(context.noBackupFilesDir, ParaformerModelContract.PRIVATE_DIRECTORY)

    @Test
    fun bundledApkAssetsMatchFrozenContract() {
        assertBundledAsset(
            ParaformerModelContract.MODEL_FILENAME,
            ParaformerModelContract.MODEL_BYTES,
            ParaformerModelContract.MODEL_SHA256,
        )
        assertBundledAsset(
            ParaformerModelContract.TOKENS_FILENAME,
            ParaformerModelContract.TOKENS_BYTES,
            ParaformerModelContract.TOKENS_SHA256,
        )
    }

    @Test
    fun missingModelIsTyped() {
        withModelRootMovedAside {
            val error = runCatching {
                ParaformerPrivateModelStore(context).validateAndResolve()
            }.exceptionOrNull()
            assertTrue(error is AsrModelMissingException)
        }
    }

    @Test
    fun corruptModelIsTyped() {
        withModelRootMovedAside {
            assertTrue(modelRoot.mkdir())
            File(modelRoot, ParaformerModelContract.MODEL_FILENAME).writeBytes(byteArrayOf(1))
            File(modelRoot, ParaformerModelContract.TOKENS_FILENAME).writeBytes(byteArrayOf(1))
            assertCorruptModel()
        }
    }

    @Test
    fun privateStoreRejectsParentSymlinkModelSymlinkAndNonRegularFile() {
        withModelRootMovedAside { backupRoot, originalWasMoved ->
            assertTrue("exact private model must be staged for security acceptance", originalWasMoved)

            Os.symlink(backupRoot.absolutePath, modelRoot.absolutePath)
            assertCorruptModel()
            cleanupTestModelRoot()

            assertTrue(modelRoot.mkdir())
            Os.symlink(
                File(backupRoot, ParaformerModelContract.MODEL_FILENAME).absolutePath,
                File(modelRoot, ParaformerModelContract.MODEL_FILENAME).absolutePath,
            )
            File(modelRoot, ParaformerModelContract.TOKENS_FILENAME).writeBytes(byteArrayOf(1))
            assertCorruptModel()
            cleanupTestModelRoot()

            assertTrue(modelRoot.mkdir())
            assertTrue(File(modelRoot, ParaformerModelContract.MODEL_FILENAME).mkdir())
            File(modelRoot, ParaformerModelContract.TOKENS_FILENAME).writeBytes(byteArrayOf(1))
            assertCorruptModel()
        }
    }

    @Test
    fun canonicalPcmRunsColdHotAndTwoUtterancesThroughProductionActor() = runBlocking {
        val pcmFile = File(context.noBackupFilesDir, PCM_PRIVATE_PATH)
        assertTrue("private canonical PCM is not staged", pcmFile.isFile)
        val samples = readCanonicalPcm16Mono16k(pcmFile)
        assertTrue("canonical PCM is empty", samples.isNotEmpty())

        val actor = ParaformerRecognitionActor(
            decoder = SherpaParaformerDecoder(ParaformerPrivateModelStore(context)),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        try {
            val firstPending = actor.submit(samples)
            val secondPending = actor.submit(samples)
            val first = withTimeout(DECODE_TIMEOUT_MS) { firstPending.await() }
            val second = withTimeout(DECODE_TIMEOUT_MS) { secondPending.await() }
            assertTrue("first production final is empty", first.text.isNotBlank())
            assertTrue("second production final is empty", second.text.isNotBlank())
            assertEquals(ParaformerModelContract.MODEL_ID, first.modelId)
            assertEquals(ParaformerModelContract.MODEL_REVISION, first.modelRevision)
            assertTrue("production finals differ", first.text == second.text)
        } finally {
            actor.close()
        }
    }

    private fun assertCorruptModel() {
        val error = runCatching {
            ParaformerPrivateModelStore(context).validateAndResolve()
        }.exceptionOrNull()
        assertTrue(error is AsrModelCorruptException)
    }

    private fun assertBundledAsset(filename: String, expectedBytes: Long, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var measuredBytes = 0L
        context.assets.open(
            "models/paraformer/$filename",
            AssetManager.ACCESS_STREAMING,
        ).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                measuredBytes += count
                digest.update(buffer, 0, count)
            }
        }
        assertEquals(expectedBytes, measuredBytes)
        assertEquals(
            expectedSha256,
            digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun withModelRootMovedAside(
        block: (backupRoot: File, originalWasMoved: Boolean) -> Unit,
    ) {
        val backupRoot = File(
            context.noBackupFilesDir,
            "${ParaformerModelContract.PRIVATE_DIRECTORY}.acceptance-backup",
        )
        assertTrue("stale acceptance backup exists", !backupRoot.exists())
        val originalWasMoved = modelRoot.exists()
        if (originalWasMoved) {
            assertTrue("could not preserve staged private model", modelRoot.renameTo(backupRoot))
        }
        try {
            block(backupRoot, originalWasMoved)
        } finally {
            cleanupTestModelRoot()
            if (originalWasMoved) {
                assertTrue("could not restore staged private model", backupRoot.renameTo(modelRoot))
            }
        }
    }

    private fun withModelRootMovedAside(block: () -> Unit) {
        withModelRootMovedAside { _, _ -> block() }
    }

    /** Deletes only test-created entries directly inside the exact fixed model root. */
    private fun cleanupTestModelRoot() {
        val rootStat = runCatching { Os.lstat(modelRoot.absolutePath) }.getOrNull() ?: return
        if (OsConstants.S_ISLNK(rootStat.st_mode)) {
            Os.remove(modelRoot.absolutePath)
            return
        }
        if (OsConstants.S_ISDIR(rootStat.st_mode)) {
            modelRoot.listFiles().orEmpty().forEach { entry ->
                val entryStat = Os.lstat(entry.absolutePath)
                if (OsConstants.S_ISDIR(entryStat.st_mode)) {
                    assertTrue("test directory is unexpectedly non-empty", entry.listFiles().orEmpty().isEmpty())
                }
                Os.remove(entry.absolutePath)
            }
        }
        Os.remove(modelRoot.absolutePath)
    }

    private fun readCanonicalPcm16Mono16k(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && ascii(bytes, 0) == "RIFF" && ascii(bytes, 8) == "WAVE")
        var offset = 12
        var formatSeen = false
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset)
            val size = littleEndianInt(bytes, offset + 4)
            val content = offset + 8
            require(size >= 0 && content + size <= bytes.size)
            if (id == "fmt ") {
                require(size >= 16)
                val view = ByteBuffer.wrap(bytes, content, size).order(ByteOrder.LITTLE_ENDIAN)
                require(view.short.toInt() == 1)
                require(view.short.toInt() == 1)
                require(view.int == 16_000)
                view.position(content + 14)
                require(view.short.toInt() == 16)
                formatSeen = true
            } else if (id == "data") {
                require(formatSeen && size % 2 == 0)
                val view = ByteBuffer.wrap(bytes, content, size).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(size / 2) { view.short / 32768f }
            }
            offset = content + size + (size and 1)
        }
        error("canonical PCM data chunk is missing")
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private companion object {
        const val PCM_PRIVATE_PATH = "asr-acceptance/canonical.wav"
        const val DECODE_TIMEOUT_MS = 180_000L
    }
}
