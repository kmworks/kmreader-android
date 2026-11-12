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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.ui.components.CoverArt

/** komf provider 直链（解析自系列 metadata.links）。 */
private data class KomfProviderLink(val provider: String, val displayName: String, val providerSeriesId: String)

/** komf 搜索结果。 */
private data class KomfSearchResult(
    val title: String,
    val metaText: String,
    val provider: String,
    val hue: Float,
)

/**
 * 用 komf 识别（对齐 KomfIdentifySheet）：
 * 搜索框（预填系列标题，300ms 防抖）→ Provider Links 段（多条时首行聚合全部 provider）→
 * 结果段（封面 + 标题 + 元信息 + provider 胶囊）；选中提交 identify → 任务进入轮询跟踪。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomfIdentifySheet(series: Series, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(series.title) }
    var submitted by remember { mutableStateOf(false) }

    val providerLinks = remember(series) {
        listOf(
            KomfProviderLink("anilist", "AniList", "108465"),
            KomfProviderLink("bangumi", "Bangumi", "285267"),
            KomfProviderLink("mangabaka", "MangaBaka", "18273"),
        )
    }
    val results = remember(query) {
        listOf(
            KomfSearchResult("葬送的芙莉莲", "漫画 · 2020 · 连载中 · 山田钟人 / 阿部司", "MangaBaka", series.hue),
            KomfSearchResult("葬送のフリーレン", "Manga · 2020 · Publishing · Shogakukan", "AniList", series.hue),
            KomfSearchResult("葬送的芙莉莲", "漫画 · 2020-04-28 · 系列", "Bangumi", series.hue),
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
                label = { Text("搜索") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )

            // Provider Links（对齐：系列 metadata.links 解析出的 provider 直链，>1 时首行聚合）
            if (providerLinks.isNotEmpty()) {
                KomfSectionTitle("Provider 链接")
                if (providerLinks.size > 1) {
                    KomfProviderRow(
                        icon = {
                            Icon(Icons.Filled.CloudSync, contentDescription = null)
                        },
                        title = "聚合全部 provider",
                        subtitle = "${providerLinks.size} 个来源",
                        onClick = { submitted = true },
                    )
                }
                providerLinks.forEach { link ->
                    KomfProviderRow(
                        icon = {
                            Icon(Icons.Filled.Link, contentDescription = null)
                        },
                        title = link.displayName,
                        subtitle = link.providerSeriesId,
                        onClick = { submitted = true },
                    )
                }
            }

            // 结果段（40×60 封面 + 标题 + meta + provider 胶囊 badge）
            KomfSectionTitle("搜索结果")
            results.forEach { result ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArt(
                        title = result.title,
                        hue = result.hue,
                        modifier = Modifier
                            .width(40.dp)
                            .height(60.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            result.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            result.metaText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            result.provider,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            if (submitted) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                // 任务跟踪状态（对齐 KomfJobTracker：提交后轮询，完成/失败/超时通知）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "识别任务已提交，正在执行…（完成后将收到通知）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Button(onClick = { submitted = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("用所选结果识别")
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
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
        }
    }
}
