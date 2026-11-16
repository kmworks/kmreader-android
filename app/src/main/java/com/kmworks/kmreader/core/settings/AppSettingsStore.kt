package com.kmworks.kmreader.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

    /** 仪表盘分区顺序（可见分区的 id，按展示顺序；对齐 iOS dashboard.sections 有序数组）。 */
    val dashboardSectionOrder: Flow<List<String>> = store.data.map { prefs ->
        prefs[Keys.DASHBOARD_SECTION_ORDER].toCsvList()
    }

    suspend fun setDashboardSectionOrder(ids: List<String>) {
        store.edit { prefs -> prefs[Keys.DASHBOARD_SECTION_ORDER] = ids.joinToString(",") }
    }

    /** 仪表盘隐藏分区（不在可见顺序里的分区 id）。 */
    val dashboardHiddenSections: Flow<Set<String>> = store.data.map { prefs ->
        prefs[Keys.DASHBOARD_HIDDEN_SECTIONS].toCsvList().toSet()
    }

    suspend fun setDashboardHiddenSections(ids: Set<String>) {
        store.edit { prefs -> prefs[Keys.DASHBOARD_HIDDEN_SECTIONS] = ids.joinToString(",") }
    }

    /** 分区标题右侧是否显示卡片形态菜单（默认开）。 */
    val dashboardCardKindMenu: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.DASHBOARD_CARD_KIND_MENU] ?: true
    }

    suspend fun setDashboardCardKindMenu(enabled: Boolean) {
        store.edit { prefs -> prefs[Keys.DASHBOARD_CARD_KIND_MENU] = enabled }
    }

    /** 是否显示「进行中的阅读列表」分区（默认开）。 */
    val dashboardContinuationEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.DASHBOARD_CONTINUATION] ?: true
    }

    suspend fun setDashboardContinuationEnabled(enabled: Boolean) {
        store.edit { prefs -> prefs[Keys.DASHBOARD_CONTINUATION] = enabled }
    }

    /** 分区卡片形态覆盖（"id=kind" 逗号分隔；未覆盖的分区跟随默认）。 */
    val dashboardKindOverrides: Flow<Map<String, String>> = store.data.map { prefs ->
        prefs[Keys.DASHBOARD_KIND_OVERRIDES].toCsvList()
            .mapNotNull { entry ->
                val parts = entry.split('=', limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
            }
            .toMap()
    }

    suspend fun setDashboardKindOverride(sectionId: String, kind: String?) {
        store.edit { prefs ->
            val current = prefs[Keys.DASHBOARD_KIND_OVERRIDES].toCsvList()
                .filter { it.substringBefore('=') != sectionId }
            val next = if (kind == null) current else current + "$sectionId=$kind"
            prefs[Keys.DASHBOARD_KIND_OVERRIDES] = next.joinToString(",")
        }
    }

    /** 仅封面卡片（隐藏标题/副标题；对齐 iOS coverOnlyCards）。 */
    val cardCoverOnly: Flow<Boolean> = booleanPref(Keys.CARD_COVER_ONLY, false)

    suspend fun setCardCoverOnly(enabled: Boolean) = writeBoolean(Keys.CARD_COVER_ONLY, enabled)

    /** 文字叠加在封面上（对齐 iOS cardTextOverlayMode）。 */
    val cardTextOverlay: Flow<Boolean> = booleanPref(Keys.CARD_TEXT_OVERLAY, false)

    suspend fun setCardTextOverlay(enabled: Boolean) = writeBoolean(Keys.CARD_TEXT_OVERLAY, enabled)

    /** 书籍卡显示所属系列名（对齐 iOS showBookCardSeriesTitle）。 */
    val cardShowBookSeriesTitle: Flow<Boolean> = booleanPref(Keys.CARD_SHOW_BOOK_SERIES_TITLE, true)

    suspend fun setCardShowBookSeriesTitle(enabled: Boolean) =
        writeBoolean(Keys.CARD_SHOW_BOOK_SERIES_TITLE, enabled)

    /** 封面保持原始比例（不裁剪；对齐 iOS thumbnailPreserveAspectRatio）。 */
    val thumbnailPreserveAspectRatio: Flow<Boolean> =
        booleanPref(Keys.THUMBNAIL_PRESERVE_ASPECT_RATIO, true)

    suspend fun setThumbnailPreserveAspectRatio(enabled: Boolean) =
        writeBoolean(Keys.THUMBNAIL_PRESERVE_ASPECT_RATIO, enabled)

    /** 封面投影（对齐 iOS thumbnailShowShadow）。 */
    val thumbnailShowShadow: Flow<Boolean> = booleanPref(Keys.THUMBNAIL_SHOW_SHADOW, true)

    suspend fun setThumbnailShowShadow(enabled: Boolean) =
        writeBoolean(Keys.THUMBNAIL_SHOW_SHADOW, enabled)

    /** 未读角标（对齐 iOS thumbnailShowUnreadIndicator）。 */
    val thumbnailShowUnreadIndicator: Flow<Boolean> =
        booleanPref(Keys.THUMBNAIL_SHOW_UNREAD_INDICATOR, true)

    suspend fun setThumbnailShowUnreadIndicator(enabled: Boolean) =
        writeBoolean(Keys.THUMBNAIL_SHOW_UNREAD_INDICATOR, enabled)

    /** 进度条（对齐 iOS thumbnailShowProgressBar）。 */
    val thumbnailShowProgressBar: Flow<Boolean> = booleanPref(Keys.THUMBNAIL_SHOW_PROGRESS_BAR, true)

    suspend fun setThumbnailShowProgressBar(enabled: Boolean) =
        writeBoolean(Keys.THUMBNAIL_SHOW_PROGRESS_BAR, enabled)

    /** 未读封面模糊（对齐 iOS thumbnailBlurUnreadCovers）。 */
    val thumbnailBlurUnreadCovers: Flow<Boolean> = booleanPref(Keys.THUMBNAIL_BLUR_UNREAD_COVERS, false)

    suspend fun setThumbnailBlurUnreadCovers(enabled: Boolean) =
        writeBoolean(Keys.THUMBNAIL_BLUR_UNREAD_COVERS, enabled)

    /** 仅 Wi-Fi 预加载（对齐 iOS wifiOnlyPreload）。 */
    val wifiOnlyPreload: Flow<Boolean> = booleanPref(Keys.WIFI_ONLY_PRELOAD, true)

    suspend fun setWifiOnlyPreload(enabled: Boolean) = writeBoolean(Keys.WIFI_ONLY_PRELOAD, enabled)

    /** SSE 事件驱动仪表盘自动刷新（对齐 iOS enableSSEAutoRefresh，默认开；随 SSE 总开关联动禁用）。 */
    val sseAutoRefresh: Flow<Boolean> = booleanPref(Keys.SSE_AUTO_REFRESH, true)

    suspend fun setSseAutoRefresh(enabled: Boolean) = writeBoolean(Keys.SSE_AUTO_REFRESH, enabled)

    /** SSE 连接/任务完成通知（对齐 iOS enableSSENotify，默认关；通知基础设施接入前仅持久化）。 */
    val sseNotify: Flow<Boolean> = booleanPref(Keys.SSE_NOTIFY, false)

    suspend fun setSseNotify(enabled: Boolean) = writeBoolean(Keys.SSE_NOTIFY, enabled)

    /** 网络请求超时（秒；设计稿默认 30，范围 5–120；对齐 iOS requestTimeout 语义）。 */
    val networkTimeoutSeconds: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.NETWORK_TIMEOUT_SECONDS] ?: DEFAULT_NETWORK_TIMEOUT_SECONDS
    }

    suspend fun setNetworkTimeoutSeconds(seconds: Int) {
        store.edit { prefs ->
            prefs[Keys.NETWORK_TIMEOUT_SECONDS] = seconds.coerceIn(5, 120)
        }
    }

    /** 整本下载的页级并发数（设计稿默认 2，范围 1–8；DownloadWorker 消费）。 */
    val downloadConcurrency: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.DOWNLOAD_CONCURRENCY] ?: DEFAULT_DOWNLOAD_CONCURRENCY
    }

    suspend fun setDownloadConcurrency(value: Int) {
        store.edit { prefs ->
            prefs[Keys.DOWNLOAD_CONCURRENCY] = value.coerceIn(1, 8)
        }
    }

    /** 图片磁盘缓存上限（MB，设计稿默认 2048；SessionManager ImageLoader 消费）。 */
    val imageCacheLimitMb: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.IMAGE_CACHE_LIMIT_MB] ?: DEFAULT_IMAGE_CACHE_LIMIT_MB
    }

    suspend fun setImageCacheLimitMb(value: Int) {
        store.edit { prefs ->
            prefs[Keys.IMAGE_CACHE_LIMIT_MB] = value.coerceIn(128, 20480)
        }
    }

    /** 应用图标变体：primary / classic / legacy（对齐 iOS AppIconOption）。 */
    val appIcon: Flow<String> = store.data.map { prefs -> prefs[Keys.APP_ICON] ?: APP_ICON_PRIMARY }

    suspend fun setAppIcon(value: String) {
        store.edit { prefs -> prefs[Keys.APP_ICON] = value }
    }

    private fun booleanPref(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        store.data.map { prefs -> prefs[key] ?: default }

    private suspend fun writeBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { prefs -> prefs[key] = value }
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
        const val APP_ICON_PRIMARY = "primary"
        const val DEFAULT_NETWORK_TIMEOUT_SECONDS = 30
        const val DEFAULT_DOWNLOAD_CONCURRENCY = 2
        const val DEFAULT_IMAGE_CACHE_LIMIT_MB = 2048
        fun scopeForLibrary(libraryId: String): String = "library:$libraryId"
    }

    private object Keys {
        val SSE_ENABLED = booleanPreferencesKey("sse_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val TRUE_BLACK = booleanPreferencesKey("true_black")
        val DIVINA_DIRECTION = stringPreferencesKey("divina_default_direction")
        val PINNED_LIBRARY_IDS = stringPreferencesKey("dashboard_pinned_library_ids")
        val LIBRARY_BROWSE_SCOPE = stringPreferencesKey("library_browse_scope")
        val DASHBOARD_SECTION_ORDER = stringPreferencesKey("dashboard_section_order")
        val DASHBOARD_HIDDEN_SECTIONS = stringPreferencesKey("dashboard_hidden_sections")
        val DASHBOARD_CARD_KIND_MENU = booleanPreferencesKey("dashboard_card_kind_menu")
        val DASHBOARD_CONTINUATION = booleanPreferencesKey("dashboard_continuation_enabled")
        val DASHBOARD_KIND_OVERRIDES = stringPreferencesKey("dashboard_kind_overrides")
        val CARD_COVER_ONLY = booleanPreferencesKey("card_cover_only")
        val CARD_TEXT_OVERLAY = booleanPreferencesKey("card_text_overlay")
        val CARD_SHOW_BOOK_SERIES_TITLE = booleanPreferencesKey("card_show_book_series_title")
        val THUMBNAIL_PRESERVE_ASPECT_RATIO = booleanPreferencesKey("thumbnail_preserve_aspect_ratio")
        val THUMBNAIL_SHOW_SHADOW = booleanPreferencesKey("thumbnail_show_shadow")
        val THUMBNAIL_SHOW_UNREAD_INDICATOR = booleanPreferencesKey("thumbnail_show_unread_indicator")
        val THUMBNAIL_SHOW_PROGRESS_BAR = booleanPreferencesKey("thumbnail_show_progress_bar")
        val THUMBNAIL_BLUR_UNREAD_COVERS = booleanPreferencesKey("thumbnail_blur_unread_covers")
        val WIFI_ONLY_PRELOAD = booleanPreferencesKey("wifi_only_preload")
        val SSE_AUTO_REFRESH = booleanPreferencesKey("sse_auto_refresh")
        val SSE_NOTIFY = booleanPreferencesKey("sse_notify")
        val NETWORK_TIMEOUT_SECONDS = intPreferencesKey("network_timeout_seconds")
        val DOWNLOAD_CONCURRENCY = intPreferencesKey("download_concurrency")
        val IMAGE_CACHE_LIMIT_MB = intPreferencesKey("image_cache_limit_mb")
        val APP_ICON = stringPreferencesKey("app_icon")
    }
}

private fun String?.toCsvList(): List<String> =
    this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
