package com.kmworks.kmreader.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

private val Context.appSettingsStore by preferencesDataStore(name = "app_settings")

/** client-settings 远端键（小数点命名空间，Komga 校验格式）。 */
object ClientSettingKeys {
    const val SSE_ENABLED = "kmreader.sse.enabled"
    const val THEME_MODE = "kmreader.appearance.theme"
    const val TRUE_BLACK = "kmreader.appearance.trueblack"
    const val DIVINA_DIRECTION = "kmreader.reader.divina.direction"
}

/** 本地设置的一次变更（远端键 + 字符串值），ClientSettingsSync 订阅上行。 */
data class AppSettingChange(
    val remoteKey: String,
    val value: String,
)

/**
 * App-level settings, same pattern as ServerConfigStore.
 * set* 写本地并发出 change（触发云同步上行）；write* 只写本地不发声（远端下行应用用）。
 */
@Singleton
class AppSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val store = context.appSettingsStore

    private val _changes = MutableSharedFlow<AppSettingChange>(extraBufferCapacity = 16)
    val changes: SharedFlow<AppSettingChange> = _changes.asSharedFlow()

    /** 是否启用 SSE 实时更新（默认开）。 */
    val sseEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.SSE_ENABLED] ?: true
    }

    suspend fun setSseEnabled(enabled: Boolean) {
        writeSseEnabled(enabled)
        emitChange(ClientSettingKeys.SSE_ENABLED, enabled.toString())
    }

    /** 主题模式：system / light / dark。 */
    val themeMode: Flow<String> = store.data.map { prefs ->
        prefs[Keys.THEME_MODE] ?: THEME_SYSTEM
    }

    suspend fun setThemeMode(mode: String) {
        writeThemeMode(mode)
        emitChange(ClientSettingKeys.THEME_MODE, mode)
    }

    /** 纯黑深色（OLED，默认开）。 */
    val trueBlackDark: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.TRUE_BLACK] ?: true
    }

    suspend fun setTrueBlackDark(enabled: Boolean) {
        writeTrueBlackDark(enabled)
        emitChange(ClientSettingKeys.TRUE_BLACK, enabled.toString())
    }

    /** Divina 默认阅读方向（ReadingDirection.name.lowercase()：ltr/rtl/vertical/webtoon）。 */
    val divinaDefaultDirection: Flow<String> = store.data.map { prefs ->
        prefs[Keys.DIVINA_DIRECTION] ?: DEFAULT_DIRECTION
    }

    suspend fun setDivinaDefaultDirection(direction: String) {
        writeDivinaDefaultDirection(direction)
        emitChange(ClientSettingKeys.DIVINA_DIRECTION, direction)
    }

    /** 钉选书库 id 集合（对齐 iOS dashboard.libraryIds；纯本地，不走 client-settings）。 */
    val pinnedLibraryIds: Flow<Set<String>> = store.data.map { prefs ->
        prefs[Keys.PINNED_LIBRARY_IDS]
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    }

    suspend fun setPinnedLibraryIds(ids: Set<String>) {
        store.edit { prefs -> prefs[Keys.PINNED_LIBRARY_IDS] = ids.joinToString(",") }
    }

    /** 库浏览范围（对齐 iOS LibraryBrowseScope rawValue：pinned/all/library:{id}）。 */
    val libraryBrowseScope: Flow<String> = store.data.map { prefs ->
        prefs[Keys.LIBRARY_BROWSE_SCOPE] ?: SCOPE_PINNED
    }

    suspend fun setLibraryBrowseScope(scope: String) {
        store.edit { prefs -> prefs[Keys.LIBRARY_BROWSE_SCOPE] = scope }
    }

    // remote-apply writers：不发出 change，避免上下行回环
    internal suspend fun writeSseEnabled(enabled: Boolean) {
        store.edit { prefs -> prefs[Keys.SSE_ENABLED] = enabled }
    }

    internal suspend fun writeThemeMode(mode: String) {
        store.edit { prefs -> prefs[Keys.THEME_MODE] = mode }
    }

    internal suspend fun writeTrueBlackDark(enabled: Boolean) {
        store.edit { prefs -> prefs[Keys.TRUE_BLACK] = enabled }
    }

    internal suspend fun writeDivinaDefaultDirection(direction: String) {
        store.edit { prefs -> prefs[Keys.DIVINA_DIRECTION] = direction }
    }

    private fun emitChange(remoteKey: String, value: String) {
        _changes.tryEmit(AppSettingChange(remoteKey, value))
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val DEFAULT_DIRECTION = "ltr"
        const val SCOPE_PINNED = "pinned"
        const val SCOPE_ALL = "all"
        fun scopeForLibrary(libraryId: String): String = "library:$libraryId"
    }

    private object Keys {
        val SSE_ENABLED = booleanPreferencesKey("sse_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val TRUE_BLACK = booleanPreferencesKey("true_black")
        val DIVINA_DIRECTION = stringPreferencesKey("divina_default_direction")
        val PINNED_LIBRARY_IDS = stringPreferencesKey("dashboard_pinned_library_ids")
        val LIBRARY_BROWSE_SCOPE = stringPreferencesKey("library_browse_scope")
    }
}
