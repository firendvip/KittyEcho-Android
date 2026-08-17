package com.wordtaker.keyboard.wordtaker.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Immutable snapshot of user settings. */
data class SettingsState(
    val skin: String = DEFAULT_SKIN,
    val role: String = DEFAULT_ROLE,
    val tone: Boolean = DEFAULT_TONE,
    /** Which legacy prompt-tone style is retained for end-tone preference compatibility. */
    val toneStyle: String = DEFAULT_TONE_STYLE,
    /** Voice prompt-tone volume, 0..100. Only scales the record end tone. */
    val toneVolume: Int = DEFAULT_TONE_VOLUME,
    /** Reserved for a future minimal-UI mode. No UI yet. */
    val minimal: Boolean = DEFAULT_MINIMAL,
    /** Whether the speech-recognition model has been downloaded. */
    val modelDownloaded: Boolean = DEFAULT_MODEL_DOWNLOADED,
    /** Never send locally recognized voice text to cloud polishing. */
    val localRecognitionOnly: Boolean = DEFAULT_LOCAL_RECOGNITION_ONLY,
) {
    companion object {
        const val DEFAULT_SKIN = "cat"
        const val DEFAULT_ROLE = "normal"
        const val DEFAULT_TONE = true
        const val DEFAULT_TONE_STYLE = "meow"
        /** New users start at a quiet but audible prompt-tone level. */
        const val DEFAULT_TONE_VOLUME = 30
        const val DEFAULT_MINIMAL = false
        const val DEFAULT_MODEL_DOWNLOADED = false
        const val DEFAULT_LOCAL_RECOGNITION_ONLY = false

        /** Prompt-tone style identifiers. */
        const val TONE_MEOW = "meow"
        const val TONE_BEEP = "beep"
    }
}

/** Resolves the persisted 0..100 slider value without migrating or overwriting existing users. */
internal fun resolveToneVolume(stored: Int?): Int =
    (stored ?: SettingsState.DEFAULT_TONE_VOLUME).coerceIn(0, 100)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "wt_settings")

/** Read-only settings boundary for consumers that only observe settings. */
interface SettingsSource {
    val settings: Flow<SettingsState>
}

class SettingsRepository(private val context: Context) : SettingsSource {

    private object Keys {
        val SKIN = stringPreferencesKey("skin")
        val ROLE = stringPreferencesKey("role")
        val TONE = booleanPreferencesKey("tone")
        val TONE_STYLE = stringPreferencesKey("tone_style")
        val TONE_VOLUME = intPreferencesKey("tone_volume")
        val MINIMAL = booleanPreferencesKey("minimal")
        val MODEL_DOWNLOADED = booleanPreferencesKey("model_downloaded")
        val LOCAL_RECOGNITION_ONLY = booleanPreferencesKey("local_recognition_only")
    }

    override val settings: Flow<SettingsState> = context.settingsDataStore.data.map { prefs ->
        SettingsState(
            skin = prefs[Keys.SKIN] ?: SettingsState.DEFAULT_SKIN,
            role = prefs[Keys.ROLE] ?: SettingsState.DEFAULT_ROLE,
            tone = prefs[Keys.TONE] ?: SettingsState.DEFAULT_TONE,
            toneStyle = prefs[Keys.TONE_STYLE] ?: SettingsState.DEFAULT_TONE_STYLE,
            toneVolume = resolveToneVolume(prefs[Keys.TONE_VOLUME]),
            minimal = prefs[Keys.MINIMAL] ?: SettingsState.DEFAULT_MINIMAL,
            modelDownloaded = prefs[Keys.MODEL_DOWNLOADED] ?: SettingsState.DEFAULT_MODEL_DOWNLOADED,
            localRecognitionOnly = prefs[Keys.LOCAL_RECOGNITION_ONLY]
                ?: SettingsState.DEFAULT_LOCAL_RECOGNITION_ONLY,
        )
    }

    suspend fun setSkin(skin: String) {
        context.settingsDataStore.edit { it[Keys.SKIN] = skin }
    }

    suspend fun setRole(role: String) {
        context.settingsDataStore.edit { it[Keys.ROLE] = role }
    }

    suspend fun setTone(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.TONE] = enabled }
    }

    suspend fun setToneStyle(style: String) {
        context.settingsDataStore.edit { it[Keys.TONE_STYLE] = style }
    }

    suspend fun setToneVolume(volume: Int) {
        context.settingsDataStore.edit { it[Keys.TONE_VOLUME] = resolveToneVolume(volume) }
    }

    suspend fun setMinimal(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.MINIMAL] = enabled }
    }

    suspend fun setModelDownloaded(downloaded: Boolean) {
        context.settingsDataStore.edit { it[Keys.MODEL_DOWNLOADED] = downloaded }
    }

    suspend fun setLocalRecognitionOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.LOCAL_RECOGNITION_ONLY] = enabled }
    }
}
