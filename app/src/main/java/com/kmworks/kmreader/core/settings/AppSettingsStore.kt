package com.kmworks.kmreader.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.core.network.instanceStorageKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 *
 * Everything that describes libraries or filters of one server (library scope, pinned libraries) is stored
 * under a preference name carrying that server's instance key, so switching servers neither shows the other
 * server's selection nor resolves its library ids against the wrong server. The other settings are
 * device-level (theme, cache caps, timeout, dashboard layout) and stay shared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AppSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
    private val serverConfig: ServerConfigStore,
    scope: CoroutineScope,
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

    /**
     * Render PDFs with the native `PdfRenderer` engine instead of the default androidx.pdf engine.
     * Device-local (not part of client-settings sync): rendering quality depends on the device.
     */
    val useNativePdfReader: Flow<Boolean> = booleanPref(Keys.USE_NATIVE_PDF_READER, true)

    suspend fun setUseNativePdfReader(enabled: Boolean) =
        writeBoolean(Keys.USE_NATIVE_PDF_READER, enabled)

    // --- Per-server-instance preferences -------------------------------------------------------
    // Library scope and pinned libraries name Komga library ids, which only mean something on the server
    // they came from. Their preference names therefore carry the instance key, and a scope read on a
    // server that never recorded one falls back to [SCOPE_ALL]: entering a server lists every library,
    // and only an explicit choice by the user makes "pinned" the active scope.

    /** Instance key of the active session (empty while no server is configured). */
    private val activeInstanceKey: Flow<String> = serverConfig.config
        .map { it.instanceStorageKey() }
        .distinctUntilChanged()

    private suspend fun currentInstanceKey(): String = serverConfig.config.first().instanceStorageKey()

    /**
     * Preference name of [prefix] for one instance. `@` never occurs in an instance key (it is sanitized
     * to `_`), so a scoped name cannot collide with a plain one or with another instance's name.
     */
    private fun scopedName(prefix: String, instance: String): String =
        "$prefix@${instance.ifBlank { NO_INSTANCE }}"

    /** [prefix] of the active instance, read as [default] when this server never recorded a value. */
    private fun scopedString(prefix: String, default: String): Flow<String> =
        activeInstanceKey.flatMapLatest { instance ->
            store.data.map { prefs -> prefs[stringPreferencesKey(scopedName(prefix, instance))] ?: default }
        }

    private suspend fun writeScopedString(prefix: String, value: String) {
        val key = stringPreferencesKey(scopedName(prefix, currentInstanceKey()))
        store.edit { prefs -> prefs[key] = value }
    }

    /**
     * Pinned library id set of the active server (aligned with iOS dashboard.libraryIds; local only, not
     * synced through client-settings). Empty until the user pins libraries on this server.
     */
    val pinnedLibraryIds: Flow<Set<String>> = scopedString(Keys.PINNED_LIBRARY_IDS.name, "").map { raw ->
        raw.split(',').filter { it.isNotBlank() }.toSet()
    }

    suspend fun setPinnedLibraryIds(ids: Set<String>) {
        writeScopedString(Keys.PINNED_LIBRARY_IDS.name, ids.joinToString(","))
    }

    /**
     * Pinned collection id set of the active server (aligned with iOS Collection isPinned: local storage only,
     * pinned items sort first in the list). Collection ids belong to one server, so the set is kept per server
     * and a server never shows the pins of the server the user just left.
     */
    val pinnedCollectionIds: Flow<Set<String>> =
        scopedString(Keys.PINNED_COLLECTION_IDS.name, "").map { raw ->
            raw.split(',').filter { it.isNotBlank() }.toSet()
        }

    suspend fun setPinnedCollectionIds(ids: Set<String>) {
        writeScopedString(Keys.PINNED_COLLECTION_IDS.name, ids.joinToString(","))
    }

    /**
     * Pinned read-list id set of the active server (aligned with iOS ReadList isPinned: local storage only,
     * pinned items sort first in the list); per server for the same reason as [pinnedCollectionIds].
     */
    val pinnedReadListIds: Flow<Set<String>> =
        scopedString(Keys.PINNED_READLIST_IDS.name, "").map { raw ->
            raw.split(',').filter { it.isNotBlank() }.toSet()
        }

    suspend fun setPinnedReadListIds(ids: Set<String>) {
        writeScopedString(Keys.PINNED_READLIST_IDS.name, ids.joinToString(","))
    }

    /**
     * Library browse scope of the library tab (aligned with iOS LibraryBrowseScope rawValue: pinned/all/library:{id}).
     *
     * Per server, defaulting to [SCOPE_ALL]: a fresh server shows every library instead of an empty "pinned"
     * selection, and switching servers never carries the previous server's choice (or its library ids) over.
     */
    val libraryBrowseScope: Flow<String> = scopedString(Keys.LIBRARY_BROWSE_SCOPE.name, SCOPE_ALL)

    suspend fun setLibraryBrowseScope(scope: String) {
        writeScopedString(Keys.LIBRARY_BROWSE_SCOPE.name, scope)
    }

    /** Library browse scope of the search tab (aligned with iOS `@AppStorage("searchBrowseScope")`, persisted per server, default all). */
    val searchBrowseScope: Flow<String> = scopedString(Keys.SEARCH_BROWSE_SCOPE.name, SCOPE_ALL)

    suspend fun setSearchBrowseScope(scope: String) {
        writeScopedString(Keys.SEARCH_BROWSE_SCOPE.name, scope)
    }

    /**
     * Library scope of the dashboard content (pinned/all/library:{id}).
     *
     * Persisted per server (the iOS DashboardLibraryScopeStore is session-only and resets to pinned on every
     * launch; here the last choice survives so the dashboard does not fall back every time), and defaulting to
     * [SCOPE_ALL] for a server that has not chosen one yet.
     */
    val dashboardBrowseScope: Flow<String> = scopedString(Keys.DASHBOARD_BROWSE_SCOPE.name, SCOPE_ALL)

    suspend fun setDashboardBrowseScope(scope: String) {
        writeScopedString(Keys.DASHBOARD_BROWSE_SCOPE.name, scope)
    }

    /** Library scope of the Lists tab (same value shape as the library tab's browse scope; persisted per server, default all). */
    val listsBrowseScope: Flow<String> = scopedString(Keys.LISTS_BROWSE_SCOPE.name, SCOPE_ALL)

    suspend fun setListsBrowseScope(scope: String) {
        writeScopedString(Keys.LISTS_BROWSE_SCOPE.name, scope)
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

    /**
     * Opt-in read-list continuation (mirrors the original readListContinuationEnabled, default off):
     * while off nothing is recorded, synced, resolved, or shown; turning it on also adds the
     * dashboard's "Read Lists in Progress" section, because that section has nothing to show otherwise.
     */
    val readListContinuationEnabled: Flow<Boolean> = booleanPref(Keys.READ_LIST_CONTINUATION, false)

    suspend fun setReadListContinuationEnabled(enabled: Boolean) =
        writeBoolean(Keys.READ_LIST_CONTINUATION, enabled)

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

    /**
     * Warm copy of [browseLayoutOverrides], collected eagerly from construction (before any screen
     * asks for a layout) so composition can seed its first frame with the persisted value.
     * Why: `browseLayout(page)` is a cold DataStore flow, so a screen that only collects it renders
     * the page default for one or more frames and then jumps to the stored layout.
     */
    private val browseLayoutSnapshot = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        scope.launch {
            browseLayoutOverrides.collect { browseLayoutSnapshot.value = it }
        }
    }

    /** Non-suspending read of the warm snapshot; null when the page has no recorded layout yet. */
    fun browseLayoutName(page: String): String? = browseLayoutSnapshot.value[page]

    suspend fun setBrowseLayout(page: String, layout: String) {
        store.edit { prefs ->
            val current = prefs[Keys.BROWSE_LAYOUT_OVERRIDES].toCsvList()
                .filter { it.substringBefore('=') != page }
            prefs[Keys.BROWSE_LAYOUT_OVERRIDES] = (current + "$page=$layout").joinToString(",")
        }
        // Keep the snapshot in step with the write itself: a page opened right after the change must
        // not read the previous layout while the DataStore emission is still in flight.
        browseLayoutSnapshot.update { it + (page to layout) }
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

    /**
     * Page-image disk cache limit in MB (mirrors iOS `maxPageCacheSize`, whose slider is 1–20 GB;
     * default 8 GB). Installs upgrading from the single shared image cache keep their old cap as the
     * starting value because there is no honest way to split it.
     */
    val pageCacheLimitMb: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.PAGE_CACHE_LIMIT_MB]
            ?: prefs[Keys.IMAGE_CACHE_LIMIT_MB]
            ?: DEFAULT_PAGE_CACHE_LIMIT_MB
    }

    suspend fun setPageCacheLimitMb(value: Int) {
        store.edit { prefs ->
            prefs[Keys.PAGE_CACHE_LIMIT_MB] = value.coerceIn(PAGE_CACHE_LIMIT_MIN_MB, PAGE_CACHE_LIMIT_MAX_MB)
        }
    }

    /** Cover disk cache limit in MB (mirrors iOS `maxCoverCacheSize`, 128–8192 MB, default 512 MB). */
    val coverCacheLimitMb: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.COVER_CACHE_LIMIT_MB] ?: DEFAULT_COVER_CACHE_LIMIT_MB
    }

    suspend fun setCoverCacheLimitMb(value: Int) {
        store.edit { prefs ->
            prefs[Keys.COVER_CACHE_LIMIT_MB] = value.coerceIn(COVER_CACHE_LIMIT_MIN_MB, COVER_CACHE_LIMIT_MAX_MB)
        }
    }

    /** Days after which a cached cover is refreshed (mirrors iOS `coverCacheExpirationDays`, 1–90, default 7). */
    val coverCacheExpirationDays: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.COVER_CACHE_EXPIRATION_DAYS] ?: DEFAULT_COVER_CACHE_EXPIRATION_DAYS
    }

    suspend fun setCoverCacheExpirationDays(days: Int) {
        store.edit { prefs ->
            prefs[Keys.COVER_CACHE_EXPIRATION_DAYS] =
                days.coerceIn(COVER_CACHE_EXPIRATION_MIN_DAYS, COVER_CACHE_EXPIRATION_MAX_DAYS)
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

        // Page / cover cache caps mirror the iOS Cache settings screen (GB slider vs MB slider).
        const val DEFAULT_PAGE_CACHE_LIMIT_MB = 8 * 1024
        const val PAGE_CACHE_LIMIT_MIN_MB = 1024
        const val PAGE_CACHE_LIMIT_MAX_MB = 20 * 1024
        const val DEFAULT_COVER_CACHE_LIMIT_MB = 512
        const val COVER_CACHE_LIMIT_MIN_MB = 128
        const val COVER_CACHE_LIMIT_MAX_MB = 8192
        const val DEFAULT_COVER_CACHE_EXPIRATION_DAYS = 7
        const val COVER_CACHE_EXPIRATION_MIN_DAYS = 1
        const val COVER_CACHE_EXPIRATION_MAX_DAYS = 90

        const val DEFAULT_PROGRESS_RECORDING_THRESHOLD = 3
        fun scopeForLibrary(libraryId: String): String = "library:$libraryId"

        /** Instance placeholder of the per-server preference names while no server is configured. */
        private const val NO_INSTANCE = "none"
    }

    private object Keys {
        val SSE_ENABLED = booleanPreferencesKey("sse_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val TRUE_BLACK = booleanPreferencesKey("true_black")
        val DIVINA_DIRECTION = stringPreferencesKey("divina_default_direction")
        val USE_NATIVE_PDF_READER = booleanPreferencesKey("use_native_pdf_reader")
        val PINNED_LIBRARY_IDS = stringPreferencesKey("dashboard_pinned_library_ids")
        val PINNED_COLLECTION_IDS = stringPreferencesKey("pinned_collection_ids")
        val PINNED_READLIST_IDS = stringPreferencesKey("pinned_readlist_ids")
        val LIBRARY_BROWSE_SCOPE = stringPreferencesKey("library_browse_scope")
        val SEARCH_BROWSE_SCOPE = stringPreferencesKey("search_browse_scope")
        val DASHBOARD_BROWSE_SCOPE = stringPreferencesKey("dashboard_browse_scope")
        val LISTS_BROWSE_SCOPE = stringPreferencesKey("lists_browse_scope")
        val DASHBOARD_SECTION_ORDER = stringPreferencesKey("dashboard_section_order")
        val DASHBOARD_HIDDEN_SECTIONS = stringPreferencesKey("dashboard_hidden_sections")
        val DASHBOARD_CARD_KIND_MENU = booleanPreferencesKey("dashboard_card_kind_menu")
        val DASHBOARD_CONTINUATION = booleanPreferencesKey("dashboard_continuation_enabled")
        val READ_LIST_CONTINUATION = booleanPreferencesKey("read_list_continuation_enabled")
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
        val PAGE_CACHE_LIMIT_MB = intPreferencesKey("page_cache_limit_mb")
        val COVER_CACHE_LIMIT_MB = intPreferencesKey("cover_cache_limit_mb")
        val COVER_CACHE_EXPIRATION_DAYS = intPreferencesKey("cover_cache_expiration_days")
        val PROGRESS_RECORDING_THRESHOLD = intPreferencesKey("progress_recording_threshold")
        val APP_ICON = stringPreferencesKey("app_icon")
    }
}

private fun String?.toCsvList(): List<String> =
    this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
