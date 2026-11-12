package com.kmworks.kmreader.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsStore by preferencesDataStore(name = "app_settings")

/** App-level settings (SSE toggle etc.), same pattern as ServerConfigStore. */
@Singleton
class AppSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val store = context.appSettingsStore

    /** 是否启用 SSE 实时更新（默认开）。 */
    val sseEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.SSE_ENABLED] ?: true
    }

    suspend fun setSseEnabled(enabled: Boolean) {
        store.edit { prefs -> prefs[Keys.SSE_ENABLED] = enabled }
    }

    private object Keys {
        val SSE_ENABLED = booleanPreferencesKey("sse_enabled")
    }
}
