package com.kmworks.kmreader.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.Paged
import com.kmworks.kmreader.core.data.SearchRepository
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.resolveLibraryScopeIds
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Section detail ("More") state; items are Book/Series, paged with bottom load-more. */
data class DashboardSectionDetailUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val items: List<Any> = emptyList(),
    val hasMore: Boolean = false,
    /** Library-scope menu data: the real library list from the server. */
    val libraries: List<LibraryDto> = emptyList(),
    /** Library scope (pinned/all/library:{id}, aligned with iOS LibraryBrowseScope); session-level choice, defaults to pinned. */
    val libraryScope: String = AppSettingsStore.SCOPE_PINNED,
    /** Pinned library ids (the pinned scope resolves to these as the libraryIds filter). */
    val pinnedIds: Set<String> = emptySet(),
)

/**
 * Re-fetches a dashboard section feed with page/size so the "More" page can load more,
 * aligned with DashboardSectionDetailView (pageSize 50).
 */
@HiltViewModel
class DashboardSectionDetailViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val libraryRepository: LibraryRepository,
    private val serverProfileStore: ServerProfileStore,
    private val appSettings: AppSettingsStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardSectionDetailUiState())
    val state = _state.asStateFlow()

    private var sectionId: String? = null
    private var page = 0
    private var loadingMore = false
    /** Generation counter: after a scope switch or rebind, discard results of in-flight stale queries. */
    private var generation = 0

    init {
        // Pinned-set change: re-query when the current scope is pinned (aligned with the iOS libraryIds-driven refresh)
        viewModelScope.launch {
            appSettings.pinnedLibraryIds.collect { pinned ->
                val changed = pinned != _state.value.pinnedIds
                _state.update { it.copy(pinnedIds = pinned) }
                if (changed &&
                    sectionId != null &&
                    _state.value.libraryScope == AppSettingsStore.SCOPE_PINNED
                ) {
                    generation++
                    page = 0
                    loadingMore = false
                    _state.update {
                        it.copy(items = emptyList(), hasMore = false, error = null, loading = true)
                    }
                    loadPage(0)
                }
            }
        }
    }

    /** Bind to a section; only the first call per section id triggers a fresh load. */
    fun start(section: DashboardSection<out Any>) {
        if (sectionId == section.id) return
        sectionId = section.id
        page = 0
        loadingMore = false
        generation++
        _state.value = DashboardSectionDetailUiState(loading = true)
        viewModelScope.launch { loadPage(0) }
        viewModelScope.launch {
            val libraries = runCatching { libraryRepository.libraries() }.getOrDefault(emptyList())
            _state.update { it.copy(libraries = libraries) }
        }
    }

    /** Library scope switch (pinned/all/library:{id}, LibraryScopeMenu): reset paging and re-fetch with libraryIds. */
    fun setScope(scope: String) {
        if (_state.value.libraryScope == scope) return
        generation++
        page = 0
        loadingMore = false
        _state.update {
            it.copy(
                libraryScope = scope,
                items = emptyList(),
                hasMore = false,
                error = null,
                loading = true,
                loadingMore = false,
            )
        }
        viewModelScope.launch { loadPage(0) }
    }

    /** Overwrite the pinned set (saved from the pin-editing sheet, persisted in AppSettingsStore). */
    fun setPinnedLibraries(ids: Set<String>) {
        viewModelScope.launch { appSettings.setPinnedLibraryIds(ids) }
    }

    /** Load the next page when scrolled to the bottom (concurrency guard + early return when no more). */
    fun loadMore() {
        if (loadingMore || !_state.value.hasMore) return
        loadingMore = true
        viewModelScope.launch { loadPage(page + 1) }
    }

    private suspend fun loadPage(target: Int) {
        val id = sectionId ?: return
        val gen = generation
        _state.update { it.copy(loadingMore = true) }
        try {
            val result = fetch(id, target, PAGE_SIZE)
            if (gen != generation) return // generation advanced; discard the stale result
            if (result == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = context.getString(R.string.dashboard_error_pagination_unsupported),
                    )
                }
                return
            }
            page = target
            _state.update { st ->
                val existing = if (target == 0) emptyList() else st.items
                val appended = result.items.filterNot { item ->
                    existing.any { current -> sameId(current, item) }
                }
                st.copy(
                    loading = false,
                    loadingMore = false,
                    error = null,
                    items = existing + appended,
                    hasMore = result.hasMore,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen != generation) return // generation advanced; do not overwrite the newer query's state
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = e.message ?: context.getString(R.string.common_load_failed),
                )
            }
        } finally {
            if (gen == generation) loadingMore = false
        }
    }

    private suspend fun fetch(sectionId: String, page: Int, size: Int): Paged<Any>? {
        // Read the pinned set straight from DataStore: avoids a startup race where start()/load runs
        // before the first pinnedIds emission and mis-resolves the pinned scope to null (= all libraries).
        val pinned = appSettings.pinnedLibraryIds.first()
        val libraryIds = resolveLibraryScopeIds(_state.value.libraryScope, pinned)
        return when (sectionId) {
            "keepReading" -> searchRepository.keepReadingPage(
                page, size, libraryIds,
                readDateAfter = serverProfileStore.recentReadingCutoff(),
            ).eraseType()
            "onDeck" -> searchRepository.onDeckPage(page, size, libraryIds).eraseType()
            "recentlyReleasedBooks" -> searchRepository.recentlyReleasedBooksPage(page, size, libraryIds).eraseType()
            "recentlyAddedBooks" -> searchRepository.latestBooksPage(page, size, libraryIds).eraseType()
            "recentlyAddedSeries" -> searchRepository.latestSeriesPage(page, size, libraryIds).eraseType()
            "recentlyUpdatedSeries" -> searchRepository.recentlyUpdatedSeriesPage(page, size, libraryIds).eraseType()
            "recentlyReadBooks" -> searchRepository.recentlyReadBooksPage(page, size, libraryIds).eraseType()
            else -> null
        }
    }

    private fun <T> Paged<T>.eraseType(): Paged<Any> = Paged(items.toList().map { it as Any }, hasMore)

    private fun sameId(a: Any, b: Any): Boolean = when {
        a is Book && b is Book -> a.id == b.id
        a is Series && b is Series -> a.id == b.id
        else -> false
    }

    private companion object {
        /** pageSize of the original DashboardSectionDetailView. */
        const val PAGE_SIZE = 50
    }
}
