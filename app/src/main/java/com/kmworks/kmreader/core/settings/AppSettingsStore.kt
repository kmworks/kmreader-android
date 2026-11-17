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

/** Remote client-settings keys (dot-namespaced; Komga validates the format). */
object ClientSettingKeys {
    const val SSE_ENABLED = "kmreader.sse.enabled"
    const val THEME_MODE = "kmreader.appearance.theme"
    const val TRUE_BLACK = "kmreader.appearance.trueblack"
    const val DIVINA_DIRECTION = "kmreader.reader.divina.direction"
}

/** One local setting change (remote key + string value); ClientSettingsSync subscribes and pushes it upstream. */
data class AppSettingChange(
    val remoteKey: String,
    val value: String,
)

/**
 * App-level settings, same pattern as ServerConfigStore.
 * set* writes locally and emits a change (triggers cloud sync upstream); write* writes locally only without emitting (used for applying remote downlink).
 */
@Singleton
class AppSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val store = context.appSettingsStore

    private val _changes = MutableSharedFlow<AppSettingChange>(extraBufferCapacity = 16)
    val changes: SharedFlow<AppSettingChange> = _changes.asSharedFlow()

    /** Whether SSE realtime updates are enabled (default on). */
    val sseEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.SSE_ENABLED] ?: true
    }

    suspend fun setSseEnabled(enabled: Boolean) {
        writeSseEnabled(enabled)
        emitChange(ClientSettingKeys.SSE_ENABLED, enabled.toString())
    }

    /** Theme mode: system / light / dark. */
    val themeMode: Flow<String> = store.data.map { prefs ->
        prefs[Keys.THEME_MODE] ?: THEME_SYSTEM
    }

    suspend fun setThemeMode(mode: String) {
        writeThemeMode(mode)
        emitChange(ClientSettingKeys.THEME_MODE, mode)
    }

    /** True black dark theme (OLED, default on). */
    val trueBlackDark: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.TRUE_BLACK] ?: true
    }

    suspend fun setTrueBlackDark(enabled: Boolean) {
        writeTrueBlackDark(enabled)
        emitChange(ClientSettingKeys.TRUE_BLACK, enabled.toString())
    }

    /** Divina default reading direction (ReadingDirection.name.lowercase(): ltr/rtl/vertical/webtoon). */
    val divinaDefaultDirection: Flow<String> = store.data.map { prefs ->
        prefs[Keys.DIVINA_DIRECTION] ?: DEFAULT_DIRECTION
    }

    suspend fun setDivinaDefaultDirection(direction: String) {
        writeDivinaDefaultDirection(direction)
        emitChange(ClientSettingKeys.DIVINA_DIRECTION, direction)
    }

    /** Pinned library id set (aligned with iOS dashboard.libraryIds; local only, not synced through client-settings). */
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

    /** Pinned collection id set (aligned with iOS Collection isPinned: local storage only, pinned items sort first in the list). */
    val pinnedCollectionIds: Flow<Set<String>> = store.data.map { prefs ->
        prefs[Keys.PINNED_COLLECTION_IDS]
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    }

    suspend fun setPinnedCollectionIds(ids: Set<String>) {
        store.edit { prefs -> prefs[Keys.PINNED_COLLECTION_IDS] = ids.joinToString(",") }
    }

    /** Pinned read-list id set (aligned with iOS ReadList isPinned: local storage only, pinned items sort first in the list). */
    val pinnedReadListIds: Flow<Set<String>> = store.data.map { prefs ->
        prefs[Keys.PINNED_READLIST_IDS]
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    }

    suspend fun setPinnedReadListIds(ids: Set<String>) {
        store.edit { prefs -> prefs[Keys.PINNED_READLIST_IDS] = ids.joinToString(",") }
    }

    /** Library browse scope (aligned with iOS LibraryBrowseScope rawValue: pinned/all/library:{id}). */
    val libraryBrowseScope: Flow<String> = store.data.map { prefs ->
        prefs[Keys.LIBRARY_BROWSE_SCOPE] ?: SCOPE_PINNED
    }

    suspend fun setLibraryBrowseScope(scope: String) {
        store.edit { prefs -> prefs[Keys.LIBRARY_BROWSE_SCOPE] = scope }
    }

    /** Library browse scope of the search tab (aligned with iOS `@AppStorage("searchBrowseScope")`, persisted, default pinned). */
    val searchBrowseScope: Flow<String> = store.data.map { prefs ->
        prefs[Keys.SEARCH_BROWSE_SCOPE] ?: SCOPE_PINNED
    }

    suspend fun setSearchBrowseScope(scope: String) {
        store.edit { prefs -> prefs[Keys.SEARCH_BROWSE_SCOPE] = scope }
    }

    /** Dashboard section order (ids of visible sections in display order; aligned with the iOS dashboard.sections ordered array). */
    val dashboardSectionOrder: Flow<List<String>> = store.data.map { prefs ->
        prefs[Keys.DASHBOARD_SECTION_ORDER].toCsvList()
    }

    suspend fun setDashboardSectionOrder(ids: List<String>) {
        store.edit { prefs -> prefs[Keys.DASHBOARD_SECTION_ORDER] = ids.joinToString(",") }
    }

    /** Hidden dashboard sections (section ids that are not in the visible order). */
    val dashboardHiddenSections: Flow<Set<String>> = store.data.map { prefs ->
        prefs[Keys.DASHBOARD_HIDDEN_SECTIONS].toCsvList().toSet()
    }

    suspend fun setDashboardHiddenSections(ids: Set<String>) {
        store.edit { prefs -> prefs[Keys.DASHBOARD_HIDDEN_SECTIONS] = ids.joinToString(",") }
    }

    /** Whether the card kind menu is shown on the right side of a section title (default on). */
    val dashboardCardKindMenu: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.DASHBOARD_CARD_KIND_MENU] ?: true
    }

    suspend fun setDashboardCardKindMenu(enabled: Boolean) {
        store.edit { prefs -> prefs[Keys.DASHBOARD_CARD_KIND_MENU] = enabled }
    }

    /** Whether the "Read Lists in Progress" section is shown (default on). */
    val dashboardContinuationEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.DASHBOARD_CONTINUATION] ?: true
    }

    suspend fun setDashboardContinuationEnabled(enabled: Boolean) {
        store.edit { prefs -> prefs[Keys.DASHBOARD_CONTINUATION] = enabled }
    }

    /** Section card kind overrides (comma-separated "id=kind"; sections without an override follow the default). */
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

    /** Per browse-page layout overrides (comma-separated "page=Layout"; aligned with the iOS per-page layout memory, BrowseLayout.name values). */
    val browseLayoutOverrides: Flow<Map<String, String>> = store.data.map { prefs ->
        prefs[Keys.BROWSE_LAYOUT_OVERRIDES].toCsvList()
            .mapNotNull { entry ->
                val parts = entry.split('=', limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
            }
            .toMap()
    }

    /** Layout of a single browse page (null when unrecorded → callers use the page default). */
    fun browseLayout(page: String): Flow<String?> = browseLayoutOverrides.map { it[page] }

    suspend fun setBrowseLayout(page: String, layout: String) {
        store.edit { prefs ->
            val current = prefs[Keys.BROWSE_LAYOUT_OVERRIDES].toCsvList()
                .filter { it.substringBefore('=') != page }
            prefs[Keys.BROWSE_LAYOUT_OVERRIDES] = (current + "$page=$layout").joinToString(",")
        }
    }

    /** Cover-only cards (hide title/subtitle; aligned with iOS coverOnlyCards). */
    val cardCoverOnly: Flow<Boolean> = booleanPref(Keys.CARD_COVER_ONLY, false)

    suspend fun setCardCoverOnly(enabled: Boolean) = writeBoolean(Keys.CARD_COVER_ONLY, enabled)

    /** Text overlaid on the cover (aligned with iOS cardTextOverlayMode). */
    val cardTextOverlay: Flow<Boolean> = booleanPref(Keys.CARD_TEXT_OVERLAY, false)

    suspend fun setCardTextOverlay(enabled: Boolean) = writeBoolean(Keys.CARD_TEXT_OVERLAY, enabled)

    /** Show the parent series title on book cards (aligned with iOS showBookCardSeriesTitle). */
    val cardShowBookSeriesTitle: Flow<Boolean> = booleanPref(Keys.CARD_SHOW_BOOK_SERIES_TITLE, true)

    suspend fun setCardShowBookSeriesTitle(enabled: Boolean) =
        writeBoolean(Keys.CARD_SHOW_BOOK_SERIES_TITLE, enabled)

    /** Keep covers at their original aspect ratio (no cropping; aligned with iOS thumbnailPreserveAspectRatio). */
    val thumbnailPreserveAspectRatio: Flow<Boolean> =
        booleanPref(Keys.THUMBNAIL_PRESERVE_ASPECT_RATIO, true)

    suspend fun setThumbnailPreserveAspectRatio(enabled: Boolean) =
        writeBoolean(Keys.THUMBNAIL_PRESERVE_ASPECT_RATIO, enabled)

    /** Cover shadow (aligned with iOS thumbnailShowShadow). */
    val thumbnailShowShadow: Flow<Boolean> = booleanPref(Keys.THUMBNAIL_SHOW_SHADOW, true)

    suspend fun setThumbnailShowShadow(enabled: Boolean) =
        writeBoolean(Keys.THUMBNAIL_SHOW_SHADOW, enabled)

    /** Unread badge (aligned with iOS thumbnailShowUnreadIndicator). */
    val thumbnailShowUnreadIndicator: Flow<Boolean> =
        booleanPref(Keys.THUMBNAIL_SHOW_UNREAD_INDICATOR, true)

    suspend fun setThumbnailShowUnreadIndicator(enabled: Boolean) =
        writeBoolean(Keys.THUMBNAIL_SHOW_UNREAD_INDICATOR, enabled)

    /** Progress bar (aligned with iOS thumbnailShowProgressBar). */
    val thumbnailShowProgressBar: Flow<Boolean> = booleanPref(Keys.THUMBNAIL_SHOW_PROGRESS_BAR, true)

    suspend fun setThumbnailShowProgressBar(enabled: Boolean) =
        writeBoolean(Keys.THUMBNAIL_SHOW_PROGRESS_BAR, enabled)

    /** Blur unread covers (aligned with iOS thumbnailBlurUnreadCovers). */
    val thumbnailBlurUnreadCovers: Flow<Boolean> = booleanPref(Keys.THUMBNAIL_BLUR_UNREAD_COVERS, false)

    suspend fun setThumbnailBlurUnreadCovers(enabled: Boolean) =
        writeBoolean(Keys.THUMBNAIL_BLUR_UNREAD_COVERS, enabled)

    /** Wi-Fi only preload (aligned with iOS wifiOnlyPreload). */
    val wifiOnlyPreload: Flow<Boolean> = booleanPref(Keys.WIFI_ONLY_PRELOAD, true)

    suspend fun setWifiOnlyPreload(enabled: Boolean) = writeBoolean(Keys.WIFI_ONLY_PRELOAD, enabled)

    /** SSE event-driven dashboard auto refresh (aligned with iOS enableSSEAutoRefresh, default on; disabled together with the overall SSE switch). */
    val sseAutoRefresh: Flow<Boolean> = booleanPref(Keys.SSE_AUTO_REFRESH, true)

    suspend fun setSseAutoRefresh(enabled: Boolean) = writeBoolean(Keys.SSE_AUTO_REFRESH, enabled)

    /**
     * Page-turn progress recording threshold (aligned with iOS AppConfig.progressRecordingThreshold, default 3):
     * an unread/finished book starts recording progress only after turning that many pages from the starting page
     * (prevents an accidental open from polluting the state); 0 = record immediately; a book in progress records on
     * every page turn.
     */
    val progressRecordingThreshold: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.PROGRESS_RECORDING_THRESHOLD] ?: DEFAULT_PROGRESS_RECORDING_THRESHOLD
    }

    suspend fun setProgressRecordingThreshold(value: Int) {
        store.edit { prefs ->
            prefs[Keys.PROGRESS_RECORDING_THRESHOLD] = value.coerceAtLeast(0)
        }
    }

    /** SSE connection/task completion notifications (aligned with iOS enableSSENotify, default off; persisted only until the notification infrastructure is wired up). */
    val sseNotify: Flow<Boolean> = booleanPref(Keys.SSE_NOTIFY, false)

    suspend fun setSseNotify(enabled: Boolean) = writeBoolean(Keys.SSE_NOTIFY, enabled)

    /** Network request timeout (seconds; design default 30, range 5–120; aligned with iOS requestTimeout semantics). */
    val networkTimeoutSeconds: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.NETWORK_TIMEOUT_SECONDS] ?: DEFAULT_NETWORK_TIMEOUT_SECONDS
    }

    suspend fun setNetworkTimeoutSeconds(seconds: Int) {
        store.edit { prefs ->
            prefs[Keys.NETWORK_TIMEOUT_SECONDS] = seconds.coerceIn(5, 120)
        }
    }

    /** Per-book page-level download concurrency (design default 2, range 1–8; consumed by DownloadWorker). */
    val downloadConcurrency: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.DOWNLOAD_CONCURRENCY] ?: DEFAULT_DOWNLOAD_CONCURRENCY
    }

    suspend fun setDownloadConcurrency(value: Int) {
        store.edit { prefs ->
            prefs[Keys.DOWNLOAD_CONCURRENCY] = value.coerceIn(1, 8)
        }
    }

    /** Image disk cache limit (MB, design default 2048; consumed by the SessionManager ImageLoader). */
    val imageCacheLimitMb: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.IMAGE_CACHE_LIMIT_MB] ?: DEFAULT_IMAGE_CACHE_LIMIT_MB
    }

    suspend fun setImageCacheLimitMb(value: Int) {
        store.edit { prefs ->
            prefs[Keys.IMAGE_CACHE_LIMIT_MB] = value.coerceIn(128, 20480)
        }
    }

    /** App icon variant: primary / classic / legacy (aligned with iOS AppIconOption). */
    val appIcon: Flow<String> = store.data.map { prefs -> prefs[Keys.APP_ICON] ?: APP_ICON_PRIMARY }

    suspend fun setAppIcon(value: String) {
        store.edit { prefs -> prefs[Keys.APP_ICON] = value }
    }

    private fun booleanPref(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        store.data.map { prefs -> prefs[key] ?: default }

    private suspend fun writeBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { prefs -> prefs[key] = value }
    }

    // remote-apply writers: emit no change, avoiding an up/down loopback
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
        const val DEFAULT_PROGRESS_RECORDING_THRESHOLD = 3
        fun scopeForLibrary(libraryId: String): String = "library:$libraryId"
    }

    private object Keys {
        val SSE_ENABLED = booleanPreferencesKey("sse_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val TRUE_BLACK = booleanPreferencesKey("true_black")
        val DIVINA_DIRECTION = stringPreferencesKey("divina_default_direction")
        val PINNED_LIBRARY_IDS = stringPreferencesKey("dashboard_pinned_library_ids")
        val PINNED_COLLECTION_IDS = stringPreferencesKey("pinned_collection_ids")
        val PINNED_READLIST_IDS = stringPreferencesKey("pinned_readlist_ids")
        val LIBRARY_BROWSE_SCOPE = stringPreferencesKey("library_browse_scope")
        val SEARCH_BROWSE_SCOPE = stringPreferencesKey("search_browse_scope")
        val DASHBOARD_SECTION_ORDER = stringPreferencesKey("dashboard_section_order")
        val DASHBOARD_HIDDEN_SECTIONS = stringPreferencesKey("dashboard_hidden_sections")
        val DASHBOARD_CARD_KIND_MENU = booleanPreferencesKey("dashboard_card_kind_menu")
        val DASHBOARD_CONTINUATION = booleanPreferencesKey("dashboard_continuation_enabled")
        val DASHBOARD_KIND_OVERRIDES = stringPreferencesKey("dashboard_kind_overrides")
        val BROWSE_LAYOUT_OVERRIDES = stringPreferencesKey("browse_layout_overrides")
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
        val PROGRESS_RECORDING_THRESHOLD = intPreferencesKey("progress_recording_threshold")
        val APP_ICON = stringPreferencesKey("app_icon")
    }
}

private fun String?.toCsvList(): List<String> =
    this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
