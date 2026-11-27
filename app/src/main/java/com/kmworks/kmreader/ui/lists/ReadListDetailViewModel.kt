package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.ContentChange
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.ContentRowRefresher
import com.kmworks.kmreader.core.data.ContentChangeKind
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.Paged
import com.kmworks.kmreader.core.data.PagedFeed
import com.kmworks.kmreader.core.data.PatchOutcome
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.data.observeChanges
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerAdminRepository
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookDetailMeta
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.ui.detail.BookMetadataDraft
import com.kmworks.kmreader.ui.detail.toBookDetailMeta
import com.kmworks.kmreader.ui.detail.toUpdateDto
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Read list detail state; the UI falls back to sample data while books is null. */
data class ReadListDetailUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val name: String? = null,
    val summary: String? = null,
    val ordered: Boolean? = null,
    val createdDate: String? = null,
    val lastModifiedDate: String? = null,
    val books: List<Book>? = null,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** Read list cover (server thumbnail endpoint). */
    val heroThumbnail: String? = null,
    /** Whether the current account is an admin (gates metadata edit/analyze/refresh and add-to-list actions). */
    val isAdmin: Boolean = false,
)

/** Read list detail data: resolves the DTO; books are fetched in order via
 *  GET api/v1/readlists/{id}/books (with real readProgress). */
@HiltViewModel
class ReadListDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val downloadRepository: DownloadRepository,
    private val contentChangeBus: ContentChangeBus,
    private val contentRowRefresher: ContentRowRefresher,
    private val serverAdminRepository: ServerAdminRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReadListDetailUiState())
    val uiState: StateFlow<ReadListDetailUiState> = _uiState.asStateFlow()

    private val _readLists = MutableStateFlow<List<ListEntry>?>(null)
    val readLists: StateFlow<List<ListEntry>?> = _readLists.asStateFlow()

    private var loadedId: Int? = null
    /** Entry the current state content belongs to (content resets when the entry changes;
     *  a forced refresh of the same entry keeps it). */
    private var contentId: Int? = null
    private var currentDtoId: String? = null
    /**
     * Book feed. The DTO is resolved on every call, so the fetch reads [currentDtoId] instead of capturing it:
     * a reload of the same read list then keeps the pages already on screen (see [PagedFeed]).
     */
    private val booksFeed = PagedFeed(
        pageSize = PAGE_SIZE,
        identity = { book: Book -> book.id },
        fetch = { page, size ->
            currentDtoId?.let { dtoId ->
                detailRepository.readListBooks(dtoId, page = page, size = size)?.let { result ->
                    Paged(result.items.map { dto -> dto.toUiBook() }, result.hasMore, result.totalElements)
                }
            }
        },
    )
    /** Bumped on every whole-list load: a load-more that lands on top of one must not overwrite it. */
    private var generation = 0
    private var loadJob: kotlinx.coroutines.Job? = null
    private var contentJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            // Follow the active session: the admin role gates the book card menus.
            serverAdminRepository.adminStatus.collect { isAdmin ->
                _uiState.update { it.copy(isAdmin = isAdmin) }
            }
        }
        observeContentChanges()
    }

    /**
     * Writes issued here or on another screen, plus server-side changes forwarded from SSE. Read state of the books
     * on screen is patched in place; a server-side change to a shown book re-reads just that row (the payload
     * carries ids only); everything else re-reads the list.
     */
    private fun observeContentChanges() {
        contentJob = viewModelScope.launch {
            contentChangeBus.observeChanges(
                patch = { patchContentChange(it) },
                reload = { change ->
                    if (change.kind == ContentChangeKind.Touched && change is ContentChange.Books) {
                        applyTouchedBooks(change)
                    } else {
                        reloadBooks()
                    }
                },
            )
        }
    }

    /** Fetch the touched book rows on screen and swap them in; ids not shown cost nothing. */
    private suspend fun applyTouchedBooks(change: ContentChange) {
        if (!showsAny(detailRepository.stableIdsOf(change))) return
        val rows = contentRowRefresher.books(change.dtoIds)
        if (rows.isEmpty()) return
        _uiState.update { state ->
            state.copy(books = state.books.orEmpty().map { row -> rows[row.id] ?: row })
        }
    }

    private fun patchContentChange(change: ContentChange): PatchOutcome {
        // Ids without values: a shown book costs one row fetch, this list's own change a reload,
        // anything else is not this page's business.
        if (change.kind == ContentChangeKind.Touched) {
            return when {
                change is ContentChange.Books ->
                    if (showsAny(detailRepository.stableIdsOf(change))) {
                        PatchOutcome.Declined
                    } else {
                        PatchOutcome.Patched
                    }

                change is ContentChange.ReadLists -> PatchOutcome.Declined
                else -> PatchOutcome.Patched
            }
        }
        return if (patchInPlace(change)) PatchOutcome.Patched else PatchOutcome.Declined
    }

    private fun patchInPlace(change: ContentChange): Boolean = when (change) {
        is ContentChange.Books -> {
            val ids = detailRepository.stableIdsOf(change)
            when (change.kind) {
                ContentChangeKind.ReadStateChanged -> patchBooksRead(ids, change.read ?: return true)
                ContentChangeKind.ProgressChanged -> patchBooksProgress(ids, change.progress ?: return true)
                // A member was renamed, moved or deleted: only the server knows the new rows.
                else -> !showsAny(ids)
            }
        }
        is ContentChange.ReadLists -> {
            val dtoId = currentDtoId
            when {
                // Another read list changed: not this page's business.
                dtoId == null || dtoId !in change.dtoIds -> true
                // The page renders only name/summary/ordered, and [save] has already put the
                // written values in the state: re-reading would blank the book list for nothing.
                change.kind == ContentChangeKind.MetadataChanged -> true
                // Membership, or this list's deletion (which closes the page): the server owns
                // the new rows.
                else -> false
            }
        }
        // Collections and smart lists are not this page's subject.
        else -> true
    }

    private fun showsAny(ids: Set<Int>): Boolean =
        ids.isNotEmpty() && _uiState.value.books.orEmpty().any { it.id in ids }

    private fun patchBooksRead(ids: Set<Int>, read: Boolean): Boolean {
        if (!showsAny(ids)) return true
        _uiState.update { state ->
            state.copy(
                books = state.books.orEmpty().map { book ->
                    if (book.id in ids) {
                        book.copy(progress = if (read) 1f else null)
                    } else {
                        book
                    }
                },
            )
        }
        return true
    }

    private fun patchBooksProgress(ids: Set<Int>, progress: Float): Boolean {
        if (!showsAny(ids)) return true
        _uiState.update { state ->
            state.copy(
                books = state.books.orEmpty().map { book ->
                    if (book.id in ids) book.copy(progress = progress) else book
                },
            )
        }
        return true
    }

    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val gen = ++generation
        val clients = sessionManager.clients.value
        val dtoId = detailRepository.readListDtoId(stableId)
        loadJob?.cancel()
        if (contentId != stableId) {
            _uiState.value = ReadListDetailUiState(hasSession = clients != null)
            _readLists.value = null
        }
        if (clients == null || dtoId == null) {
            _uiState.update { it.copy(hasSession = clients != null, loading = false) }
            return
        }
        currentDtoId = dtoId
        // A reload of the same read list keeps the rows and pages it already loaded; the loading flag alone must
        // not blank the list (that flashed the whole list on every server-side change).
        val sameContent = contentId == stableId
        val keepPages = if (sameContent) booksFeed.page else 0
        val current = if (sameContent) _uiState.value.books.orEmpty() else emptyList()
        _uiState.update { it.copy(hasSession = true, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val readList = detailRepository.readListDetail(dtoId)
                if (readList == null) {
                    _uiState.update { it.copy(loading = false) }
                    return@launch
                }
                val books = booksFeed.reload(current, keepPages, newQuery = !sameContent)
                if (gen != generation) return@launch
                _uiState.update {
                    it.copy(
                        loading = false,
                        name = readList.name,
                        summary = readList.summary,
                        ordered = readList.ordered,
                        createdDate = readList.createdDate,
                        lastModifiedDate = readList.lastModifiedDate,
                        books = books,
                        hasMore = booksFeed.hasMore,
                        heroThumbnail = detailRepository.readListThumbnailUrl(dtoId),
                    )
                }
                contentId = stableId
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: context.getString(R.string.common_load_failed))
                }
            }
        }
    }

    fun loadMore() {
        val gen = generation
        val state = _uiState.value
        if (currentDtoId == null) return
        if (state.loading || state.loadingMore || !state.hasMore) return
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val books = booksFeed.append(state.books.orEmpty())
                if (gen != generation) { // a newer load owns the window; only clear this page's spinner
                    _uiState.update { it.copy(loadingMore = false) }
                    return@launch
                }
                _uiState.update { st ->
                    // A whole-list reload started meanwhile and owns the window: this page must not overwrite it
                    // (the reload's deeper page count already covers it), so only the spinner is cleared.
                    if (st.loading) {
                        st.copy(loadingMore = false)
                    } else {
                        st.copy(loadingMore = false, books = books, hasMore = booksFeed.hasMore)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loadingMore = false, error = e.message ?: context.getString(R.string.common_load_failed))
                }
            }
        }
    }

    /** Remove the selected books (stableId set) from the list; returns false when there is
     *  no DTO mapping (demo data, handled locally by the UI). */
    fun removeBooks(stableIds: Set<Int>): Boolean {
        val readListDtoId = loadedId?.let { detailRepository.readListDtoId(it) } ?: return false
        val bookDtoIds = stableIds.mapNotNull { detailRepository.bookDtoId(it) }
        if (bookDtoIds.isEmpty()) return false
        viewModelScope.launch {
            if (actionRepository.removeBooksFromReadList(readListDtoId, bookDtoIds)) {
                _uiState.update { st ->
                    st.copy(books = st.books?.filterNot { it.id in stableIds })
                }
            }
        }
        return true
    }

    /** Mark read/unread in batch and update local state immediately; returns false when there
     *  is no DTO mapping (demo data). */
    fun markBooksRead(stableIds: Set<Int>, read: Boolean): Boolean {
        val bookDtoIds = stableIds.mapNotNull { detailRepository.bookDtoId(it) }
        if (bookDtoIds.isEmpty()) return false
        viewModelScope.launch {
            val results = bookDtoIds.map { dtoId ->
                if (read) actionRepository.markBookRead(dtoId)
                else actionRepository.markBookUnread(dtoId)
            }
            if (results.any { it }) {
                _uiState.update { st ->
                    st.copy(books = st.books?.map {
                        if (it.id in stableIds) it.copy(progress = if (read) 1f else null) else it
                    })
                }
            }
        }
        return true
    }

    /** Download the selected books in batch; returns false when any book cannot be mapped to a server DTO. */
    fun downloadBooks(stableIds: Set<Int>, onDone: (Boolean) -> Unit) {
        val books = _uiState.value.books.orEmpty().filter { it.id in stableIds }
        if (books.size != stableIds.size) return onDone(false)
        viewModelScope.launch {
            val ok = books.all { book ->
                val dtoId = detailRepository.bookDtoId(book.id)
                if (dtoId == null) {
                    false
                } else {
                    downloadRepository.enqueueBook(
                        dtoId,
                        book.title,
                        book.pageCount,
                        mediaType = book.mediaType,
                        epubDivinaCompatible = book.epubDivinaCompatible,
                        seriesDtoId = book.seriesDtoId,
                    )
                    true
                }
            }
            onDone(ok)
        }
    }

    /** Add the selected books to another read list in batch. */
    fun addBooksToReadList(entry: ListEntry, stableIds: Set<Int>, onDone: (Boolean) -> Unit) {
        val readListDtoId = detailRepository.readListDtoId(entry.id)
        val bookDtoIds = stableIds.mapNotNull { detailRepository.bookDtoId(it) }
        if (readListDtoId == null || bookDtoIds.size != stableIds.size) return onDone(false)
        viewModelScope.launch {
            onDone(actionRepository.addBooksToReadList(readListDtoId, bookDtoIds))
        }
    }

    /** Resolve the stableId of the series a book belongs to, for the iOS BooksListView "Open Series". */
    fun seriesStableIdForBook(bookStableId: Int, onResult: (Int?) -> Unit) {
        viewModelScope.launch {
            onResult(detailRepository.seriesStableIdForBook(bookStableId))
        }
    }

    /** Load read lists on demand (for the "Add to Read List" picker). */
    fun ensureReadListsLoaded() {
        if (_readLists.value != null) return
        viewModelScope.launch {
            val dtos = runCatching { listRepository.readLists() }.getOrElse { return@launch }
            _readLists.value = dtos.map { dto ->
                ListEntry(
                    id = StableIds.readList(dto.id),
                    title = dto.name,
                    count = dto.bookIds.size,
                    hue = hueFor(dto.id),
                    pinned = false,
                )
            }
        }
    }

    /** Add a book to another read list; returns false when there is no DTO mapping. */
    fun addBookToReadList(entry: ListEntry, bookStableId: Int): Boolean {
        val bookDtoId = detailRepository.bookDtoId(bookStableId) ?: return false
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return false
        viewModelScope.launch {
            actionRepository.addBookToReadList(readListDtoId, bookDtoId)
        }
        return true
    }

    /**
     * Rename/reorder the read list (PATCH api/v1/readlists/{id}). The new values are applied to the
     * state here so the content-change broadcast can be patched instead of forcing a reload.
     */
    fun save(name: String, summary: String, ordered: Boolean, onDone: (Boolean) -> Unit) {
        val dtoId = currentDtoId ?: return onDone(false)
        viewModelScope.launch {
            val ok = listRepository.updateReadList(dtoId, name, summary, ordered)
            if (ok) _uiState.update { it.copy(name = name, summary = summary, ordered = ordered) }
            onDone(ok)
        }
    }

    /** Delete the read list; the caller closes the page when the write succeeds. */
    fun delete(onDone: (Boolean) -> Unit) {
        val dtoId = currentDtoId ?: return onDone(false)
        viewModelScope.launch { onDone(listRepository.deleteReadList(dtoId)) }
    }

    /** Create a read list from the picker's selection (its books, or the books of its series); reports the new entry. */
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

    /** Metadata baseline for editing a book from the read list rows (the row only carries the card fields). */
    suspend fun bookMetadata(stableId: Int): BookDetailMeta? =
        detailRepository.bookDtoId(stableId)?.let { dtoId ->
            detailRepository.bookDetail(dtoId)?.toBookDetailMeta()
        }

    /** Save book metadata (BookEditSheet flow, mirrors BookDetailViewModel.saveMetadata):
     *  succeeds immediately when nothing changed; the write signal re-reads the list. */
    fun saveBookMetadata(stableId: Int, baseTitle: String, draft: BookMetadataDraft, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId) ?: return onDone(false)
        val body = draft.toUpdateDto(baseTitle, null) ?: return onDone(true)
        viewModelScope.launch {
            onDone(actionRepository.updateBookMetadata(dtoId, body))
        }
    }

    /** Re-analyze a book (thin wrapper: dtoId lookup + ActionRepository). */
    fun analyzeBook(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId) ?: return onDone(false)
        viewModelScope.launch { onDone(actionRepository.analyzeBook(dtoId)) }
    }

    /** Refresh book metadata (thin wrapper: dtoId lookup + ActionRepository). */
    fun refreshBookMetadata(stableId: Int, onDone: (Boolean) -> Unit) {
        val dtoId = detailRepository.bookDtoId(stableId) ?: return onDone(false)
        viewModelScope.launch { onDone(actionRepository.refreshBookMetadata(dtoId)) }
    }

    /** Web share link for a book ({baseUrl}/book/{dtoId}). */
    fun bookWebUrl(stableId: Int): String? {
        val dtoId = detailRepository.bookDtoId(stableId) ?: return null
        val clients = sessionManager.clients.value ?: return null
        return "${clients.baseUrl}/book/$dtoId"
    }

    /** Force-reload the current list (load deduplicates the same entry, so clear loadedId first). */
    private fun reloadBooks() {
        val stableId = loadedId ?: return
        loadedId = null
        load(stableId)
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
