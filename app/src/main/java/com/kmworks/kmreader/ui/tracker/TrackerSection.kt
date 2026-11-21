package com.kmworks.kmreader.ui.tracker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.ui.components.AppModalDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
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
import com.kmworks.kmreader.core.tracker.TrackerRepository
import com.kmworks.kmreader.data.Series
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Provider badge: real brand logo (kept in original colors, matching iOS); falls back to a first-letter disc when no asset exists. */
@Composable
fun ProviderLogo(provider: TrackerProvider, modifier: Modifier = Modifier) {
    if (provider.logoRes != 0) {
        Image(
            painter = painterResource(provider.logoRes),
            contentDescription = provider.displayName,
            modifier = modifier
                .size(22.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                provider.displayName.take(1),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Detail page tracker section (aligned with TrackerSeriesSection):
 * linked tracker chips (logo + status + progress) + Add Tracker (shown while link count < provider count).
 *
 * Fills the width on purpose: the phone detail page centers its hero column, and a wrap-content block would be
 * centered as a whole and read as a floating row instead of the start of the section below the summary.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackerSection(
    links: List<TrackerLink> = emptyList(),
    onManage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            stringResource(R.string.tracker_trackers_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            links.forEach { link ->
                Surface(
                    onClick = onManage,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProviderLogo(link.provider)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                link.provider.displayName,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            val statusLabel = stringResource(link.remoteStatus.labelRes)
                            val progressText = link.lastReadChapter?.let { ch ->
                                link.totalChapters?.let { total ->
                                    stringResource(R.string.tracker_chapter_of_total, ch.toInt(), total)
                                } ?: stringResource(R.string.tracker_chapter_label, ch.toInt())
                            }
                            Text(
                                buildString {
                                    append(statusLabel)
                                    progressText?.let {
                                        append(" · ")
                                        append(it)
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (links.size < TrackerProvider.entries.size) {
                Surface(
                    onClick = onManage,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.tracker_add_tracker),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tracker management (aligned with TrackerSeriesSheet):
 * linked list (tap to open the link editor) + addable provider list;
 * footer note: completing books advances tracker progress, marking unread never moves it backward.
 * With a session and server-side Trackers support this uses real links; otherwise it falls back to demo data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerSeriesSheet(
    series: Series,
    onDismiss: () -> Unit,
    alternateTitles: List<String> = emptyList(),
    viewModel: TrackerSeriesViewModel = hiltViewModel(),
) {
    LaunchedEffect(series.id) { viewModel.load(series) }
    val links by viewModel.links.collectAsStateWithLifecycle()
    val supported by viewModel.supported.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<TrackerProvider?>(null) }

    AppModalDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            // Fixed header (aligned with the iOS SheetView navigation bar title)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 8.dp),
            ) {
                Text(
                    stringResource(R.string.tracker_trackers_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
            ) {
                item { TrackerSheetSectionTitle(stringResource(R.string.tracker_linked_trackers)) }
                item {
                    TrackerGroupCard {
                        when {
                            !supported -> TrackerSheetInfoRow(stringResource(R.string.tracker_not_enabled))
                            links == null -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                            links.orEmpty().isEmpty() -> TrackerSheetInfoRow(stringResource(R.string.tracker_no_linked))
                            else -> links.orEmpty().forEachIndexed { index, link ->
                                TrackerLinkRow(
                                    link = link,
                                    onClick = { editingProvider = link.provider },
                                )
                                if (index < links.orEmpty().lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant
                                            .copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }

                val unlinked = TrackerProvider.entries.filter { p ->
                    links.orEmpty().none { it.provider == p }
                }
                if (supported && links != null && unlinked.isNotEmpty()) {
                    item { TrackerSheetSectionTitle(stringResource(R.string.common_add)) }
                    item {
                        TrackerGroupCard {
                            unlinked.forEachIndexed { index, provider ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { editingProvider = provider }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ProviderLogo(provider)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        provider.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = stringResource(
                                            R.string.tracker_link_provider,
                                            provider.displayName,
                                        ),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                if (index < unlinked.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant
                                            .copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        stringResource(R.string.tracker_series_sheet_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }

    editingProvider?.let { provider ->
        TrackerLinkEditSheet(
            series = series,
            alternateTitles = alternateTitles,
            provider = provider,
            existing = links.orEmpty().firstOrNull { it.provider == provider },
            onUnlink = { viewModel.unlink(provider) },
            onDismiss = {
                editingProvider = null
                // Refresh real links after an edit (save/unlink)
                if (supported) viewModel.refresh()
            },
        )
    }
}

@Composable
private fun TrackerLinkRow(link: TrackerLink, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderLogo(link.provider)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                link.provider.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
            val progressText = link.lastReadChapter?.let { ch ->
                link.totalChapters?.let { total ->
                    stringResource(R.string.tracker_chapter_of_total, ch.toInt(), total)
                } ?: stringResource(R.string.tracker_chapter_label, ch.toInt())
            }
            Text(
                buildString {
                    append(link.title)
                    append(" · #")
                    append(link.trackId)
                    progressText?.let {
                        append(" · ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Grouped card inside the sheet (aligned with iOS inset-grouped); colored one step above the sheet background or it would be invisible. */
@Composable
private fun TrackerGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun TrackerSheetSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun TrackerSheetInfoRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/** Series tracker link data; supported=false when the server lacks support (UI shows a notice, no demo fallback). */
@HiltViewModel
class TrackerSeriesViewModel @Inject constructor(
    private val detailRepository: DetailRepository,
    private val trackerRepository: TrackerRepository,
) : ViewModel() {

    private val _links = MutableStateFlow<List<TrackerLink>?>(null)
    val links: StateFlow<List<TrackerLink>?> = _links.asStateFlow()

    private val _supported = MutableStateFlow(true)
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    private var seriesDtoId: String? = null

    fun load(series: Series) {
        seriesDtoId = detailRepository.seriesDtoId(series.id)
        _supported.value = trackerRepository.supported
        if (seriesDtoId == null || !trackerRepository.supported) return
        _links.value = null
        refresh()
    }

    /** Re-fetches links and merges each provider's remote progress. */
    fun refresh() {
        val dtoId = seriesDtoId ?: return
        if (!trackerRepository.supported) return
        viewModelScope.launch {
            val merged = coroutineScope {
                trackerRepository.links(dtoId).map { link ->
                    async {
                        link.toUiTrackerLink(
                            trackerRepository.remoteState(link.provider, link.trackId)
                        )
                    }
                }.awaitAll()
            }
            _links.value = merged
        }
    }

    fun unlink(provider: TrackerProvider) {
        val dtoId = seriesDtoId ?: return
        viewModelScope.launch {
            trackerRepository.deleteLink(dtoId, provider.apiName)
            refresh()
        }
    }
}
