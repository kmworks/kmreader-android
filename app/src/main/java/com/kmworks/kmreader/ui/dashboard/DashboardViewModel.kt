package com.kmworks.kmreader.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.CollectionDto
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.api.model.ReadListDto
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.ui.lists.ListSelection
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ImageCacheStore
import com.kmworks.kmreader.core.data.CatalogCache
import com.kmworks.kmreader.core.data.ContentChange
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.data.ContentChangeKind
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.Paged
import com.kmworks.kmreader.core.data.PagedFeed
import com.kmworks.kmreader.core.data.SearchRepository
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.data.toListEntry
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.ThumbnailCache
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.network.ServerReachability
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerAdminRepository
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.sse.SseEvent
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookDetailMeta
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.ReadListContinuationUi
import com.kmworks.kmreader.data.ReadListsInProgressSectionId
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.normalizeLibraryScope
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
import com.kmworks.kmreader.ui.detail.BookMetadataDraft
import com.kmworks.kmreader.ui.detail.SeriesEditValues
import com.kmworks.kmreader.ui.detail.toBookDetailMeta
import com.kmworks.kmreader.ui.detail.toEditValues
import com.kmworks.kmreader.ui.detail.toUpdateDto
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** Paging bookkeeping of one dashboard section feed (strip and "More" page share it). */
data class DashboardSectionPaging(
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
)

/** Dashboard sections state; sections == null keeps the sample demo UI. */
data class DashboardUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val sections: List<DashboardSection<out Any>>? = null,
    /** Per-section feed paging: the strip requests the next page at its end, the "More" page does the same. */
    val sectionPaging: Map<String, DashboardSectionPaging> = emptyMap(),
    /** Continuation cards for read lists in progress (computed from real data; empty when none are in progress). */
    val continuations: List<ReadListContinuationUi> = emptyList(),
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
    /** Whether the card-kind menu is shown next to section titles (persisted in the settings screen). */
    val kindMenuEnabled: Boolean = true,
    /** Explicit card-kind choices per section id (persisted in the settings screen). */
    val kindOverrides: Map<String, DashboardCardKind> = emptyMap(),
    /** Library-scope menu data: the real library list from the server. */
    val libraries: List<LibraryDto> = emptyList(),
    /** Library scope (pinned/all/library:{id}, aligned with iOS LibraryBrowseScope); persisted per server, defaults to all. */
    val libraryScope: String = AppSettingsStore.SCOPE_ALL,
    /** Pinned library ids (the pinned scope resolves to these as the libraryIds filter). */
    val pinnedIds: Set<String> = emptySet(),
    /** Whether the current account is an admin (gates the analyze/refresh-metadata/add-to-list menu items). */
    val isAdmin: Boolean = false,
    /** kmf capability: admin + server feature; when false the three komf menu items are not rendered. */
    val komfAvailable: Boolean = false,
)

/** Dashboard layout configuration (persisted in AppSettingsStore, aligned with iOS DashboardConfiguration). */
private data class DashboardLayout(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val continuation: Boolean = false,
    val kindMenu: Boolean = true,
    val overrides: Map<String, DashboardCardKind> = emptyMap(),
)

/** Real dashboard data for the eight sections (books + series feeds, aligned with iOS DashboardSection). */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val detailRepository: DetailRepository,
    private val libraryRepository: LibraryRepository,
    private val searchRepository: SearchRepository,
    private val sseService: SseService,
    private val appSettings: AppSettingsStore,
    private val serverProfileStore: ServerProfileStore,
    private val downloadRepository: DownloadRepository,
    private val thumbnailCache: ThumbnailCache,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context,
    private val imageCacheStore: ImageCacheStore,
    private val catalogCache: CatalogCache,
    private val contentChangeBus: ContentChangeBus,
    private val serverAdminRepository: ServerAdminRepository,
    private val serverReachability: ServerReachability,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state = _state.asStateFlow()

    /** Sections as produced by the server (not yet trimmed by settings); sections are re-derived on layout changes. */
    private var rawSections: List<DashboardSection<out Any>> = emptyList()
    private var layout = DashboardLayout()

    /** Serializes reloads: an SSE burst and a local write can each request one at nearly the same instant. */
    private val loadMutex = Mutex()

    /** Bumped on every wholesale reload; a section load-more landing after one discards its result. */
    private var feedsGeneration = 0
    /** Paged feed per section id, shared by the strip and the "More" page (see [PagedFeed]). */
    private val sectionFeeds = mutableMapOf<String, PagedFeed<Any>>()
    /** Library scope the feeds were built for: a different scope is a different query and gets fresh feeds. */
    private var feedsScopeKey: String? = null
    /** Bumped on every list-row / continuation refresh; a slower one landing late is discarded. */
    private var listsGeneration = 0
    /** One lock per section feed: a load-more and a targeted re-read must not advance the same feed at once. */
    private val sectionLocks = mutableMapOf<String, Mutex>()

    /**
     * One generation per section feed. A targeted re-read only invalidates the sections it re-read: using the
     * global [feedsGeneration] would discard an append already in flight for an untouched section after the feed
     * advanced its page, so the next append would skip that page's rows.
     */
    private val sectionGenerations = mutableMapOf<String, Int>()

    private fun sectionGeneration(id: String): Int = sectionGenerations[id] ?: 0

    private fun bumpSectionGeneration(id: String) {
        sectionGenerations[id] = sectionGeneration(id) + 1
    }

    /** The persisted dashboard scope is read once (before the first feed query); the state is authoritative after that. */
    private var scopeLoaded = false

    /**
     * Re-fetch the section contents (content sync after a successful action, an SSE event or a
     * pull-to-refresh). The returned job completes when the reload landed, so a caller can keep its
     * refresh indicator up meanwhile while the work itself stays in this view model's scope.
     */
    fun refresh(): Job {
        return viewModelScope.launch { load() }
    }
    /** What one SSE event kind can move: sections to re-read and/or the list rows plus continuation strip. */
    private data class AffectedSections(val sections: Set<String> = emptySet(), val lists: Boolean = false)

    /** Null means the event needs the full reload. */
    private fun sectionsForEvent(kind: SseEventKind): AffectedSections? = when (kind) {
        // Mirrors DashboardSectionRefreshNotifier on iOS: a series change moves the two series strips; a book
        // change also moves every series aggregate, because the cards are computed from the books; progress
        // moves the lists built from read state. A collection / read list change moves only the list rows and
        // the read-lists-in-progress strip, neither of which is a paged feed.
        SseEventKind.Series -> AffectedSections(SERIES_SECTIONS)
        SseEventKind.Book -> AffectedSections(BOOK_AND_SERIES_SECTIONS)
        // Progress moves the continuation strip too, and the strip is a client-side join rather than a paged feed,
        // so it is refreshed on its own. iOS posts every read-progress event through postReadStatusChanged →
        // readStatusSections, which also carries the series strips: a completion flips their all-read badge.
        // The SSE payload carries no progress value, so the recently added/released cards (which render progress
        // and completion) cannot be patched here and are re-read with the rest of the affected set.
        SseEventKind.ReadProgress -> AffectedSections(READ_STATUS_SECTIONS + READ_STATE_CARD_SECTIONS, lists = true)
        SseEventKind.Collection, SseEventKind.ReadList -> AffectedSections(lists = true)
        else -> null
    }

    /** Library a change names, when the source pinned it (SSE payloads do; local writes do not). */
    private fun ContentChange.libraryIdOrNull(): String? = when (this) {
        is ContentChange.Books -> libraryId
        is ContentChange.Series -> libraryId
        else -> null
    }

    /**
     * What a change that reached the local bus can move. SSE changes ride this channel too (SseContentSync
     * forwards them), so this keeps a remote edit from reloading the whole dashboard: without it the 500 ms
     * local path would undo the targeted SSE refresh with a full reload.
     */
    private fun sectionsForChange(change: ContentChange): AffectedSections? = when (change) {
        is ContentChange.Books -> when (change.kind) {
            // Rows appearing or disappearing can add or drop a whole section: only a full reload knows that.
            ContentChangeKind.Created, ContentChangeKind.Deleted -> null
            // A read-state flip moves the read-state lists and the series strips (their all-read badge); a progress
            // change carries the same set, mirroring iOS. The continuation strip is built from progress and is
            // refreshed as a list either way.
            ContentChangeKind.ReadStateChanged -> AffectedSections(READ_STATUS_SECTIONS, lists = true)
            // Same set as a read-state flip: iOS posts progress through readStatusSections too (a completion
            // changes the series badge without being distinguishable from raw progress here).
            ContentChangeKind.ProgressChanged -> AffectedSections(READ_STATUS_SECTIONS, lists = true)
            else -> AffectedSections(BOOK_AND_SERIES_SECTIONS)
        }
        is ContentChange.Series -> when (change.kind) {
            ContentChangeKind.Created, ContentChangeKind.Deleted -> null
            ContentChangeKind.ReadStateChanged -> AffectedSections(READ_STATUS_SECTIONS, lists = true)
            // Same set as a read-state flip: iOS posts progress through readStatusSections too (a completion
            // changes the series badge without being distinguishable from raw progress here).
            ContentChangeKind.ProgressChanged -> AffectedSections(READ_STATUS_SECTIONS, lists = true)
            else -> AffectedSections(SERIES_SECTIONS)
        }
        // A collection / read list edit moves the strip only, and the dashboard does not show smart lists at all.
        is ContentChange.Collections, is ContentChange.ReadLists -> AffectedSections(lists = true)
        is ContentChange.SmartLists -> AffectedSections()
        // Library configuration and unclassified bursts keep the full reload.
        else -> null
    }

    /**
     * Re-reads only the given sections' windows, the way the iOS reload command carries its section set
     * (DashboardSectionRefreshNotifier.sectionsForSeriesProjectionChange). A section this screen does not hold is
     * skipped rather than reloading everything: it is empty (a reload drops an empty section) or hidden, and a
     * section that has to come back is a membership change, which the caller turns into a full reload.
     */
    private fun revalidateSections(ids: Set<String>) {
        if (ids.isEmpty() || !_state.value.hasSession || _state.value.loading) return
        val wanted = ids - layout.hidden
        if (wanted.isEmpty()) return
        // A section this screen does not hold is not necessarily empty: starting the first book, or finishing it,
        // can populate keepReading / recentlyReadBooks without adding or deleting a row. A window cannot create a
        // section, so missing ones are read as their own first page below and inserted when they come back with
        // rows; falling back to the full reload here would re-read every section on each progress event, since
        // the empty ones stay missing.
        val present = rawSections.mapTo(HashSet()) { it.id }
        // Only the sections re-read here are invalidated (per-section generations): a targeted re-read must not
        // discard an append already in flight for another section.
        wanted.forEach { bumpSectionGeneration(it) }
        val generations = wanted.associateWith { sectionGeneration(it) }
        val target = wanted
        val absent = target - present
        val pagingBefore = _state.value.sectionPaging
        viewModelScope.launch {
            try {
                val libraryIds = resolveLibraryScopeIds(_state.value.libraryScope, appSettings.pinnedLibraryIds.first())
                val reloaded = coroutineScope {
                    target.associateWith { id ->
                        async {
                            val feed = feedFor(id, libraryIds)
                            // Serialized with a load-more on this feed: without the lock the two would advance
                            // page/hasMore against each other and one of them would overwrite the other's rows.
                            id to lockFor(id).withLock {
                                // The feed may have paged past the entry this window was computed from (a
                                // load-more ran just before): keep the deeper of the two, or its rows get cut off.
                                val keepPages = maxOf(pagingBefore[id]?.page ?: 0, feed.page)
                                // A missing section has no rows to cover: its window is just the first page,
                                // which either brings it back (rows) or confirms it is still empty (no rows).
                                feed.reload(sectionItems(id), keepPages)
                            }
                        }
                    }.mapValues { it.value.await().second }
                }
                // A newer re-read (or a full reload) owns the sections it replaced. Drop only those: the rest of
                // this batch still carries corrections nothing else will publish, and their feeds have already
                // advanced, so abandoning it would serve those rows again later for nothing.
                val fresh = reloaded.filterKeys { generations.getValue(it) == sectionGeneration(it) }
                rawSections = rawSections.mapNotNull { section ->
                    val items = fresh[section.id] ?: return@mapNotNull section
                    if (items.isEmpty()) {
                        null
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        (section as DashboardSection<Any>).copy(items = items)
                    }
                }.let { current ->
                    // Sections that were missing and came back: insert them where derivedSections() puts them
                    // (the layout order / the iOS allCases fallback), so a section that just became non-empty
                    // shows up without waiting for a full reload.
                    val have = current.mapTo(HashSet()) { it.id }
                    current + absent.filter { it !in have }.mapNotNull { id ->
                        buildSection(id, reloaded[id].orEmpty())
                    }
                }
                // Start from the live map, not the snapshot taken before the requests: a load-more that
                // landed on another section meanwhile advanced its entry, and the snapshot would roll it back.
                val paging = _state.value.sectionPaging.toMutableMap()
                fresh.forEach { (id, items) ->
                    val feed = sectionFeeds[id] ?: return@forEach
                    // A section that came back empty left the screen, so its paging goes with it: otherwise a
                    // stale "more pages" entry would page a section that is no longer in [rawSections].
                    if (items.isEmpty()) {
                        paging.remove(id)
                    } else {
                        // loadingMore resets on purpose: a load-more that was in flight is discarded by the
                        // generation bump above, so keeping its flag would leave a spinner nothing ever clears.
                        paging[id] = DashboardSectionPaging(page = feed.page, hasMore = feed.hasMore)
                    }
                }
                // A section removed meanwhile must not keep a paging entry nothing can page.
                paging.keys.retainAll(rawSections.mapTo(HashSet()) { it.id })
                runCatching { persistDashboard(rawSections) }
                // Every section gone means no sections, not the previous cards: keeping `it.sections` would
                // leave removed cards on screen until the next full load.
                _state.update { it.copy(sectionPaging = paging, sections = derivedSections().orEmpty()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A correction that failed keeps the rows on screen exactly as they are, silently: the iOS
                // revalidateWindow returns early on a failed fetch, and the next event or reload tries again.
            }
        }
    }



    init {
        viewModelScope.launch {
            // Back online after an outage (probe, SSE reconnect or any request): reload the cached screen,
            // mirroring the iOS recovery refresh. drop(1) skips the initial `false` (StateFlow conflates repeats).
            serverReachability.offline
                .drop(1)
                .filter { !it }
                .collect { load() }
        }
        viewModelScope.launch {
            // Follow the active session: the admin role gates the card menus, and a server switch must
            // re-evaluate them instead of keeping the previous server's answer.
            combine(
                serverAdminRepository.adminStatus,
                serverProfileStore.profile,
            ) { isAdmin, profile ->
                isAdmin to (isAdmin && profile?.features?.contains(ServerFeature.Komf) == true)
            }.collect { (isAdmin, komfAvailable) ->
                _state.update { it.copy(isAdmin = isAdmin, komfAvailable = komfAvailable) }
            }
        }
        viewModelScope.launch {
            var instanceKey: String? = null
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    _state.update { it.copy(hasSession = false, loading = false, sections = null) }
                } else {
                    if (clients.cacheNamespace != instanceKey) {
                        // Scope and pins are stored per server; drop the one-time read so the first load below
                        // adopts this server's own choice instead of the server we just left.
                        instanceKey = clients.cacheNamespace
                        scopeLoaded = false
                    }
                    load()
                }
            }
        }
        // Reorder instantly when the settings screen changes order/visibility/card kind, without re-requesting
        viewModelScope.launch {
            combine(
                appSettings.dashboardSectionOrder,
                appSettings.dashboardHiddenSections,
                // The section is opt-in through the read-list continuation switch (as on iOS), not through a
                // separately defaulted "show the section" flag: it has nothing to show until that is on.
                appSettings.readListContinuationEnabled,
                appSettings.dashboardCardKindMenu,
                appSettings.dashboardKindOverrides,
            ) { order, hidden, continuation, kindMenu, overrides ->
                DashboardLayout(
                    order = order,
                    hidden = hidden,
                    continuation = continuation,
                    kindMenu = kindMenu,
                    overrides = overrides.mapNotNull { (id, value) ->
                        DashboardCardKind.entries.firstOrNull { it.name == value }?.let { id to it }
                    }.toMap(),
                )
            }.collect { next ->
                layout = next
                val sections = derivedSections()
                _state.update {
                    it.copy(
                        sections = sections ?: it.sections,
                        kindMenuEnabled = next.kindMenu,
                        kindOverrides = next.overrides,
                    )
                }
            }
        }
        // The pinned set is persisted in AppSettingsStore; re-query when it changes while the current scope is pinned
        viewModelScope.launch {
            appSettings.pinnedLibraryIds.collect { pinned ->
                val changed = pinned != _state.value.pinnedIds
                _state.update { it.copy(pinnedIds = pinned) }
                if (changed &&
                    _state.value.libraryScope == AppSettingsStore.SCOPE_PINNED &&
                    _state.value.hasSession
                ) {
                    refresh()
                }
            }
        }
        // The section detail page edits the same persisted dashboard scope; follow it while this screen is alive
        // (the "More" page writes AppSettingsStore.dashboardBrowseScope, this collector keeps the dashboard in step).
        viewModelScope.launch {
            appSettings.dashboardBrowseScope.collect { stored ->
                if (!scopeLoaded) return@collect
                if (stored == _state.value.libraryScope) return@collect
                _state.update { it.copy(libraryScope = stored) }
                if (_state.value.hasSession) refresh()
            }
        }
        // Refresh after aggregating SSE content-change events over 5 s, the iOS coordinator's window (does not
        // follow events when "auto refresh" is off in settings). Which sections an event can touch is decided
        // while the window collects, so only those re-read.
        viewModelScope.launch {
            val pendingSections = mutableSetOf<String>()
            var pendingAll = false
            var pendingMembership = false
            var pendingLists = false
            fun drainPending() {
                val all = pendingAll || pendingMembership
                val sections = pendingSections.toSet()
                val lists = pendingLists
                pendingSections.clear()
                pendingAll = false
                pendingMembership = false
                pendingLists = false
                if (all) {
                    refresh()
                } else {
                    revalidateSections(sections)
                    if (lists) refreshLists()
                }
            }
            coroutineScope {
                launch {
                    combine(
                        sseService.events.filter { it.kind in REFRESH_KINDS },
                        appSettings.sseAutoRefresh,
                    ) { event, autoRefresh -> if (autoRefresh) event else null }
                        .filterNotNull()
                        .onEach { event ->
                            // A thumbnail event only means the cover image became available: the card URL does
                            // not change and `SseContentSync` already warms its cache, so no section is re-read.
                            // Without this, every generated cover (a bulk import emits hundreds) re-read seven
                            // sections, and the ones named "…Added" even forced a whole-dashboard reload.
                            if (event.name.startsWith("Thumbnail")) return@onEach
                            val affected = sectionsForEvent(event.kind)
                            if (affected == null) {
                                pendingAll = true
                            } else {
                                pendingSections += affected.sections
                                pendingLists = pendingLists || affected.lists
                                // Rows appearing or disappearing can change which sections exist at all, so those
                                // keep the full reload; a change to existing rows only needs the sections on screen.
                                if (affected.sections.isNotEmpty() &&
                                    (event.name.contains("Added") || event.name.contains("Deleted") ||
                                        event.name.contains("Imported"))
                                ) {
                                    pendingMembership = true
                                }
                            }
                        }
                        .debounce(5_000)
                        .collect {
                            // A reader session defers the refresh (iOS parity): the set stays pending while it
                            // stays open, then only those sections are re-read instead of the whole dashboard.
                            if (contentChangeBus.readerActive.value) return@collect
                            drainPending()
                        }
                }
                launch {
                    contentChangeBus.readerActive
                        .drop(1)
                        .filter { active -> !active }
                        .collect { drainPending() }
                }
            }
        }
        // Refresh after a write issued anywhere in the app (mark read/unread, delete, metadata edit, list membership).
        // SSE also carries these, but it needs the long connection and the auto-refresh switch; the local signal stays
        // correct when either is unavailable. Shorter debounce than SSE because the user is looking at the action.
        //
        // A reader close replays the changes it held, several of them at once: each is mapped into the pending set
        // while the window collects, so a replay touching three kinds still re-reads all three instead of only the
        // last one. The debounce only decides when the set is drained.
        viewModelScope.launch {
            val pendingSections = mutableSetOf<String>()
            var pendingAll = false
            var pendingLists = false
            fun drainPending() {
                val all = pendingAll
                val sections = pendingSections.toSet()
                val lists = pendingLists
                pendingSections.clear()
                pendingAll = false
                pendingLists = false
                if (!_state.value.hasSession) return
                if (all) {
                    refresh()
                } else {
                    revalidateSections(sections)
                    if (lists) refreshLists()
                }
            }
            contentChangeBus.localChanges
                .onEach { change ->
                    if (!_state.value.hasSession) return@onEach
                    // Only writes issued here reach this collector: SSE events come through the direct collector
                    // above, which owns the auto-refresh switch. Library scope cannot be used to tell them apart
                    // (collection/read-list events carry no library id and multi-library batches clear it).
                    patchRecentCards(change)
                    val affected = sectionsForChange(change)
                    if (affected == null) {
                        pendingAll = true
                    } else {
                        pendingSections += affected.sections
                        pendingLists = pendingLists || affected.lists
                    }
                }
                .debounce(500)
                .collect { drainPending() }
        }
    }

    /**
     * The recently added / released cards also render progress and the completion badge, but a progress change
     * does not re-read those strips. The local write carries the values, so patch those rows instead of reading
     * them again: the badge is current and the event costs no request.
     */
    private fun patchRecentCards(change: ContentChange) {
        if (change !is ContentChange.Books) return
        if (change.kind != ContentChangeKind.ProgressChanged &&
            change.kind != ContentChangeKind.ReadStateChanged
        ) {
            return
        }
        val ids = change.dtoIds
        var changed = false
        rawSections = rawSections.map { section ->
            if (section.id !in READ_STATE_CARD_SECTIONS) return@map section
            val items = section.items.map { item ->
                val book = item as? Book ?: return@map item
                val dtoId = DetailIdRegistry.bookDtoId(book.id) ?: return@map item
                if (dtoId !in ids) return@map item
                val next = when {
                    change.read == true -> 1f
                    change.read == false -> null
                    change.progress != null -> change.progress
                    else -> return@map item
                }
                if (book.progress == next) return@map item
                changed = true
                book.copy(progress = next)
            }
            if (items == section.items) {
                section
            } else {
                @Suppress("UNCHECKED_CAST")
                (section as DashboardSection<Any>).copy(items = items)
            }
        }
        if (changed) _state.update { it.copy(sections = derivedSections().orEmpty()) }
    }

    /** Position of a section in the iOS default order (unknown ids sort last). */
    private fun canonicalSectionIndex(id: String): Int {
        val index = CanonicalSectionOrder.indexOf(id)
        return if (index < 0) CanonicalSectionOrder.size else index
    }

    /** Derive the section list from the persisted order/visibility/card kinds; returns null before content is loaded. */
    /** Rebuilds one section with the same title and default card kind the full load gives it. */
    private fun buildSection(id: String, items: List<Any>): DashboardSection<Any>? {
        if (items.isEmpty()) return null
        val title = when (id) {
            "keepReading" -> R.string.dashboard_section_keep_reading
            "onDeck" -> R.string.dashboard_section_on_deck
            "recentlyReleasedBooks" -> R.string.dashboard_section_recently_released_books
            "recentlyAddedBooks" -> R.string.dashboard_section_recently_added_books
            "recentlyAddedSeries" -> R.string.dashboard_section_recently_added_series
            "recentlyUpdatedSeries" -> R.string.dashboard_section_recently_updated_series
            "recentlyReadBooks" -> R.string.dashboard_section_recently_read_books
            else -> return null
        }
        val kind = if (id == "keepReading") DashboardCardKind.Horizontal else DashboardCardKind.Large
        return DashboardSection(id, context.getString(title), kind, items)
    }

    private fun derivedSections(): List<DashboardSection<out Any>>? {
        if (rawSections.isEmpty()) return null
        return rawSections
            .sortedBy { section ->
                // The persisted order wins where the user listed a section; everything else falls back to the
                // iOS DashboardSection.allCases order instead of Int.MAX_VALUE. Without that fallback the cached
                // first paint kept whatever order the cache store returned (SQLite's sectionId ordering puts
                // recentlyReadBooks above recentlyUpdatedSeries), so the sections visibly swapped once the
                // network answer arrived.
                val index = layout.order.indexOf(section.id)
                if (index >= 0) index else layout.order.size + canonicalSectionIndex(section.id)
            }
            .filter { section ->
                section.id !in layout.hidden &&
                    (section.id != ReadListsInProgressSectionId || layout.continuation)
            }
            .map { section ->
                val kind = layout.overrides[section.id] ?: section.kind
                if (kind == section.kind) section else section.copy(kind = kind)
            }
    }

    /** Switch a section's card kind (menu next to the section title on the dashboard). */
    fun setSectionKind(sectionId: String, kind: DashboardCardKind?) {
        viewModelScope.launch {
            appSettings.setDashboardKindOverride(sectionId, kind?.name)
        }
    }

    /**
     * Library scope switch (pinned/all/library:{id}). The choice is persisted in AppSettingsStore so the
     * dashboard remembers it across launches (the iOS DashboardLibraryScopeStore only keeps it in memory).
     */
    fun setScope(scope: String) {
        if (_state.value.libraryScope == scope) return
        _state.update { it.copy(libraryScope = scope) }
        scopeLoaded = true
        viewModelScope.launch { appSettings.setDashboardBrowseScope(scope) }
        if (_state.value.hasSession) refresh()
    }

    /** Overwrite the pinned set (saved from the pin-editing sheet, persisted in AppSettingsStore). */
    fun setPinnedLibraries(ids: Set<String>) {
        viewModelScope.launch { appSettings.setPinnedLibraryIds(ids) }
    }

    /**
     * Load the next page of one section's feed (strip scrolled to its end, or the "More" page scrolled
     * to its bottom — both share this state, so either one grows what the other shows).
     */
    fun loadMoreSection(sectionId: String) {
        val paging = _state.value.sectionPaging[sectionId] ?: return
        if (paging.loadingMore || !paging.hasMore || _state.value.loading) return
        val gen = sectionGeneration(sectionId)
        viewModelScope.launch {
            updateSectionPaging(sectionId) { it.copy(loadingMore = true) }
            try {
                val libraryIds = resolveLibraryScopeIds(
                    _state.value.libraryScope,
                    appSettings.pinnedLibraryIds.first(),
                )
                // Same lock the targeted re-read takes: the append and a reload must not interleave on one feed.
                val items = lockFor(sectionId).withLock {
                    feedFor(sectionId, libraryIds).append(sectionItems(sectionId))
                }
                if (gen != sectionGeneration(sectionId)) {
                    // This section was re-read (or reloaded) meanwhile and owns the rows. A targeted re-read of
                    // other sections never bumps this generation, so its append still lands normally.
                    updateSectionPaging(sectionId) { it.copy(loadingMore = false) }
                    return@launch
                }
                rawSections = rawSections.map { section ->
                    if (section.id != sectionId) {
                        section
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        (section as DashboardSection<Any>).copy(items = items)
                    }
                }
                updateSectionPaging(sectionId) {
                    it.copy(
                        page = sectionFeeds.getValue(sectionId).page,
                        hasMore = sectionFeeds.getValue(sectionId).hasMore,
                        loadingMore = false,
                    )
                }
                _state.update { it.copy(sections = derivedSections()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != sectionGeneration(sectionId)) {
                    updateSectionPaging(sectionId) { it.copy(loadingMore = false) }
                    return@launch
                }
                updateSectionPaging(sectionId) { it.copy(loadingMore = false) }
                _state.update {
                    it.copy(error = e.message ?: context.getString(R.string.common_load_more_failed))
                }
            }
        }
    }

    /** Rows a section currently shows (they come from the same feed the load-more grows). */
    private fun sectionItems(sectionId: String): List<Any> =
        rawSections.firstOrNull { it.id == sectionId }?.items.orEmpty()

    /**
     * The section's feed, created on first use. The library ids are read at creation: [loadLocked] clears the
     * map whenever the scope changes, because that scope is a different query.
     */
    private fun lockFor(sectionId: String): Mutex = sectionLocks.getOrPut(sectionId) { Mutex() }

    private fun feedFor(sectionId: String, libraryIds: List<String>?): PagedFeed<Any> =
        sectionFeeds.getOrPut(sectionId) {
            PagedFeed(
                pageSize = SECTION_PAGE_SIZE,
                identity = { item ->
                    when (item) {
                        is Book -> "book:${item.id}"
                        is Series -> "series:${item.id}"
                        else -> item
                    }
                },
                fetch = { page, size -> fetchSectionPage(sectionId, page, size, libraryIds) },
            )
        }

    /** One page of a section's feed; null for the sections that have no paged feed (continuations). */
    private suspend fun fetchSectionPage(
        sectionId: String,
        page: Int,
        size: Int,
        libraryIds: List<String>?,
    ): Paged<Any>? =
        when (sectionId) {
            "keepReading" -> searchRepository.keepReadingPage(
                page, size, libraryIds,
                readDateAfter = serverProfileStore.recentReadingCutoff(),
            ).eraseType()
            "onDeck" -> searchRepository.onDeckPage(page, size, libraryIds).eraseType()
            "recentlyReleasedBooks" -> searchRepository.recentlyReleasedBooksPage(page, size, libraryIds).eraseType()
            "recentlyAddedBooks" -> searchRepository.latestBooksPage(page, size, libraryIds).eraseType()
            "recentlyAddedSeries" -> searchRepository.latestSeriesPage(page, size, libraryIds).eraseType()
            "recentlyUpdatedSeries" -> searchRepository.recentlyUpdatedSeriesPage(page, size, libraryIds).eraseType()
            "recentlyReadBooks" -> searchRepository.recentlyReadBooksPage(page, size, libraryIds).eraseType()
            else -> null
        }

    private fun updateSectionPaging(sectionId: String, transform: (DashboardSectionPaging) -> DashboardSectionPaging) {
        _state.update { state ->
            val current = state.sectionPaging[sectionId] ?: return@update state
            state.copy(sectionPaging = state.sectionPaging + (sectionId to transform(current)))
        }
    }

    private fun <T> Paged<T>.eraseType(): Paged<Any> = Paged(items.map { it as Any }, hasMore)

    /** Shared mapper on purpose: it registers the stable-id → dto-id and cover mappings the rows need to open. */
    private fun collectionEntry(dto: CollectionDto): ListEntry = dto.toListEntry()

    private fun readListEntry(dto: ReadListDto): ListEntry = dto.toListEntry()

    /**
     * Continuation cards: fetch each list's books and pick the one it continues with. Lists nobody has started
     * have no continuation, so a freshly created list never shows up as "in progress".
     */
    private suspend fun computeContinuations(
        readListDtos: List<ReadListDto>,
    ): List<ReadListContinuationUi> = coroutineScope {
        readListDtos.map { dto ->
            async {
                if (dto.bookIds.isEmpty()) return@async null
                val books = detailRepository.readListBooks(dto.id).items.map { b -> b.toUiBook() }
                val pick = resolveReadListContinuation(
                    progresses = books.map { it.progress },
                    readDatesMs = books.map { it.lastReadAtMs },
                ) ?: return@async null
                val next = books[pick.nextBookIndex]
                pick to ReadListContinuationUi(
                    readListId = StableIds.readList(dto.id),
                    readListName = dto.name,
                    nextBookIndex = pick.nextBookIndex + 1,
                    nextBookTitle = next.title,
                    hue = hueFor(dto.id),
                    bookProgress = next.progress,
                    booksRead = pick.booksRead,
                    bookCount = books.size,
                )
            }
        }.mapNotNull { it.await() }
            // Most recently read list first (iOS sorts continuations on lastReadAt).
            .sortedWith(compareByDescending { it.first.lastReadAtMs ?: Long.MIN_VALUE })
            .map { it.second }
    }

    /**
     * Re-reads the list rows and the continuation cards without touching a single section: the strip is not a
     * paged feed (its cards are joined client-side from every read list's members), so a collection / read list
     * change cannot be revalidated like one and used to cost a wholesale reload.
     */
    private fun refreshLists() {
        if (!_state.value.hasSession) return
        val generation = ++listsGeneration
        viewModelScope.launch {
            try {
                val readListDtos = runCatching { listRepository.readLists() }.getOrNull() ?: return@launch
                val collectionDtos = runCatching { listRepository.collections() }.getOrNull()
                if (generation != listsGeneration) return@launch
                val readLists = readListDtos.map { dto -> readListEntry(dto) }
                val collections = collectionDtos?.map { dto -> collectionEntry(dto) }
                _state.update {
                    it.copy(readLists = readLists, collections = collections ?: it.collections)
                }
                // Both endpoints are the full, unfiltered set the Lists tab caches, so persisting them keeps the
                // next cold start's first paint in step with what this refresh just showed. smartLists stays
                // untouched: a null there means "not written".
                runCatching {
                    // Same first page the Lists tab caches (its PAGE_SIZE): this is a launch seed, not the strip.
                    catalogCache.saveLists(
                        collections?.take(LISTS_CACHE_PAGE_SIZE),
                        readLists.take(LISTS_CACHE_PAGE_SIZE),
                    )
                }
                if (!layout.continuation) return@launch
                val continuations = computeContinuations(readListDtos)
                if (generation != listsGeneration) return@launch
                applyContinuations(continuations)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Quiet: this is a background correction, the rows on screen stay as they were.
            }
        }
    }

    /** Swaps the continuation strip in place, adding or removing its placeholder section to match. */
    private fun applyContinuations(continuations: List<ReadListContinuationUi>) {
        val hasStrip = rawSections.any { it.id == ReadListsInProgressSectionId }
        rawSections = when {
            continuations.isEmpty() -> rawSections.filterNot { it.id == ReadListsInProgressSectionId }
            hasStrip -> rawSections
            else -> listOf(
                DashboardSection(
                    ReadListsInProgressSectionId,
                    context.getString(R.string.dashboard_section_readlists_in_progress),
                    DashboardCardKind.Horizontal,
                    emptyList<Any>(),
                ),
            ) + rawSections
        }
        // Same rule as a section re-read: a refresh that removed the last section publishes an empty list,
        // instead of leaving the removed cards (or a lone continuation header) behind.
        _state.update { it.copy(continuations = continuations, sections = derivedSections().orEmpty()) }
    }

    /** Persists the first page of every section: the launch seed contract, whatever depth the screen is at. */
    private suspend fun persistDashboard(sections: List<DashboardSection<out Any>>) {
        catalogCache.saveDashboard(
            sections.map { section ->
                @Suppress("UNCHECKED_CAST")
                (section as DashboardSection<Any>).copy(items = section.items.take(SECTION_PAGE_SIZE))
            },
        )
    }

    private suspend fun load() {
        loadMutex.withLock { loadLocked() }
    }

    /** One-time read of the persisted scope before the first feed query (otherwise the "all libraries" default wins). */
    private suspend fun ensureScopeLoaded() {
        if (scopeLoaded) return
        scopeLoaded = true
        val stored = runCatching { appSettings.dashboardBrowseScope.first() }
            .getOrDefault(AppSettingsStore.SCOPE_ALL)
        if (stored != _state.value.libraryScope) {
            _state.update { it.copy(libraryScope = stored) }
        }
    }

    /** A persisted single-library scope whose library disappeared falls back to "all libraries" (as on iOS). */
    private suspend fun normalizeScope(libraries: List<LibraryDto>) {
        val current = _state.value.libraryScope
        val normalized = normalizeLibraryScope(current, libraries)
        if (normalized != current) {
            _state.update { it.copy(libraryScope = normalized) }
            appSettings.setDashboardBrowseScope(normalized)
        }
    }

    private suspend fun loadLocked() {
        ensureScopeLoaded()
        feedsGeneration++
        // A load-more already in flight was computed before this reload started: make it land as stale for every
        // section, since this reload owns all of them.
        SectionFeedIds.forEach { bumpSectionGeneration(it) }
        // A list-row refresh already in flight was computed before this reload started: make it land as stale
        // instead of overwriting the newer rows this reload is about to write.
        listsGeneration++
        _state.update { it.copy(hasSession = true, loading = true, error = null) }
        // Data-cache parity (iOS DashboardSectionCacheStore): paint the cached first page immediately,
        // before any network round-trip below — waiting even one request would show a blank screen for
        // that long and then replace the cache almost instantly, which looks like no cache at all.
        // Only seeds an empty screen, never overrides.
        if (_state.value.sections == null) {
            val cached = runCatching { catalogCache.seedDashboard() }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                rawSections = cached
                _state.update {
                    it.copy(
                        sections = derivedSections() ?: cached,
                        // The cache holds exactly the first page; assume more until the server says otherwise.
                        sectionPaging = cached.associate { section ->
                            section.id to DashboardSectionPaging(page = 0, hasMore = true)
                        },
                    )
                }
            }
        }
        // Read the library list first: the scope menu needs it anyway, and the single-library fallback above
        // must not query the feeds with a library id the server no longer knows.
        val libraries = runCatching { libraryRepository.libraries() }.getOrDefault(emptyList())
        normalizeScope(libraries)
        // Read the pinned set straight from DataStore: avoids a startup race where the first load triggered by a ready
        // clients session runs before the first pinnedIds emission and mis-resolves the pinned scope to null (= all libraries).
        val pinned = appSettings.pinnedLibraryIds.first()
        val libraryIds = resolveLibraryScopeIds(_state.value.libraryScope, pinned)
        try {
            // A reload of the same scope re-reads every page a section already loaded, so neither the strips nor
            // the "More" pages lose their rows (and the scroll position) when a write or an SSE event triggers
            // this reload; a different scope is a different query and starts from page 0 (see [PagedFeed]).
            val scopeKey = libraryIds.orEmpty().joinToString(",")
            // A scope switch is a different query: its feeds start empty and must not inherit the page depth the
            // previous scope had scrolled to, or the first paint would spend one request per old page per section.
            val scopeChanged = feedsScopeKey != scopeKey
            if (scopeChanged) {
                sectionFeeds.clear()
                feedsScopeKey = scopeKey
            }
            val previousPaging = _state.value.sectionPaging
            val previousItems = rawSections.associate { it.id to it.items }
            val sectionItems = coroutineScope {
                SectionFeedIds.associateWith { id ->
                    async {
                        val feed = feedFor(id, libraryIds)
                        val keepPages = if (scopeChanged) 0 else previousPaging[id]?.page ?: 0
                        feed.reload(previousItems[id].orEmpty(), keepPages, newQuery = scopeChanged)
                    }
                }.mapValues { it.value.await() }
            }
            val keepReading = sectionItems.getValue("keepReading")
            val onDeck = sectionItems.getValue("onDeck")
            val releasedBooks = sectionItems.getValue("recentlyReleasedBooks")
            val addedBooks = sectionItems.getValue("recentlyAddedBooks")
            val addedSeries = sectionItems.getValue("recentlyAddedSeries")
            val updatedSeries = sectionItems.getValue("recentlyUpdatedSeries")
            val readBooks = sectionItems.getValue("recentlyReadBooks")
            val sectionPaging = SectionFeedIds.associateWith { id ->
                val feed = sectionFeeds.getValue(id)
                DashboardSectionPaging(page = feed.page, hasMore = feed.hasMore)
            }
            // Section order/titles/default card kinds aligned with iOS DashboardSection.allCases and defaultCardKind
            val sections = listOfNotNull(
                if (keepReading.isNotEmpty()) DashboardSection("keepReading", context.getString(R.string.dashboard_section_keep_reading), DashboardCardKind.Horizontal, keepReading) else null,
                if (onDeck.isNotEmpty()) DashboardSection("onDeck", context.getString(R.string.dashboard_section_on_deck), DashboardCardKind.Large, onDeck) else null,
                if (releasedBooks.isNotEmpty()) DashboardSection("recentlyReleasedBooks", context.getString(R.string.dashboard_section_recently_released_books), DashboardCardKind.Large, releasedBooks) else null,
                if (addedBooks.isNotEmpty()) DashboardSection("recentlyAddedBooks", context.getString(R.string.dashboard_section_recently_added_books), DashboardCardKind.Large, addedBooks) else null,
                if (addedSeries.isNotEmpty()) DashboardSection("recentlyAddedSeries", context.getString(R.string.dashboard_section_recently_added_series), DashboardCardKind.Large, addedSeries) else null,
                if (updatedSeries.isNotEmpty()) DashboardSection("recentlyUpdatedSeries", context.getString(R.string.dashboard_section_recently_updated_series), DashboardCardKind.Large, updatedSeries) else null,
                if (readBooks.isNotEmpty()) DashboardSection("recentlyReadBooks", context.getString(R.string.dashboard_section_recently_read_books), DashboardCardKind.Large, readBooks) else null,
            )
            val collectionDtos = listRepository.collections()
            val readListDtos = listRepository.readLists()
            val collections = collectionDtos.map { collectionEntry(it) }
            val readLists = readListDtos.map { readListEntry(it) }
            val continuations = computeContinuations(readListDtos)
            // readListsInProgress comes first in the iOS allCases order (opt-in via the settings toggle)
            val allSections = if (continuations.isNotEmpty()) {
                listOf(
                    DashboardSection(
                        ReadListsInProgressSectionId, context.getString(R.string.dashboard_section_readlists_in_progress),
                        DashboardCardKind.Horizontal, emptyList<Any>(),
                    ),
                ) + sections
            } else {
                sections
            }
            rawSections = allSections
            // Persist the first page (ids + shaped rows) so the next launch can paint before the network answers.
            // First page only, even when this reload kept deeper pages on screen: the cache is a launch seed,
            // and writing every merged row on each content change would churn the store for no benefit.
            runCatching { persistDashboard(allSections) }
            _state.update {
                it.copy(
                    loading = false,
                    sections = derivedSections() ?: allSections,
                    sectionPaging = sectionPaging,
                    continuations = continuations,
                    collections = collections,
                    readLists = readLists,
                    libraries = libraries,
                )
            }
        } catch (e: Exception) {
            _state.update {
                // Keep whatever is on screen (the cached first page): a failed refresh must not blank
                // the tab. The global offline indicator carries the connectivity signal, so the error
                // line only appears when there is nothing to show at all.
                it.copy(
                    loading = false,
                    error = if (it.sections == null) {
                        e.message ?: context.getString(R.string.common_load_failed)
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** Mark a book read/unread; the section contents follow from the server, so ContentChangeBus triggers the reload. */
    fun markBookRead(book: Book, read: Boolean) {
        val dtoId = detailRepository.bookDtoId(book.id) ?: return
        viewModelScope.launch {
            if (read) actionRepository.markBookRead(dtoId) else actionRepository.markBookUnread(dtoId)
        }
    }

    /** Mark a series read/unread; ContentChangeBus triggers the reload. */
    fun markSeriesRead(series: Series, read: Boolean) {
        val dtoId = detailRepository.seriesDtoId(series.id) ?: return
        viewModelScope.launch {
            if (read) actionRepository.markSeriesRead(dtoId) else actionRepository.markSeriesUnread(dtoId)
        }
    }

    /** Download a whole book (silently ignored when there is no DTO mapping). */
    fun downloadBook(book: Book) {
        val dtoId = detailRepository.bookDtoId(book.id) ?: return
        downloadRepository.enqueueBook(
            bookDtoId = dtoId,
            title = book.title,
            pageCount = book.pageCount,
            mediaType = book.mediaType,
            epubDivinaCompatible = book.epubDivinaCompatible,
            seriesDtoId = book.seriesDtoId,
        )
    }

    /** Download all books of the series (enqueued one by one). */
    fun downloadSeries(stableId: Int) {
        val dtoId = detailRepository.seriesDtoId(stableId) ?: return
        viewModelScope.launch {
            val books = detailRepository.seriesBooks(dtoId)
            books.forEach { book ->
                downloadRepository.enqueueBook(
                    book.id,
                    book.metadata.title.ifBlank { book.name },
                    book.media.pagesCount,
                    mediaType = book.media.mediaType,
                    epubDivinaCompatible = book.media.epubDivinaCompatible,
                    libraryId = book.libraryId,
                    seriesDtoId = dtoId,
                    seriesBookCount = books.size,
                )
            }
        }
    }

    /** Metadata baseline for editing a book outside its detail page: grid cards only carry the card fields. */
    suspend fun bookMetadata(stableId: Int): BookDetailMeta? =
        detailRepository.bookDtoId(stableId)?.let { dtoId ->
            detailRepository.bookDetail(dtoId)?.toBookDetailMeta()
        }

    /** Editing baseline for a series edited from the grid (full server values plus the locks). */
    suspend fun seriesEditValues(stableId: Int): SeriesEditValues? =
        detailRepository.seriesDtoId(stableId)?.let { dtoId ->
            detailRepository.seriesDetail(dtoId)?.metadata?.toEditValues()
        }

    /** Save series metadata edits; body == null means no changes, which succeeds immediately. */
    fun saveSeriesMetadata(stableId: Int, body: JsonObject?, onDone: (Boolean) -> Unit) {
        if (body == null) {
            onDone(true)
            return
        }
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.updateSeriesMetadata(dtoId, body)
            onDone(ok)
        }
    }

    /** Save book metadata edits (only changed fields are submitted); a draft with no changes succeeds immediately. */
    fun saveBookMetadata(stableId: Int, baseTitle: String, draft: BookMetadataDraft, onDone: (Boolean) -> Unit) {
        val body = draft.toUpdateDto(baseTitle, null)
        if (body == null) {
            onDone(true)
            return
        }
        val dtoId = detailRepository.bookDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.updateBookMetadata(dtoId, body)
            onDone(ok)
        }
    }

    /** Refresh the cover (clear the cache so the next display re-fetches it). */
    fun refreshCover(stableId: Int) {
        thumbnailCache.refresh(stableId)
    }

    /** Re-analyze the book. */
    fun analyzeBook(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch { onDone(actionRepository.analyzeBook(dtoId)) }
    }

    /** Refresh book metadata; ContentChangeBus triggers the reload. */
    fun refreshBookMetadata(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.refreshBookMetadata(dtoId)
            onDone(ok)
        }
    }

    /** Re-analyze the series. */
    fun analyzeSeries(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch { onDone(actionRepository.analyzeSeries(dtoId)) }
    }

    /** Refresh the series metadata; ContentChangeBus triggers the reload. */
    fun refreshSeriesMetadata(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch { onDone(actionRepository.refreshSeriesMetadata(dtoId)) }
    }

    /** Delete the series files; ContentChangeBus triggers the reload. */
    fun deleteSeries(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.deleteSeries(dtoId)
            if (ok) catalogCache.deleteSeries(dtoId)
            onDone(ok)
        }
    }

    /** Delete the book file; ContentChangeBus triggers the reload. */
    fun deleteBook(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            // Purge this book's cached pages while its page list still exists (see ImageCacheStore.clearBookPages).
            imageCacheStore.clearBookPages(dtoId)
            val ok = actionRepository.deleteBook(dtoId)
            if (ok) catalogCache.deleteBook(dtoId)
            onDone(ok)
        }
    }

    /** Komga web URL of the series (for sharing). */
    fun seriesWebUrl(stableId: Int): String? {
        val clients = sessionManager.clients.value ?: return null
        val dtoId = detailRepository.seriesDtoId(stableId) ?: return null
        return "${clients.baseUrl}/series/$dtoId"
    }

    /** Komga web URL of the book (for sharing). */
    fun bookWebUrl(stableId: Int): String? {
        val clients = sessionManager.clients.value ?: return null
        val dtoId = detailRepository.bookDtoId(stableId) ?: return null
        return "${clients.baseUrl}/book/$dtoId"
    }

    /** Add the series to a collection. */
    fun addSeriesToCollection(entry: ListEntry, series: Series) {
        val seriesDtoId = detailRepository.seriesDtoId(series.id) ?: return
        val collectionDtoId = detailRepository.collectionDtoId(entry.id) ?: return
        viewModelScope.launch {
            actionRepository.addSeriesToCollection(collectionDtoId, seriesDtoId)
        }
    }

    /** Add all books of the series to a read list (Komga read lists only accept books). */
    fun addSeriesToReadList(entry: ListEntry, series: Series) {
        val seriesDtoId = detailRepository.seriesDtoId(series.id) ?: return
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return
        viewModelScope.launch {
            detailRepository.seriesBooks(seriesDtoId).forEach { book ->
                actionRepository.addBookToReadList(readListDtoId, book.id)
            }
        }
    }

    /** Add the book to a read list. */
    fun addBookToReadList(entry: ListEntry, book: Book) {
        val bookDtoId = detailRepository.bookDtoId(book.id) ?: return
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return
        viewModelScope.launch {
            actionRepository.addBookToReadList(readListDtoId, bookDtoId)
        }
    }

    /** Create a collection from the picker's selection (Komga refuses empty ones); returns the new entry on success. */
    fun createCollectionEntry(
        name: String,
        ordered: Boolean,
        selection: ListSelection,
        onDone: (ListEntry?) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(newCollectionEntry(listRepository, detailRepository, name, ordered, selection.seriesIds))
        }
    }

    /** Create a read list from the picker's selection (its books, or the books of its series); same contract. */
    fun createReadListEntry(
        name: String,
        summary: String,
        ordered: Boolean,
        selection: ListSelection,
        onDone: (ListEntry?) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(newReadListEntry(listRepository, detailRepository, name, summary, ordered, selection))
        }
    }

    private companion object {
        /** Section feed page size, aligned with iOS DashboardViewModel pagination.pageSize. */
        const val SECTION_PAGE_SIZE = 20

        /** First page kept in the Lists cache, the same count the Lists tab stores (its private PAGE_SIZE). */
        const val LISTS_CACHE_PAGE_SIZE = 50

        /** Default section order, aligned with iOS DashboardSection.allCases. */
        val CanonicalSectionOrder = listOf(
            ReadListsInProgressSectionId,
            "keepReading",
            "onDeck",
            "recentlyReleasedBooks",
            "recentlyAddedBooks",
            "recentlyAddedSeries",
            "recentlyUpdatedSeries",
            "recentlyReadBooks",
        )

        /** Sections that own a paged feed (the read-list continuation section has none). */
        val SectionFeedIds = listOf(
            "keepReading",
            "onDeck",
            "recentlyReleasedBooks",
            "recentlyAddedBooks",
            "recentlyAddedSeries",
            "recentlyUpdatedSeries",
            "recentlyReadBooks",
        )

        /** Sections a series-side SSE change can reorder (iOS DashboardSectionRefreshNotifier.seriesContentSections). */
        val SERIES_SECTIONS = setOf("recentlyAddedSeries", "recentlyUpdatedSeries")

        /** Sections a book-side change can reorder: its own strips plus the series aggregates built from books. */
        val BOOK_AND_SERIES_SECTIONS = SERIES_SECTIONS + setOf(
            "keepReading",
            "onDeck",
            "recentlyReleasedBooks",
            "recentlyAddedBooks",
            "recentlyReadBooks",
        )

        /**
         * Sections a read-progress or read-state change can move: the read-state lists plus the series strips,
         * whose cards carry an "all read" badge (unreadCount == 0) that a completion flips. iOS posts every
         * read-progress event through `postReadStatusChanged` with `readStatusSections`
         * (`readingProgressSections ∪ seriesContentSections`), so both event kinds share this set; the
         * readListsInProgress strip rides along as a list refresh.
         */
        val READ_STATUS_SECTIONS = setOf("keepReading", "onDeck", "recentlyReadBooks") + SERIES_SECTIONS

        /** Book strips whose cards render read progress / completion without being part of the read-state set. */
        val READ_STATE_CARD_SECTIONS = setOf("recentlyAddedBooks", "recentlyReleasedBooks")

        /** Event kinds that trigger a dashboard refresh. */
        val REFRESH_KINDS = setOf(
            SseEventKind.Book,
            SseEventKind.Series,
            SseEventKind.ReadProgress,
            SseEventKind.Collection,
            SseEventKind.ReadList,
            SseEventKind.Library,
        )
    }
}
