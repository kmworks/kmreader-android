package com.kmworks.kmreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.BookRepository
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.SeriesRepository
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Library grid state; series/books == null keeps the sample demo UI. */
data class LibraryUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val series: List<Series>? = null,
    val books: List<Book>? = null,
    val thumbnails: Map<Int, String> = emptyMap(),
    val libraryCount: Int = 0,
)

/** Real library content (paged series and book lists). */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
    private val libraryRepository: LibraryRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    _state.update { it.copy(hasSession = false, loading = false, series = null, books = null, thumbnails = emptyMap()) }
                } else {
                    load()
                }
            }
        }
    }

    private suspend fun load() {
        _state.update { it.copy(hasSession = true, loading = true, error = null) }
        try {
            val (seriesDtos, bookDtos) = coroutineScope {
                val series = async { seriesRepository.list() }
                val books = async { bookRepository.list() }
                series.await() to books.await()
            }
            val libraryCount = libraryRepository.libraries().size
            val series = seriesDtos.map { it.toUiSeries() }
            val books = bookDtos.map { it.toUiBook() }
            val thumbs = buildMap {
                seriesDtos.forEach { seriesRepository.thumbnailUrl(it.id)?.let { url -> put(it.id.hashCode(), url) } }
                bookDtos.forEach { bookRepository.thumbnailUrl(it.id)?.let { url -> put(it.id.hashCode(), url) } }
            }
            _state.update { it.copy(loading = false, series = series, books = books, thumbnails = thumbs, libraryCount = libraryCount) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "加载失败", series = null, books = null) }
        }
    }
}
