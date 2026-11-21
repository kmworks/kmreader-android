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

/** Download screen state: WorkManager queue + file-scanned completed list and storage stats. */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    /** Library scope (aligned with LibraryScopeMenu): null = all libraries; filters the queue and the downloaded list. */
    private val _libraryScope = MutableStateFlow<String?>(null)
    val libraryScope: StateFlow<String?> = _libraryScope.asStateFlow()

    private val _libraries = MutableStateFlow<List<LibraryDto>>(emptyList())

    /** Real server library list (data source for the library scope menu; empty without a session or when the fetch fails). */
    val libraries: StateFlow<List<LibraryDto>> = _libraries.asStateFlow()

    fun setLibraryScope(libraryId: String?) {
        _libraryScope.value = libraryId
    }

    /** In-progress queue (active/failed), filtered by library scope. */
    val tasks: StateFlow<List<DownloadTaskUi>> =
        combine(downloadRepository.downloads, libraryScope) { tasks, scope ->
            if (scope == null) tasks
            else tasks.filter { downloadRepository.libraryIdOf(it.bookDtoId) == scope }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _downloaded = MutableStateFlow<List<DownloadedBookUi>>(emptyList())

    /** Completed books (file scan result), filtered by library scope. */
    val downloaded: StateFlow<List<DownloadedBookUi>> =
        combine(_downloaded, libraryScope) { books, scope ->
            if (scope == null) books else books.filter { it.libraryId == scope }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _storage = MutableStateFlow(0L to 0L)

    /** Used/total storage bytes. */
    val storage: StateFlow<Pair<Long, Long>> = _storage

    /** Wi-Fi only downloads (real constraint UNMETERED). */
    val wifiOnly: StateFlow<Boolean> = downloadRepository.wifiOnly
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Auto-download unread (shared by the SSE trigger and the series-detail supplemental download). */
    val autoDownloadUnread: StateFlow<Boolean> = downloadRepository.autoDownloadUnread
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setWifiOnly(enabled: Boolean) = downloadRepository.setWifiOnly(enabled)

    fun setAutoDownloadUnread(enabled: Boolean) = downloadRepository.setAutoDownloadUnread(enabled)

    init {
        refreshLocal()
        // Library scope menu data: real server library list
        viewModelScope.launch {
            _libraries.value = runCatching { libraryRepository.libraries() }
                .getOrDefault(emptyList())
        }
        // Rescan files after queue state changes (complete/cancel/fail all change what lands on disk)
        viewModelScope.launch {
            tasks.collect { refreshLocal() }
        }
    }

    /** Rescan downloaded files and storage stats. */
    fun refreshLocal() {
        viewModelScope.launch {
            _downloaded.value = downloadRepository.downloadedBooks()
            _storage.value = downloadRepository.usedBytes() to downloadRepository.totalBytes()
        }
    }

    /** Cancel the download task and clean up partial files. */
    fun cancel(task: DownloadTaskUi) {
        viewModelScope.launch { downloadRepository.cancelBook(task.bookDtoId) }
    }

    /** Retry a failed task using the kind/format recorded at enqueue time (existing content is skipped). */
    fun retry(task: DownloadTaskUi) {
        downloadRepository.retryBook(task.bookDtoId)
    }

    /** Real pause: cancel the task but keep pages already on disk (resume skips what is there). */
    fun pause(task: DownloadTaskUi) {
        viewModelScope.launch { downloadRepository.pauseBook(task.bookDtoId) }
    }

    /** Resume a paused task (re-enqueue; pages that already exist are skipped automatically). */
    fun resume(task: DownloadTaskUi) {
        viewModelScope.launch { downloadRepository.resumeBook(task.bookDtoId) }
    }

    /** Delete a completed book and free up space. */
    fun deleteBook(book: DownloadedBookUi) {
        viewModelScope.launch {
            downloadRepository.deleteBook(book.bookDtoId)
            refreshLocal()
        }
    }
}
