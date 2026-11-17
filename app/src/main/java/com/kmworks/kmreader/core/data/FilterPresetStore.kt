package com.kmworks.kmreader.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.SavedFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.filterPresetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "filter_presets",
)

/** Filter preset persistence (mirrors the original savedFilter table: overwrite by name, reused across pages, deletable). */
@Singleton
class FilterPresetStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.filterPresetDataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("presets_json")

    val presets: Flow<List<SavedFilter>> = dataStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString<List<SavedFilter>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    /** Save a preset; same-name overwrite (mirrors the original savedFilter upsert semantics). */
    suspend fun savePreset(name: String, options: BookBrowseOptions) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString<List<SavedFilter>>(raw) }.getOrDefault(emptyList())
            } ?: emptyList()
            val next = current.filterNot { it.name == trimmed } + SavedFilter(trimmed, options)
            prefs[key] = json.encodeToString(next)
        }
    }

    suspend fun deletePreset(name: String) {
        dataStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString<List<SavedFilter>>(raw) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[key] = json.encodeToString(current.filterNot { it.name == name })
        }
    }
}
