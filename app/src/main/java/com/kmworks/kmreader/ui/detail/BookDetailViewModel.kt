package com.kmworks.kmreader.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.SeriesMetadataUpdateDto
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.CatalogCache
import com.kmworks.kmreader.ui.lists.newCollectionEntry
import com.kmworks.kmreader.ui.lists.newReadListEntry
import com.kmworks.kmreader.core.data.ContentChange
import com.kmworks.kmreader.core.data.ContentChangeBus
import com.kmworks.kmreader.core.data.ContentChangeKind
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ImageCacheStore
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.PatchOutcome
import com.kmworks.kmreader.core.data.StableIds
import com.kmworks.kmreader.core.data.awaitBody
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.data.observeChanges
import com.kmworks.kmreader.core.data.toCreator
import com.kmworks.kmreader.core.data.toListEntry
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookDetailMeta
import com.kmworks.kmreader.data.BookMetadataLocks
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.data.SampleData.ListEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Book/one-shot detail state; the UI falls back to sample data when book/meta is null. */
data class BookDetailUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val book: Book? = null,
    val meta: BookDetailMeta? = null,
    val thumbnail: String? = null,
    /** Owning read lists (real data; mapped by Mappers.toListEntry, taps bubble up via
     *  onOpenReadList). */
    val readLists: List<ListEntry> = emptyList(),
    /** Whether the current account is an admin (gates the edit/analyze/refresh/delete menus,
     *  mirrors iOS current.isAdmin). */
    val isAdmin: Boolean = false,
    /** Series-side initial values of the oneshot combined editing (straight from the DTO; null
     *  when not oneshot or without a session). */
    val oneshotEditable: SeriesEditValues? = null,
)

/** Book detail data: resolves the DTO id from the registry, fetches the single book (with
 *  readProgress and media info). */
@HiltViewModel
class BookDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val downloadRepository: DownloadRepository,
    private val configStore: ServerConfigStore,
    private val imageCacheStore: ImageCacheStore,
    private val catalogCache: CatalogCache,
    private val contentChangeBus: ContentChangeBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    private val _readLists = MutableStateFlow<List<ListEntry>?>(null)
    val readLists: StateFlow<List<ListEntry>?> = _readLists.asStateFlow()

    private val _downloaded = MutableStateFlow<Boolean?>(null)

    /** Whether this book is fully downloaded locally (null = unknown/no mapping). */
    val downloaded: StateFlow<Boolean?> = _downloaded.asStateFlow()

    private var loadedId: Int? = null
    /** Entry the current state content belongs to (content resets when the entry changes, and is
     *  kept on a force refresh of the same entry). */
    private var contentId: Int? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    /** DTO id of the series owning the oneshot book (for combined-edit saving; null when not
     *  oneshot). */
    private var seriesDtoId: String? = null
    private var contentJob: kotlinx.coroutines.Job? = null

    init {
        observeContentChanges()
    }

    /**
     * Writes issued here or on another screen, and server-side changes forwarded from SSE. This page shows a
     * single book, so only that book's read state and flushed progress are patched in place; a server-side change
     * to it (ids without values) re-reads that one row, and any other entity's change is ignored.
     */
    private fun observeContentChanges() {
        contentJob = viewModelScope.launch {
            contentChangeBus.observeChanges(
                patch = { patchContentChange(it) },
                reload = { change ->
                    if (change is ContentChange.Books || change is ContentChange.Unknown) reloadDetail()
                },
            )
        }
    }

    private fun patchContentChange(change: ContentChange): PatchOutcome {
        if (change.kind == ContentChangeKind.Touched) {
            // Nothing to patch with: fetch the row if this page shows it, ignore it otherwise.
            val stableId = loadedId ?: return PatchOutcome.Patched
            val touches = stableId in detailRepository.stableIdsOf(change)
            return if (touches && change is ContentChange.Books) PatchOutcome.Declined else PatchOutcome.Patched
        }
        return if (patchInPlace(change)) PatchOutcome.Patched else PatchOutcome.Declined
    }

    private fun patchInPlace(change: ContentChange): Boolean {
        val stableId = loadedId ?: return true
        if (stableId !in detailRepository.stableIdsOf(change)) return true
        return when (change.kind) {
            ContentChangeKind.ReadStateChanged -> {
                val read = change.read ?: return true
                _uiState.update { it.copy(book = it.book?.copy(progress = if (read) 1f else null)) }
                true
            }
            // A reader flushed its page on exit: the book on this page keeps its own fraction.
            ContentChangeKind.ProgressChanged -> {
                val progress = change.progress ?: return true
                _uiState.update { it.copy(book = it.book?.copy(progress = progress)) }
                true
            }
            // This book's own deletion closes the page, so there is nothing left to re-read.
            ContentChangeKind.Deleted -> change is ContentChange.Books
            // Renamed or re-analyzed: re-read the detail.
            else -> false
        }
    }

    private fun reloadDetail() {
        val stableId = loadedId ?: return
        loadedId = null
        load(stableId)
    }

    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val clients = sessionManager.clients.value
        val dtoId = detailRepository.bookDtoId(stableId)
        loadJob?.cancel()
        if (contentId != stableId) {
            _uiState.value = BookDetailUiState(hasSession = clients != null)
            _readLists.value = null
            _downloaded.value = null
        }
        if (clients == null || dtoId == null) {
            _uiState.update { it.copy(hasSession = clients != null, loading = false) }
            return
        }
        _uiState.update { it.copy(hasSession = true, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val isAdmin = configStore.config.first()?.isAdmin == true ||
                    runCatching {
                        clients.currentUser.getCurrentUser().awaitBody()
                            ?.roles
                            ?.contains("ADMIN") == true
                    }.getOrDefault(false)
                val dto = detailRepository.bookDetail(dtoId)
                if (dto == null) {
                    _uiState.update { it.copy(loading = false, isAdmin = isAdmin) }
                    return@launch
                }
                val meta = BookDetailMeta(
                    releaseDate = dto.metadata.releaseDate?.take(10)
                        ?: dto.created.take(10),
                    sizeLabel = formatSize(dto.sizeBytes),
                    format = formatLabel(dto.media.mediaType),
                    filePath = dto.url,
                    summary = dto.metadata.summary.ifBlank {
                        "${dto.seriesTitle} · ${dto.metadata.title}"
                    },
                    authors = dto.metadata.authors.map { it.toCreator() },
                    tags = dto.metadata.tags.toList(),
                    number = dto.metadata.number,
                    numberSort = dto.metadata.numberSort,
                    isbn = dto.metadata.isbn.ifBlank { null },
                    links = dto.metadata.links.map { it.label to it.url },
                    notableEntities = dto.metadata.notableEntities.map {
                        NotableEntity(name = it.name, kind = it.kind.trim().lowercase())
                    },
                    locks = BookMetadataLocks(
                        titleLock = dto.metadata.titleLock,
                        numberLock = dto.metadata.numberLock,
                        numberSortLock = dto.metadata.numberSortLock,
                        releaseDateLock = dto.metadata.releaseDateLock,
                        isbnLock = dto.metadata.isbnLock,
                        summaryLock = dto.metadata.summaryLock,
                        authorsLock = dto.metadata.authorsLock,
                        tagsLock = dto.metadata.tagsLock,
                        linksLock = dto.metadata.linksLock,
                        notableEntitiesLock = dto.metadata.notableEntitiesLock,
                    ),
                )
                val memberships = detailRepository.bookReadLists(dtoId)
                    .map { it.toListEntry() }
                // oneshot: fetch the owning series metadata as the series-side initial values of
                // the combined editing (failure does not block the detail body)
                seriesDtoId = null
                val oneshotEditable = if (dto.oneshot) {
                    seriesDtoId = dto.seriesId
                    detailRepository.seriesDetail(dto.seriesId)?.metadata?.let { metadata ->
                        SeriesEditValues(
                            title = metadata.title,
                            titleSort = metadata.titleSort,
                            summary = metadata.summary,
                            status = metadata.status,
                            readingDirection = metadata.readingDirection,
                            language = metadata.language,
                            publisher = metadata.publisher,
                            ageRating = metadata.ageRating?.toString().orEmpty(),
                            totalBookCount = metadata.totalBookCount?.toString().orEmpty(),
                            genres = metadata.genres.toList(),
                            tags = metadata.tags.toList(),
                            links = metadata.links.map { link -> link.label to link.url },
                            alternateTitles = metadata.alternateTitles.map { link -> link.label to link.title },
                            sharingLabels = metadata.sharingLabels.toList(),
                            locks = SeriesEditLocks(
                                titleLock = metadata.titleLock,
                                titleSortLock = metadata.titleSortLock,
                                summaryLock = metadata.summaryLock,
                                statusLock = metadata.statusLock,
                                readingDirectionLock = metadata.readingDirectionLock,
                                languageLock = metadata.languageLock,
                                publisherLock = metadata.publisherLock,
                                ageRatingLock = metadata.ageRatingLock,
                                totalBookCountLock = metadata.totalBookCountLock,
                                genresLock = metadata.genresLock,
                                tagsLock = metadata.tagsLock,
                                linksLock = metadata.linksLock,
                                alternateTitlesLock = metadata.alternateTitlesLock,
                                sharingLabelsLock = metadata.sharingLabelsLock,
                            ),
                        )
                    }
                } else {
                    null
                }
                _uiState.update {
                    it.copy(
                        loading = false,
                        book = dto.toUiBook(),
                        meta = meta,
                        thumbnail = detailRepository.bookThumbnailUrl(dto.id),
                        readLists = memberships,
                        isAdmin = isAdmin,
                        oneshotEditable = oneshotEditable,
                    )
                }
                contentId = stableId
                _downloaded.value = downloadRepository.isBookDownloaded(dto.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: context.getString(R.string.common_load_failed))
                }
            }
        }
    }

    /** Load the read lists on demand (for the "Add to Read List" picker). */
    fun ensureReadListsLoaded() {
        if (_readLists.value != null) return
        viewModelScope.launch {
            _readLists.value = listRepository.readLists().map { dto ->
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

    /** Toggle the read state and update local data immediately; false means no DTO mapping
     *  (demo data). */
    fun setRead(read: Boolean): Boolean {
        val dtoId = loadedId?.let { detailRepository.bookDtoId(it) } ?: return false
        viewModelScope.launch {
            val ok = if (read) actionRepository.markBookRead(dtoId)
            else actionRepository.markBookUnread(dtoId)
            if (ok) {
                _uiState.update {
                    it.copy(book = it.book?.copy(progress = if (read) 1f else null))
                }
            }
        }
        return true
    }

    /** Add the current book to a read list; false means no DTO mapping. */
    fun addToReadList(entry: ListEntry): Boolean {
        val bookDtoId = loadedId?.let { detailRepository.bookDtoId(it) } ?: return false
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return false
        viewModelScope.launch {
            actionRepository.addBookToReadList(readListDtoId, bookDtoId)
        }
        return true
    }

    /** Download the current book; false means no session/no DTO mapping (demo data). */
    fun downloadBook(): Boolean {
        if (sessionManager.clients.value == null) return false
        val dtoId = loadedId?.let { detailRepository.bookDtoId(it) } ?: return false
        val book = _uiState.value.book ?: return false
        downloadRepository.enqueueBook(dtoId, book.title, book.pageCount)
        return true
    }

    /** Save the metadata edit (submits only changed fields + their locks, mirrors iOS
     *  BookMetadataUpdate.toAPIDict). */
    fun saveMetadata(draft: BookMetadataDraft, onDone: (Boolean) -> Unit) {
        val dtoId = loadedId?.let { detailRepository.bookDtoId(it) }
        val state = _uiState.value
        val baseTitle = state.book?.title
        if (dtoId == null || baseTitle == null) {
            onDone(false)
            return
        }
        val body = draft.toUpdateDto(baseTitle, state.meta)
        if (body == null) {
            onDone(true)
            return
        }
        viewModelScope.launch {
            val ok = actionRepository.updateBookMetadata(dtoId, body)
            onDone(ok)
        }
    }

    /**
     * Oneshot combined save (mirrors iOS OneshotEditSheet.saveChanges):
     * save the book metadata first, then the series metadata; when both are null (no change) it
     * succeeds immediately; a failed book save skips the series save; after full success the
     * detail (including the series-side initial values) is reloaded.
     */
    fun saveOneshotMetadata(
        draft: BookMetadataDraft,
        seriesUpdate: SeriesMetadataUpdateDto?,
        onDone: (Boolean) -> Unit,
    ) {
        val dtoId = loadedId?.let { detailRepository.bookDtoId(it) }
        val state = _uiState.value
        val baseTitle = state.book?.title
        if (dtoId == null || baseTitle == null) {
            onDone(false)
            return
        }
        val bookBody = draft.toUpdateDto(baseTitle, state.meta)
        if (bookBody == null && seriesUpdate == null) {
            onDone(true)
            return
        }
        val sid = seriesDtoId
        viewModelScope.launch {
            var ok = true
            if (bookBody != null) {
                ok = actionRepository.updateBookMetadata(dtoId, bookBody)
            }
            if (ok && seriesUpdate != null) {
                ok = sid != null && actionRepository.updateSeriesMetadata(sid, seriesUpdate)
            }
            onDone(ok)
        }
    }

    /** Re-analyze the book (POST /books/{id}/analyze, admin; goes through ActionRepository so screens hear about it). */
    fun analyze(onDone: (Boolean) -> Unit) {
        val dtoId = loadedId?.let { detailRepository.bookDtoId(it) }
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch { onDone(actionRepository.analyzeBook(dtoId)) }
    }

    /** Refresh the metadata (POST /books/{id}/metadata/refresh, admin; the write signal re-reads this page). */
    fun refreshMetadata(onDone: (Boolean) -> Unit) {
        val dtoId = loadedId?.let { detailRepository.bookDtoId(it) }
        if (dtoId == null) {
            onDone(false)
            return
        }
        viewModelScope.launch { onDone(actionRepository.refreshBookMetadata(dtoId)) }
    }

    /** Delete the book file (DELETE /books/{id}/file, admin; goes through ActionRepository so screens hear about it). */
    fun deleteBook(onDone: (Boolean) -> Unit) {
        val dtoId = loadedId?.let { detailRepository.bookDtoId(it) }
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

    /** Komga web URL (for sharing, mirrors iOS KomgaWebLinkBuilder.book). */
    fun bookWebUrl(): String? {
        val clients = sessionManager.clients.value ?: return null
        val dtoId = loadedId?.let { detailRepository.bookDtoId(it) } ?: return null
        return "${clients.baseUrl}/book/$dtoId"
    }

    /** Create a read list (the "New…" flow of the "Add to List" picker); returns the new entry on
     *  success. */
    fun createReadListEntry(name: String, summary: String, ordered: Boolean, onDone: (ListEntry?) -> Unit) {
        viewModelScope.launch { onDone(newReadListEntry(listRepository, name, summary, ordered)) }
    }

    /** Create a collection (the "New…" flow of the "Add to Collection" picker); returns the new
     *  entry on success. */
    fun createCollectionEntry(name: String, ordered: Boolean, onDone: (ListEntry?) -> Unit) {
        viewModelScope.launch { onDone(newCollectionEntry(listRepository, name, ordered)) }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
    else -> String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
}

/** Komga mediaType (application/x-cbz, application/epub+zip, application/pdf …) → short format
 *  name. */
private fun formatLabel(mediaType: String): String {
    val t = mediaType.lowercase(Locale.US)
    return when {
        "epub" in t -> "EPUB"
        "pdf" in t -> "PDF"
        "cbr" in t || "rar" in t -> "CBR"
        "cb7" in t || "7z" in t -> "CB7"
        "cbt" in t || "tar" in t -> "CBT"
        else -> "CBZ"
    }
}
