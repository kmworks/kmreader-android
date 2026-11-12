package com.kmworks.kmreader.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.ReadListContinuationUi
import com.kmworks.kmreader.data.ReadListsInProgressSectionId
import com.kmworks.kmreader.data.SampleDashboardData
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.BookHorizontalCard
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.ui.components.SectionHeader
import com.kmworks.kmreader.ui.components.SeriesGridCard

/** 仪表盘：分区列表，每分区 = 标题头 + 横向卡片流。对齐 KMReader DashboardView。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSeries: (Series) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBook: (Book) -> Unit = {},
    onOpenSectionDetail: (DashboardSection<out Any>) -> Unit = {},
    onOpenContinuation: (ReadListContinuationUi) -> Unit = {},
) {
    val viewModel: DashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    // 有会话且拉到数据用真实分区；否则保持 SampleData 演示
    val sections = state.sections ?: SampleData.dashboardSections

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("仪表盘") },
                actions = {
                    // 库范围切换（全部/已钉选/单库），对应 LibraryScopeMenu；仪表盘为独立会话内 scope
                    IconButton(onClick = { }) {
                        Icon(Icons.Outlined.Checklist, contentDescription = "库范围")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (state.loading) {
                item(key = "loading") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            state.error?.let { message ->
                item(key = "error") {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(sections, key = { it.id }) { section ->
                DashboardSectionRow(
                    section = section,
                    onOpenSeries = onOpenSeries,
                    onOpenBook = onOpenBook,
                    onOpenSectionDetail = onOpenSectionDetail,
                    onOpenContinuation = onOpenContinuation,
                    thumbnails = state.thumbnails,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DashboardSectionRow(
    section: DashboardSection<out Any>,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSectionDetail: (DashboardSection<out Any>) -> Unit,
    onOpenContinuation: (ReadListContinuationUi) -> Unit,
    thumbnails: Map<Int, String> = emptyMap(),
) {
    SectionHeader(
        title = section.title,
        onMore = { onOpenSectionDetail(section) },
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // read-list section renders continuation cards (aligned with ReadListsInProgressSectionView)
        if (section.id == ReadListsInProgressSectionId) {
            items(SampleDashboardData.readListContinuations, key = { it.readListId }) { continuation ->
                ReadListContinuationCard(
                    continuation = continuation,
                    onClick = { onOpenContinuation(continuation) },
                    modifier = Modifier.width(DashboardCardKind.Horizontal.widthDp.dp),
                )
            }
        } else {
            items(section.items) { item ->
                when {
                    // 书籍卡片打开书籍详情（对齐原作仪表盘卡片落点 BookDetailView）
                    item is Book && section.kind == DashboardCardKind.Horizontal ->
                        BookHorizontalCard(
                            book = item,
                            onClick = { onOpenBook(item) },
                            modifier = Modifier.width(DashboardCardKind.Horizontal.widthDp.dp),
                            imageUrl = thumbnails[item.id],
                        )

                    item is Book ->
                        BookGridCard(
                            book = item,
                            onClick = { onOpenBook(item) },
                            modifier = Modifier.width(section.kind.widthDp.dp),
                            imageUrl = thumbnails[item.id],
                        )

                    item is Series ->
                        SeriesGridCard(
                            series = item,
                            onClick = { onOpenSeries(item) },
                            modifier = Modifier.width(section.kind.widthDp.dp),
                            imageUrl = thumbnails[item.id],
                        )
                }
            }
        }
    }
}
