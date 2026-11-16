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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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

/** provider 徽标：真实品牌 logo（保持原色，与 iOS 一致）；无资源时回退首字母圆块。 */
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
 * 详情页 Tracker 区（对齐 TrackerSeriesSection）：
 * 已链接 tracker chips（logo + 状态 + 进度）+ Add Tracker（链接数 < provider 数时显示）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackerSection(
    links: List<TrackerLink> = emptyList(),
    onManage: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            "阅读进度追踪",
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
                            Text(
                                buildString {
                                    append(link.remoteStatus.label)
                                    link.lastReadChapter?.let { ch ->
                                        append(" · 第 ${ch.toInt()} 话")
                                        link.totalChapters?.let { append(" / $it") }
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
                            "添加 Tracker",
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
 * Tracker 管理（对齐 TrackerSeriesSheet）：
 * 已链接列表（点开进入绑定编辑）+ 可添加的 provider 列表；
 * footer 提示：读完书籍推进 tracker，标记未读不会回退。
 * 有会话且服务器支持 Trackers 时走真实绑定；否则回退演示数据。
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "阅读进度追踪",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            Text(
                "已链接",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            if (!supported) {
                Text(
                    "当前服务器未启用 Tracker 集成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else if (links.isEmpty()) {
                Text(
                    "尚未链接任何 tracker",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            links.forEach { link ->
                TrackerLinkRow(
                    link = link,
                    onClick = { editingProvider = link.provider },
                )
            }

            val unlinked = TrackerProvider.entries.filter { p -> links.none { it.provider == p } }
            if (unlinked.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    "添加",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                unlinked.forEach { provider ->
                    Surface(
                        onClick = { editingProvider = provider },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
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
                                contentDescription = "链接 ${provider.displayName}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Text(
                "读完书籍会自动推进 tracker 进度；标记为未读不会回退。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    editingProvider?.let { provider ->
        TrackerLinkEditSheet(
            series = series,
            alternateTitles = alternateTitles,
            provider = provider,
            existing = links.firstOrNull { it.provider == provider },
            onUnlink = { viewModel.unlink(provider) },
            onDismiss = {
                editingProvider = null
                // 编辑（保存/解绑）后刷新真实绑定
                if (supported) viewModel.refresh()
            },
        )
    }
}

@Composable
private fun TrackerLinkRow(link: TrackerLink, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderLogo(link.provider)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    link.provider.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    buildString {
                        append(link.title)
                        append(" · #")
                        append(link.trackId)
                        link.lastReadChapter?.let { ch ->
                            append(" · 第 ${ch.toInt()} 话")
                            link.totalChapters?.let { append("/$it") }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
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
}

/** 系列 Tracker 绑定数据；服务器不支持时 supported=false（UI 显示提示，无演示回退）。 */
@HiltViewModel
class TrackerSeriesViewModel @Inject constructor(
    private val detailRepository: DetailRepository,
    private val trackerRepository: TrackerRepository,
) : ViewModel() {

    private val _links = MutableStateFlow<List<TrackerLink>>(emptyList())
    val links: StateFlow<List<TrackerLink>> = _links.asStateFlow()

    private val _supported = MutableStateFlow(true)
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    private var seriesDtoId: String? = null

    fun load(series: Series) {
        seriesDtoId = detailRepository.seriesDtoId(series.id)
        _supported.value = trackerRepository.supported
        if (seriesDtoId == null || !trackerRepository.supported) return
        refresh()
    }

    /** 重新拉取绑定并合并各 provider 的远端进度。 */
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
