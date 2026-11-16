package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R

/** 主题选项（存储值 ↔ 显示标签）。 */
private val ThemeOptions = listOf(
    "system" to "跟随系统",
    "light" to "浅色",
    "dark" to "深色",
)

/** 应用图标选项（存储值 ↔ 显示标签；对齐 iOS AppIconOption）。 */
private val AppIconOptions = listOf(
    "primary" to "默认",
    "classic" to "经典",
    "legacy" to "旧版",
)

/** Appearance settings (mirrors SettingsAppearanceView + AppIconOption). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
) {
    // 主题/纯黑/应用图标走 AppSettingsStore 持久化（主题与纯黑自动获得 client-settings 云同步）
    val themeMode by prefsViewModel.themeMode.collectAsStateWithLifecycle()
    val trueBlack by prefsViewModel.trueBlackDark.collectAsStateWithLifecycle()
    val appIcon by prefsViewModel.appIcon.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观") },
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
                AppearanceSectionTitle("主题")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeOptions.forEach { option ->
                        FilterChip(
                            selected = themeMode == option.first,
                            onClick = { prefsViewModel.setThemeMode(option.first) },
                            label = { Text(option.second) },
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("纯黑深色", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "深色模式下使用纯黑背景（OLED 省电）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = trueBlack,
                        onCheckedChange = { prefsViewModel.setTrueBlackDark(it) },
                    )
                }
            }
            item {
                AppearanceSectionTitle("应用图标")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Mirrors AppIconOption: primary / classic / legacy
                    AppIconOptions.forEach { (value, label) ->
                        AppIconCell(
                            value = value,
                            label = label,
                            selected = appIcon == value,
                            onClick = { prefsViewModel.setAppIcon(value) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 图标预览：镜像 iOS AppIconPreview —— 真实启动图标的前景字形 + 背景填充。
 * primary/legacy 背景白/深灰，classic 为蓝色渐变（深色模式下 iOS 塌缩为深灰纯色）。
 */
@Composable
private fun AppIconCell(value: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val foreground = when (value) {
        "classic" -> R.mipmap.ic_launcher_classic_foreground
        "legacy" -> R.mipmap.ic_launcher_legacy_foreground
        else -> R.mipmap.ic_launcher_foreground
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (value == "classic" && !dark) {
                        Modifier.background(
                            Brush.verticalGradient(
                                // iOS appIconBackgroundClassicTop/Bottom
                                listOf(Color(0xFF275CC9), Color(0xFF0088FF)),
                            ),
                        )
                    } else {
                        Modifier.background(if (dark) Color(0xFF131316) else Color.White)
                    },
                )
                .then(
                    if (selected) {
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(14.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (selected) {
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppearanceSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
