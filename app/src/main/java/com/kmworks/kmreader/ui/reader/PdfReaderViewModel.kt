package com.kmworks.kmreader.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.pdf.PdfDocument
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.pdf.PdfAndroidxLoader
import com.kmworks.kmreader.core.pdf.PdfRepository
import com.kmworks.kmreader.data.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PdfReaderUiState(
    val hasSession: Boolean = false,
    val bookDtoId: String? = null,
    val loading: Boolean = false,
    /** 下载进度；contentLength 未知时为 null（不定进度）。 */
    val downloadProgress: Float? = null,
    val error: Boolean = false,
    val ready: Boolean = false,
    val pageCount: Int = 0,
    val initialPage: Int = 1,
    /** androidx.pdf 文档实例；渲染与文本层由 PdfView 托管。 */
    val document: PdfDocument? = null,
)

@HiltViewModel
class PdfReaderViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val pdfRepository: PdfRepository,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    private var lastBook: Book? = null
    private var document: PdfDocument? = null
    private var lastReportedPage = 0

    /** 入口：无真实映射时保持演示（hasSession=false，调用方显示占位）。 */
    fun load(book: Book) {
        if (lastBook?.id == book.id) return
        lastBook = book
        val dtoId = pdfRepository.bookDtoId(book.id)
        if (dtoId == null) {
            _uiState.update { it.copy(hasSession = false) }
            return
        }
        _uiState.update {
            it.copy(hasSession = true, bookDtoId = dtoId, loading = true, error = false, ready = false)
        }
        viewModelScope.launch {
            val file =
                pdfRepository.downloadPdf(dtoId) { copied, total ->
                    _uiState.update { s ->
                        s.copy(downloadProgress = if (total > 0) copied / total.toFloat() else null)
                    }
                }
            if (file == null) {
                _uiState.update { it.copy(loading = false, error = true) }
                return@launch
            }
            val doc = PdfAndroidxLoader(context).open(file)
            if (doc == null) {
                _uiState.update { it.copy(loading = false, error = true) }
                return@launch
            }
            document = doc
            val progress = detailRepository.bookDetail(dtoId)?.readProgress
            val initialPage =
                when {
                    progress == null || progress.completed -> 1
                    else -> progress.page.coerceIn(1, doc.pageCount)
                }
            _uiState.update {
                it.copy(
                    loading = false,
                    ready = true,
                    pageCount = doc.pageCount,
                    initialPage = initialPage,
                    document = doc,
                )
            }
        }
    }

    /** 全文搜索：返回命中页码列表（1 基，升序）。 */
    suspend fun search(query: String): List<Int> {
        val doc = document ?: return emptyList()
        return runCatching {
                val results = doc.searchDocument(query, 0 until doc.pageCount)
                (0 until results.size()).map { results.keyAt(it) + 1 }
            }
            .getOrDefault(emptyList())
    }

    /** 上报当前页（去重；末页标记完成）。 */
    fun reportProgress(page: Int) {
        val dtoId = _uiState.value.bookDtoId ?: return
        if (page == lastReportedPage) return
        lastReportedPage = page
        val completed = page >= _uiState.value.pageCount
        viewModelScope.launch { actionRepository.updateBookReadProgress(dtoId, page, completed) }
    }

    fun retry() {
        val book = lastBook ?: return
        lastBook = null
        load(book)
    }

    override fun onCleared() {
        document?.close()
        document = null
    }
}
