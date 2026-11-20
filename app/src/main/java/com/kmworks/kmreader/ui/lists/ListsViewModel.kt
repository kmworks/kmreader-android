package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.CatalogCache
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.SmartListRepository
import com.kmworks.kmreader.core.data.ThumbnailCache
import com.kmworks.kmreader.core.data.toListEntry
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import com.kmworks.kmreader.data.SampleData.ListEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lists screen state; each list is null when there is no session (UI shows the empty state). */
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
)

/** SSE kinds that trigger a full refresh (mirrors the DashboardViewModel pattern). */
private val REFRESH_KINDS = setOf(
    SseEventKind.Book,
    SseEventKind.Series,
    SseEventKind.ReadList,
    SseEventKind.Collection,
    SseEventKind.SmartList,
)

/** Real collections / read lists / smart lists (a failing smart list hides its whole group, no error). */
@HiltViewModel
class ListsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listRepository: ListRepository,
    private val smartListRepository: SmartListRepository,
    private val sseService: SseService,
    private val appSettingsStore: AppSettingsStore,
    private val thumbnailCache: ThumbnailCache,
    private val sessionManager: SessionManager,
    private val catalogCache: CatalogCache,
) : ViewModel() {

    private val _state = MutableStateFlow(ListsUiState())
    val state = _state.asStateFlow()

    private var collectionsPage = 0
    private var readListsPage = 0
    private var loadingMoreCollections = false
    private var loadingMoreReadLists = false

    init {
        viewModelScope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    collectionsPage = 0
                    readListsPage = 0
                    _state.update {
                        it.copy(
                            hasSession = false,
                            loading = false,
                            loadingMore = false,
                            collections = null,
                            readLists = null,
                            smartLists = null,
                            collectionsHasMore = true,
                            readListsHasMore = true,
                        )
                    }
                } else {
                    load()
                }
            }
        }
        // SSE-driven debounced refresh (mirrors DashboardViewModel)
        viewModelScope.launch {
            sseService.events
                .filter { it.kind in REFRESH_KINDS }
                .debounce(3_000)
                .collect {
                    if (_state.value.hasSession) load()
                }
        }
    }

    private suspend fun load() {
        _state.update { it.copy(hasSession = true, loading = true, error = null) }
        collectionsPage = 0
        readListsPage = 0
        // Data-cache parity: paint the cached Lists rows before the network answers (empty screen only).
        if (_state.value.collections == null && _state.value.readLists == null) {
            val cached = runCatching { catalogCache.seedLists() }.getOrNull()
            if (cached != null) {
                _state.update { it.copy(collections = cached.first, readLists = cached.second) }
            }
        }
        try {
            val (collectionPage, readListPage, smartLists) = coroutineScope {
                val c = async { listRepository.collectionsPage(page = 0, size = PAGE_SIZE) }
                val r = async { listRepository.readListsPage(page = 0, size = PAGE_SIZE) }
                val s = async { smartListRepository.smartLists() }
                Triple(c.await(), r.await(), s.await())
            }
            val pinnedCollectionIds = appSettingsStore.pinnedCollectionIds.first()
            val pinnedReadListIds = appSettingsStore.pinnedReadListIds.first()
            val collectionEntries = collectionPage.items.map { dto ->
                dto.toListEntry()
            }.applyPinned(pinnedCollectionIds, DetailIdRegistry::collectionDtoId)
            val readListEntries = readListPage.items.map { dto ->
                dto.toListEntry().copy(
                    modifiedLabel = context.getString(
                        R.string.lists_modified_at,
                        dto.lastModifiedDate.take(10),
                    ),
                )
            }.applyPinned(pinnedReadListIds, DetailIdRegistry::readListDtoId)
            val smartListEntries = smartLists.map { dto ->
                // No modifiedLabel: a smart list card shows only what it groups by, and its
                // own page carries the timestamps.
                dto.toListEntry().copy(
                    targetLabel = context.getString(
                        if (dto.target == "SERIES") R.string.common_series else R.string.common_book,
                    ),
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    collections = collectionEntries,
                    readLists = readListEntries,
                    smartLists = smartListEntries,
                    collectionsHasMore = collectionPage.hasMore,
                    readListsHasMore = readListPage.hasMore,
                )
            }
            // Persist the Lists rows so the next launch can paint them before the network answers.
            runCatching { catalogCache.saveLists(collectionEntries, readListEntries) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = e.message ?: context.getString(R.string.common_load_failed),
                    collections = null,
                    readLists = null,
                    smartLists = null,
                )
            }
        }
    }

    /** Load the next page when the collections strip hits bottom. */
    fun loadMoreCollections() {
        val state = _state.value
        if (loadingMoreCollections || state.loading || !state.collectionsHasMore) return
        loadingMoreCollections = true
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val nextPage = collectionsPage + 1
                val result = listRepository.collectionsPage(page = nextPage, size = PAGE_SIZE)
                collectionsPage = nextPage
                val pinnedIds = appSettingsStore.pinnedCollectionIds.first()
                _state.update { st ->
                    st.copy(
                        loadingMore = false,
                        collections = (st.collections.orEmpty() +
                            result.items.map { it.toListEntry() }.filterNot { entry ->
                                st.collections.orEmpty().any { it.id == entry.id }
                            }).applyPinned(pinnedIds, DetailIdRegistry::collectionDtoId),
                        collectionsHasMore = result.hasMore,
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
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val nextPage = readListsPage + 1
                val result = listRepository.readListsPage(page = nextPage, size = PAGE_SIZE)
                readListsPage = nextPage
                val pinnedIds = appSettingsStore.pinnedReadListIds.first()
                _state.update { st ->
                    st.copy(
                        loadingMore = false,
                        readLists = (st.readLists.orEmpty() +
                            result.items.map {
                                it.toListEntry().copy(
                                    modifiedLabel = context.getString(
                                        R.string.lists_modified_at,
                                        it.lastModifiedDate.take(10),
                                    ),
                                )
                            }.filterNot { entry ->
                                st.readLists.orEmpty().any { it.id == entry.id }
                            }).applyPinned(pinnedIds, DetailIdRegistry::readListDtoId),
                        readListsHasMore = result.hasMore,
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

    fun saveCollection(entry: ListEntry, name: String, ordered: Boolean, onDone: (Boolean) -> Unit) {
        val dtoId = DetailIdRegistry.collectionDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = listRepository.updateCollection(dtoId, name, ordered)
            if (ok) load()
            onDone(ok)
        }
    }

    fun deleteCollection(entry: ListEntry, onDone: (Boolean) -> Unit) {
        val dtoId = DetailIdRegistry.collectionDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = listRepository.deleteCollection(dtoId)
            if (ok) load()
            onDone(ok)
        }
    }

    fun saveReadList(entry: ListEntry, name: String, summary: String, ordered: Boolean, onDone: (Boolean) -> Unit) {
        val dtoId = DetailIdRegistry.readListDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = listRepository.updateReadList(dtoId, name, summary, ordered)
            if (ok) load()
            onDone(ok)
        }
    }

    fun deleteReadList(entry: ListEntry, onDone: (Boolean) -> Unit) {
        val dtoId = DetailIdRegistry.readListDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = listRepository.deleteReadList(dtoId)
            if (ok) load()
            onDone(ok)
        }
    }

    fun saveSmartList(
        entry: ListEntry,
        name: String,
        matchAll: Boolean,
        rules: List<SmartListRule>,
        onDone: (Boolean) -> Unit,
    ) {
        val dtoId = DetailIdRegistry.smartListDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = smartListRepository.updateSmartList(
                dtoId = dtoId,
                name = name,
                summary = entry.summary.orEmpty(),
                search = buildSmartListSearchDocument(matchAll, rules),
            )
            if (ok) load()
            onDone(ok)
        }
    }

    fun deleteSmartList(entry: ListEntry, onDone: (Boolean) -> Unit) {
        val dtoId = DetailIdRegistry.smartListDtoId(entry.id) ?: return onDone(false)
        viewModelScope.launch {
            val ok = smartListRepository.deleteSmartList(dtoId)
            if (ok) load()
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
