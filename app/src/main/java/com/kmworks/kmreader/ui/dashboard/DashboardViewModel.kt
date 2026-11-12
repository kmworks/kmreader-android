package com.kmworks.kmreader.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.BookRepository
import com.kmworks.kmreader.core.data.SeriesRepository
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.data.toUiSeries
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.data.DashboardSection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Dashboard sections state; sections == null keeps the sample demo UI. */
data class DashboardUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val sections: List<DashboardSection<out Any>>? = null,
    /** UI id -> thumbnail URL for series and books. */
    val thumbnails: Map<Int, String> = emptyMap(),
)

/** Real dashboard data for the five sections (books + series feeds). */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val seriesRepository: SeriesRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    _state.update { it.copy(hasSession = false, loading = false, sections = null, thumbnails = emptyMap()) }
                } else {
                    load()
                }
            }
        }
    }

    private suspend fun load() {
        _state.update { it.copy(hasSession = true, loading = true, error = null) }
        try {
            val fetched = coroutineScope {
                val keepReading = async { bookRepository.keepReading() }
                val onDeck = async { bookRepository.onDeck() }
                val latestBooks = async { bookRepository.latest() }
                val latestSeries = async { seriesRepository.latest() }
                val updatedSeries = async { seriesRepository.recentlyUpdated() }
                DashboardFeeds(
                    keepReading.await(),
                    onDeck.await(),
                    latestBooks.await(),
                    latestSeries.await(),
                    updatedSeries.await(),
                )
            }
            val keepReading = fetched.keepReading.map { it.toUiBook() }
            val onDeck = fetched.onDeck.map { it.toUiBook() }
            val latestBooks = fetched.latestBooks.map { it.toUiBook() }
            val latestSeries = fetched.latestSeries.map { it.toUiSeries() }
            val updatedSeries = fetched.updatedSeries.map { it.toUiSeries() }
            val thumbs = buildMap {
                fetched.keepReading.forEach { bookRepository.thumbnailUrl(it.id)?.let { url -> put(it.id.stableIdForThumb(), url) } }
                fetched.onDeck.forEach { bookRepository.thumbnailUrl(it.id)?.let { url -> put(it.id.stableIdForThumb(), url) } }
                fetched.latestBooks.forEach { bookRepository.thumbnailUrl(it.id)?.let { url -> put(it.id.stableIdForThumb(), url) } }
                fetched.latestSeries.forEach { seriesRepository.thumbnailUrl(it.id)?.let { url -> put(it.id.stableIdForThumb(), url) } }
                fetched.updatedSeries.forEach { seriesRepository.thumbnailUrl(it.id)?.let { url -> put(it.id.stableIdForThumb(), url) } }
            }
            val sections = listOfNotNull(
                if (keepReading.isNotEmpty()) DashboardSection("keepReading", "继续阅读", DashboardCardKind.Large, keepReading) else null,
                if (onDeck.isNotEmpty()) DashboardSection("onDeck", "待读", DashboardCardKind.Large, onDeck) else null,
                if (latestBooks.isNotEmpty()) DashboardSection("recentlyAddedBooks", "最近更新书籍", DashboardCardKind.Large, latestBooks) else null,
                if (latestSeries.isNotEmpty()) DashboardSection("recentlyAddedSeries", "最近更新系列", DashboardCardKind.Large, latestSeries) else null,
                if (updatedSeries.isNotEmpty()) DashboardSection("recentlyUpdatedSeries", "最近更新系列（内容）", DashboardCardKind.Large, updatedSeries) else null,
            )
            _state.update { it.copy(loading = false, sections = sections, thumbnails = thumbs) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "加载失败", sections = null) }
        }
    }
}

private data class DashboardFeeds(
    val keepReading: List<com.kmworks.kmreader.core.api.model.BookDto>,
    val onDeck: List<com.kmworks.kmreader.core.api.model.BookDto>,
    val latestBooks: List<com.kmworks.kmreader.core.api.model.BookDto>,
    val latestSeries: List<com.kmworks.kmreader.core.api.model.SeriesDto>,
    val updatedSeries: List<com.kmworks.kmreader.core.api.model.SeriesDto>,
)

// Same Int mapping as Mappers.stableId (kept local to avoid exposing internals).
private fun String.stableIdForThumb(): Int = hashCode()
