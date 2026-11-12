package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.BookGridCard
import com.kmworks.kmreader.ui.components.CardDisplayOptions
import com.kmworks.kmreader.ui.components.LocalCardDisplay
import com.kmworks.kmreader.ui.components.SeriesGridCard

/**
 * 浏览设置（对齐 KMReader SettingsBrowseView）：卡片预览 + 显示项开关，全部持久化到
 * [com.kmworks.kmreader.core.settings.AppSettingsStore]，卡片组件经 [LocalCardDisplay] 实时生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsPrefsViewModel = hiltViewModel(),
) {
    val display by viewModel.cardDisplay.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("浏览") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                BrowseSectionTitle("卡片预览")
                CompositionLocalProvider(LocalCardDisplay provides display) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SeriesGridCard(
                            series = previewSeries,
                            onClick = { },
                            modifier = Modifier.width(132.dp),
                        )
                        BookGridCard(
                            book = previewInProgressBook,
                            onClick = { },
                            modifier = Modifier.width(132.dp),
                        )
                        BookGridCard(
                            book = previewCompletedBook,
                            onClick = { },
                            modifier = Modifier.width(132.dp),
                        )
                    }
                }
                Text(
                    text = "预览使用真实卡片组件渲染，开关立即生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                BrowseSectionTitle("卡片")
                BrowseSwitchRow(
                    title = "文字叠加封面",
                    subtitle = "标题与说明压在封面上，不占额外高度",
                    checked = display.textOverlay,
                    onCheckedChange = viewModel::setCardTextOverlay,
                )
                if (!display.textOverlay) {
                    BrowseSwitchRow(
                        title = "仅显示封面",
                        subtitle = "隐藏卡片上的全部文字",
                        checked = display.coverOnly,
                        onCheckedChange = viewModel::setCardCoverOnly,
                    )
                }
                BrowseSwitchRow(
                    title = "书籍卡显示系列名",
                    subtitle = "书籍卡第二行显示所属系列",
                    checked = display.showBookSeriesTitle,
                    onCheckedChange = viewModel::setCardShowBookSeriesTitle,
                )
                if (!display.textOverlay) {
                    BrowseSwitchRow(
                        title = "保持封面比例",
                        subtitle = "关闭后封面按方形裁切",
                        checked = display.preserveAspectRatio,
                        onCheckedChange = viewModel::setThumbnailPreserveAspectRatio,
                    )
                }
                BrowseSectionTitle("缩略图")
                BrowseSwitchRow(
                    title = "封面投影",
                    subtitle = "为封面加轻微阴影",
                    checked = display.showShadow,
                    onCheckedChange = viewModel::setThumbnailShowShadow,
                )
                BrowseSwitchRow(
                    title = "未读角标",
                    subtitle = "未读内容右上角显示圆点",
                    checked = display.showUnreadIndicator,
                    onCheckedChange = viewModel::setThumbnailShowUnreadIndicator,
                )
                BrowseSwitchRow(
                    title = "模糊未读封面",
                    subtitle = "未读内容封面做模糊处理",
                    checked = display.blurUnreadCovers,
                    onCheckedChange = viewModel::setThumbnailBlurUnreadCovers,
                )
                BrowseSwitchRow(
                    title = "显示进度条",
                    subtitle = "在卡片下方显示阅读进度",
                    checked = display.showProgressBar,
                    onCheckedChange = viewModel::setThumbnailShowProgressBar,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private val previewSeries = Series(
    id = 0,
    title = "示例系列",
    author = "示例作者",
    hue = 210f,
    bookCount = 12,
    unreadCount = 3,
    progress = 0.75f,
)

private val previewInProgressBook = Book(
    id = 0,
    number = "第 7 卷",
    title = "示例书籍",
    seriesTitle = "示例系列",
    hue = 210f,
    pageCount = 200,
    progress = 0.35f,
)

private val previewCompletedBook = Book(
    id = 0,
    number = "第 6 卷",
    title = "示例书籍",
    seriesTitle = "示例系列",
    hue = 210f,
    pageCount = 200,
    progress = 1f,
)

@Composable
private fun BrowseSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BrowseSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
