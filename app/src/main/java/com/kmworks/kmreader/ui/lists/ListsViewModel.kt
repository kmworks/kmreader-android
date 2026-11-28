package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.data.Paged
import com.kmworks.kmreader.core.data.PagedFeed
import com.kmworks.kmreader.core.data.CatalogCache
import com.kmworks.kmreader.core.data.ContentChange
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.ContentChangeKind
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.PatchOutcome
import com.kmworks.kmreader.core.data.SmartListRepository
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.core.data.ThumbnailCache
import com.kmworks.kmreader.core.data.observeChanges
import com.kmworks.kmreader.core.data.toDtoTarget
import com.kmworks.kmreader.core.data.toListEntry
import com.kmworks.kmreader.core.network.ServerReachability
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.SmartListTarget
import com.kmworks.kmreader.ui.components.normalizeLibraryScope
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Lists screen state; each list is null when there is no session (UI shows the empty state).
 * The library scope/pinned set mirror the dashboard: the toolbar menu filters the two strips.
 */
data class ListsUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
    val smartLists: List<ListEntry>? = null,
    val collectionsHasMore: Boolean = true,
    val readListsHasMore: Boolean = true,
    val smartListsHasMore: Boolean = true,
    /** Library-scope menu data: the real library list from the server. */
    val libraries: List<LibraryDto> = emptyList(),
    /** Library scope of the strips (pinned/all/library:{id}); persisted per server, defaults to all. */
    val libraryScope: String = AppSettingsStore.SCOPE_ALL,
    /** Pinned library ids (the pinned scope resolves to these as the libraryIds filter). */
    val pinnedIds: Set<String> = emptySet(),
    /** kmrs smartLists capability; the "New Smart List" entry is hidden when the endpoint does not exist. */
    val smartListsSupported: Boolean = false,
)

/** Real collections / read lists / smart lists (a failing smart list hides its whole group, no error). */
@HiltViewModel
class ListsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listRepository: ListRepository,
    private val libraryRepository: LibraryRepository,
    private val contentChangeBus: ContentChangeBus,
    private val smartListRepository: SmartListRepository,
    private val appSettingsStore: AppSettingsStore,
    private val thumbnailCache: ThumbnailCache,
    private val sessionManager: SessionManager,
    private val catalogCache: CatalogCache,
    private val serverProfileStore: ServerProfileStore,
    private val serverReachability: ServerReachability,
) : ViewModel() {

    private val _state = MutableStateFlow(ListsUiState())
    val state = _state.asStateFlow()

    /**
     * Strip feeds (collections, read lists, smart lists). Each maps the DTOs to the cards it shows and applies
     * the current pins, so a reload of the same scope re-reads the pages already on screen instead of dropping
     * back to page one (see [PagedFeed]).
     */
    private val collectionsFeed = PagedFeed(
        pageSize = PAGE_SIZE,
        identity = { entry: ListEntry -> entry.id },
        fetch = { page, size ->
            listRepository.collectionsPage(page = page, size = size, libraryIds = currentLibraryIds)
                ?.let { result ->
                    val pinned = appSettingsStore.pinnedCollectionIds.first()
                    Paged(
                        result.items.map { it.toListEntry() }.applyPinned(pinned, DetailIdRegistry::collectionDtoId),
                        result.hasMore,
                        result.totalElements,
                    )
                }
        },
    )
    private val readListsFeed = PagedFeed(
        pageSize = PAGE_SIZE,
        identity = { entry: ListEntry -> entry.id },
        fetch = { page, size ->
            listRepository.readListsPage(page = page, size = size, libraryIds = currentLibraryIds)
                ?.let { result ->
                    val pinned = appSettingsStore.pinnedReadListIds.first()
                    Paged(
                        result.items.map { dto ->
                            dto.toListEntry().copy(
                                modifiedLabel = context.getString(
                                    R.string.lists_modified_at,
                                    dto.lastModifiedDate.take(10),
                                ),
                            )
                        }.applyPinned(pinned, DetailIdRegistry::readListDtoId),
                        result.hasMore,
                        result.totalElements,
                    )
                }
        },
    )
    private val smartListsFeed = PagedFeed(
        pageSize = PAGE_SIZE,
        identity = { entry: ListEntry -> entry.id },
        fetch = { page, size ->
            smartListRepository.smartListsPage(page = page, size = size)?.let { result ->
                Paged(
                    result.items.map { dto ->
                        dto.toListEntry().copy(
                            targetLabel = context.getString(
                                if (dto.target == "SERIES") R.string.common_series else R.string.common_book,
                            ),
                        )
                    },
                    result.hasMore,
                    result.totalElements,
                )
            }
        },
    )
    private var loadingMoreCollections = false
    private var loadingMoreReadLists = false
    private var loadingMoreSmartLists = false

    /** Resolved libraryIds filter of the current query (null = every library); load-more reuses it. */
    private var currentLibraryIds: List<String>? = null

    /** Generation counter: after a scope change, discard results of in-flight stale queries and pages. */
    private var generation = 0

    /** The scope/pins the visible strips were fetched with (null before the first load); a different query clears them. */
    private var displayedScope: String? = null
    private var displayedPinned: Set<String>? = null

    init {
        viewModelScope.launch {
            // Back online after an outage (probe, SSE reconnect or any request): reload the cached strips,
            // mirroring the iOS recovery refresh. drop(1) skips the initial `false` (StateFlow conflates repeats).
            serverReachability.offline
                .drop(1)
                .filter { !it }
                .collect { refresh() }
        }
        // The persisted scope and pinned set are the source of truth (like the library tab): changing either
        // re-queries the two strips with the resolved libraryIds.
        viewModelScope.launch {
            combine(
                sessionManager.clients,
                appSettingsStore.listsBrowseScope,
                appSettingsStore.pinnedLibraryIds,
            ) { clients, scope, pinned -> Triple(clients, scope, pinned) }
                .collectLatest { (clients, scope, pinned) ->
                    if (clients == null) {
                        resetForSignedOut()
                    } else {
                        load(scope, pinned)
                    }
                }
        }
        // These strips only show names, counts and covers, so a list write (here or on another screen)
        // re-reads once instead of patching: same cost as before, one shared debounce. Book and series
        // events are ignored: they change no name, count or cover on this screen.
        viewModelScope.launch {
            contentChangeBus.observeChanges(
                patch = { change ->
                    when {
                        change.kind == ContentChangeKind.Touched ->
                            if (change is ContentChange.Books || change is ContentChange.Series) {
                                PatchOutcome.Patched
                            } else {
                                PatchOutcome.Declined
                            }

                        change.kind == ContentChangeKind.ReadStateChanged ||
                            change.kind == ContentChangeKind.ProgressChanged -> PatchOutcome.Patched

                        else -> PatchOutcome.Declined
                    }
                },
                reload = { refresh() },
            )
        }
        // Smart list creation is a kmrs-private endpoint; the capability probe may land after the first
        // strips load, so the entry follows the profile instead of being read once.
        viewModelScope.launch {
            serverProfileStore.profile.collect { profile ->
                val supported = profile?.features?.contains(ServerFeature.SmartLists) == true
                _state.update { it.copy(smartListsSupported = supported) }
            }
        }
    }

    private fun resetForSignedOut() {
        generation++
        loadingMoreCollections = false
        loadingMoreReadLists = false
        loadingMoreSmartLists = false
        currentLibraryIds = null
        displayedScope = null
        displayedPinned = null
        _state.update {
            it.copy(
                hasSession = false,
                loading = false,
                loadingMore = false,
                libraries = emptyList(),
                collections = null,
                readLists = null,
                smartLists = null,
                collectionsHasMore = true,
                readListsHasMore = true,
                smartListsHasMore = true,
                smartListsSupported = false,
            )
        }
    }

    /**
     * Reload both strips for [scope]/[pinned]. The resolved libraryIds filter request, paging and the
     * data cache alike; smart lists are not library-scoped (same as the iOS ListsBrowseView).
     */
    private suspend fun load(scope: String, pinned: Set<String>) {
        val gen = ++generation
        loadingMoreCollections = false
        loadingMoreReadLists = false
        loadingMoreSmartLists = false
        // A changed query must not keep the previous scope's rows on screen; a plain refresh may keep them,
        // including the pages each strip already loaded (see [PagedFeed]).
        val sameQuery = displayedScope == scope && displayedPinned == pinned
        val keepCollections = if (sameQuery) collectionsFeed.page else 0
        val keepReadLists = if (sameQuery) readListsFeed.page else 0
        val keepSmartLists = if (sameQuery) smartListsFeed.page else 0
        _state.update {
            it.copy(
                hasSession = true,
                loading = true,
                error = null,
                libraryScope = scope,
                pinnedIds = pinned,
                collections = if (sameQuery) it.collections else null,
                readLists = if (sameQuery) it.readLists else null,
            )
        }
        // Data-cache parity: paint the cached Lists rows before any network round-trip (empty screen
        // only). The cache is written unfiltered (also by the library tab), so it can only seed an
        // unfiltered query; the raw scope resolves to "no filter" without waiting for the library list.
        if (resolveLibraryScopeIds(scope, pinned) == null &&
            _state.value.collections == null &&
            _state.value.readLists == null
        ) {
            val cached = runCatching { catalogCache.seedLists() }.getOrNull()
            if (cached != null && gen == generation) {
                // The cached pinned flags are from the last save; apply the current sets so a pin toggled
                // since then does not reshuffle the strip once the network answer lands.
                val pinnedCollectionIds = appSettingsStore.pinnedCollectionIds.first()
                val pinnedReadListIds = appSettingsStore.pinnedReadListIds.first()
                _state.update {
                    it.copy(
                        collections = cached.first.applyPinned(pinnedCollectionIds, DetailIdRegistry::collectionDtoId),
                        readLists = cached.second.applyPinned(pinnedReadListIds, DetailIdRegistry::readListDtoId),
                        smartLists = cached.third,
                    )
                }
            }
        }
        // The scope menu needs the real library list; read it first so a persisted single-library scope whose
        // library disappeared can fall back to "all libraries" before the feeds are queried against a dead filter.
        val libraries = runCatching { libraryRepository.libraries() }.getOrDefault(emptyList())
        val resolvedScope = normalizeLibraryScope(scope, libraries)
        if (resolvedScope != scope) {
            // Persisted, so the normalized choice survives; the collector then re-runs this load.
            appSettingsStore.setListsBrowseScope(resolvedScope)
        }
        val libraryIds = resolveLibraryScopeIds(resolvedScope, pinned)
        currentLibraryIds = libraryIds
        _state.update { it.copy(libraryScope = resolvedScope, libraries = libraries) }
        try {
            val (collectionEntries, readListEntries, smartListEntries) = coroutineScope {
                val previousCollections = if (sameQuery) _state.value.collections.orEmpty() else emptyList()
                val previousReadLists = if (sameQuery) _state.value.readLists.orEmpty() else emptyList()
                val previousSmartLists = if (sameQuery) _state.value.smartLists.orEmpty() else emptyList()
                val c = async { collectionsFeed.reload(previousCollections, keepCollections, newQuery = !sameQuery) }
                val r = async { readListsFeed.reload(previousReadLists, keepReadLists, newQuery = !sameQuery) }
                val smart = async { smartListsFeed.reload(previousSmartLists, keepSmartLists, newQuery = !sameQuery) }
                Triple(c.await(), r.await(), smart.await())
            }
            if (gen != generation) return // a newer scope/pins load started; discard this stale result
            displayedScope = resolvedScope
            displayedPinned = pinned
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    collections = collectionEntries,
                    readLists = readListEntries,
                    smartLists = smartListEntries,
                    collectionsHasMore = collectionsFeed.hasMore,
                    readListsHasMore = readListsFeed.hasMore,
                    smartListsHasMore = smartListsFeed.hasMore,
                )
            }
            // Persist the Lists rows so the next launch can paint them before the network answers; only the
            // unfiltered set is cached, because the cache has no scope dimension shared with the library tab.
            if (libraryIds == null) {
                runCatching {
                    catalogCache.saveLists(
                        collectionEntries.take(PAGE_SIZE),
                        readListEntries.take(PAGE_SIZE),
                        smartListEntries.take(PAGE_SIZE),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen != generation) return
            _state.update {
                // Keep whatever is on screen (the cached rows): a failed refresh must not blank the
                // tab. The offline indicator carries the connectivity signal; the error line is only
                // for the nothing-to-show case.
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = if (it.collections == null && it.readLists == null && it.smartLists == null) {
                        e.message ?: context.getString(R.string.common_load_failed)
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * Reload the Lists strips from the first page (pull-to-refresh; paging state is reset by [load]).
     * The returned job completes when the reload landed, so a caller can keep its indicator up
     * meanwhile while the work itself stays in this view model's scope.
     */
    fun refresh(): Job = viewModelScope.launch {
        val state = _state.value
        if (state.hasSession) load(state.libraryScope, state.pinnedIds)
    }

    /** Library scope switch of this tab (pinned/all/library:{id}); persisted so the tab remembers it across launches. */
    fun setScope(scope: String) {
        if (_state.value.libraryScope == scope) return
        _state.update { it.copy(libraryScope = scope) }
        viewModelScope.launch { appSettingsStore.setListsBrowseScope(scope) }
    }

    /** Overwrite the pinned library set (saved from the pin-editing sheet; persisted in AppSettingsStore). */
    fun setPinnedLibraries(ids: Set<String>) {
        viewModelScope.launch { appSettingsStore.setPinnedLibraryIds(ids) }
    }

    /** Load the next page when the collections strip hits bottom. */
    fun loadMoreCollections() {
        val state = _state.value
        if (loadingMoreCollections || state.loading || !state.collectionsHasMore) return
        loadingMoreCollections = true
        val gen = generation
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val entries = collectionsFeed.append(state.collections.orEmpty())
                if (gen != generation) return@launch // the scope changed meanwhile; the new load owns the strips
                // The appended page is sorted with the rows already on screen: sorting each page on its own would
                // leave a pinned row that arrives later behind unpinned rows already displayed.
                val pinned = appSettingsStore.pinnedCollectionIds.first()
                _state.update {
                    it.copy(
                        loadingMore = false,
                        collections = entries.applyPinned(pinned, DetailIdRegistry::collectionDtoId),
                        collectionsHasMore = collectionsFeed.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loadingMore = false,
                        error = e.message ?: context.getString(R.string.common_load_more_failed),
                    )
                }
            } finally {
                loadingMoreCollections = false
            }
        }
    }

    /** Load the next page when the read lists strip hits bottom. */
    fun loadMoreReadLists() {
        val state = _state.value
        if (loadingMoreReadLists || state.loading || !state.readListsHasMore) return
        loadingMoreReadLists = true
        val gen = generation
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val entries = readListsFeed.append(state.readLists.orEmpty())
                if (gen != generation) return@launch // the scope changed meanwhile; the new load owns the strips
                // Same whole-strip ordering as the collections strip: pinned rows stay ahead of unpinned ones.
                val pinned = appSettingsStore.pinnedReadListIds.first()
                _state.update {
                    it.copy(
                        loadingMore = false,
                        readLists = entries.applyPinned(pinned, DetailIdRegistry::readListDtoId),
                        readListsHasMore = readListsFeed.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loadingMore = false,
                        error = e.message ?: context.getString(R.string.common_load_more_failed),
                    )
                }
            } finally {
                loadingMoreReadLists = false
            }
        }
    }

    /** Load the next page when the smart lists strip hits bottom. */
    fun loadMoreSmartLists() {
        val state = _state.value
        if (loadingMoreSmartLists || state.loading || !state.smartListsHasMore) return
        loadingMoreSmartLists = true
        val gen = generation
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val entries = smartListsFeed.append(state.smartLists.orEmpty())
                if (gen != generation) return@launch // a reload started meanwhile; the new load owns the strips
                _state.update {
                    it.copy(
                        loadingMore = false,
                        smartLists = entries,
                        smartListsHasMore = smartListsFeed.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loadingMore = false,
                        error = e.message ?: context.getString(R.string.common_load_more_failed),
                    )
                }
            } finally {
                loadingMoreSmartLists = false
            }
        }
    }

    fun saveCollection(entry: ListEntry, name: String, ordered: Boolean, onDone: (Boolean) -> Unit) {
        val dtoId = DetailIdRegistry.collectionDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = listRepository.updateCollection(dtoId, name, ordered)
            onDone(ok)
        }
    }

    fun deleteCollection(entry: ListEntry, onDone: (Boolean) -> Unit) {
        val dtoId = DetailIdRegistry.collectionDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = listRepository.deleteCollection(dtoId)
            onDone(ok)
        }
    }

    fun saveReadList(entry: ListEntry, name: String, summary: String, ordered: Boolean, onDone: (Boolean) -> Unit) {
        val dtoId = DetailIdRegistry.readListDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = listRepository.updateReadList(dtoId, name, summary, ordered)
            onDone(ok)
        }
    }

    fun deleteReadList(entry: ListEntry, onDone: (Boolean) -> Unit) {
        val dtoId = DetailIdRegistry.readListDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = listRepository.deleteReadList(dtoId)
            onDone(ok)
        }
    }

    /**
     * Update name/summary, and conditions only when the editor produced a new search document
     * ([search] null keeps the server document so a rename cannot erase filters the editor cannot show).
     * Sharing fields are likewise null for regular users, so stored values survive.
     */
    fun saveSmartList(
        entry: ListEntry,
        name: String,
        summary: String,
        search: JsonObject?,
        visibility: String? = null,
        sharedWithUserIds: List<String>? = null,
        onDone: (Boolean) -> Unit,
    ) {
        val dtoId = DetailIdRegistry.smartListDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = smartListRepository.updateSmartList(
                dtoId = dtoId,
                name = name,
                summary = summary,
                search = search,
                visibility = visibility,
                sharedWithUserIds = sharedWithUserIds,
            )
            onDone(ok)
        }
    }

    /**
     * Create a smart list (mirrors iOS SmartListEditSheet mode .create): the server assigns the id, so
     * [onDone] receives the new entry's stableId (null when the server refused — stock Komga 404 or a
     * validation error). The strips reload before the callback so the new entry is behind the opened page.
     */
    fun createSmartList(
        name: String,
        summary: String,
        target: SmartListTarget,
        search: JsonObject?,
        visibility: String? = null,
        sharedWithUserIds: List<String>? = null,
        onDone: (Int?) -> Unit,
    ) {
        viewModelScope.launch {
            val created = try {
                smartListRepository.createSmartList(
                    name = name,
                    summary = summary,
                    target = target.toDtoTarget(),
                    search = search ?: JsonObject(emptyMap()),
                    visibility = visibility,
                    sharedWithUserIds = sharedWithUserIds,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (created == null) {
                onDone(null)
                return@launch
            }
            refresh().join()
            onDone(StableIds.smartList(created.id))
        }
    }

    fun deleteSmartList(entry: ListEntry, onDone: (Boolean) -> Unit) {
        val dtoId = DetailIdRegistry.smartListDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = smartListRepository.deleteSmartList(dtoId)
            onDone(ok)
        }
    }

    /** Pin/unpin a collection (stores the dtoId set locally; pinned sorts first; mirrors iOS CollectionsViewModel). */
    fun toggleCollectionPinned(entry: ListEntry) {
        val dtoId = DetailIdRegistry.collectionDtoId(entry.id) ?: return
        viewModelScope.launch {
            val current = appSettingsStore.pinnedCollectionIds.first()
            val next = if (dtoId in current) current - dtoId else current + dtoId
            appSettingsStore.setPinnedCollectionIds(next)
            _state.update { st ->
                st.copy(collections = st.collections?.applyPinned(next, DetailIdRegistry::collectionDtoId))
            }
        }
    }

    /** Pin/unpin a read list. */
    fun toggleReadListPinned(entry: ListEntry) {
        val dtoId = DetailIdRegistry.readListDtoId(entry.id) ?: return
        viewModelScope.launch {
            val current = appSettingsStore.pinnedReadListIds.first()
            val next = if (dtoId in current) current - dtoId else current + dtoId
            appSettingsStore.setPinnedReadListIds(next)
            _state.update { st ->
                st.copy(readLists = st.readLists?.applyPinned(next, DetailIdRegistry::readListDtoId))
            }
        }
    }

    /** Refresh the cover cache (ThumbnailCache supports registered Book/Series/Collection/ReadList/SmartList stableIds). */
    fun refreshCover(stableId: Int): Boolean = thumbnailCache.refresh(stableId)

    /** Web share link for a read list ({baseUrl}/readlist/{dtoId}). */
    fun readListWebUrl(stableId: Int): String? {
        val dtoId = DetailIdRegistry.readListDtoId(stableId) ?: return null
        val clients = sessionManager.clients.value ?: return null
        return "${clients.baseUrl}/readlist/$dtoId"
    }

    /** Mirrors iOS pinnedIds + the rest: entries in the pinned set get pinned=true and are stably sorted first. */
    private fun List<ListEntry>.applyPinned(
        pinnedIds: Set<String>,
        dtoIdOf: (Int) -> String?,
    ): List<ListEntry> =
        map { entry -> entry.copy(pinned = dtoIdOf(entry.id)?.let { it in pinnedIds } ?: false) }
            .sortedByDescending { it.pinned }

    private companion object {
        /** 50 items per page for collections/read lists (unified across list screens). */
        const val PAGE_SIZE = 50
    }
}
