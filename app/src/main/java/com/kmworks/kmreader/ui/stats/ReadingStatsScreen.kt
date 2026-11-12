package com.kmworks.kmreader.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.data.HeatmapDay
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.SampleSystemData
import com.kmworks.kmreader.ui.components.BookHorizontalCard

/** Reading history stats (mirrors ServerReadingStatsView + ReadingPagesHeatmapView).
 *  kmrs 能力门控：无会话回退假数据演示；stock Komga（无 ReadingStats 特性）显示占位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(
    onBack: () -> Unit,
    viewModel: ReadingStatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // 真实数据优先；无会话（或未加载完成前）回退假数据演示
    val stats = state.summary ?: SampleSystemData.readingStats
    val weeks = state.heatmapWeeks ?: SampleSystemData.heatmapWeeks
    val maxPages = if (state.heatmapWeeks != null) state.maxHeatmapPages else SampleSystemData.maxHeatmapPages
    var selectedDay by remember { mutableStateOf<HeatmapDay?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (state.unavailable) {
            // kmrs 能力缺失：占位说明，不报错
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "该服务器不支持阅读统计（kmrs 特性）",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                state.error?.let { error ->
                    Text(
                        text = "加载失败：$error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // Overview cards (mirrors stats grid; 4 key figures)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatCard(
                        label = "总页数",
                        value = "%,d".format(stats.totalPages),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "总时长",
                        value = "${stats.totalMinutes / 60} 小时",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatCard(
                        label = "完成册数",
                        value = "${stats.booksCompleted}",
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "连续天数",
                        value = "${stats.currentStreakDays} 天",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "最长连续 ${stats.longestStreakDays} 天 · 阅读 ${stats.readingDays} 天 · 每册均 ${stats.avgPagesPerBook} 页 · 最近阅读：${stats.lastReadLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                StatsSectionTitle("页数热力图")
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
                        "${day.date} · ${day.pages} 页",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 「最近阅读」不在统计 API 内（iOS 用本地历史）：仅演示态显示
                if (!state.hasSession) {
                    StatsSectionTitle("最近阅读")
                }
            }
            if (!state.hasSession) {
                items(SampleData.recentlyRead, key = { it.id }) { book ->
                    BookHorizontalCard(book = book, onClick = { })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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

/** Grayscale density: 0 pages = faint, more pages = more opaque (mirrors 4-level ramp). */
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
            "少",
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
            "多",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}
