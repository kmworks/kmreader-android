package com.kmworks.kmreader.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.toUiBook
import com.kmworks.kmreader.core.komf.KomfJobState
import com.kmworks.kmreader.core.komf.KomfRepository
import com.kmworks.kmreader.core.komf.KomfSearchResultDto
import com.kmworks.kmreader.data.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** komf state for the series long-press menu (dashboard/library), mirroring the detail page's komf fields. */
data class KomfSeriesUiState(
    /** The series DTO id/library id/books are resolved; search can run. */
    val ready: Boolean = false,
    val books: List<Book> = emptyList(),
    val searching: Boolean = false,
    val searchFailed: Boolean = false,
    val results: List<KomfSearchResultDto> = emptyList(),
    val jobRunning: Boolean = false,
)

/**
 * komf actions for the series context menu. The context menu lives outside the series detail route,
 * so it needs its own small owner for the identify sheet and the match/reset jobs; the detail page
 * keeps its own komf state in [SeriesDetailViewModel].
 */
@HiltViewModel
class KomfSeriesViewModel @Inject constructor(
    private val komfRepository: KomfRepository,
    private val detailRepository: DetailRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(KomfSeriesUiState())
    val uiState: StateFlow<KomfSeriesUiState> = _uiState.asStateFlow()

    /** One-shot status messages (submitted/completed/failed), shown as a snackbar by the host screen. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var seriesDtoId: String? = null
    private var libraryId: String? = null
    private var searchJob: Job? = null

    /** Refresh hook for identify/match jobs started from the context menu. */
    private var jobSettled: (() -> Unit)? = null

    /** Resolves the series ids/books before showing the identify sheet (the sheet waits for [KomfSeriesUiState.ready]). */
    fun prepare(stableId: Int, onJobSettled: () -> Unit = {}) {
        jobSettled = onJobSettled
        searchJob?.cancel()
        seriesDtoId = null
        libraryId = null
        _uiState.value = KomfSeriesUiState()
        val dtoId = detailRepository.seriesDtoId(stableId) ?: return
        seriesDtoId = dtoId
        viewModelScope.launch {
            val dto = detailRepository.seriesDetail(dtoId) ?: return@launch
            val books = detailRepository.seriesBooks(dtoId).map { it.toUiBook() }
            libraryId = dto.libraryId
            _uiState.update { it.copy(ready = true, books = books) }
        }
    }

    /** komf search (300ms debounce, same semantics as the detail page). */
    fun komfSearch(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(searching = false, searchFailed = false, results = emptyList()) }
            return
        }
        val dtoId = seriesDtoId ?: return
        val libraryId = libraryId ?: return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            _uiState.update { it.copy(searching = true, searchFailed = false) }
            val results = komfRepository.search(trimmed, libraryId, dtoId)
            _uiState.update {
                if (results == null) {
                    it.copy(searching = false, searchFailed = true, results = emptyList())
                } else {
                    it.copy(searching = false, results = results)
                }
            }
        }
    }

    /** Submits identify; on success the sheet closes and the job keeps polling in the background. */
    fun komfIdentify(
        provider: String,
        providerSeriesId: String,
        onSubmitted: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val dtoId = seriesDtoId ?: return onFailed()
        viewModelScope.launch {
            val jobId = komfRepository.identify(libraryId, dtoId, provider, providerSeriesId)
            if (jobId != null) {
                onSubmitted()
                _events.tryEmit(context.getString(R.string.komf_event_identify_submitted))
                trackJob(jobId)
            } else {
                onFailed()
            }
        }
    }

    /** Submits a komf match; [onSettled] runs when the job reaches a terminal state (or submission fails). */
    fun komfMatch(stableId: Int, onSettled: () -> Unit) {
        viewModelScope.launch {
            val ids = resolve(stableId)
            if (ids == null) {
                _events.tryEmit(context.getString(R.string.komf_event_match_failed))
                return@launch onSettled()
            }
            val jobId = komfRepository.match(ids.second, ids.first)
            if (jobId == null) {
                _events.tryEmit(context.getString(R.string.komf_event_match_failed))
                return@launch onSettled()
            }
            _events.tryEmit(context.getString(R.string.komf_event_match_submitted))
            trackJob(jobId, onSettled)
        }
    }

    /** Resets komf for the series (not a job: the result is the outcome). */
    fun komfReset(stableId: Int, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ids = resolve(stableId)
            if (ids == null) {
                _events.tryEmit(context.getString(R.string.komf_event_reset_failed))
                return@launch onDone(false)
            }
            val ok = komfRepository.reset(ids.second, ids.first)
            _events.tryEmit(
                context.getString(if (ok) R.string.komf_event_reset_done else R.string.komf_event_reset_failed),
            )
            onDone(ok)
        }
    }

    /** dtoId to libraryId for the series (a detail read resolves the library the komf calls need). */
    private suspend fun resolve(stableId: Int): Pair<String, String>? {
        val dtoId = detailRepository.seriesDtoId(stableId) ?: return null
        val libraryId = detailRepository.seriesDetail(dtoId)?.libraryId ?: return null
        return dtoId to libraryId
    }

    private fun trackJob(jobId: String, onSettled: (() -> Unit)? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(jobRunning = true) }
            komfRepository.trackJob(jobId).collect { state ->
                when (state) {
                    KomfJobState.Running -> Unit
                    KomfJobState.Completed ->
                        _events.tryEmit(context.getString(R.string.komf_event_job_completed))
                    is KomfJobState.Failed -> {
                        val message = state.message?.takeIf { it.isNotBlank() }?.let {
                            context.getString(R.string.komf_event_job_failed_with_reason, it)
                        } ?: context.getString(R.string.komf_event_job_failed)
                        _events.tryEmit(message)
                    }
                    KomfJobState.TimedOut ->
                        _events.tryEmit(context.getString(R.string.komf_event_job_tracking_stopped))
                }
            }
            _uiState.update { it.copy(jobRunning = false) }
            (onSettled ?: jobSettled)?.invoke()
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L
    }
}
