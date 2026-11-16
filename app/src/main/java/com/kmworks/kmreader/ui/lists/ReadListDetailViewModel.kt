package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.SampleData.ListEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 阅读列表详情状态；books 为 null 时界面回退假数据。 */
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
    /** 阅读列表封面（服务端缩略图端点）。 */
    val heroThumbnail: String? = null,
)

/** 阅读列表详情数据：反查 DTO，书籍经 GET api/v1/readlists/{id}/books 按序拉取（含真实 readProgress）。 */
@HiltViewModel
class ReadListDetailViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReadListDetailUiState())
    val uiState: StateFlow<ReadListDetailUiState> = _uiState.asStateFlow()

    private val _readLists = MutableStateFlow<List<ListEntry>?>(null)
    val readLists: StateFlow<List<ListEntry>?> = _readLists.asStateFlow()

    private var loadedId: Int? = null
    private var currentDtoId: String? = null
    private var nextPage = 0

    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val clients = sessionManager.clients.value
        val dtoId = detailRepository.readListDtoId(stableId)
        if (clients == null || dtoId == null) {
            _uiState.update { it.copy(hasSession = clients != null, loading = false) }
            return
        }
        currentDtoId = dtoId
        nextPage = 0
        _uiState.update { it.copy(hasSession = true, loading = true, error = null, books = null, hasMore = false) }
        viewModelScope.launch {
            try {
                val readList = detailRepository.readListDetail(dtoId)
                if (readList == null) {
                    _uiState.update { it.copy(loading = false) }
                    return@launch
                }
                val page = detailRepository.readListBooks(dtoId, page = 0, size = PAGE_SIZE)
                nextPage = 1
                _uiState.update {
                    it.copy(
                        loading = false,
                        name = readList.name,
                        summary = readList.summary,
                        ordered = readList.ordered,
                        createdDate = readList.createdDate,
                        lastModifiedDate = readList.lastModifiedDate,
                        books = page.items.map { dto -> dto.toUiBook() },
                        hasMore = page.hasMore,
                        heroThumbnail = detailRepository.readListThumbnailUrl(dtoId),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val dtoId = currentDtoId ?: return
        if (state.loading || state.loadingMore || !state.hasMore) return
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = detailRepository.readListBooks(dtoId, page = nextPage, size = PAGE_SIZE)
                nextPage += 1
                _uiState.update { state ->
                    val seen = state.books.orEmpty().map { it.id }.toMutableSet()
                    val appended = page.items.map { dto -> dto.toUiBook() }.filter { seen.add(it.id) }
                    state.copy(
                        loadingMore = false,
                        books = state.books.orEmpty() + appended,
                        hasMore = page.hasMore && appended.isNotEmpty(),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loadingMore = false, error = e.message ?: "加载失败") }
            }
        }
    }

    /** 从列表移除所选书籍（stableId 集合）；返回 false 表示无 DTO 映射（演示数据，界面本地处理）。 */
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

    /** 批量标记已读/未读并即时更新本地状态；返回 false 表示无 DTO 映射（演示数据）。 */
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

    /** 批量下载所选书籍；返回 false 表示任何一本都无法映射到服务端 DTO。 */
    fun downloadBooks(stableIds: Set<Int>, onDone: (Boolean) -> Unit) {
        val books = _uiState.value.books.orEmpty().filter { it.id in stableIds }
        if (books.size != stableIds.size) return onDone(false)
        viewModelScope.launch {
            val ok = books.all { book ->
                val dtoId = detailRepository.bookDtoId(book.id)
                if (dtoId == null) {
                    false
                } else {
                    downloadRepository.enqueueBook(dtoId, book.title, book.pageCount)
                    true
                }
            }
            onDone(ok)
        }
    }

    /** 批量加入另一个阅读列表。 */
    fun addBooksToReadList(entry: ListEntry, stableIds: Set<Int>, onDone: (Boolean) -> Unit) {
        val readListDtoId = detailRepository.readListDtoId(entry.id)
        val bookDtoIds = stableIds.mapNotNull { detailRepository.bookDtoId(it) }
        if (readListDtoId == null || bookDtoIds.size != stableIds.size) return onDone(false)
        viewModelScope.launch {
            onDone(actionRepository.addBooksToReadList(readListDtoId, bookDtoIds))
        }
    }

    /** 反查书籍所属系列 stableId，用于 iOS BooksListView 的“打开所属系列”。 */
    fun seriesStableIdForBook(bookStableId: Int, onResult: (Int?) -> Unit) {
        viewModelScope.launch {
            onResult(detailRepository.seriesStableIdForBook(bookStableId))
        }
    }

    /** 按需加载阅读列表（「加入阅读列表」选择器用）。 */
    fun ensureReadListsLoaded() {
        if (_readLists.value != null) return
        viewModelScope.launch {
            _readLists.value = listRepository.readLists().map { dto ->
                ListEntry(
                    id = dto.id.hashCode(),
                    title = dto.name,
                    count = dto.bookIds.size,
                    hue = hueFor(dto.id),
                    pinned = false,
                )
            }
        }
    }

    /** 把书籍加入另一个阅读列表；返回 false 表示无 DTO 映射。 */
    fun addBookToReadList(entry: ListEntry, bookStableId: Int): Boolean {
        val bookDtoId = detailRepository.bookDtoId(bookStableId) ?: return false
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return false
        viewModelScope.launch {
            actionRepository.addBookToReadList(readListDtoId, bookDtoId)
        }
        return true
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
