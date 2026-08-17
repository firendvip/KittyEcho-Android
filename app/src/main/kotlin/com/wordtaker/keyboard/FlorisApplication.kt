/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wordtaker.keyboard

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.util.Log
import androidx.core.os.UserManagerCompat
import com.wordtaker.keyboard.app.FlorisPreferenceModel
import com.wordtaker.keyboard.app.FlorisPreferenceStore
import com.wordtaker.keyboard.ime.clipboard.ClipboardManager
import com.wordtaker.keyboard.ime.core.SubtypeManager
import com.wordtaker.keyboard.ime.dictionary.DictionaryManager
import com.wordtaker.keyboard.ime.editor.EditorInstance
import com.wordtaker.keyboard.ime.keyboard.KeyboardManager
import com.wordtaker.keyboard.ime.media.emoji.FlorisEmojiCompat
import com.wordtaker.keyboard.ime.nlp.NlpManager
import com.wordtaker.keyboard.ime.text.gestures.GlideTypingManager
import com.wordtaker.keyboard.ime.theme.ThemeManager
import com.wordtaker.keyboard.lib.cache.CacheManager
import com.wordtaker.keyboard.lib.crashutility.CrashUtility
import com.wordtaker.keyboard.lib.devtools.Flog
import com.wordtaker.keyboard.lib.devtools.LogTopic
import com.wordtaker.keyboard.lib.devtools.flogError
import com.wordtaker.keyboard.lib.ext.ExtensionManager
import dev.patrickgold.jetpref.datastore.runtime.initAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.wordtaker.lib.kotlin.io.deleteContentsRecursively
import com.wordtaker.lib.kotlin.tryOrNull
import org.florisboard.libnative.dummyAdd
import java.lang.ref.WeakReference

/**
 * Global weak reference for the [FlorisApplication] class. This is needed as in certain scenarios an application
 * reference is needed, but the Android framework hasn't finished setting up
 */
private var FlorisApplicationReference = WeakReference<FlorisApplication?>(null)

@Suppress("unused")
class FlorisApplication : Application() {
    companion object {
        init {
            try {
                System.loadLibrary("fl_native")
            } catch (e: Exception) {
                android.util.Log.e("FlorisApplication", "Failed to load fl_native: $e")
            }
            try {
                System.loadLibrary("pinyinime")
            } catch (e: Exception) {
                android.util.Log.e("FlorisApplication", "Failed to load pinyinime: $e")
            }
        }
    }

    private val mainHandler by lazy { Handler(mainLooper) }
    private val scope = CoroutineScope(Dispatchers.Default)
    val preferenceStoreLoaded = MutableStateFlow(false)

    val cacheManager = lazy { CacheManager(this) }
    val clipboardManager = lazy { ClipboardManager(this) }
    val editorInstance = lazy { EditorInstance(this) }
    val extensionManager = lazy { ExtensionManager(this) }
    val glideTypingManager = lazy { GlideTypingManager(this) }
    val keyboardManager = lazy { KeyboardManager(this) }
    val nlpManager = lazy { NlpManager(this) }
    val subtypeManager = lazy { SubtypeManager(this) }
    val themeManager = lazy { ThemeManager(this) }

    override fun onCreate() {
        super.onCreate()
        FlorisApplicationReference = WeakReference(this)
        com.wordtaker.keyboard.wordtaker.di.AppGraph.init(applicationContext)
        // Prime the on-device ASR engine EARLY on a background thread. The APK contains no
        // model; this only validates/warms a previously installed private Paraformer model.
        scope.launch {
            try {
                com.wordtaker.keyboard.wordtaker.di.AppGraph.speechEngine
            } catch (t: Throwable) {
                android.util.Log.e("FlorisApplication", "ASR warm-up failed: $t")
            }
        }
        try {
            Flog.install(
                context = this,
                isFloggingEnabled = BuildConfig.DEBUG,
                flogTopics = LogTopic.ALL,
                flogLevels = Flog.LEVEL_ALL,
                flogOutputs = Flog.OUTPUT_CONSOLE,
            )
            CrashUtility.install(this)
            FlorisEmojiCompat.init(this)
            flogError { "dummy result: ${dummyAdd(3,4)}" }

            if (!UserManagerCompat.isUserUnlocked(this)) {
                cacheDir?.deleteContentsRecursively()
                extensionManager.value.init()
                registerReceiver(BootComplete(), IntentFilter(Intent.ACTION_USER_UNLOCKED))
                return
            }

            init()
        } catch (e: Exception) {
            CrashUtility.stageException(e)
            return
        }
    }

    fun init() {
        // D-1 ANR residual root cause (night4 r4/r5, ANR trace anr_2026-07-13-14-39-42-975):
        // even after r3 serialized clipboardManager/DictionaryManager behind the prefs-load
        // coroutine below, `AppPrefsKt.<clinit>` (triggered by first touch of the top-level
        // `val FlorisPreferenceStore = jetprefDataStoreOf(...)` in AppPrefs.kt) was still first
        // touched on the `scope.launch` background thread inside that coroutine. FlorisImeService
        // independently touches the same `FlorisPreferenceStore` property in its own field
        // initializer (`private val prefs by FlorisPreferenceStore`, evaluated during
        // FlorisImeService.<init> on the MAIN thread whenever the system creates the service).
        // JVM class-init is a monitor-guarded, exactly-once operation: whichever thread reaches
        // it first holds the lock until the (allocation-heavy, reflection-built) preference model
        // graph finishes constructing; under this environment's memory pressure that construction
        // occasionally stalls for seconds inside a GC pause, and if FlorisImeService.<init> races
        // in during that window the main thread blocks on the class monitor -> "executing service
        // FlorisImeService" ANR (confirmed via trace: main thread waiting on
        // `Class<com.wordtaker.keyboard.app.AppPrefsKt>`, held by `DefaultDispatcher-worker-4`
        // running `FlorisApplication$init$2` at this file's `FlorisPreferenceStore.initAndroid`
        // call). Fix: force this cheap, in-memory-only model construction (NOT the disk-backed
        // `.initAndroid()` I/O, which stays async below exactly as r3 left it) synchronously here,
        // on the main thread, before returning from `init()` -- since `Application.onCreate()`
        // (which calls `init()` synchronously) is guaranteed by the Android framework to fully
        // complete before any other component (incl. FlorisImeService) is created in this
        // process, this makes the class-init race structurally impossible rather than merely
        // less likely.
        Log.i("PREFS", "prefs model class warmed synchronously: ${FlorisPreferenceStore.hashCode()}")
        cacheDir?.deleteContentsRecursively()
        // D-1 ANR root cause (night4 r3): `clipboardManager`/`DictionaryManager` both read
        // `private val prefs by FlorisPreferenceStore` in their constructors, which forces
        // class-init of AppPrefsKt (a large reflection-built JetPref preference model). That
        // used to run HERE, synchronously on the main thread, at the same moment this very
        // coroutine below raced to touch the same class via `FlorisPreferenceStore.initAndroid`.
        // Whichever thread lost the JVM class-init monitor race blocked for the entire
        // (CPU/allocation-heavy) construction — under memory pressure that stalled the main
        // thread long enough to ANR "executing service FlorisImeService" and, since
        // Application.onCreate() gates every entry point in this process, also starved
        // Activity startup (Settings FocusEvent ANR). Fix: do all AppPrefsKt-touching work on
        // one thread, sequentially, off the main thread — never let the main thread contend
        // for that class-init.
        // ExtensionManager never touches FlorisPreferenceStore, so it doesn't need to wait
        // for the preference store below — keep its (already-async) asset indexing starting
        // as early as possible on its own coroutine.
        scope.launch {
            try {
                extensionManager.value.init()
            } catch (t: Throwable) {
                android.util.Log.e("FlorisApplication", "extensionManager init failed: $t")
            }
        }
        scope.launch {
            val result = FlorisPreferenceStore.initAndroid(
                context = this@FlorisApplication,
                datastoreName = FlorisPreferenceModel.NAME,
            )
            Log.i("PREFS", result.toString())
            preferenceStoreLoaded.value = true
            try {
                clipboardManager.value.initializeForContext(this@FlorisApplication)
                DictionaryManager.init(this@FlorisApplication)
            } catch (t: Throwable) {
                android.util.Log.e("FlorisApplication", "background init failed: $t")
            }
        }
    }

    private inner class BootComplete : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                try {
                    unregisterReceiver(this)
                } catch (e: Exception) {
                    flogError { e.toString() }
                }
                mainHandler.post { init() }
            }
        }
    }
}

private tailrec fun Context.florisApplication(): FlorisApplication {
    return when (this) {
        is FlorisApplication -> this
        is ContextWrapper -> when {
            this.baseContext != null -> this.baseContext.florisApplication()
            else -> FlorisApplicationReference.get()!!
        }
        else -> tryOrNull { this.applicationContext as FlorisApplication } ?: FlorisApplicationReference.get()!!
    }
}

fun Context.appContext() = lazyOf(this.florisApplication())

fun Context.cacheManager() = this.florisApplication().cacheManager

fun Context.clipboardManager() = this.florisApplication().clipboardManager

fun Context.editorInstance() = this.florisApplication().editorInstance

fun Context.extensionManager() = this.florisApplication().extensionManager

fun Context.glideTypingManager() = this.florisApplication().glideTypingManager

fun Context.keyboardManager() = this.florisApplication().keyboardManager

fun Context.nlpManager() = this.florisApplication().nlpManager

fun Context.subtypeManager() = this.florisApplication().subtypeManager

fun Context.themeManager() = this.florisApplication().themeManager
