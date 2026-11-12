package com.kmworks.kmreader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.fonts.FontRepository
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.CustomFont
import com.kmworks.kmreader.data.SampleSystemData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Server font list state (mirrors Komga FontsApi 家庭管理)。 */
@HiltViewModel
class CustomFontsViewModel @Inject constructor(
    sessionManager: SessionManager,
    private val fontRepository: FontRepository,
) : ViewModel() {

    val hasSession: Boolean = sessionManager.clients.value != null

    private val _families = MutableStateFlow<List<String>?>(null)
    val families: StateFlow<List<String>?> = _families.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    init {
        refreshDownloaded()
        if (hasSession) {
            viewModelScope.launch {
                _families.value = fontRepository.families()
            }
        }
    }

    fun refreshDownloaded() {
        _downloaded.value = fontRepository.downloadedFamilies()
    }

    fun download(family: String) {
        if (family in _downloading.value) return
        viewModelScope.launch {
            _downloading.value = _downloading.value + family
            fontRepository.downloadFontFamily(family)
            _downloading.value = _downloading.value - family
            refreshDownloaded()
        }
    }

    fun delete(family: String) {
        fontRepository.deleteFontFamily(family)
        refreshDownloaded()
    }
}

/** Custom font manager (mirrors CustomFontsSheet + Komga FontsApi 下载路线)，无会话回退演示列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFontsScreen(
    onBack: () -> Unit,
    viewModel: CustomFontsViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自定义字体") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (viewModel.hasSession) {
            RealFontList(viewModel = viewModel, modifier = Modifier.padding(padding))
        } else {
            DemoFontList(modifier = Modifier.padding(padding))
        }
    }
}

/** Real server font families with download/delete actions. */
@Composable
private fun RealFontList(viewModel: CustomFontsViewModel, modifier: Modifier = Modifier) {
    val families by viewModel.families.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item {
            Text(
                "已下载 ${downloaded.size} 款字体 · 服务端共 ${families?.size ?: "…"} 款",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        val list = families
        if (list == null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "正在获取字体列表…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(list, key = { it }) { family ->
                ServerFontRow(
                    family = family,
                    downloaded = family in downloaded,
                    downloading = family in downloading,
                    onDownload = { viewModel.download(family) },
                    onDelete = { viewModel.delete(family) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** One server family row: name + status + download/delete action. */
@Composable
private fun ServerFontRow(
    family: String,
    downloaded: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                family,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                when {
                    downloading -> "下载中…"
                    downloaded -> "已下载，可在 EPUB 阅读器中选择"
                    else -> "未下载"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            downloading -> CircularProgressIndicator(modifier = Modifier.size(22.dp))
            downloaded -> {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "已下载",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> IconButton(onClick = onDownload) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "下载",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Demo fallback list (no session). */
@Composable
private fun DemoFontList(modifier: Modifier = Modifier) {
    val fonts = remember { SampleSystemData.customFonts.toMutableStateList() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item {
            OutlinedButton(
                onClick = { /* wiring point: file picker for .ttf/.otf */ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text("导入字体文件（.ttf / .otf）")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "已安装 ${fonts.size} 款自定义字体",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        items(fonts, key = { it.id }) { font ->
            DemoFontRow(font = font, onDelete = { fonts.remove(font) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun DemoFontRow(font: CustomFont, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                font.name,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${font.fileName} · ${font.sizeLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
