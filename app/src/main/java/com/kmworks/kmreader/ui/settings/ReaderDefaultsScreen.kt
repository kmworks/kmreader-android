package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.ui.reader.DivinaSettingsViewModel
import com.kmworks.kmreader.ui.reader.EpubSettingsContent
import com.kmworks.kmreader.ui.reader.PageLayout
import com.kmworks.kmreader.ui.reader.PdfSettingsContent
import com.kmworks.kmreader.ui.reader.ReadingDirection
import com.kmworks.kmreader.ui.reader.SplitWidePageMode

/**
 * 阅读器默认值（对齐原作 ReaderPreferencesView + Divina/Epub/Pdf preferences）。
 *
 * 语义对齐原作：这里的默认值是【全局偏好】，立即持久化生效；
 * 阅读器会话内改动的「方向/布局/拆分宽页/独显封面」只活在当次会话，不回写这里的默认值
 * （对齐原作 DivinaReaderView @State 初始值 + resetReaderPreferencesForCurrentBook 语义）。
 * 默认阅读方向走 AppSettingsStore（自动获得 client-settings 云同步），其余走 DivinaSettingsStore。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderDefaultsScreen(
    onBack: () -> Unit,
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
    readerSettingsViewModel: DivinaSettingsViewModel = hiltViewModel(),
) {
    // Divina defaults (mirrors DivinaPreferencesView)
    val persistedDirection by prefsViewModel.divinaDefaultDirection.collectAsStateWithLifecycle()
    val divinaDirection = ReadingDirection.entries
        .firstOrNull { it.name.lowercase() == persistedDirection } ?: ReadingDirection.Ltr
    val divinaSettings by readerSettingsViewModel.settings.collectAsStateWithLifecycle()
    val epubSettings by readerSettingsViewModel.epubSettings.collectAsStateWithLifecycle()
    val pdfSettings by readerSettingsViewModel.pdfSettings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读器默认") },
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
                DefaultsSectionTitle("Divina（漫画）")
                DefaultsChipRow(
                    label = "默认阅读方向",
                    options = ReadingDirection.entries.map { it.label },
                    selected = divinaDirection.label,
                    onSelect = { label ->
                        ReadingDirection.entries.firstOrNull { it.label == label }
                            ?.let { prefsViewModel.setDivinaDefaultDirection(it.name.lowercase()) }
                    },
                )
                DefaultsSwitchRow(
                    label = "强制默认阅读方向",
                    checked = divinaSettings.forceDefaultDirection,
                    onCheckedChange = { value ->
                        readerSettingsViewModel.update { it.copy(forceDefaultDirection = value) }
                    },
                )
                DefaultsHintText("开启后忽略系列自带的阅读方向（对齐原作 forceDefaultReadingDirection）")
                // 对齐 DivinaPreferencesView：页面布局 single/auto/dual
                DefaultsChipRow(
                    label = "页面布局",
                    options = PageLayout.entries.map { it.label },
                    selected = divinaSettings.defaultPageLayout.label,
                    onSelect = { label ->
                        PageLayout.entries.firstOrNull { it.label == label }?.let { layout ->
                            readerSettingsViewModel.update { it.copy(defaultPageLayout = layout) }
                        }
                    },
                )
                // 对齐 DivinaPreferencesView：拆分宽页是 5 档 picker（none/auto/ltr/rtl/scroll），不是开关
                DefaultsChipRow(
                    label = "拆分宽页",
                    options = SplitWidePageMode.entries.map { it.label },
                    selected = divinaSettings.defaultSplitWideMode.label,
                    onSelect = { label ->
                        SplitWidePageMode.entries.firstOrNull { it.label == label }?.let { mode ->
                            readerSettingsViewModel.update { it.copy(defaultSplitWideMode = mode) }
                        }
                    },
                )
                DefaultsHintText(divinaSettings.defaultSplitWideMode.hint)
                DefaultsSwitchRow(
                    label = "双页模式下独显封面",
                    checked = divinaSettings.defaultSoloCoverPage,
                    onCheckedChange = { value ->
                        readerSettingsViewModel.update { it.copy(defaultSoloCoverPage = value) }
                    },
                )
            }
            item {
                DefaultsSectionTitle("EPUB")
                EpubSettingsContent(
                    settings = epubSettings,
                    onSettingsChange = { new -> readerSettingsViewModel.updateEpub { new } },
                    customFonts = emptyList(),
                    onOpenPresets = { /* wiring point: open presets sheet（导航越界，待 lead 接线） */ },
                    onOpenCustomFonts = { /* wiring point: open CustomFontsScreen（导航越界，待 lead 接线） */ },
                )
            }
            item {
                DefaultsSectionTitle("PDF")
                PdfSettingsContent(
                    settings = pdfSettings,
                    onSettingsChange = { new -> readerSettingsViewModel.updatePdf { new } },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DefaultsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun DefaultsChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

@Composable
private fun DefaultsHintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun DefaultsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
