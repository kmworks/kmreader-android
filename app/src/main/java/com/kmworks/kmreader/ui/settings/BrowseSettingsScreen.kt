package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.SampleData
import com.kmworks.kmreader.ui.components.SeriesGridCard

/** Browse settings (mirrors SettingsBrowseView): live card preview + display toggles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseSettingsScreen(onBack: () -> Unit) {
    var showTitle by remember { mutableStateOf(true) }
    var showProgress by remember { mutableStateOf(true) }
    var showUnreadBadge by remember { mutableStateOf(true) }

    // Preview source: first series from sample data
    val previewSeries = SampleData.series.first()

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
                Row(modifier = Modifier.fillMaxWidth()) {
                    SeriesGridCard(
                        series = previewSeries,
                        onClick = { },
                        modifier = Modifier.width(140.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "预览使用真实卡片组件渲染",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                BrowseSectionTitle("显示项")
                BrowseSwitchRow(
                    title = "显示标题",
                    checked = showTitle,
                    onCheckedChange = { showTitle = it },
                )
                BrowseSwitchRow(
                    title = "显示进度条",
                    checked = showProgress,
                    onCheckedChange = { showProgress = it },
                )
                BrowseSwitchRow(
                    title = "显示未读角标",
                    checked = showUnreadBadge,
                    onCheckedChange = { showUnreadBadge = it },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BrowseSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
