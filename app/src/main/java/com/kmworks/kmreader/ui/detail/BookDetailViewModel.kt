package com.kmworks.kmreader.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.hueFor
import com.kmworks.kmreader.core.data.toCreator
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BookDetailMeta
import com.kmworks.kmreader.data.SampleData.ListEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 书籍/单发详情状态；book/meta 为 null 时界面回退假数据。 */
data class BookDetailUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val book: Book? = null,
    val meta: BookDetailMeta? = null,
    val thumbnail: String? = null,
)

/** 书籍详情数据：按注册表反查 DTO id，拉单本书（含 readProgress 与媒体信息）。 */
@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val listRepository: ListRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    private val _readLists = MutableStateFlow<List<ListEntry>?>(null)
    val readLists: StateFlow<List<ListEntry>?> = _readLists.asStateFlow()

    private var loadedId: Int? = null

    fun load(stableId: Int) {
        if (loadedId == stableId) return
        loadedId = stableId
        val clients = sessionManager.clients.value
        val dtoId = detailRepository.bookDtoId(stableId)
        if (clients == null || dtoId == null) {
            _uiState.update { it.copy(hasSession = clients != null, loading = false) }
            return
        }
        _uiState.update { it.copy(hasSession = true, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val dto = detailRepository.bookDetail(dtoId)
                if (dto == null) {
                    _uiState.update { it.copy(loading = false) }
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
                )
                _uiState.update {
                    it.copy(
                        loading = false,
                        book = dto.toUiBook(),
                        meta = meta,
                        thumbnail = detailRepository.bookThumbnailUrl(dto.id),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
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

    /** 切换已读状态并即时更新本地数据；返回 false 表示无 DTO 映射（演示数据）。 */
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

    /** 把当前书籍加入阅读列表；返回 false 表示无 DTO 映射。 */
    fun addToReadList(entry: ListEntry): Boolean {
        val bookDtoId = loadedId?.let { detailRepository.bookDtoId(it) } ?: return false
        val readListDtoId = detailRepository.readListDtoId(entry.id) ?: return false
        viewModelScope.launch {
            actionRepository.addBookToReadList(readListDtoId, bookDtoId)
        }
        return true
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
    else -> String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
}

/** Komga mediaType（application/x-cbz、application/epub+zip、application/pdf …）→ 短格式名。 */
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
