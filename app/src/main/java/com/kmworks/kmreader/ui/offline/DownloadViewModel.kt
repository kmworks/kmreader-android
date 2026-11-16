package com.kmworks.kmreader.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.download.DownloadTaskUi
import com.kmworks.kmreader.core.download.DownloadedBookUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 下载页状态：WorkManager 队列 + 文件扫描的已完成列表与存储统计。 */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    /** 库范围（对齐 LibraryScopeMenu）：null = 全部书库；过滤队列与已下载列表。 */
    private val _libraryScope = MutableStateFlow<String?>(null)
    val libraryScope: StateFlow<String?> = _libraryScope.asStateFlow()

    private val _libraries = MutableStateFlow<List<LibraryDto>>(emptyList())

    /** 服务器真实库列表（库范围菜单数据源；无会话/拉取失败为空）。 */
    val libraries: StateFlow<List<LibraryDto>> = _libraries.asStateFlow()

    fun setLibraryScope(libraryId: String?) {
        _libraryScope.value = libraryId
    }

    /** 下载中队列（进行中/失败），按库范围过滤。 */
    val tasks: StateFlow<List<DownloadTaskUi>> =
        combine(downloadRepository.downloads, libraryScope) { tasks, scope ->
            if (scope == null) tasks
            else tasks.filter { downloadRepository.libraryIdOf(it.bookDtoId) == scope }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _downloaded = MutableStateFlow<List<DownloadedBookUi>>(emptyList())

    /** 已完成书籍（文件扫描结果），按库范围过滤。 */
    val downloaded: StateFlow<List<DownloadedBookUi>> =
        combine(_downloaded, libraryScope) { books, scope ->
            if (scope == null) books else books.filter { it.libraryId == scope }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _storage = MutableStateFlow(0L to 0L)

    /** 已用/总存储字节数。 */
    val storage: StateFlow<Pair<Long, Long>> = _storage

    /** 仅 Wi-Fi 下载（真实约束 UNMETERED）。 */
    val wifiOnly: StateFlow<Boolean> = downloadRepository.wifiOnly
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 自动下载未读（SSE 触发器 + 系列详情补下共用此开关）。 */
    val autoDownloadUnread: StateFlow<Boolean> = downloadRepository.autoDownloadUnread
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setWifiOnly(enabled: Boolean) = downloadRepository.setWifiOnly(enabled)

    fun setAutoDownloadUnread(enabled: Boolean) = downloadRepository.setAutoDownloadUnread(enabled)

    init {
        refreshLocal()
        // 库范围菜单数据：服务器真实库列表
        viewModelScope.launch {
            _libraries.value = runCatching { libraryRepository.libraries() }
                .getOrDefault(emptyList())
        }
        // 队列状态变化后重扫文件（完成/取消/失败都会改变落盘状态）
        viewModelScope.launch {
            tasks.collect { refreshLocal() }
        }
    }

    /** 重扫已下载文件与存储统计。 */
    fun refreshLocal() {
        viewModelScope.launch {
            _downloaded.value = downloadRepository.downloadedBooks()
            _storage.value = downloadRepository.usedBytes() to downloadRepository.totalBytes()
        }
    }

    /** 取消下载任务并清理部分文件。 */
    fun cancel(task: DownloadTaskUi) {
        viewModelScope.launch { downloadRepository.cancelBook(task.bookDtoId) }
    }

    /** 重试失败任务（重新入队，已落盘的页会跳过）。 */
    fun retry(task: DownloadTaskUi) {
        downloadRepository.enqueueBook(task.bookDtoId, task.title, task.pageTotal)
    }

    /** 真暂停：取消任务但保留已落盘的页（恢复时跳过续传）。 */
    fun pause(task: DownloadTaskUi) {
        viewModelScope.launch { downloadRepository.pauseBook(task.bookDtoId) }
    }

    /** 恢复已暂停任务（重新入队，已存在的页自动跳过）。 */
    fun resume(task: DownloadTaskUi) {
        viewModelScope.launch { downloadRepository.resumeBook(task.bookDtoId) }
    }

    /** 删除已完成书籍并释放空间。 */
    fun deleteBook(book: DownloadedBookUi) {
        viewModelScope.launch {
            downloadRepository.deleteBook(book.bookDtoId)
            refreshLocal()
        }
    }
}
