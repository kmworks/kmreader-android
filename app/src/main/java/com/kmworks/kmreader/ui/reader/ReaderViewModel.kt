package com.kmworks.kmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ReaderRepository
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.ReaderPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 页面列表三态 + 假数据回退（无会话/样例书）。 */
enum class ReaderPagesStatus { Loading, Ready, Error, Sample }

data class ReaderUiState(
    val status: ReaderPagesStatus = ReaderPagesStatus.Loading,
    /** 真实页面（宽高来自 PageDto）；Sample 状态为空，由 UI 回退假数据。 */
    val pages: List<ReaderPage> = emptyList(),
    /** 当前书的 Komga id（样例书为 null，图片 URL 随之不可用）。 */
    val bookDtoId: String? = null,
)

/** DIVINA 阅读器：按 Book 拉取真实页列表（宽图检测的宽高来源）。 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val readerRepository: ReaderRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var loadedBookId: Int? = null

    /** 加载页面列表；重复调用同书幂等。 */
    fun load(book: Book) {
        if (loadedBookId == book.id) return
        loadedBookId = book.id
        val dtoId = readerRepository.bookDtoId(book.id)
        if (dtoId == null) {
            _uiState.value = ReaderUiState(status = ReaderPagesStatus.Sample)
            return
        }
        _uiState.value = ReaderUiState(status = ReaderPagesStatus.Loading, bookDtoId = dtoId)
        viewModelScope.launch {
            val dtos = readerRepository.pages(dtoId)
            _uiState.value = when {
                dtos.isEmpty() -> ReaderUiState(status = ReaderPagesStatus.Error, bookDtoId = dtoId)
                else -> ReaderUiState(
                    status = ReaderPagesStatus.Ready,
                    pages = dtos.map { dto ->
                        ReaderPage(
                            id = dto.number,
                            number = dto.number,
                            // PageDto 宽高可空：未知按竖版处理（不影响配对逻辑）
                            width = dto.width ?: 0,
                            height = dto.height ?: 0,
                        )
                    },
                    bookDtoId = dtoId,
                )
            }
        }
    }

    fun retry(book: Book) {
        loadedBookId = null
        load(book)
    }

    /** 整页图片 URL（无真实会话时为 null，UI 显示占位）。 */
    fun pageUrl(pageNumber: Int): String? =
        _uiState.value.bookDtoId?.let { readerRepository.pageUrl(it, pageNumber) }

    /** 页缩略图 URL（缩略图条用）。 */
    fun pageThumbnailUrl(pageNumber: Int): String? =
        _uiState.value.bookDtoId?.let { readerRepository.pageThumbnailUrl(it, pageNumber) }
}
