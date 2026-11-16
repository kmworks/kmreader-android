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
import kotlin.math.abs

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

    // 进度上报门控（对齐原作 PdfReaderViewModel 的 ReaderProgressRecordingGate）：
    // 未读过的书不移动 3 页以上不上报；在读过的书移动 1 页即上报；完成/已记录总放行。
    private var sessionStartPage: Int? = null
    private var wasInProgress: Boolean = false
    private var hasRecorded: Boolean = false

    /** 隐私阅读（incognito）：跳过进度上报。 */
    var incognito: Boolean = false

    /** 入口：无真实映射时进入错误态（无演示回退）。 */
    fun load(book: Book) {
        if (lastBook?.id == book.id) return
        lastBook = book
        val dtoId = pdfRepository.bookDtoId(book.id)
        if (dtoId == null) {
            _uiState.update { it.copy(hasSession = false, loading = false, error = true) }
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
            wasInProgress = progress != null && !progress.completed
            sessionStartPage = null
            hasRecorded = false
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

    /** 上报当前页（去重；末页标记完成）。incognito 跳过。 */
    fun reportProgress(page: Int) {
        if (incognito) return
        val dtoId = _uiState.value.bookDtoId ?: return
        if (page == lastReportedPage) return
        val pageCount = _uiState.value.pageCount
        if (pageCount <= 0) return
        if (sessionStartPage == null) sessionStartPage = page
        val completed = page >= pageCount
        if (!admitsRecording(page, completed, pageCount)) return
        lastReportedPage = page
        viewModelScope.launch {
            val ok = actionRepository.updateBookReadProgress(dtoId, page, completed)
            if (ok) hasRecorded = true
        }
    }

    /** 对齐原作 ReaderProgressRecordingGate：threshold=3，clamp 到 pageCount-1，在读过的书 req=1。 */
    private fun admitsRecording(page: Int, completed: Boolean, pageCount: Int): Boolean {
        if (completed || hasRecorded) return true
        val start = sessionStartPage ?: return false
        val threshold = minOf(ReaderProgressRecordingGate.THRESHOLD, pageCount - 1)
        val requiredDistance = if (wasInProgress) minOf(threshold, 1) else threshold
        if (requiredDistance <= 0) return true
        return abs(page - start) >= requiredDistance
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
