package com.kmworks.kmreader.ui.reader

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.pdf.PdfDocumentHolder
import com.kmworks.kmreader.core.pdf.PdfRepository
import com.kmworks.kmreader.data.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PDF 阅读器状态；ready=false 时界面显示下载/加载流程或演示占位。 */
data class PdfReaderUiState(
    val hasSession: Boolean = false,
    val bookDtoId: String? = null,
    /** 下载中（含首次打开文档）。 */
    val loading: Boolean = false,
    /** 下载进度 0..1；null 表示不定进度（服务端未给 Content-Length）。 */
    val downloadProgress: Float? = null,
    val error: String? = null,
    val ready: Boolean = false,
    val pageCount: Int = 0,
    /** 初始页（1-based，来自服务端阅读进度）。 */
    val initialPage: Int = 1,
)

/**
 * PDF 阅读器：整本下载（filesDir/pdf/）→ PdfRenderer 逐页渲染。
 * 阅读进度与 DIVINA 共用 PATCH /api/v1/books/{id}/read-progress（ActionRepository）。
 */
@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    private val pdfRepository: PdfRepository,
    private val detailRepository: DetailRepository,
    private val actionRepository: ActionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState = _uiState.asStateFlow()

    private var loadedBookId: Int? = null
    private var holder: PdfDocumentHolder? = null
    private var lastReportedPage: Int = 0

    // 页面 Bitmap 小缓存：当前页 + 前后各 1 页的量级，防 OOM
    private val pageCache = LruCache<Int, Bitmap>(6)

    fun load(book: Book) {
        if (loadedBookId == book.id) return
        loadedBookId = book.id
        val dtoId = pdfRepository.bookDtoId(book.id)
        if (dtoId == null) {
            // 无真实映射：保持演示骨架
            _uiState.value = PdfReaderUiState(hasSession = false)
            return
        }
        _uiState.update {
            it.copy(
                hasSession = true,
                bookDtoId = dtoId,
                loading = true,
                downloadProgress = null,
                error = null,
                ready = false,
            )
        }
        viewModelScope.launch {
            val file = pdfRepository.downloadPdf(dtoId) { copied, total ->
                _uiState.update { s ->
                    s.copy(
                        downloadProgress = if (total > 0) {
                            (copied.toFloat() / total).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                    )
                }
            }
            if (file == null) {
                _uiState.update { it.copy(loading = false, error = "PDF 下载失败") }
                return@launch
            }
            val opened = withContext(Dispatchers.IO) {
                runCatching { PdfDocumentHolder(file) }.getOrNull()
            }
            if (opened == null || opened.pageCount <= 0) {
                opened?.close()
                _uiState.update { it.copy(loading = false, error = "PDF 打开失败") }
                return@launch
            }
            holder?.close()
            holder = opened
            pageCache.evictAll()
            lastReportedPage = 0
            // 初始页：服务端阅读进度（未完成时按页恢复；已完结从头开始）
            val progress = detailRepository.bookDetail(dtoId)?.readProgress
            val initialPage = when {
                progress == null || progress.completed -> 1
                progress.page > 0 -> progress.page.coerceAtMost(opened.pageCount)
                else -> 1
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    ready = true,
                    pageCount = opened.pageCount,
                    initialPage = initialPage,
                )
            }
        }
    }

    fun retry(book: Book) {
        loadedBookId = null
        load(book)
    }

    /** 渲染页（1-based page）；缓存命中直接返回。 */
    suspend fun renderPage(page: Int, targetWidth: Int): Bitmap? {
        val index = page - 1
        pageCache.get(index)?.let { return it }
        val rendered = withContext(Dispatchers.Default) {
            holder?.renderPage(index, targetWidth)
        } ?: return null
        pageCache.put(index, rendered)
        return rendered
    }

    /** 上报阅读进度（末页同时标记完成）；与上次重复则跳过。 */
    fun reportProgress(page: Int) {
        val dtoId = _uiState.value.bookDtoId ?: return
        val total = _uiState.value.pageCount
        if (total <= 0 || page == lastReportedPage) return
        lastReportedPage = page
        viewModelScope.launch {
            actionRepository.updateBookReadProgress(
                bookDtoId = dtoId,
                page = page,
                completed = page >= total,
            )
        }
    }

    override fun onCleared() {
        pageCache.evictAll()
        holder?.close()
        holder = null
    }
}
