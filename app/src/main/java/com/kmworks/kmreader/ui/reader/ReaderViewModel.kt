package com.kmworks.kmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ActionRepository
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ReaderRepository
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.settings.DivinaSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.ReaderPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 页面列表三态（无假数据回退；失败走错误态重试）。 */
enum class ReaderPagesStatus { Loading, Ready, Error }

data class ReaderUiState(
    val status: ReaderPagesStatus = ReaderPagesStatus.Loading,
    /** 真实页面（宽高来自 PageDto）。 */
    val pages: List<ReaderPage> = emptyList(),
    /** 当前书的 Komga id（未映射时为 null，页面不可用）。 */
    val bookDtoId: String? = null,
    /**
     * 恢复阅读的服务器页号（对齐原作 DivinaReaderView.loadBook：
     * incognito 或已完成 → null 从第 0 页起；否则 readProgress.page）。
     */
    val initialPage: Int? = null,
    /** 会话开始时该书处于「在读」状态（对齐原作 wasInProgress：gate 的最小步距放宽到 1）。 */
    val wasInProgress: Boolean = false,
    /** 系列元数据方向（raw：LEFT_TO_RIGHT/RIGHT_TO_LEFT/VERTICAL/WEBTOON，对齐 SeriesDto.metadata.readingDirection）。 */
    val seriesDirection: String? = null,
)

/**
 * DIVINA 阅读器：按 Book 拉取真实页列表、进度恢复语义、单页独显集合、
 * 进度上报（对齐原作 ReaderProgressRecordingGate 的门控语义）。
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val readerRepository: ReaderRepository,
    private val actionRepository: ActionRepository,
    private val detailRepository: DetailRepository,
    private val settingsStore: DivinaSettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** 单页独显集合（服务器页号，按书持久化；对齐原作 soloPagesByBookId）。 */
    private val _soloPages = MutableStateFlow<Set<Int>>(emptySet())
    val soloPages: StateFlow<Set<Int>> = _soloPages.asStateFlow()

    private var loadedBookId: Int? = null

    /** 隐私阅读（incognito）：跳过进度上报。 */
    var incognito: Boolean = false

    /** 加载页面列表 + 进度恢复 + 系列方向；重复调用同书幂等。 */
    fun load(book: Book) {
        if (loadedBookId == book.id) return
        loadedBookId = book.id
        val dtoId = readerRepository.bookDtoId(book.id)
        if (dtoId == null) {
            _uiState.value = ReaderUiState(status = ReaderPagesStatus.Error)
            return
        }
        _uiState.value = ReaderUiState(status = ReaderPagesStatus.Loading, bookDtoId = dtoId)
        viewModelScope.launch {
            // 独显集合（本地持久化，失败不阻塞）
            runCatching { settingsStore.soloPages(dtoId).first() }
                .onSuccess { _soloPages.value = it }

            val progress = runCatching { detailRepository.bookDetail(dtoId)?.readProgress }.getOrNull()
            val seriesDirection = runCatching {
                detailRepository.bookDetail(dtoId)
                    ?.seriesId
                    ?.let { detailRepository.seriesDetail(it) }
                    ?.metadata
                    ?.readingDirection
            }.getOrNull()

            val dtos = readerRepository.pages(dtoId)
            _uiState.value = when {
                dtos.isEmpty() -> ReaderUiState(status = ReaderPagesStatus.Error, bookDtoId = dtoId)
                else -> {
                    // 对齐原作 loadBook：incognito 或已完成 → 从第 0 页起
                    val resumePage = if (incognito || progress?.completed == true) null else progress?.page
                    ReaderUiState(
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
                        initialPage = resumePage,
                        wasInProgress = progress != null && progress.completed != true,
                        seriesDirection = seriesDirection,
                    )
                }
            }
        }
    }

    fun retry(book: Book) {
        loadedBookId = null
        load(book)
    }

    // ---- 单页独显（对齐原作 toggleSoloPage：仅竖版页可独显，按书持久化）----
    fun toggleSoloPage(pageNumber: Int) {
        val dtoId = _uiState.value.bookDtoId ?: return
        val next = _soloPages.value.toMutableSet().apply {
            if (!remove(pageNumber)) add(pageNumber)
        }.toSet()
        _soloPages.value = next
        viewModelScope.launch {
            runCatching { settingsStore.setSoloPages(dtoId, next) }
                .onFailure { AppLog.w("ReaderVM", "soloPages persist failed: ${it.message}") }
        }
    }

    /** 整页图片 URL（无真实会话时为 null，UI 显示占位）。 */
    fun pageUrl(pageNumber: Int): String? =
        _uiState.value.bookDtoId?.let { readerRepository.pageUrl(it, pageNumber) }

    /** 页缩略图 URL（缩略图条用）。 */
    fun pageThumbnailUrl(pageNumber: Int): String? =
        _uiState.value.bookDtoId?.let { readerRepository.pageThumbnailUrl(it, pageNumber) }

    // ---- 进度上报（对齐原作 ReaderProgressRecordingGate）----
    /** 会话起始页（首个观察到的 settled 页）。 */
    private var sessionStartPage: Int? = null
    private var hasRecordedProgress = false
    private var lastReportedPage: Int = 0

    /** 上报阅读进度（settled 单元最后一页）。incognito 跳过；过 gate 才去重上报。 */
    fun reportProgress(page: Int) {
        if (incognito) return
        val state = _uiState.value
        val dtoId = state.bookDtoId ?: return
        val pageCount = state.pages.size
        if (sessionStartPage == null) sessionStartPage = page

        val completed = pageCount > 0 && page >= pageCount
        if (!completed && !hasRecordedProgress) {
            // 对齐原作 gate：requiredDistance = min(threshold, pageCount-1)，wasInProgress 时放宽到 1
            val threshold = min(ReaderProgressRecordingGate.THRESHOLD, maxOf(pageCount - 1, 0))
            val requiredDistance = if (state.wasInProgress) min(threshold, 1) else threshold
            val distance = abs(page - (sessionStartPage ?: page))
            if (distance < requiredDistance) return
        }
        hasRecordedProgress = true
        if (page == lastReportedPage) return
        lastReportedPage = page
        viewModelScope.launch {
            runCatching {
                actionRepository.updateBookReadProgress(
                    bookDtoId = dtoId,
                    page = page,
                    completed = completed,
                )
            }.onFailure { AppLog.w("ReaderVM", "progress PUT failed page=$page: ${it.message}") }
        }
    }

    /** 退出时 flush（对齐原作 flushProgress()=enqueue(from:current,to:nil)，同样过 gate）。 */
    fun flushProgress(currentPage: Int) {
        reportProgress(currentPage)
    }
}

/** 进度门控常量（对齐原作 ReaderProgressRecordingGate + AppConfig.progressRecordingThreshold）。 */
object ReaderProgressRecordingGate {
    const val THRESHOLD = 3
}
