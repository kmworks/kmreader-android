package com.kmworks.kmreader.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerFeature
import com.kmworks.kmreader.core.server.ServerProfileStore
import com.kmworks.kmreader.core.stats.ReadingStatsRepository
import com.kmworks.kmreader.data.HeatmapDay
import com.kmworks.kmreader.data.ReadingStatsSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 阅读统计状态；summary/heatmapWeeks 为 null 时界面回退假数据演示。 */
data class ReadingStatsUiState(
    val hasSession: Boolean = false,
    /** kmrs 能力缺失（stock Komga）：显示占位，不报错。 */
    val unavailable: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val summary: ReadingStatsSummary? = null,
    val heatmapWeeks: List<List<HeatmapDay?>>? = null,
    val maxHeatmapPages: Int = 1,
)

/**
 * 阅读统计：kmrs ReadingStats 能力门控，支持时加载真实数据。
 * 连续天数由 readingTimeSeries 客户端计算（服务端 summary 无 streak 字段）。
 */
@HiltViewModel
class ReadingStatsViewModel @Inject constructor(
    private val repository: ReadingStatsRepository,
    private val serverProfileStore: ServerProfileStore,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReadingStatsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // 会话与能力探测都可变：两者就绪后再加载
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                sessionManager.clients,
                serverProfileStore.profile,
            ) { clients, _ -> clients != null }.collect { hasSession ->
                when {
                    !hasSession -> _uiState.value = ReadingStatsUiState()
                    !serverProfileStore.hasFeature(ServerFeature.ReadingStats) ->
                        _uiState.value = ReadingStatsUiState(hasSession = true, unavailable = true)
                    else -> load()
                }
            }
        }
    }

    private fun load() {
        _uiState.update { it.copy(hasSession = true, unavailable = false, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val (summaryRes, activityRes) = coroutineScope {
                    val s = async { repository.summary() }
                    val a = async { repository.activity() }
                    s.await() to a.await()
                }
                if (summaryRes == null && activityRes == null) {
                    _uiState.update { it.copy(loading = false, error = "加载失败") }
                    return@launch
                }
                val series = activityRes?.readingTimeSeries.orEmpty()
                val pagesByDate = series.mapNotNull { point ->
                    val date = point.dateString?.take(10) ?: return@mapNotNull null
                    date to (point.value ?: 0.0).roundToInt()
                }.toMap()
                val s = summaryRes?.summary
                val summary = ReadingStatsSummary(
                    totalPages = s?.totalPagesRead ?: 0,
                    totalMinutes = ((s?.estimatedReadingHours ?: 0.0) * 60).roundToInt(),
                    booksCompleted = s?.booksCompletedReading ?: 0,
                    currentStreakDays = currentStreak(pagesByDate),
                    longestStreakDays = longestStreak(pagesByDate),
                    readingDays = s?.readingDays ?: pagesByDate.count { it.value > 0 },
                    avgPagesPerBook = s?.averagePagesPerBook ?: 0,
                    lastReadLabel = lastReadLabel(s?.lastReadAt, pagesByDate),
                )
                val weeks = buildHeatmapWeeks(pagesByDate)
                _uiState.update {
                    it.copy(
                        loading = false,
                        summary = summary,
                        heatmapWeeks = weeks,
                        maxHeatmapPages = weeks.flatten().filterNotNull().maxOfOrNull { d -> d.pages } ?: 1,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    /** 52 周 x 7 天，周一始，未来格 null；与假数据网格同构。 */
    private fun buildHeatmapWeeks(pagesByDate: Map<String, Int>): List<List<HeatmapDay?>> {
        val end = LocalDate.now()
        val start = end.minusWeeks(51).with(DayOfWeek.MONDAY)
        return (0 until 52).map { week ->
            (0 until 7).map { day ->
                val date = start.plusDays((week * 7 + day).toLong())
                if (date.isAfter(end)) {
                    null
                } else {
                    HeatmapDay(date.toString(), pagesByDate[date.toString()] ?: 0)
                }
            }
        }
    }

    private fun currentStreak(pagesByDate: Map<String, Int>): Int {
        // 今天无记录时从昨天起算（昨天还在连载中）
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
            0L -> "今天"
            1L -> "昨天"
            in 2..29 -> "${days} 天前"
            else -> date.toString()
        }
    }
}
