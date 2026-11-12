package com.kmworks.kmreader.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kmworks.kmreader.core.komf.parseKomfProviderLinks
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.CoverArt

/**
 * 用 komf 识别（对齐 KomfIdentifySheet）：
 * 搜索框（预填系列标题，300ms 防抖在 VM）→ Provider Links 段（系列 links 反解，多条时首行聚合）→
 * 结果段（40×60 封面 + 标题 + meta + provider 胶囊）；选中提交 identify → 任务进入轮询跟踪，
 * 提交成功即关闭 sheet，完成/失败由详情页 snackbar 通知。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomfIdentifySheet(
    series: Series,
    viewModel: SeriesDetailViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf(series.title) }
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var submitFailed by remember { mutableStateOf(false) }

    val providerLinks = remember(series) { parseKomfProviderLinks(series.links) }

    // 300ms 防抖在 VM；query 变化即转发（空查询由 VM 清空结果）
    LaunchedEffect(query) { viewModel.komfSearch(query) }

    val submit: (String, String, String) -> Unit = { key, provider, providerSeriesId ->
        pendingKey = key
        submitFailed = false
        viewModel.komfIdentify(
            provider = provider,
            providerSeriesId = providerSeriesId,
            onSubmitted = onDismiss,
            onFailed = {
                pendingKey = null
                submitFailed = true
            },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "用 komf 识别",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索 komf provider…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )

            if (submitFailed) {
                Text(
                    "识别提交失败，请重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // Provider Links（对齐：系列 metadata.links 反解的 provider 直链，>1 时首行聚合提交首个）
            if (providerLinks.isNotEmpty()) {
                KomfSectionTitle("Provider 链接")
                if (providerLinks.size > 1) {
                    val first = providerLinks.first()
                    KomfProviderRow(
                        icon = {
                            Icon(Icons.Filled.CloudSync, contentDescription = null)
                        },
                        title = "聚合全部 provider",
                        subtitle = "合并 ${providerLinks.size} 个 provider 直链",
                        pending = pendingKey == first.key,
                        enabled = pendingKey == null,
                        onClick = { submit(first.key, first.provider, first.providerSeriesId) },
                    )
                }
                providerLinks.forEach { link ->
                    KomfProviderRow(
                        icon = {
                            Icon(Icons.Filled.Link, contentDescription = null)
                        },
                        title = link.displayName,
                        subtitle = link.providerSeriesId,
                        pending = pendingKey == link.key,
                        enabled = pendingKey == null,
                        onClick = { submit(link.key, link.provider, link.providerSeriesId) },
                    )
                }
            }

            // 结果段（对齐 resultsSection：加载中/失败+重试/无结果/列表）
            KomfSectionTitle("搜索结果")
            when {
                state.komfSearching && state.komfSearchResults.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                state.komfSearchFailed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "搜索失败",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { viewModel.komfSearch(query) }) {
                            Text("重试")
                        }
                    }
                }
                query.isNotBlank() && state.komfSearchResults.isEmpty() && !state.komfSearching -> {
                    Text(
                        "无结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                else -> {
                    state.komfSearchResults.forEach { result ->
                        KomfResultRow(
                            title = result.title,
                            metaText = result.metaText,
                            provider = result.provider,
                            imageUrl = result.imageUrl,
                            fallbackHue = series.hue,
                            pending = pendingKey == result.key,
                            enabled = pendingKey == null,
                            onClick = { submit(result.key, result.provider, result.resultId) },
                        )
                    }
                }
            }

            // 任务跟踪状态（对齐 KomfJobTracker：提交后轮询，完成/失败/超时通知）
            if (state.komfJobRunning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "识别任务执行中…（完成后将收到通知）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun KomfResultRow(
    title: String,
    metaText: String,
    provider: String,
    imageUrl: String?,
    fallbackHue: Float,
    pending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面：imageUrl 存在时叠在占位上（对齐 AsyncImage 的加载/失败兜底）
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(60.dp),
            ) {
                CoverArt(
                    title = title,
                    hue = fallbackHue,
                    modifier = Modifier
                        .width(40.dp)
                        .height(60.dp),
                )
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(40.dp)
                            .height(60.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaText.isNotEmpty()) {
                    Text(
                        metaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (pending) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        provider,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun KomfSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun KomfProviderRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    pending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pending) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}
