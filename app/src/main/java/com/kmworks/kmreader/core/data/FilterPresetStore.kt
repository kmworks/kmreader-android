package com.kmworks.kmreader.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.core.network.instanceStorageKey
import com.kmworks.kmreader.data.BookBrowseOptions
import com.kmworks.kmreader.data.SavedFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.filterPresetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "filter_presets",
)

/**
 * Filter preset persistence (mirrors the original savedFilter table: overwrite by name, reused across pages, deletable).
 *
 * Presets are stored per server instance: a preset carries library ids and metadata values (authors, tags,
 * read statuses) that exist on one server, so showing them on another server would offer filters that match
 * nothing there. Every server lists only its own presets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FilterPresetStore @Inject constructor(
    @ApplicationContext context: Context,
    private val serverConfig: ServerConfigStore,
) {
    private val dataStore = context.filterPresetDataStore
    private val json = Json { ignoreUnknownKeys = true }

    /** Single global list written by earlier builds (upgrade path, see [adoptLegacy]). */
    private val legacyKey = stringPreferencesKey("presets_json")
    private val legacyAdoptedKey = booleanPreferencesKey("presets_legacy_adopted")

    val presets: Flow<List<SavedFilter>> = serverConfig.config
        .map { it.instanceStorageKey() }
        .distinctUntilChanged()
        .flatMapLatest { instance ->
            dataStore.data.map { prefs ->
                if (prefs.contains(prefKey(instance))) decode(prefs[prefKey(instance)])
                else adoptLegacy(instance)
            }
        }

    /**
     * One-time upgrade path: presets used to be one global list. Adopt it for the first server that reads
     * them so an existing installation keeps its presets; every later server starts empty.
     *
     * The library scope/pins get no such adoption on purpose: they are state, and the requested behaviour is
     * that every server starts at all libraries with nothing pinned.
     */
    private suspend fun adoptLegacy(instance: String): List<SavedFilter> {
        if (instance.isBlank()) return emptyList()
        val legacy = dataStore.data.first()[legacyKey] ?: return emptyList()
        val written = dataStore.edit { prefs ->
            if (prefs[legacyAdoptedKey] != true) {
                prefs[prefKey(instance)] = legacy
                prefs[legacyAdoptedKey] = true
            }
        }
        return decode(written[prefKey(instance)])
    }

    /** Save a preset; same-name overwrite (mirrors the original savedFilter upsert semantics). */
    suspend fun savePreset(name: String, options: BookBrowseOptions) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val instance = currentInstanceKey()
        ensureAdopted(instance)
        val key = prefKey(instance)
        dataStore.edit { prefs ->
            val current = decode(prefs[key])
            val next = current.filterNot { it.name == trimmed } + SavedFilter(trimmed, options)
            prefs[key] = json.encodeToString(next)
        }
    }

    suspend fun deletePreset(name: String) {
        val instance = currentInstanceKey()
        ensureAdopted(instance)
        val key = prefKey(instance)
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(decode(prefs[key]).filterNot { it.name == name })
        }
    }

    /** Write the upgraded global list into [instance] before the first save/delete so edits keep it. */
    private suspend fun ensureAdopted(instance: String) {
        if (instance.isBlank()) return
        if (dataStore.data.first().contains(prefKey(instance))) return
        adoptLegacy(instance)
    }

    private fun decode(raw: String?): List<SavedFilter> = raw
        ?.let { runCatching { json.decodeFromString<List<SavedFilter>>(it) }.getOrDefault(emptyList()) }
        ?: emptyList()

    private suspend fun currentInstanceKey(): String = serverConfig.config.first().instanceStorageKey()

    /** Preset list of one instance; `@` never occurs in an instance key, so names cannot collide. */
    private fun prefKey(instance: String): Preferences.Key<String> =
        stringPreferencesKey("presets_json@${instance.ifBlank { NO_INSTANCE }}")

    private companion object {
        /** Instance placeholder while no server is configured (same convention as AppSettingsStore). */
        const val NO_INSTANCE = "none"
    }
}
