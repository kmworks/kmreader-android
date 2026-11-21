package com.kmworks.kmreader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.HeatmapDay
import com.kmworks.kmreader.ui.settings.SettingsBackIcon

/**
 * Reading history stats (mirrors ServerReadingStatsView + ReadingPagesHeatmapView).
 * kmrs capability gate: stock Komga (no ReadingStats feature) shows a placeholder;
 * no-session/loading shows an empty state, never falling back to fake data.
 * Section order aligned with iOS: control bar (library/updated time) -> summary card grid (up to 9)
 * -> pages heatmap (with time range) -> reading-status donut -> weekday/hourly bar charts
 * -> Top authors/genres/tags -> genre/tag distribution donuts.
 * Visuals follow the project's monochrome black/white/red line: charts use alpha ramps of
 * onSurface/primary and introduce no colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(
    onBack: () -> Unit,
    viewModel: ReadingStatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var selectedDay by remember { mutableStateOf<HeatmapDay?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = SettingsBackIcon {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    // Manual refresh (aligned with the iOS toolbar refresh button)
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.common_refresh),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.unavailable) {
            // kmrs capability missing: show a placeholder without an error
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.stats_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        // Data not ready: loading / failure / no-session empty states (no sample fake data)
        val stats = state.summary
        val weeks = state.heatmapWeeks
        if (stats == null || weeks == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when {
                        state.loading -> stringResource(R.string.common_loading)
                        state.error != null -> stringResource(R.string.stats_error_with_message, state.error ?: "")
                        !state.hasSession -> stringResource(R.string.stats_sign_in_prompt)
                        else -> stringResource(R.string.stats_empty)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.error != null && !state.loading) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            return@Scaffold
        }
        val maxPages = state.maxHeatmapPages
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // Control bar (aligned with iOS: library Picker + last-updated time)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The local aggregation has no library dimension, so the picker is hidden there.
                    if (!state.local) {
                        LibraryPicker(
                            state = state,
                            onSelect = { viewModel.setLibrary(it) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    state.lastUpdatedLabel?.let {
                        Text(
                            stringResource(R.string.stats_updated_at, it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.error?.let { error ->
                    Text(
                        text = stringResource(R.string.stats_error_with_message, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // Cached-data fallback notice (aligned with iOS "Showing cached data")
                if (state.showingCachedData) {
                    Text(
                        text = stringResource(
                            if (state.loading) {
                                R.string.stats_cached_refreshing
                            } else {
                                R.string.stats_cached_failed
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // Local-aggregation notice (offline / stock Komga without the readingStats feature)
                if (state.local) {
                    Text(
                        text = stringResource(R.string.stats_local_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Summary card grid (aligned with iOS summarySection: up to 9 cards, streak cards only when > 0)
            item {
                val cards = buildList {
                    add(stringResource(R.string.stats_books_started) to "${stats.booksStarted}")
                    add(stringResource(R.string.common_completed) to "${stats.booksCompleted}")
                    add(stringResource(R.string.stats_total_pages) to "%,d".format(stats.totalPages))
                    add(stringResource(R.string.stats_reading_days) to "${stats.readingDays}")
                    if (stats.currentStreakDays > 0) {
                        add(
                            stringResource(R.string.stats_current_streak) to
                                stringResource(R.string.stats_streak_days, stats.currentStreakDays),
                        )
                    }
                    if (stats.longestStreakDays > 0) {
                        add(
                            stringResource(R.string.stats_longest_streak) to
                                stringResource(R.string.stats_streak_days, stats.longestStreakDays),
                        )
                    }
                    add(stringResource(R.string.stats_avg_pages_per_book) to "${stats.avgPagesPerBook}")
                    add(stringResource(R.string.stats_total_books) to "%,d".format(stats.totalBooks))
                    add(stringResource(R.string.stats_last_read) to stats.lastReadLabel)
                }
                cards.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { (label, value) ->
                            StatCard(label = label, value = value, modifier = Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // Pages heatmap (time-range Picker aligned with the iOS Range Picker)
            item {
                StatsSectionTitle(stringResource(R.string.stats_pages_heatmap)) {
                    RangePicker(
                        selected = state.timeRange,
                        onSelect = { viewModel.setTimeRange(it) },
                    )
                }
                PagesHeatmap(
                    weeks = weeks,
                    maxPages = maxPages,
                    selected = selectedDay,
                    onSelect = { selectedDay = it },
                )
                Spacer(Modifier.height(6.dp))
                HeatmapLegend()
                selectedDay?.let { day ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.stats_heatmap_day_pages, day.date, day.pages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Reading-status donut (aligned with the iOS Reading Status pieChart)
            if (state.statusDistribution.isNotEmpty()) {
                item {
                    StatsSectionTitle(stringResource(R.string.stats_reading_status))
                    DonutChartCard(items = state.statusDistribution)
                }
            }

            // Weekday bar chart (index 0 = Sunday, keeping server order; aligned with iOS Reading by Weekday)
            if (state.weekdayDistribution.isNotEmpty()) {
                item {
                    StatsSectionTitle(stringResource(R.string.stats_by_weekday))
                    BarChartCard(
                        values = state.weekdayDistribution,
                        labels = weekdayLabels().take(state.weekdayDistribution.size),
                    )
                }
            }

            // Hourly bar chart (24 slots, sparse axis labels; aligned with iOS Reading by Hour)
            if (state.hourlyDistribution.isNotEmpty()) {
                item {
                    StatsSectionTitle(stringResource(R.string.stats_by_hour))
                    BarChartCard(
                        values = state.hourlyDistribution,
                        labels = state.hourlyDistribution.indices.map { i ->
                            if (i % 6 == 0 || i == state.hourlyDistribution.lastIndex) "$i" else ""
                        },
                    )
                }
            }

            // Top authors/genres/tags numbered lists (top 10 each, aligned with iOS topItemsSection)
            if (state.topAuthors.isNotEmpty()) {
                item {
                    StatsSectionTitle(stringResource(R.string.stats_top_authors))
                    TopListCard(items = state.topAuthors.take(10))
                }
            }
            if (state.topGenres.isNotEmpty()) {
                item {
                    StatsSectionTitle(stringResource(R.string.stats_top_genres))
                    TopListCard(items = state.topGenres.take(10))
                }
            }
            if (state.topTags.isNotEmpty()) {
                item {
                    StatsSectionTitle(stringResource(R.string.stats_top_tags))
                    TopListCard(items = state.topTags.take(10))
                }
            }

            // Genre/tag distribution donuts (top 12, aligned with the iOS distribution pie charts)
            if (state.genreDistribution.isNotEmpty()) {
                item {
                    StatsSectionTitle(stringResource(R.string.stats_genre_distribution))
                    DonutChartCard(items = state.genreDistribution.take(12))
                }
            }
            if (state.tagDistribution.isNotEmpty()) {
                item {
                    StatsSectionTitle(stringResource(R.string.stats_tag_distribution))
                    DonutChartCard(items = state.tagDistribution.take(12))
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun weekdayLabels(): List<String> = listOf(
    stringResource(R.string.stats_weekday_sun),
    stringResource(R.string.stats_weekday_mon),
    stringResource(R.string.stats_weekday_tue),
    stringResource(R.string.stats_weekday_wed),
    stringResource(R.string.stats_weekday_thu),
    stringResource(R.string.stats_weekday_fri),
    stringResource(R.string.stats_weekday_sat),
)

/** Library-filter Picker (aligned with the in-page library Picker on iOS; all libraries = null). */
@Composable
private fun LibraryPicker(
    state: ReadingStatsUiState,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val allLibraries = stringResource(R.string.common_all_libraries)
    val label = state.libraryId?.let { id ->
        state.libraries.firstOrNull { it.id == id }?.name
    } ?: allLibraries
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.stats_library_label, label))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(allLibraries) },
                onClick = {
                    open = false
                    onSelect(null)
                },
            )
            state.libraries.forEach { library ->
                DropdownMenuItem(
                    text = { Text(library.name) },
                    onClick = {
                        open = false
                        onSelect(library.id)
                    },
                )
            }
        }
    }
}

/** Heatmap time-range Picker (aligned with the 7 steps of iOS ReadingStatsTimeRange). */
@Composable
private fun RangePicker(
    selected: ReadingStatsTimeRange,
    onSelect: (ReadingStatsTimeRange) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                stringResource(selected.labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReadingStatsTimeRange.entries.forEach { range ->
                DropdownMenuItem(
                    text = { Text(stringResource(range.labelRes)) },
                    onClick = {
                        open = false
                        onSelect(range)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * Donut chart + legend (aligned with the iOS pieChart: SectorMark innerRadius 0.58).
 * Monochrome alpha ramp (the project's black/white/red line); higher values are more opaque.
 */
@Composable
private fun DonutChartCard(items: List<StatsItem>) {
    val total = items.sumOf { it.value }.coerceAtLeast(1)
    val ringColor = MaterialTheme.colorScheme.onSurface
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(110.dp)) {
                val strokeWidth = size.minDimension * 0.22f
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                var startAngle = -90f
                items.forEachIndexed { index, item ->
                    val sweep = item.value.toFloat() / total * 360f
                    drawArc(
                        color = ringColor.copy(alpha = rampAlpha(index, items.size)),
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                    )
                    startAngle += sweep
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    MaterialTheme.colorScheme.onSurface
                                        .copy(alpha = rampAlpha(index, items.size)),
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "%,d".format(item.value),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Monochrome ramp: item 0 most opaque (0.92), the last 0.18, decreasing linearly. */
private fun rampAlpha(index: Int, count: Int): Float {
    if (count <= 1) return 0.92f
    return 0.92f - (0.92f - 0.18f) * index / (count - 1)
}

/** Bar chart (aligned with the iOS BarMark distribution charts; monochrome primary). */
@Composable
private fun BarChartCard(
    values: List<Int>,
    labels: List<String>,
) {
    val maxValue = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                values.forEach { value ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight((value.toFloat() / maxValue).coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                labels.forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Top-N numbered list card (aligned with iOS topItemsSection: "index. name … value"). */
@Composable
private fun TopListCard(items: List<StatsItem>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}. ${item.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "%,d".format(item.value),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index != items.lastIndex) Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** GitHub-style pages heatmap (grayscale alpha on onSurface). */
@Composable
private fun PagesHeatmap(
    weeks: List<List<HeatmapDay?>>,
    maxPages: Int,
    selected: HeatmapDay?,
    onSelect: (HeatmapDay) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        weeks.forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { day ->
                    HeatmapTile(
                        day = day,
                        maxPages = maxPages,
                        selected = day != null && day == selected,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatmapTile(
    day: HeatmapDay?,
    maxPages: Int,
    selected: Boolean,
    onSelect: (HeatmapDay) -> Unit,
) {
    val shape = RoundedCornerShape(2.dp)
    val alpha = heatmapAlpha(day?.pages, maxPages)
    Box(
        modifier = Modifier
            .size(13.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            .then(
                if (selected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .then(
                if (day != null) Modifier.clickable { onSelect(day) } else Modifier,
            ),
    )
}

/** Density ramp: 0 pages is the faintest, more pages more opaque (5 alpha steps aligned with iOS 0.12/0.28/0.46/0.68/0.92). */
private fun heatmapAlpha(pages: Int?, maxPages: Int): Float = when {
    pages == null -> 0.06f
    pages <= 0 -> 0.12f
    else -> {
        val ratio = pages.toFloat() / maxPages.coerceAtLeast(1)
        when {
            ratio < 0.25f -> 0.28f
            ratio < 0.5f -> 0.46f
            ratio < 0.75f -> 0.68f
            else -> 0.92f
        }
    }
}

@Composable
private fun HeatmapLegend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.stats_less),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        listOf(0.12f, 0.28f, 0.46f, 0.68f, 0.92f).forEach { alpha ->
            Box(
                modifier = Modifier
                    .padding(end = 3.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
            )
        }
        Spacer(Modifier.width(3.dp))
        Text(
            stringResource(R.string.stats_more),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsSectionTitle(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}
