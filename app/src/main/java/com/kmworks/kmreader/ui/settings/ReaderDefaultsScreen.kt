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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.data.SampleSystemData
import com.kmworks.kmreader.ui.reader.EpubReaderSettings
import com.kmworks.kmreader.ui.reader.EpubSettingsContent
import com.kmworks.kmreader.ui.reader.PdfReaderSettings
import com.kmworks.kmreader.ui.reader.PdfSettingsContent
import com.kmworks.kmreader.ui.reader.ReadingDirection
import com.kmworks.kmreader.ui.reader.SplitWidePageMode

/** Reader engine defaults hub (mirrors ReaderPreferencesView + Divina/Epub/Pdf preferences). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderDefaultsScreen(
    onBack: () -> Unit,
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
) {
    // Divina defaults (mirrors DivinaPreferencesView)
    // 默认阅读方向走 AppSettingsStore 持久化（自动获得 client-settings 云同步）
    val persistedDirection by prefsViewModel.divinaDefaultDirection.collectAsStateWithLifecycle()
    val divinaDirection = ReadingDirection.entries
        .firstOrNull { it.name.lowercase() == persistedDirection } ?: ReadingDirection.Ltr
    var divinaDualPage by remember { mutableStateOf(false) }
    var divinaSplitWideMode by remember { mutableStateOf(SplitWidePageMode.Auto) }
    // EPUB / PDF defaults reuse the option models from the reader sheets
    var epubDefaults by remember { mutableStateOf(EpubReaderSettings()) }
    var pdfDefaults by remember { mutableStateOf(PdfReaderSettings()) }

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
                    label = "横屏双页",
                    checked = divinaDualPage,
                    onCheckedChange = { divinaDualPage = it },
                )
                // 对齐 DivinaPreferencesView：拆分宽页是 5 档 picker（none/auto/ltr/rtl/scroll），不是开关
                DefaultsChipRow(
                    label = "拆分宽页",
                    options = SplitWidePageMode.entries.map { it.label },
                    selected = divinaSplitWideMode.label,
                    onSelect = { label ->
                        divinaSplitWideMode = SplitWidePageMode.entries.first { it.label == label }
                    },
                )
                DefaultsHintText(divinaSplitWideMode.hint)
            }
            item {
                DefaultsSectionTitle("EPUB")
                EpubSettingsContent(
                    settings = epubDefaults,
                    onSettingsChange = { epubDefaults = it },
                    customFonts = SampleSystemData.customFonts,
                    onOpenPresets = { /* wiring point: open presets sheet */ },
                    onOpenCustomFonts = { /* wiring point: open CustomFontsScreen */ },
                )
            }
            item {
                DefaultsSectionTitle("PDF")
                PdfSettingsContent(
                    settings = pdfDefaults,
                    onSettingsChange = { pdfDefaults = it },
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
