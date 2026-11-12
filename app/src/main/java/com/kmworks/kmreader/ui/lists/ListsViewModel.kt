package com.kmworks.kmreader.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.data.ListRepository
import com.kmworks.kmreader.core.data.SeriesRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.SampleData.ListEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lists screen state; collections/readLists == null keeps the sample demo UI. */
data class ListsUiState(
    val hasSession: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val collections: List<ListEntry>? = null,
    val readLists: List<ListEntry>? = null,
    /** Thumbnails keyed by stable Int series/book id (see Mappers.stableId). */
    val thumbnails: Map<Int, String> = emptyMap(),
)

/** Real collections and read lists (smart lists stay sample-only for now). */
@HiltViewModel
class ListsViewModel @Inject constructor(
    private val listRepository: ListRepository,
    private val seriesRepository: SeriesRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ListsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    _state.update { it.copy(hasSession = false, loading = false, collections = null, readLists = null, thumbnails = emptyMap()) }
                } else {
                    load()
                }
            }
        }
    }

    private suspend fun load() {
        _state.update { it.copy(hasSession = true, loading = true, error = null) }
        try {
            val (collections, readLists) = coroutineScope {
                val c = async { listRepository.collections() }
                val r = async { listRepository.readLists() }
                c.await() to r.await()
            }
            val collectionEntries = collections.map { dto ->
                ListEntry(
                    id = dto.id.hashCode(),
                    title = dto.name,
                    count = dto.seriesIds.size,
                    hue = hueForKey(dto.id),
                    pinned = false,
                )
            }
            val readListEntries = readLists.map { dto ->
                ListEntry(
                    id = dto.id.hashCode(),
                    title = dto.name,
                    count = dto.bookIds.size,
                    hue = hueForKey(dto.id),
                    pinned = false,
                    modifiedLabel = "更新于 ${dto.lastModifiedDate.take(10)}",
                )
            }
            // Thumbnail for the first series of each collection (strip cover),
            // keyed by the collection entry id for easy lookup in the UI.
            val thumbs = buildMap {
                collections.forEach { dto ->
                    dto.seriesIds.firstOrNull()?.let { firstId ->
                        seriesRepository.thumbnailUrl(firstId)?.let { url -> put(dto.id.hashCode(), url) }
                    }
                }
            }
            _state.update {
                it.copy(
                    loading = false,
                    collections = collectionEntries,
                    readLists = readListEntries,
                    thumbnails = thumbs,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "加载失败", collections = null, readLists = null) }
        }
    }
}

private fun hueForKey(key: String): Float = (key.hashCode().toLong().and(0x7fffffffL) % 360L).toFloat()
