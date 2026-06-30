package com.wordtaker.keyboard.wordtaker.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Immutable snapshot of user settings. */
data class SettingsState(
    val skin: String = DEFAULT_SKIN,
    val role: String = DEFAULT_ROLE,
    val tone: Boolean = DEFAULT_TONE,
    /** Which prompt-tone style plays on record start/end: "meow" (default) or "beep". */
    val toneStyle: String = DEFAULT_TONE_STYLE,
    /** Reserved for a future minimal-UI mode. No UI yet. */
    val minimal: Boolean = DEFAULT_MINIMAL,
    /** Whether the speech-recognition model has been downloaded. */
    val modelDownloaded: Boolean = DEFAULT_MODEL_DOWNLOADED,
) {
    companion object {
        const val DEFAULT_SKIN = "cat"
        const val DEFAULT_ROLE = "normal"
        const val DEFAULT_TONE = true
        const val DEFAULT_TONE_STYLE = "meow"
        const val DEFAULT_MINIMAL = false
        const val DEFAULT_MODEL_DOWNLOADED = false

        /** Prompt-tone style identifiers. */
        const val TONE_MEOW = "meow"
        const val TONE_BEEP = "beep"
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "wt_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SKIN = stringPreferencesKey("skin")
        val ROLE = stringPreferencesKey("role")
        val TONE = booleanPreferencesKey("tone")
        val TONE_STYLE = stringPreferencesKey("tone_style")
        val MINIMAL = booleanPreferencesKey("minimal")
        val MODEL_DOWNLOADED = booleanPreferencesKey("model_downloaded")
    }

    val settings: Flow<SettingsState> = context.settingsDataStore.data.map { prefs ->
        SettingsState(
            skin = prefs[Keys.SKIN] ?: SettingsState.DEFAULT_SKIN,
            role = prefs[Keys.ROLE] ?: SettingsState.DEFAULT_ROLE,
            tone = prefs[Keys.TONE] ?: SettingsState.DEFAULT_TONE,
            toneStyle = prefs[Keys.TONE_STYLE] ?: SettingsState.DEFAULT_TONE_STYLE,
            minimal = prefs[Keys.MINIMAL] ?: SettingsState.DEFAULT_MINIMAL,
            modelDownloaded = prefs[Keys.MODEL_DOWNLOADED] ?: SettingsState.DEFAULT_MODEL_DOWNLOADED,
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

    suspend fun setMinimal(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.MINIMAL] = enabled }
    }

    suspend fun setModelDownloaded(downloaded: Boolean) {
        context.settingsDataStore.edit { it[Keys.MODEL_DOWNLOADED] = downloaded }
    }
}
