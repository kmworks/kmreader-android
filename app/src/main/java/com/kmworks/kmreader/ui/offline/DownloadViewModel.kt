package com.kmworks.kmreader.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.download.DownloadRepository
import com.kmworks.kmreader.core.download.DownloadTaskUi
import com.kmworks.kmreader.core.download.DownloadedBookUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 下载页状态：WorkManager 队列 + 文件扫描的已完成列表与存储统计。 */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    /** 下载中队列（进行中/失败）。 */
    val tasks: StateFlow<List<DownloadTaskUi>> = downloadRepository.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _downloaded = MutableStateFlow<List<DownloadedBookUi>>(emptyList())

    /** 已完成书籍（文件扫描结果）。 */
    val downloaded: StateFlow<List<DownloadedBookUi>> = _downloaded

    private val _storage = MutableStateFlow(0L to 0L)

    /** 已用/总存储字节数。 */
    val storage: StateFlow<Pair<Long, Long>> = _storage

    init {
        refreshLocal()
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

    /** 删除已完成书籍并释放空间。 */
    fun deleteBook(book: DownloadedBookUi) {
        viewModelScope.launch {
            downloadRepository.deleteBook(book.bookDtoId)
            refreshLocal()
        }
    }
}
