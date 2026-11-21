package com.kmworks.kmreader.core.network

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * A saved server instance (mirrors iOS KomgaInstance).
 * The instance list persists under the ServerConfigStore `instances_json` key; the "currently active
 * instance" still uses the original single-value keys (base_url/username/…). Switching = writing the
 * selected instance back to the active keys; SessionManager's clients flow rebuilds automatically and
 * the consumer chain needs no changes.
 */
@Serializable
data class ServerInstance(
    val id: String = UUID.randomUUID().toString(),
    /** Display name; falls back to the host when empty (mirrors iOS defaultName). */
    val name: String = "",
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    val isAdmin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
) {
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')

    val useApiKey: Boolean get() = apiKey.isNotBlank()

    /** List display name (mirrors iOS ServerDisplayItem: empty name falls back to host). */
    val displayName: String
        get() = name.ifBlank {
            runCatching { java.net.URI(normalizedBaseUrl).host }.getOrNull() ?: normalizedBaseUrl
        }

    /** Convert to the active session configuration (written back to the ServerConfig single-value keys). */
    fun toConfig(): ServerConfig = ServerConfig(
        baseUrl = normalizedBaseUrl,
        username = username,
        password = password,
        apiKey = apiKey,
        isAdmin = isAdmin,
    )

    companion object {
        /** Build an instance from the active configuration after a successful login (upsert dedup key = normalizedBaseUrl + username). */
        fun fromConfig(
            config: ServerConfig,
            existingId: String? = null,
            createdAt: Long? = null,
            name: String = "",
        ): ServerInstance =
            ServerInstance(
                id = existingId ?: UUID.randomUUID().toString(),
                name = name,
                baseUrl = config.normalizedBaseUrl,
                username = config.username,
                password = config.password,
                apiKey = config.apiKey,
                isAdmin = config.isAdmin,
                createdAt = createdAt ?: System.currentTimeMillis(),
                lastUsedAt = System.currentTimeMillis(),
            )
    }
}
