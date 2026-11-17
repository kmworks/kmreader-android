package com.kmworks.kmreader.core.reader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.epubThemePresetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "epub_theme_presets",
)

/** EPUB theme preset (fields mirror the UI model EpubReaderSettings, plus name and update time). */
@Serializable
data class EpubThemePreset(
    val name: String,
    val theme: String = "system",
    val fontSizeSp: Float = 18f,
    val typeface: String = "publisher",
    val fontWeight: Float = 0.4f,
    val margins: Float = 1.0f,
    val lineHeight: Float = 1.4f,
    val flowStyle: String = "paged",
    val columns: String = "single",
    val publisherStyles: Boolean = true,
    val animatePageTurns: Boolean = true,
    val updatedAt: Long = 0L,
)

/**
 * EPUB theme preset persistence (pattern mirrors core/data/FilterPresetStore:
 * dedicated DataStore file + @Serializable list + kotlinx.serialization JSON encoding).
 * Overwrites by name (upsert), supports rename/delete, survives restarts.
 */
@Singleton
class EpubThemePresetStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.epubThemePresetDataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("presets_json")

    val presets: Flow<List<EpubThemePreset>> = dataStore.data.map { prefs ->
        decode(prefs[key])
    }

    /** Save a preset; same-name overwrite (mirrors the FilterPresetStore upsert semantics). */
    suspend fun savePreset(preset: EpubThemePreset) {
        val trimmed = preset.name.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decode(prefs[key])
            val next = current.filterNot { it.name == trimmed } +
                preset.copy(name = trimmed, updatedAt = System.currentTimeMillis())
            prefs[key] = json.encodeToString(next)
        }
    }

    /** Rename; ignored when the new name is empty or conflicts with another existing preset. */
    suspend fun renamePreset(oldName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == oldName) return
        dataStore.edit { prefs ->
            val current = decode(prefs[key])
            if (current.any { it.name == trimmed }) return@edit
            val next = current.map { preset ->
                if (preset.name == oldName) {
                    preset.copy(name = trimmed, updatedAt = System.currentTimeMillis())
                } else {
                    preset
                }
            }
            prefs[key] = json.encodeToString(next)
        }
    }

    suspend fun deletePreset(name: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[key])
            prefs[key] = json.encodeToString(current.filterNot { it.name == name })
        }
    }

    private fun decode(raw: String?): List<EpubThemePreset> =
        raw?.let {
            runCatching { json.decodeFromString<List<EpubThemePreset>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()
}
