package com.kmworks.kmreader.core.network

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 服务器实例与凭据。认证方式对应 Komga 文档：Basic Auth 或 `X-API-Key`。 */
data class ServerConfig(
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    /** 登录探测时由 UserDto.roles 判定并随会话保存（komf 等管理员能力门控用）。 */
    val isAdmin: Boolean = false,
) {
    val useApiKey: Boolean get() = apiKey.isNotBlank()
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && (useApiKey || (username.isNotBlank() && password.isNotBlank()))

    /** 规范化后的 base URL（去掉末尾斜杠）。 */
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')
}

private val Context.serverConfigStore by preferencesDataStore(name = "server_config")

/** 实例配置的持久化（DataStore）。 */
class ServerConfigStore(private val context: Context) {

    private object Keys {
        val BaseUrl = stringPreferencesKey("base_url")
        val Username = stringPreferencesKey("username")
        val Password = stringPreferencesKey("password")
        val ApiKey = stringPreferencesKey("api_key")
        val IsAdmin = booleanPreferencesKey("is_admin")
    }

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

    suspend fun save(config: ServerConfig) {
        context.serverConfigStore.edit { prefs ->
            prefs[Keys.BaseUrl] = config.normalizedBaseUrl
            prefs[Keys.Username] = config.username
            prefs[Keys.Password] = config.password
            prefs[Keys.ApiKey] = config.apiKey
            prefs[Keys.IsAdmin] = config.isAdmin
        }
    }

    suspend fun clear() {
        context.serverConfigStore.edit { it.clear() }
    }
}
