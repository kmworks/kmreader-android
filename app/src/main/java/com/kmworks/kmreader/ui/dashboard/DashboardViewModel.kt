package com.kmworks.kmreader.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.BookRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.SeriesRepository
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.data.ThumbnailCache
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.ReadListContinuationUi
import com.kmworks.kmreader.data.ReadListsInProgressSectionId
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
import com.kmworks.kmreader.ui.detail.BookMetadataDraft
import com.kmworks.kmreader.ui.detail.toUpdateDto
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Dashboard sections state; sections == null keeps the sample demo UI. */
data class DashboardUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val sections: List<DashboardSection<out Any>>? = null,
    /** Continuation cards for read lists in progress (computed from real data; empty when none are in progress). */
    val continuations: List<ReadListContinuationUi> = emptyList(),
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
    /** Whether the card-kind menu is shown next to section titles (persisted in the settings screen). */
    val kindMenuEnabled: Boolean = true,
    /** Library-scope menu data: the real library list from the server. */
    val libraries: List<LibraryDto> = emptyList(),
    /** Library scope (pinned/all/library:{id}, aligned with iOS LibraryBrowseScope); session-level choice on the dashboard, defaults to pinned. */
    val libraryScope: String = AppSettingsStore.SCOPE_PINNED,
    /** Pinned library ids (the pinned scope resolves to these as the libraryIds filter). */
    val pinnedIds: Set<String> = emptySet(),
)

/** Dashboard layout configuration (persisted in AppSettingsStore, aligned with iOS DashboardConfiguration). */
private data class DashboardLayout(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val continuation: Boolean = true,
    val kindMenu: Boolean = true,
    val overrides: Map<String, DashboardCardKind> = emptyMap(),
)

/** Real dashboard data for the eight sections (books + series feeds, aligned with iOS DashboardSection). */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val seriesRepository: SeriesRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val detailRepository: DetailRepository,
    private val libraryRepository: LibraryRepository,
    private val sseService: SseService,
    private val appSettings: AppSettingsStore,
    private val serverProfileStore: ServerProfileStore,
    private val downloadRepository: DownloadRepository,
    private val thumbnailCache: ThumbnailCache,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state = _state.asStateFlow()

    /** Sections as produced by the server (not yet trimmed by settings); sections are re-derived on layout changes. */
    private var rawSections: List<DashboardSection<out Any>> = emptyList()
    private var layout = DashboardLayout()

    /** Re-fetch the section contents (content sync after a successful action). */
    fun refresh() {
        viewModelScope.launch { load() }
    }

    init {
        viewModelScope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    _state.update { it.copy(hasSession = false, loading = false, sections = null) }
                } else {
                    load()
                }
            }
        }
        // Reorder instantly when the settings screen changes order/visibility/card kind, without re-requesting
        viewModelScope.launch {
            combine(
                appSettings.dashboardSectionOrder,
                appSettings.dashboardHiddenSections,
                appSettings.dashboardContinuationEnabled,
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
        // Refresh after aggregating SSE content-change events over 3s (does not follow events when "auto refresh" is off in settings)
        viewModelScope.launch {
            combine(
                sseService.events.filter { it.kind in REFRESH_KINDS },
                appSettings.sseAutoRefresh,
            ) { _, autoRefresh -> autoRefresh }
                .filter { it }
                .debounce(3_000)
                .collect { refresh() }
        }
    }

    /** Derive the section list from the persisted order/visibility/card kinds; returns null before content is loaded. */
    private fun derivedSections(): List<DashboardSection<out Any>>? {
        if (rawSections.isEmpty()) return null
        return rawSections
            .sortedBy { section ->
                val index = layout.order.indexOf(section.id)
                if (index < 0) Int.MAX_VALUE else index
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

    /** Library scope switch (pinned/all/library:{id}; session-level, not persisted, aligned with iOS DashboardLibraryScopeStore). */
    fun setScope(scope: String) {
        if (_state.value.libraryScope == scope) return
        _state.update { it.copy(libraryScope = scope) }
        if (_state.value.hasSession) refresh()
    }

    /** Overwrite the pinned set (saved from the pin-editing sheet, persisted in AppSettingsStore). */
    fun setPinnedLibraries(ids: Set<String>) {
        viewModelScope.launch { appSettings.setPinnedLibraryIds(ids) }
    }

    private suspend fun load() {
        _state.update { it.copy(hasSession = true, loading = true, error = null) }
        // Read the pinned set straight from DataStore: avoids a startup race where the first load triggered by a ready
        // clients session runs before the first pinnedIds emission and mis-resolves the pinned scope to null (= all libraries).
        val pinned = appSettings.pinnedLibraryIds.first()
        val libraryIds = resolveLibraryScopeIds(_state.value.libraryScope, pinned)
        try {
            val fetched = coroutineScope {
                val readDateAfter = serverProfileStore.recentReadingCutoff()
                // First-screen item count per section aligned with iOS DashboardViewModel pagination.pageSize = 20
                val keepReading = async {
                    bookRepository.keepReading(size = 20, libraryIds = libraryIds, readDateAfter = readDateAfter)
                }
                val onDeck = async { bookRepository.onDeck(size = 20, libraryIds = libraryIds) }
                val releasedBooks = async { bookRepository.recentlyReleased(size = 20, libraryIds = libraryIds) }
                val addedBooks = async { bookRepository.latest(size = 20, libraryIds = libraryIds) }
                val addedSeries = async { seriesRepository.newlyAdded(size = 20, libraryIds = libraryIds) }
                val updatedSeries = async { seriesRepository.recentlyUpdated(size = 20, libraryIds = libraryIds) }
                val readBooks = async { bookRepository.recentlyRead(size = 20, libraryIds = libraryIds) }
                DashboardFeeds(
                    keepReading.await(),
                    onDeck.await(),
                    releasedBooks.await(),
                    addedBooks.await(),
                    addedSeries.await(),
                    updatedSeries.await(),
                    readBooks.await(),
                )
            }
            val keepReading = fetched.keepReading.map { it.toUiBook() }
            val onDeck = fetched.onDeck.map { it.toUiBook() }
            val releasedBooks = fetched.releasedBooks.map { it.toUiBook() }
            val addedBooks = fetched.addedBooks.map { it.toUiBook() }
            val addedSeries = fetched.addedSeries.map { it.toUiSeries() }
            val updatedSeries = fetched.updatedSeries.map { it.toUiSeries() }
            val readBooks = fetched.readBooks.map { it.toUiBook() }
            // Section order/titles/default card kinds aligned with iOS DashboardSection.allCases and defaultCardKind
            val sections = listOfNotNull(
                if (keepReading.isNotEmpty()) DashboardSection("keepReading", context.getString(R.string.dashboard_section_keep_reading), DashboardCardKind.Horizontal, keepReading) else null,
                if (onDeck.isNotEmpty()) DashboardSection("onDeck", context.getString(R.string.dashboard_section_on_deck), DashboardCardKind.Large, onDeck) else null,
                if (releasedBooks.isNotEmpty()) DashboardSection("recentlyReleasedBooks", context.getString(R.string.dashboard_section_recently_released_books), DashboardCardKind.Large, releasedBooks) else null,
                if (addedBooks.isNotEmpty()) DashboardSection("recentlyAddedBooks", context.getString(R.string.dashboard_section_recently_added_books), DashboardCardKind.Large, addedBooks) else null,
                if (addedSeries.isNotEmpty()) DashboardSection("recentlyAddedSeries", context.getString(R.string.dashboard_section_recently_added_series), DashboardCardKind.Small, addedSeries) else null,
                if (updatedSeries.isNotEmpty()) DashboardSection("recentlyUpdatedSeries", context.getString(R.string.dashboard_section_recently_updated_series), DashboardCardKind.Large, updatedSeries) else null,
                if (readBooks.isNotEmpty()) DashboardSection("recentlyReadBooks", context.getString(R.string.dashboard_section_recently_read_books), DashboardCardKind.Small, readBooks) else null,
            )
            val collectionDtos = listRepository.collections()
            val readListDtos = listRepository.readLists()
            val libraries = runCatching { libraryRepository.libraries() }.getOrDefault(emptyList())
            val collections = collectionDtos.map { dto ->
                ListEntry(StableIds.collection(dto.id), dto.name, dto.seriesIds.size, hueFor(dto.id), false)
            }
            val readLists = readListDtos.map { dto ->
                ListEntry(StableIds.readList(dto.id), dto.name, dto.bookIds.size, hueFor(dto.id), false)
            }
            // Continuation cards: fetch each list's books and find the first unfinished one (aligned with ReadListContinuation semantics)
            val continuations = coroutineScope {
                readListDtos.map { dto ->
                    async {
                        if (dto.bookIds.isEmpty()) return@async null
                        val books = detailRepository.readListBooks(dto.id).items.map { b -> b.toUiBook() }
                        if (books.isEmpty()) return@async null
                        val booksRead = books.count { it.progress == 1f }
                        if (booksRead >= books.size) return@async null
                        val nextIdx = books.indexOfFirst { it.progress == null || it.progress < 1f }
                            .let { if (it < 0) 0 else it }
                        val next = books[nextIdx]
                        ReadListContinuationUi(
                            readListId = StableIds.readList(dto.id),
                            readListName = dto.name,
                            nextBookIndex = nextIdx + 1,
                            nextBookTitle = next.title,
                            hue = hueFor(dto.id),
                            bookProgress = next.progress,
                            booksRead = booksRead,
                            bookCount = books.size,
                        )
                    }
                }.mapNotNull { it.await() }
            }
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
            _state.update {
                it.copy(
                    loading = false,
                    sections = derivedSections() ?: allSections,
                    continuations = continuations,
                    collections = collections,
                    readLists = readLists,
                    libraries = libraries,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: context.getString(R.string.common_load_failed), sections = null) }
        }
    }

    /** Mark a book read/unread; refresh the section contents on success (sections are driven by server progress and cannot be recomputed locally). */
    fun markBookRead(book: Book, read: Boolean) {
        val dtoId = detailRepository.bookDtoId(book.id) ?: return
        viewModelScope.launch {
            val ok = if (read) actionRepository.markBookRead(dtoId)
            else actionRepository.markBookUnread(dtoId)
            if (ok) refresh()
        }
    }

    /** Mark a series read/unread; refresh the section contents on success. */
    fun markSeriesRead(series: Series, read: Boolean) {
        val dtoId = detailRepository.seriesDtoId(series.id) ?: return
        viewModelScope.launch {
            val ok = if (read) actionRepository.markSeriesRead(dtoId)
            else actionRepository.markSeriesUnread(dtoId)
            if (ok) refresh()
        }
    }

    /** Download a whole book (silently ignored when there is no DTO mapping). */
    fun downloadBook(stableId: Int, title: String, pageCount: Int) {
        val dtoId = detailRepository.bookDtoId(stableId) ?: return
        downloadRepository.enqueueBook(dtoId, title, pageCount)
    }

    /** Download all books of the series (enqueued one by one). */
    fun downloadSeries(stableId: Int) {
        val dtoId = detailRepository.seriesDtoId(stableId) ?: return
        viewModelScope.launch {
            detailRepository.seriesBooks(dtoId).forEach { book ->
                downloadRepository.enqueueBook(
                    book.id,
                    book.metadata.title.ifBlank { book.name },
                    book.media.pagesCount,
                    libraryId = book.libraryId,
                )
            }
        }
    }

    /** Save series metadata edits; dto == null means no changes, which succeeds immediately. */
    fun saveSeriesMetadata(stableId: Int, dto: SeriesMetadataUpdateDto?, onDone: (Boolean) -> Unit) {
        if (dto == null) {
            onDone(true)
            return
        }
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.updateSeriesMetadata(dtoId, dto)
            if (ok) refresh()
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
            if (ok) refresh()
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

    /** Refresh book metadata; refresh the section contents on success. */
    fun refreshBookMetadata(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.refreshBookMetadata(dtoId)
            if (ok) refresh()
            onDone(ok)
        }
    }

    /** Delete the series files; refresh the section contents on success. */
    fun deleteSeries(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.seriesDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.deleteSeries(dtoId)
            if (ok) refresh()
            onDone(ok)
        }
    }

    /** Delete the book file; refresh the section contents on success. */
    fun deleteBook(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId)
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.deleteBook(dtoId)
            if (ok) refresh()
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

    /** Create a read list ("New…" flow of the "Add to List" picker); returns the new entry on success. */
    fun createReadListEntry(name: String, summary: String, ordered: Boolean, onDone: (ListEntry?) -> Unit) {
        viewModelScope.launch { onDone(newReadListEntry(listRepository, name, summary, ordered)) }
    }

    /** Create a collection ("New…" flow of the "Add to Collection" picker); returns the new entry on success. */
    fun createCollectionEntry(name: String, ordered: Boolean, onDone: (ListEntry?) -> Unit) {
        viewModelScope.launch { onDone(newCollectionEntry(listRepository, name, ordered)) }
    }

    private companion object {
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

private data class DashboardFeeds(
    val keepReading: List<com.kmworks.kmreader.core.api.model.BookDto>,
    val onDeck: List<com.kmworks.kmreader.core.api.model.BookDto>,
    val releasedBooks: List<com.kmworks.kmreader.core.api.model.BookDto>,
    val addedBooks: List<com.kmworks.kmreader.core.api.model.BookDto>,
    val addedSeries: List<com.kmworks.kmreader.core.api.model.SeriesDto>,
    val updatedSeries: List<com.kmworks.kmreader.core.api.model.SeriesDto>,
    val readBooks: List<com.kmworks.kmreader.core.api.model.BookDto>,
)
