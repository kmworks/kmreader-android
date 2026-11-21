package com.kmworks.kmreader.ui.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.ui.components.AppModalDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.tracker.TrackerRemoteStateDto
import com.kmworks.kmreader.core.tracker.TrackerRepository
import com.kmworks.kmreader.core.tracker.TrackerSearchItemDto
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.CoverArt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Link editor (aligned with TrackerLinkEditSheet):
 * unselected = search (prefilled with the series title) + multi-title capsules + suggestions from series links + search results;
 * selected = Tracker Entry (result row + #id + Open/Change) → Progress Mapping (mode + chapter offset) →
 * Remote Progress (status/score/chapter/volume/start-finish dates; only changed, nonempty fields are submitted);
 * controls = Unlink (when already linked) + Save (disabled until an entry is selected).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackerLinkEditSheet(
    series: Series,
    provider: TrackerProvider,
    existing: TrackerLink?,
    alternateTitles: List<String> = emptyList(),
    onUnlink: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: TrackerLinkEditViewModel = hiltViewModel(),
) {
    var query by remember { mutableStateOf(existing?.title ?: series.title) }
    var selectedTitle by remember { mutableStateOf<String?>(existing?.title) }
    var selectedId by remember { mutableStateOf(existing?.trackId) }
    var mode by remember { mutableStateOf(existing?.mode ?: TrackerMode.Auto) }
    var chapterOffset by remember { mutableIntStateOf(existing?.chapterOffset ?: 0) }
    var remoteStatus by remember { mutableStateOf<TrackerStatus?>(null) } // null = keep unchanged
    val searchResults by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val searched by viewModel.searched.collectAsStateWithLifecycle()
    val supported by viewModel.supported.collectAsStateWithLifecycle()
    val remoteState by viewModel.remoteState.collectAsStateWithLifecycle()
    val suggestion by viewModel.suggestion.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(series.id, provider) {
        viewModel.bind(series, provider)
        if (selectedId == null) viewModel.search(query)
    }
    // Also fetch remote progress when opening an already-linked entry
    LaunchedEffect(selectedId) {
        selectedId?.let { viewModel.select(it) }
    }

    val titles = remember(series, alternateTitles) {
        (listOf(series.title) + alternateTitles).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    val results: List<TrackerSearchItemDto> = searchResults.orEmpty()
    // Suggestion from series links: first resolve the provider URL from metadata links, then query the real entry with that URL, matching iOS behavior.
    val linkSuggestion = remember(series, provider) {
        series.links.firstNotNullOfOrNull { (_, url) ->
            provider.trackIdFromUrl(url)?.let { trackId -> trackId to url }
        }
    }
    LaunchedEffect(provider, linkSuggestion?.second, existing?.trackId) {
        if (existing == null) linkSuggestion?.second?.let { viewModel.loadSuggestion(it) }
    }

    AppModalDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderLogo(provider)
                Spacer(Modifier.width(10.dp))
                Text(
                    provider.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            TrackerSectionTitle(stringResource(R.string.tracker_entry))
            if (selectedId != null) {
                // Selected state: result row + #id monospaced + Open external link + Change
                // No cover: aligned with the cover-less form of iOS TrackerSearchResultRow (book icon + gray background);
                // kmrs links do not store remote covers, and the local series cover is not shown either.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .width(40.dp)
                            .height(60.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selectedTitle ?: series.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "#$selectedId",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            val selectedUrl = searchResults.orEmpty().firstOrNull { it.id == selectedId }?.url
                                ?: suggestion?.takeIf { it.id == selectedId }?.url
                                ?: provider.entryUrl(selectedId.orEmpty())
                            if (selectedUrl.isNotBlank()) uriHandler.openUri(selectedUrl)
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(
                                R.string.tracker_open_in_provider,
                                provider.displayName,
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    TextButton(onClick = { selectedId = null }) { Text(stringResource(R.string.tracker_change)) }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.search(it)
                    },
                    label = { Text(stringResource(R.string.tracker_search_provider, provider.displayName)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Multi-title capsule chips (aliases/translations)
                FlowRow(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    titles.forEach { title ->
                        FilterChip(
                            selected = query == title,
                            onClick = {
                                query = title
                                viewModel.search(title)
                            },
                            label = { Text(title, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
                // Suggestion from series links: shown only when the suggested URL resolves to a real entry, aligned with iOS.
                suggestion?.let { item ->
                    Surface(
                        onClick = {
                            selectedId = item.id
                            selectedTitle = item.title
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverArt(
                                title = item.title,
                                hue = series.hue,
                                imageUrl = item.coverUrl,
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(54.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.tracker_from_series_links),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "#${item.id}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                TrackerSectionTitle(stringResource(R.string.tracker_search_results))
                if (!supported) {
                    Text(
                        stringResource(R.string.tracker_search_not_enabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .padding(vertical = 4.dp),
                        strokeWidth = 2.dp,
                    )
                }
                if (supported && !searching && searched && results.isEmpty()) {
                    Text(
                        stringResource(R.string.tracker_no_results),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                results.forEach { item ->
                    Surface(
                        onClick = {
                            selectedId = item.id
                            selectedTitle = item.title
                        },
                        modifier = Modifier.fillMaxWidth(),
                        // Transparent: the default surface color is one step darker than the sheet background and would render as a black row
                        color = Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverArt(
                                title = item.title,
                                hue = series.hue,
                                imageUrl = item.coverUrl,
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(60.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "#${item.id}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (selectedId != null) {
                // Progress Mapping: mode + chapter offset
                TrackerSectionTitle(stringResource(R.string.tracker_progress_mapping))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackerMode.entries.forEach { entry ->
                        FilterChip(
                            selected = mode == entry,
                            onClick = { mode = entry },
                            label = { Text(stringResource(entry.labelRes)) },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.tracker_chapter_offset),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { chapterOffset = (chapterOffset - 1).coerceAtLeast(-10000) }) {
                        Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.tracker_decrease))
                    }
                    Text(
                        "$chapterOffset",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(48.dp),
                    )
                    IconButton(onClick = { chapterOffset = (chapterOffset + 1).coerceAtMost(10000) }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tracker_increase))
                    }
                }

                // Remote Progress: status/score/chapter/volume/start-finish dates (only changed, nonempty fields are submitted)
                TrackerSectionTitle(stringResource(R.string.tracker_remote_progress))
                remoteState?.let { state ->
                    val statusLabel = state.status?.let { stringResource(trackerStatusFromApi(it).labelRes) }
                    val chapterText = state.lastReadChapter?.let { ch ->
                        state.totalChapters?.let { total ->
                            stringResource(R.string.tracker_chapter_of_total, ch.toInt(), total)
                        } ?: stringResource(R.string.tracker_chapter_label, ch.toInt())
                    }
                    val volumeText = state.lastReadVolume?.let {
                        stringResource(R.string.tracker_volume_label, it)
                    }
                    val scoreText = state.score?.let { stringResource(R.string.tracker_score_label, it) }
                    Text(
                        buildString {
                            append(stringResource(R.string.tracker_current_remote))
                            statusLabel?.let { append(it) }
                            chapterText?.let {
                                append(" · ")
                                append(it)
                            }
                            volumeText?.let {
                                append(" · ")
                                append(it)
                            }
                            scoreText?.let {
                                append(" · ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = remoteStatus == null,
                        onClick = { remoteStatus = null },
                        label = { Text(stringResource(R.string.tracker_unchanged)) },
                    )
                    TrackerStatus.entries.forEach { status ->
                        FilterChip(
                            selected = remoteStatus == status,
                            onClick = { remoteStatus = status },
                            label = { Text(stringResource(status.labelRes)) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.tracker_remote_progress_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (existing != null) {
                    OutlinedButton(
                        onClick = {
                            onUnlink()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.tracker_unlink_button), color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    onClick = {
                        val trackId = selectedId ?: return@Button
                        viewModel.save(
                            trackId = trackId,
                            title = selectedTitle,
                            mode = mode,
                            chapterOffset = chapterOffset,
                            newStatus = remoteStatus,
                            onDone = onDismiss,
                        )
                    },
                    enabled = selectedId != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}

@Composable
private fun TrackerSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

/** Link editor data; when supported=false the UI shows a notice directly and no longer falls back to demo data. */
@HiltViewModel
class TrackerLinkEditViewModel @Inject constructor(
    private val detailRepository: DetailRepository,
    private val trackerRepository: TrackerRepository,
) : ViewModel() {

    private val _results = MutableStateFlow<List<TrackerSearchItemDto>?>(null)
    val results: StateFlow<List<TrackerSearchItemDto>?> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searched = MutableStateFlow(false)
    val searched: StateFlow<Boolean> = _searched.asStateFlow()

    private val _supported = MutableStateFlow(true)
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    private val _remoteState = MutableStateFlow<TrackerRemoteStateDto?>(null)
    val remoteState: StateFlow<TrackerRemoteStateDto?> = _remoteState.asStateFlow()

    private val _suggestion = MutableStateFlow<TrackerSearchItemDto?>(null)
    val suggestion: StateFlow<TrackerSearchItemDto?> = _suggestion.asStateFlow()

    private var seriesDtoId: String? = null
    private var provider: TrackerProvider = TrackerProvider.AniList
    private var searchJob: Job? = null

    fun bind(series: Series, provider: TrackerProvider) {
        this.provider = provider
        seriesDtoId = detailRepository.seriesDtoId(series.id)
        _supported.value = trackerRepository.supported
        _suggestion.value = null
    }

    /** Fetches the real tracker entry for the series-link suggestion URL; on failure/no result no fake suggestion is shown. */
    fun loadSuggestion(url: String) {
        if (!trackerRepository.supported) return
        viewModelScope.launch {
            _suggestion.value = trackerRepository.search(provider.apiName, url).firstOrNull()
        }
    }

    /** 300ms debounced remote search; a blank query clears results, and results stay empty without capability. */
    fun search(query: String) {
        searchJob?.cancel()
        if (!trackerRepository.supported) {
            _supported.value = false
            _results.value = emptyList()
            _searched.value = true
            return
        }
        if (query.isBlank()) {
            _results.value = emptyList()
            _searched.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _searching.value = true
            _results.value = trackerRepository.search(provider.apiName, query)
            _searched.value = true
            _searching.value = false
        }
    }

    /** Fetches remote progress after an entry is selected. */
    fun select(trackId: String) {
        if (!trackerRepository.supported) return
        viewModelScope.launch {
            _remoteState.value = trackerRepository.remoteState(provider.apiName, trackId)
        }
    }

    /** Saves the link; when a new status was chosen, pushes an update afterwards (only changed fields are submitted). */
    fun save(
        trackId: String,
        title: String?,
        mode: TrackerMode,
        chapterOffset: Int,
        newStatus: TrackerStatus?,
        onDone: () -> Unit,
    ) {
        val dtoId = seriesDtoId ?: return onDone()
        if (!trackerRepository.supported) return onDone()
        viewModelScope.launch {
            val ok = trackerRepository.upsertLink(
                seriesDtoId = dtoId,
                provider = provider.apiName,
                trackId = trackId,
                title = title,
                mode = mode.name.lowercase(),
                chapterOffset = chapterOffset,
            )
            if (ok && newStatus != null) {
                trackerRepository.pushUpdate(
                    provider = provider.apiName,
                    trackId = trackId,
                    status = newStatus.name.lowercase(),
                )
            }
            onDone()
        }
    }
}
