package com.kmworks.kmreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.BookRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.SeriesRepository
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.sse.SseEventKind
import com.kmworks.kmreader.core.sse.SseService
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.Series
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Library grid state; series/books == null keeps the sample demo UI. */
data class LibraryUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val series: List<Series>? = null,
    val books: List<Book>? = null,
    val thumbnails: Map<Int, String> = emptyMap(),
    val libraryCount: Int = 0,
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
)

/** Real library content (paged series and book lists). */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
    private val libraryRepository: LibraryRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val detailRepository: DetailRepository,
    private val sseService: SseService,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    _state.update { it.copy(hasSession = false, loading = false, series = null, books = null, thumbnails = emptyMap()) }
                } else {
                    load()
                }
            }
        }
        // SSE 内容变化事件聚合 3s 后刷新
        viewModelScope.launch {
            sseService.events
                .filter { it.kind in REFRESH_KINDS }
                .debounce(3_000)
                .collect { refresh() }
        }
    }

    /** 重新拉取列表内容（动作成功或 SSE 事件后的内容同步）。 */
    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.update { it.copy(hasSession = true, loading = true, error = null) }
        try {
            val (seriesDtos, bookDtos) = coroutineScope {
                val series = async { seriesRepository.list() }
                val books = async { bookRepository.list() }
                series.await() to books.await()
            }
            val libraryCount = libraryRepository.libraries().size
            val (collectionDtos, readListDtos) = coroutineScope {
                val c = async { listRepository.collections() }
                val r = async { listRepository.readLists() }
                c.await() to r.await()
            }
            val series = seriesDtos.map { it.toUiSeries() }
            val books = bookDtos.map { it.toUiBook() }
            val thumbs = buildMap {
                seriesDtos.forEach { seriesRepository.thumbnailUrl(it.id)?.let { url -> put(it.id.hashCode(), url) } }
                bookDtos.forEach { bookRepository.thumbnailUrl(it.id)?.let { url -> put(it.id.hashCode(), url) } }
            }
            val collections = collectionDtos.map { dto ->
                ListEntry(dto.id.hashCode(), dto.name, dto.seriesIds.size, hueFor(dto.id), false)
            }
            val readLists = readListDtos.map { dto ->
                ListEntry(dto.id.hashCode(), dto.name, dto.bookIds.size, hueFor(dto.id), false)
            }
            _state.update {
                it.copy(
                    loading = false,
                    series = series,
                    books = books,
                    thumbnails = thumbs,
                    libraryCount = libraryCount,
                    collections = collections,
                    readLists = readLists,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "加载失败", series = null, books = null) }
        }
    }

    /** 标记系列已读/未读并即时更新本地状态；无 DTO 映射时静默忽略（演示数据）。 */
    fun markSeriesRead(series: Series, read: Boolean) {
        val dtoId = detailRepository.seriesDtoId(series.id) ?: return
        viewModelScope.launch {
            val ok = if (read) actionRepository.markSeriesRead(dtoId)
            else actionRepository.markSeriesUnread(dtoId)
            if (ok) {
                _state.update { st ->
                    st.copy(series = st.series?.map {
                        if (it.id == series.id) {
                            it.copy(
                                progress = if (read) 1f else null,
                                unreadCount = if (read) 0 else it.bookCount,
                            )
                        } else {
                            it
                        }
                    })
                }
            }
        }
    }

    /** 标记书籍已读/未读并即时更新本地状态；无 DTO 映射时静默忽略。 */
    fun markBookRead(book: Book, read: Boolean) {
        val dtoId = detailRepository.bookDtoId(book.id) ?: return
        viewModelScope.launch {
            val ok = if (read) actionRepository.markBookRead(dtoId)
            else actionRepository.markBookUnread(dtoId)
            if (ok) {
                _state.update { st ->
                    st.copy(books = st.books?.map {
                        if (it.id == book.id) it.copy(progress = if (read) 1f else null) else it
                    })
                }
            }
        }
    }

    /** 把系列加入收藏。 */
    fun addSeriesToCollection(entry: ListEntry, series: Series) {
        val seriesDtoId = detailRepository.seriesDtoId(series.id) ?: return
        val collectionDtoId = detailRepository.collectionDtoId(entry.id) ?: return
        viewModelScope.launch {
            actionRepository.addSeriesToCollection(collectionDtoId, seriesDtoId)
        }
    }

    /** 把系列的全部书籍加入阅读列表（Komga 阅读列表只收书籍）。 */
    fun addSeriesToReadList(entry: ListEntry, series: Series) {
        val seriesDtoId = detailRepository.seriesDtoId(series.id) ?: return
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return
        viewModelScope.launch {
            detailRepository.seriesBooks(seriesDtoId).forEach { book ->
                actionRepository.addBookToReadList(readListDtoId, book.id)
            }
        }
    }

    /** 把书籍加入阅读列表。 */
    fun addBookToReadList(entry: ListEntry, book: Book) {
        val bookDtoId = detailRepository.bookDtoId(book.id) ?: return
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return
        viewModelScope.launch {
            actionRepository.addBookToReadList(readListDtoId, bookDtoId)
        }
    }

    private companion object {
        /** 触发书库刷新的事件类别。 */
        val REFRESH_KINDS = setOf(
            SseEventKind.Book,
            SseEventKind.Series,
            SseEventKind.ReadProgress,
            SseEventKind.Library,
        )
    }
}
