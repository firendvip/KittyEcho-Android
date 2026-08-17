package com.wordtaker.keyboard.wordtaker.voice

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wordtaker.keyboard.wordtaker.speech.CaptureStartFailure
import com.wordtaker.keyboard.wordtaker.speech.CaptureStartResult
import com.wordtaker.keyboard.wordtaker.speech.RealSpeechEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceHighRiskProductionAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun deniedMicPermissionReturnsTypedFailureBeforeProductionCaptureStarts() {
        val deniedMicContext = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.RECORD_AUDIO) {
                    PackageManager.PERMISSION_DENIED
                } else {
                    super.checkPermission(permission, pid, uid)
                }
        }
        val engine = RealSpeechEngine(deniedMicContext)

        val result = engine.start()

        assertTrue(result is CaptureStartResult.Failed)
        assertSame(
            CaptureStartFailure.PermissionDenied,
            (result as CaptureStartResult.Failed).failure,
        )
        engine.cancel()
    }

    @Test
    fun staleVoiceCommitIsRejectedWhenEditorTokenChangesBeforeCollectorAction() {
        val emitted = VoiceCommit(text = "不会上屏", editorSessionToken = 41L)
        var activeEditorSessionToken = 41L
        assertTrue(emitted.belongsTo(activeEditorSessionToken))

        activeEditorSessionToken = 42L
        var committedText: String? = null
        if (emitted.belongsTo(activeEditorSessionToken)) {
            committedText = emitted.text
        }

        assertFalse(emitted.belongsTo(activeEditorSessionToken))
        assertNull(committedText)
    }
}
