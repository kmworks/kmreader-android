package com.kmworks.kmreader.ui.stats

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.core.stats.LocalReadingStats
import com.kmworks.kmreader.core.stats.ReadingStatsCacheStore
import com.kmworks.kmreader.core.stats.ReadingStatsItemDto
import com.kmworks.kmreader.core.stats.ReadingStatsRepository
import com.kmworks.kmreader.data.HeatmapDay
import com.kmworks.kmreader.data.ReadingStatsSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Heatmap time range (aligned with the 7 steps of iOS ReadingStatsTimeRange; days=null means this week, -1 means all time). */
enum class ReadingStatsTimeRange(@param:StringRes val labelRes: Int, val days: Int?) {
    ThisWeek(R.string.stats_range_this_week, null),
    Last7Days(R.string.stats_range_last_7_days, 7),
    Last30Days(R.string.stats_range_last_30_days, 30),
    Last90Days(R.string.stats_range_last_90_days, 90),
    Last6Months(R.string.stats_range_last_6_months, 183),
    LastYear(R.string.stats_range_last_year, 365),
    AllTime(R.string.stats_range_all_time, -1),
}

/** Ranking/distribution entry (aligned with iOS ReadingStatsItem). */
data class StatsItem(val name: String, val value: Int)

/** Reading-stats state; null summary/heatmapWeeks means not loaded or no session, and the UI shows loading/empty states. */
data class ReadingStatsUiState(
    val hasSession: Boolean = false,
    /** kmrs capability missing (stock Komga): show a placeholder without an error. */
    val unavailable: Boolean = false,
    /** True when the numbers were aggregated from the local cache (offline / no server stats). */
    val local: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** Showing a cached snapshot (stale cache refreshing in background, or fell back after a failed refresh; aligned with iOS "Showing cached data"). */
    val showingCachedData: Boolean = false,
    val summary: ReadingStatsSummary? = null,
    val heatmapWeeks: List<List<HeatmapDay?>>? = null,
    val maxHeatmapPages: Int = 1,
    /** Raw daily pages sequence (yyyy-MM-dd -> pages), used to recompute the window on time-range switches. */
    val pagesByDate: Map<String, Int> = emptyMap(),
    /** Library filter (null = all libraries, aligned with the in-page Picker on iOS). */
    val libraries: List<LibraryDto> = emptyList(),
    val libraryId: String? = null,
    val timeRange: ReadingStatsTimeRange = ReadingStatsTimeRange.Last6Months,
    val lastUpdatedLabel: String? = null,
    /** Reading-status distribution (names mapped from server values). */
    val statusDistribution: List<StatsItem> = emptyList(),
    /** Weekly distribution, index 0 = Sunday (same as server/iOS). */
    val weekdayDistribution: List<Int> = emptyList(),
    /** 24-hour distribution. */
    val hourlyDistribution: List<Int> = emptyList(),
    val topAuthors: List<StatsItem> = emptyList(),
    val topGenres: List<StatsItem> = emptyList(),
    val topTags: List<StatsItem> = emptyList(),
    val genreDistribution: List<StatsItem> = emptyList(),
    val tagDistribution: List<StatsItem> = emptyList(),
)

/**
 * Reading stats: gated on the kmrs ReadingStats capability, loading real data when supported.
 * Three endpoints fetched concurrently (summary/activity/tops, aligned with iOS ReadingStatsService.fetchServerPayload).
 * Streaks are computed client-side from readingTimeSeries (the server summary has no streak fields).
 */
@HiltViewModel
class ReadingStatsViewModel @Inject constructor(
    private val repository: ReadingStatsRepository,
    private val localStats: LocalReadingStats,
    private val cacheStore: ReadingStatsCacheStore,
    private val libraryRepository: LibraryRepository,
    private val serverProfileStore: ServerProfileStore,
    private val sessionManager: SessionManager,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReadingStatsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Both the session and the capability probe can change: load once both are ready
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                sessionManager.clients,
                serverProfileStore.profile,
            ) { clients, _ -> clients != null }.collect { hasSession ->
                when {
                    // Offline and capability-less servers both fall back to the local cache
                    // (mirrors iOS ReadingStatsService: local payload when offline / unsupported).
                    !hasSession -> loadLocal()
                    !serverProfileStore.hasFeature(ServerFeature.ReadingStats) -> loadLocal()
                    else -> {
                        if (_uiState.value.libraries.isEmpty()) {
                            val libraries = runCatching { libraryRepository.libraries() }
                                .getOrDefault(emptyList())
                            _uiState.update { it.copy(libraries = libraries) }
                        }
                        load()
                    }
                }
            }
        }
    }

    /** Manual refresh (toolbar refresh button, aligned with iOS toolbar refresh; ignores cache freshness and force-refetches). */
    fun refresh() {
        if (_uiState.value.local) {
            loadLocal()
        } else {
            load(forceNetwork = true)
        }
    }

    /** Switches the library filter (null = all) and refetches the three endpoints (aligned with the in-page library Picker on iOS). */
    fun setLibrary(libraryId: String?) {
        // Local aggregation has no library dimension (cached rows carry no libraryId).
        if (_uiState.value.local) return
        if (_uiState.value.libraryId == libraryId) return
        _uiState.update { it.copy(libraryId = libraryId) }
        load()
    }

    /** Switches the heatmap time range (window recompute only, no network refetch; aligned with the iOS Range Picker). */
    fun setTimeRange(range: ReadingStatsTimeRange) {
        if (_uiState.value.timeRange == range) return
        _uiState.update { state ->
            val weeks = buildHeatmapWeeks(state.pagesByDate, range)
            state.copy(
                timeRange = range,
                heatmapWeeks = weeks,
                maxHeatmapPages = weeks.flatten().filterNotNull().maxOfOrNull { d -> d.pages } ?: 1,
            )
        }
    }

    /**
     * Cache strategy (aligned with iOS ReadingStatsViewModel):
     * use the cache directly within 24h; when stale, show it first and refresh in background;
     * fall back to the cache with a notice when the refresh fails.
     */
    /**
     * Local fallback: aggregates the cached catalog into the same response shapes
     * (mirrors iOS ReadingStatsService.fetchLocalPayload). The library picker is hidden because the
     * cached rows carry no library dimension.
     */
    private fun loadLocal() {
        val hasSession = sessionManager.clients.value != null
        _uiState.update {
            it.copy(
                hasSession = hasSession,
                local = true,
                unavailable = false,
                loading = true,
                error = null,
                showingCachedData = false,
                libraries = emptyList(),
                libraryId = null,
            )
        }
        viewModelScope.launch {
            if (!applyLocalFallback()) {
                // Nothing cached on this device: keep the honest placeholder instead of zeros.
                _uiState.value = if (hasSession) {
                    ReadingStatsUiState(hasSession = true, unavailable = true, local = true)
                } else {
                    ReadingStatsUiState()
                }
            }
        }
    }

    /** Aggregates the local cache into the UI; false when this device has no cached catalog. */
    private suspend fun applyLocalFallback(): Boolean {
        val summary = runCatching { localStats.summary() }.getOrNull()
        val activity = runCatching { localStats.activity() }.getOrNull()
        if (summary == null && activity == null) return false
        // First pass without the series-metadata request so the cached numbers render immediately.
        apply(
            summaryRes = summary,
            activityRes = activity,
            topsRes = runCatching { localStats.tops(withMetadata = false) }.getOrNull(),
            showingCachedData = false,
            loading = false,
            local = true,
        )
        // Second pass: genre/tag rankings need series metadata (network). Failures keep them empty.
        val tops = runCatching { localStats.tops(withMetadata = true) }.getOrNull()
        if (tops != null) {
            apply(
                summaryRes = summary,
                activityRes = activity,
                topsRes = tops,
                showingCachedData = false,
                loading = false,
                local = true,
            )
        }
        return true
    }

    private fun load(forceNetwork: Boolean = false) {
        val libraryId = _uiState.value.libraryId
        _uiState.update {
            it.copy(hasSession = true, unavailable = false, loading = true, error = null, showingCachedData = false)
        }
        viewModelScope.launch {
            val cached = cacheStore.read(libraryId)
            if (cached != null) {
                val fresh = System.currentTimeMillis() - cached.savedAtEpochMs < cacheStore.ttlMs
                if (fresh && !forceNetwork) {
                    apply(cached.summary, cached.activity, cached.tops, showingCachedData = false, loading = false)
                    return@launch
                }
                // Stale cache: show it first, refresh in background
                apply(cached.summary, cached.activity, cached.tops, showingCachedData = true, loading = true)
            }
            try {
                // Three endpoints concurrently (aligned with iOS fetchServerPayload)
                val (summaryRes, activityRes, topsRes) = coroutineScope {
                    val s = async { repository.summary(libraryId) }
                    val a = async { repository.activity(libraryId) }
                    val t = async { repository.tops(libraryId) }
                    Triple(s.await(), a.await(), t.await())
                }
                if (summaryRes == null && activityRes == null) {
                    if (cached != null) {
                        // Refresh failed: fall back to the cache (aligned with iOS failure fallback + "Showing cached data")
                        apply(cached.summary, cached.activity, cached.tops, showingCachedData = true, loading = false)
                    } else if (!applyLocalFallback()) {
                        _uiState.update {
                            it.copy(loading = false, error = context.getString(R.string.common_load_failed))
                        }
                    }
                    return@launch
                }
                apply(summaryRes, activityRes, topsRes, showingCachedData = false, loading = false)
                cacheStore.write(libraryId, summaryRes, activityRes, topsRes)
            } catch (e: Exception) {
                if (cached != null) {
                    apply(cached.summary, cached.activity, cached.tops, showingCachedData = true, loading = false)
                } else if (!applyLocalFallback()) {
                    _uiState.update {
                        it.copy(loading = false, error = e.message ?: context.getString(R.string.common_load_failed))
                    }
                }
            }
        }
    }

    /** Refreshes the UI state from the three-endpoint response (shared by the cache and network paths). */
    private fun apply(
        summaryRes: com.kmworks.kmreader.core.stats.ReadingStatsSummaryResponse?,
        activityRes: com.kmworks.kmreader.core.stats.ReadingStatsActivityResponse?,
        topsRes: com.kmworks.kmreader.core.stats.ReadingStatsTopsResponse?,
        showingCachedData: Boolean,
        loading: Boolean,
        local: Boolean = false,
    ) {
        val series = activityRes?.readingTimeSeries.orEmpty()
        val pagesByDate = series.mapNotNull { point ->
            val date = point.date?.take(10) ?: return@mapNotNull null
            date to (point.pagesRead ?: 0.0).roundToInt()
        }.toMap()
        val lastReadDate = summaryRes?.lastReadAt?.take(10)
        val summary = ReadingStatsSummary(
            totalPages = summaryRes?.pagesRead ?: 0,
            // The server provides no reading-time estimate (aligned with kmrs ReadingSummaryDto, no estimatedReadingHours)
            totalMinutes = 0,
            booksCompleted = summaryRes?.booksCompleted ?: 0,
            // Prefer the server streak values (computed from historical events), falling back to a client-side computation from heatmap data
            currentStreakDays = summaryRes?.currentStreakDays ?: currentStreak(pagesByDate),
            longestStreakDays = summaryRes?.longestStreakDays ?: longestStreak(pagesByDate),
            readingDays = summaryRes?.readingDays ?: pagesByDate.count { it.value > 0 },
            avgPagesPerBook = summaryRes?.averagePagesPerBook ?: 0,
            lastReadLabel = lastReadLabel(summaryRes?.lastReadAt, pagesByDate),
            booksStarted = summaryRes?.booksStarted ?: 0,
            totalBooks = summaryRes?.totalBooks ?: 0,
            lastReadDate = lastReadDate,
        )
        val weeks = buildHeatmapWeeks(pagesByDate, _uiState.value.timeRange)
        _uiState.update {
            it.copy(
                loading = loading,
                local = local,
                showingCachedData = showingCachedData,
                summary = summary,
                heatmapWeeks = weeks,
                maxHeatmapPages = weeks.flatten().filterNotNull().maxOfOrNull { d -> d.pages } ?: 1,
                pagesByDate = pagesByDate,
                lastUpdatedLabel = summaryRes?.generatedAt?.let { at -> formatUpdated(at) },
                statusDistribution = summaryRes?.statusDistribution.orEmpty()
                    .mapNotNull { item -> item.toStatsItem(mapStatus = true) },
                weekdayDistribution = activityRes?.weekdayDistribution.orEmpty(),
                hourlyDistribution = activityRes?.hourlyDistribution.orEmpty(),
                topAuthors = topsRes?.topAuthors.orEmpty().mapNotNull { item -> item.toStatsItem() },
                topGenres = topsRes?.topGenres.orEmpty().mapNotNull { item -> item.toStatsItem() },
                topTags = topsRes?.topTags.orEmpty().mapNotNull { item -> item.toStatsItem() },
                genreDistribution = topsRes?.genreDistribution.orEmpty()
                    .mapNotNull { item -> item.toStatsItem() },
                tagDistribution = topsRes?.tagDistribution.orEmpty()
                    .mapNotNull { item -> item.toStatsItem() },
            )
        }
    }

    /** Maps server reading-status names (aligned with iOS ReadingStatsService.mapStatusName). */
    private fun ReadingStatsItemDto.toStatsItem(mapStatus: Boolean = false): StatsItem? {
        val raw = name?.takeIf { it.isNotBlank() } ?: return null
        val v = value?.roundToInt() ?: return null
        return StatsItem(if (mapStatus) statusLabel(raw) else raw, v)
    }

    private fun statusLabel(name: String): String = when (name.lowercase()) {
        "read" -> context.getString(R.string.stats_status_read)
        "inprogress", "in_progress", "in progress" -> context.getString(R.string.stats_status_in_progress)
        "unread" -> context.getString(R.string.common_unread)
        else -> name
    }

    private fun formatUpdated(at: String): String =
        runCatching {
            val instant = java.time.Instant.parse(at)
            DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(instant)
        }.getOrDefault(at.take(16).replace('T', ' '))

    /**
     * N weeks x 7 days, starting Monday; future/out-of-window cells are null; the window length
     * follows the time range (aligned with iOS ReadingStatsViewModel.makeHeatmapWindow, out-of-window cells grayed).
     */
    private fun buildHeatmapWeeks(
        pagesByDate: Map<String, Int>,
        range: ReadingStatsTimeRange,
    ): List<List<HeatmapDay?>> {
        val end = LocalDate.now()
        val windowStart = when (range.days) {
            null -> end.with(DayOfWeek.MONDAY) // this week
            -1 -> pagesByDate.keys
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .minOrNull()
                ?: end.minusWeeks(51) // no data: fall back to 52 weeks
            else -> end.minusDays((range.days - 1).toLong())
        }
        val gridStart = windowStart.with(DayOfWeek.MONDAY)
        val totalDays = java.time.temporal.ChronoUnit.DAYS.between(gridStart, end) + 1
        val weekCount = ((totalDays + 6) / 7).toInt().coerceAtLeast(1)
        return (0 until weekCount).map { week ->
            (0 until 7).map { day ->
                val date = gridStart.plusDays((week * 7 + day).toLong())
                if (date.isAfter(end) || date.isBefore(windowStart)) {
                    null
                } else {
                    HeatmapDay(date.toString(), pagesByDate[date.toString()] ?: 0)
                }
            }
        }
    }

    private fun currentStreak(pagesByDate: Map<String, Int>): Int {
        // Start from yesterday when today has no record yet (yesterday is still an ongoing streak)
        var cursor = LocalDate.now()
        if ((pagesByDate[cursor.toString()] ?: 0) <= 0) cursor = cursor.minusDays(1)
        var streak = 0
        while ((pagesByDate[cursor.toString()] ?: 0) > 0) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun longestStreak(pagesByDate: Map<String, Int>): Int {
        val active = pagesByDate.filterValues { it > 0 }.keys
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .sorted()
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        active.forEach { date ->
            run = if (prev?.plusDays(1) == date) run + 1 else 1
            if (run > best) best = run
            prev = date
        }
        return best
    }

    private fun lastReadLabel(lastReadAt: String?, pagesByDate: Map<String, Int>): String {
        val date = lastReadAt?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: pagesByDate.filterValues { it > 0 }.keys.maxOrNull()
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return "—"
        val days = java.time.temporal.ChronoUnit.DAYS.between(date, LocalDate.now())
        return when (days) {
            0L -> context.getString(R.string.stats_last_read_today)
            1L -> context.getString(R.string.stats_last_read_yesterday)
            in 2..29 -> context.getString(R.string.stats_last_read_days_ago, days)
            else -> date.toString()
        }
    }
}
