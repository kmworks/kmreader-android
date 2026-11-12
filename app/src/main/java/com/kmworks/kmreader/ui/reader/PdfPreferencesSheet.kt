package com.kmworks.kmreader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.dp

/** PDF reader prefs (mirrors PdfPreferencesView / PdfReaderSettingsSheet). */
data class PdfReaderSettings(
    val continuousScroll: Boolean = false,
    val pagePresentation: String = "自动",
    val background: String = "跟随系统",
    val soloCoverPage: Boolean = true,
    val controlsScrim: Boolean = true,
    val showProgressWhileReading: Boolean = true,
)

/** PDF settings content (mirrors PdfPreferencesView sections). Shared by sheet and defaults page. */
@Composable
fun PdfSettingsContent(
    settings: PdfReaderSettings,
    onSettingsChange: (PdfReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        PdfSectionTitle("翻页")
        PdfSwitchRow(
            label = "连续滚动",
            subtitle = "关闭时逐页翻页",
            checked = settings.continuousScroll,
            onCheckedChange = { onSettingsChange(settings.copy(continuousScroll = it)) },
        )
        PdfChipRow(
            label = "页面呈现",
            options = listOf("自动", "单页", "单页连续", "双页连续"),
            selected = settings.pagePresentation,
            onSelect = { onSettingsChange(settings.copy(pagePresentation = it)) },
        )
        // Solo cover only meaningful for auto / dual (mirrors supportsCoverSolo)
        if (settings.pagePresentation == "自动" || settings.pagePresentation == "双页连续") {
            PdfSwitchRow(
                label = "封面独立成页",
                checked = settings.soloCoverPage,
                onCheckedChange = { onSettingsChange(settings.copy(soloCoverPage = it)) },
            )
        }

        PdfSectionTitle("外观")
        PdfChipRow(
            label = "阅读背景",
            options = listOf("跟随系统", "白", "灰", "黑"),
            selected = settings.background,
            onSelect = { onSettingsChange(settings.copy(background = it)) },
        )

        PdfSectionTitle("阅读覆盖层")
        PdfSwitchRow(
            label = "控制栏渐变底色",
            checked = settings.controlsScrim,
            onCheckedChange = { onSettingsChange(settings.copy(controlsScrim = it)) },
        )
        PdfSwitchRow(
            label = "阅读时显示进度条",
            checked = settings.showProgressWhileReading,
            onCheckedChange = { onSettingsChange(settings.copy(showProgressWhileReading = it)) },
        )
    }
}

/** PDF preferences sheet (mirrors PdfReaderSettingsSheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreferencesSheet(
    settings: PdfReaderSettings,
    onSettingsChange: (PdfReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "PDF 设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            PdfSettingsContent(settings = settings, onSettingsChange = onSettingsChange)
        }
    }
}

@Composable
private fun PdfSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun PdfSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
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
private fun PdfChipRow(
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
