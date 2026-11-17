package com.kmworks.kmreader.core.network

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Server instance and credentials. Auth schemes per the Komga docs: Basic Auth or `X-API-Key`. */
data class ServerConfig(
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    /** Determined from UserDto.roles during the login probe and saved with the session (gates admin capabilities such as komf). */
    val isAdmin: Boolean = false,
) {
    val useApiKey: Boolean get() = apiKey.isNotBlank()
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && (useApiKey || (username.isNotBlank() && password.isNotBlank()))

    /** Normalized base URL (trailing slash removed). */
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')
}

private val Context.serverConfigStore by preferencesDataStore(name = "server_config")

/** Persistence of the instance configuration (DataStore). */
class ServerConfigStore(private val context: Context) {

    private object Keys {
        val BaseUrl = stringPreferencesKey("base_url")
        val Username = stringPreferencesKey("username")
        val Password = stringPreferencesKey("password")
        val ApiKey = stringPreferencesKey("api_key")
        val IsAdmin = booleanPreferencesKey("is_admin")
        /** Saved server instance list (JSON array, mirrors the iOS komga_instance table). */
        val InstancesJson = stringPreferencesKey("instances_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val config: Flow<ServerConfig?> = context.serverConfigStore.data.map { prefs ->
        val baseUrl = prefs[Keys.BaseUrl].orEmpty()
        if (baseUrl.isBlank()) {
            null
        } else {
            ServerConfig(
                baseUrl = baseUrl,
                username = prefs[Keys.Username].orEmpty(),
                password = prefs[Keys.Password].orEmpty(),
                apiKey = prefs[Keys.ApiKey].orEmpty(),
                isAdmin = prefs[Keys.IsAdmin] ?: false,
            )
        }
    }

    /** Saved instance list, ordered by lastUsedAt descending (mirrors iOS fetchServerDisplayItems). */
    val instances: Flow<List<ServerInstance>> = context.serverConfigStore.data.map { prefs ->
        decodeInstances(prefs[Keys.InstancesJson])
            .ifEmpty {
                // Migration: when only the legacy single-value keys exist, seed the current configuration as the first instance
                val baseUrl = prefs[Keys.BaseUrl].orEmpty()
                if (baseUrl.isBlank()) {
                    emptyList()
                } else {
                    listOf(
                        ServerInstance.fromConfig(
                            ServerConfig(
                                baseUrl = baseUrl,
                                username = prefs[Keys.Username].orEmpty(),
                                password = prefs[Keys.Password].orEmpty(),
                                apiKey = prefs[Keys.ApiKey].orEmpty(),
                                isAdmin = prefs[Keys.IsAdmin] ?: false,
                            ),
                        ),
                    )
                }
            }
            .sortedByDescending { it.lastUsedAt }
    }

    private fun decodeInstances(raw: String?): List<ServerInstance> =
        raw?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { json.decodeFromString<List<ServerInstance>>(text) }.getOrNull()
        }.orEmpty()

    suspend fun save(config: ServerConfig) {
        context.serverConfigStore.edit { prefs ->
            prefs[Keys.BaseUrl] = config.normalizedBaseUrl
            prefs[Keys.Username] = config.username
            prefs[Keys.Password] = config.password
            prefs[Keys.ApiKey] = config.apiKey
            prefs[Keys.IsAdmin] = config.isAdmin
        }
    }

    /**
     * Persist a successful login (mirrors iOS upsertInstance): deduped by normalizedBaseUrl + username;
     * repeated logins only refresh credentials and lastUsedAt, avoiding orphan instances.
     */
    suspend fun upsertInstance(config: ServerConfig) {
        context.serverConfigStore.edit { prefs ->
            val list = decodeInstances(prefs[Keys.InstancesJson]).toMutableList()
            val existingIndex = list.indexOfFirst {
                it.normalizedBaseUrl == config.normalizedBaseUrl && it.username == config.username
            }
            val instance = if (existingIndex >= 0) {
                ServerInstance.fromConfig(
                    config,
                    existingId = list[existingIndex].id,
                    createdAt = list[existingIndex].createdAt,
                )
            } else {
                ServerInstance.fromConfig(config)
            }
            if (existingIndex >= 0) list[existingIndex] = instance else list.add(instance)
            prefs[Keys.InstancesJson] = json.encodeToString(list)
        }
    }

    /** Update an instance's lastUsedAt (called after switching/login, keeping the list order fresh). */
    suspend fun touchInstance(config: ServerConfig) {
        context.serverConfigStore.edit { prefs ->
            val list = decodeInstances(prefs[Keys.InstancesJson])
            val index = list.indexOfFirst {
                it.normalizedBaseUrl == config.normalizedBaseUrl && it.username == config.username
            }
            if (index >= 0) {
                val updated = list.toMutableList()
                updated[index] = updated[index].copy(lastUsedAt = System.currentTimeMillis())
                prefs[Keys.InstancesJson] = json.encodeToString(updated)
            }
        }
    }

    /**
     * Full instance update (saved from the edit page): replaced by id; if the updated baseUrl+username
     * collides with another instance, that other instance is removed (mirrors the dedup semantics of iOS
     * upsertInstance).
     */
    suspend fun updateInstance(updated: ServerInstance) {
        context.serverConfigStore.edit { prefs ->
            val list = decodeInstances(prefs[Keys.InstancesJson])
            if (list.none { it.id == updated.id }) return@edit
            val next = list
                .filter {
                    it.id == updated.id ||
                        !(it.normalizedBaseUrl == updated.normalizedBaseUrl &&
                            it.username == updated.username)
                }
                .map { if (it.id == updated.id) updated else it }
                .sortedByDescending { it.lastUsedAt }
            prefs[Keys.InstancesJson] = json.encodeToString(next)
        }
    }

    /** Delete an instance (including credentials; mirrors iOS deleteServerDisplayItem). */
    suspend fun deleteInstance(id: String) {
        context.serverConfigStore.edit { prefs ->
            val remaining = decodeInstances(prefs[Keys.InstancesJson]).filterNot { it.id == id }
            prefs[Keys.InstancesJson] = json.encodeToString(remaining)
        }
    }

    /** Clear only the active session keys, keeping the instance rows (mirrors the iOS default logout: still one tap away from switching back). */
    suspend fun clearActiveSession() {
        context.serverConfigStore.edit { prefs ->
            prefs.remove(Keys.BaseUrl)
            prefs.remove(Keys.Username)
            prefs.remove(Keys.Password)
            prefs.remove(Keys.ApiKey)
            prefs.remove(Keys.IsAdmin)
        }
    }

    suspend fun clear() {
        context.serverConfigStore.edit { it.clear() }
    }
}
