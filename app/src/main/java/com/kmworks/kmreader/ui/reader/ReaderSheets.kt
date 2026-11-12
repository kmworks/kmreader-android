package com.kmworks.kmreader.ui.reader

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 阅读方向（对齐原作 ReadingDirection：ltr/rtl/vertical/webtoon）。 */
enum class ReadingDirection(val label: String) {
    Ltr("从左到右"), Rtl("从右到左"), Vertical("垂直滚动"), Webtoon("条漫");

    val next: ReadingDirection get() = entries[(ordinal + 1) % entries.size]
}

/**
 * 宽页（跨页图）处理，对齐原作 SplitWidePageMode（Models/SplitWidePageMode.swift）：
 * none 不拆分 / auto 自动 / ltr·rtl 按方向切两半 / scroll 保留整幅并左右平移。
 */
enum class SplitWidePageMode(val label: String, val hint: String) {
    None("关闭", "宽图整页显示，不切分"),
    Auto("自动", "按页面内容自动判断是否切分"),
    Ltr("从左到右", "切成前后两半，按从左到右翻"),
    Rtl("从右到左", "切成前后两半，按从右到左翻"),
    Scroll("整幅平移", "保留整幅跨页图，可左右拖动");

    val isEnabled: Boolean get() = this != None
    val keepsWholeSpread: Boolean get() = this == Scroll
    val next: SplitWidePageMode get() = entries[(ordinal + 1) % entries.size]
}

/**
 * 阅读器顶栏「更多」菜单（对应 DivinaControlsOverlayView+Menu）：
 * 当前阅读选项（方向/布局循环切换）→ 设置/目录/跳页/分享。
 */
@Composable
fun ReaderOptionsMenu(
    expanded: Boolean,
    direction: ReadingDirection,
    dualPage: Boolean,
    splitWideMode: SplitWidePageMode,
    rotationDegrees: Int,
    onDirectionChange: (ReadingDirection) -> Unit,
    onDualPageChange: (Boolean) -> Unit,
    onSplitWideModeChange: (SplitWidePageMode) -> Unit,
    onRotationChange: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenPageJump: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Current Reading Options（条漫模式只保留阅读方向，对齐原作的门控）
        MenuCycleItem(
            label = "阅读方向",
            value = direction.label,
            onClick = { onDirectionChange(direction.next) },
        )
        if (direction != ReadingDirection.Webtoon) {
            MenuCycleItem(
                label = "页面布局",
                value = if (dualPage) "双页" else "单页",
                onClick = { onDualPageChange(!dualPage) },
            )
            MenuCycleItem(
                label = "拆分宽页",
                value = splitWideMode.label,
                onClick = { onSplitWideModeChange(splitWideMode.next) },
            )
            MenuCycleItem(
                label = "页面旋转",
                value = if (rotationDegrees == 0) "无" else "$rotationDegrees°",
                onClick = { onRotationChange((rotationDegrees + 90) % 360) },
            )
        }
        HorizontalDivider()
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("阅读器设置") },
            onClick = {
                onDismiss()
                onOpenSettings()
            },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("目录") },
            onClick = {
                onDismiss()
                onOpenToc()
            },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("跳页") },
            onClick = {
                onDismiss()
                onOpenPageJump()
            },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("分享页面") },
            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
            onClick = onDismiss,
        )
    }
}

@Composable
private fun MenuCycleItem(label: String, value: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.weight(1f))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * 阅读器设置面板（对应 ReaderSettingsSheet）：
 * 外观 / 翻页 / 手势 / 性能 / 图像处理。
 * 差异说明：面板分镜（Panel Mode）与 Waifu2x 超分按用户决定不实现，仅记录差异。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(onDismiss: () -> Unit) {
    var background by remember { mutableStateOf("跟随系统") }
    var showPageNumber by remember { mutableStateOf(true) }
    var pageShadow by remember { mutableStateOf(true) }
    var controlsGradient by remember { mutableStateOf(true) }
    var progressBar by remember { mutableStateOf(true) }
    var transitionStyle by remember { mutableStateOf("覆盖") }
    var animateTapTurn by remember { mutableStateOf(true) }
    var tapZoneMode by remember { mutableStateOf("左中右") }
    var tapZoneMirroring by remember { mutableStateOf(false) }
    var showTapZoneHints by remember { mutableStateOf(false) }
    var doubleTapAction by remember { mutableStateOf("缩放") }
    var doubleTapScale by remember { mutableStateOf(2.5f) }
    var longPressAction by remember { mutableStateOf("无") }
    var preloadProfile by remember { mutableStateOf("均衡") }
    var borderCrop by remember { mutableStateOf("关闭") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "阅读器设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            SettingsSectionTitle("外观")
            SettingsChipRow(
                label = "阅读器背景",
                options = listOf("跟随系统", "白色", "黑色"),
                selected = background,
                onSelect = { background = it },
            )
            SettingsSwitchRow("显示页码", showPageNumber) { showPageNumber = it }
            SettingsSwitchRow("页面阴影", pageShadow) { pageShadow = it }
            SettingsSwitchRow("控件渐变背景", controlsGradient) { controlsGradient = it }
            SettingsSwitchRow("阅读时显示进度条", progressBar) { progressBar = it }

            SettingsSectionTitle("翻页")
            SettingsChipRow(
                label = "翻页动画样式",
                options = listOf("覆盖", "仿真卷页", "滚动"),
                selected = transitionStyle,
                onSelect = { transitionStyle = it },
            )
            SettingsSwitchRow("点按翻页动画", animateTapTurn) { animateTapTurn = it }
            SettingsChipRow(
                label = "点按区域布局",
                options = listOf("左中右", "四分区", "九宫格"),
                selected = tapZoneMode,
                onSelect = { tapZoneMode = it },
            )
            SettingsSwitchRow("点按区域镜像", tapZoneMirroring) { tapZoneMirroring = it }
            SettingsSwitchRow("显示点按区域提示", showTapZoneHints) { showTapZoneHints = it }

            SettingsSectionTitle("手势")
            SettingsChipRow(
                label = "双击动作",
                options = listOf("缩放", "无"),
                selected = doubleTapAction,
                onSelect = { doubleTapAction = it },
            )
            SettingsSliderRow(
                label = "双击缩放倍率",
                value = doubleTapScale,
                valueRange = 1f..8f,
                display = "%.1fx".format(doubleTapScale),
                onValueChange = { doubleTapScale = (it * 2).roundToInt() / 2f },
            )
            SettingsChipRow(
                label = "长按动作",
                options = listOf("无", "分享页面"),
                selected = longPressAction,
                onSelect = { longPressAction = it },
            )

            SettingsSectionTitle("性能")
            SettingsChipRow(
                label = "图片预加载",
                options = listOf("保守", "均衡", "激进"),
                selected = preloadProfile,
                onSelect = { preloadProfile = it },
            )

            SettingsSectionTitle("图像处理")
            SettingsChipRow(
                label = "裁边模式",
                options = listOf("关闭", "自动", "手动"),
                selected = borderCrop,
                onSelect = { borderCrop = it },
            )
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                display,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

/** 目录条目（设计稿静态示例）。 */
data class TocEntry(
    val title: String,
    val page: Int,
    val children: List<TocEntry> = emptyList(),
)

val SampleToc = listOf(
    TocEntry("第 87 话 启程", 3),
    TocEntry("第 88 话 魔法使的弟子", 24, children = listOf(TocEntry("回忆", 30))),
    TocEntry("第 89 话 考验", 47, children = listOf(TocEntry("插曲", 52), TocEntry("觉醒", 61))),
    TocEntry("第 90 话 新的旅程", 78),
)

/** 目录（对应 DivinaTOCSheetView：树形、当前项书签标记、含当前项子树自动展开）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTocSheet(currentPage: Int, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "目录",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            SampleToc.forEach { entry ->
                TocNode(entry = entry, depth = 0, currentPage = currentPage, onJump = onJump)
            }
        }
    }
}

@Composable
private fun TocNode(entry: TocEntry, depth: Int, currentPage: Int, onJump: (Int) -> Unit) {
    val endPage = entry.children.lastOrNull()?.page ?: entry.page
    val isCurrent = currentPage in entry.page..endPage
    var expanded by remember { mutableStateOf(isCurrent) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (entry.children.isNotEmpty()) expanded = !expanded else onJump(entry.page)
            }
            .padding(start = (12 + depth * 20).dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.children.isNotEmpty()) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(22.dp))
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Icon(
                Icons.Filled.Bookmark,
                contentDescription = "当前位置",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "${entry.page}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        entry.children.forEach { child ->
            TocNode(entry = child, depth = depth + 1, currentPage = currentPage, onJump = onJump)
        }
    }
}

/** 跳页（对应 PageJumpSheetView：预览 + 滑杆 + 页码）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPageJumpSheet(currentPage: Int, pageCount: Int, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    var target by remember { mutableIntStateOf(currentPage) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("跳页", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            // 页面预览占位（真实实现 = 该页缩略图）
            Surface(
                modifier = Modifier
                    .width(160.dp)
                    .height(240.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "第 $target 页",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Slider(
                value = target.toFloat(),
                onValueChange = { target = it.roundToInt().coerceIn(1, pageCount) },
                valueRange = 1f..pageCount.toFloat(),
            )
            Text(
                "$target / $pageCount",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = onDismiss) { Text("取消") }
                androidx.compose.material3.Button(onClick = { onJump(target) }) { Text("跳转") }
            }
        }
    }
}

/** 底栏页码胶囊（对应 DivinaControlsOverlayView 底部中央 capsule：书签图标 + 当前/总页，点开 PageJumpSheetView）。 */
@Composable
fun PageJumpCapsule(page: Int, pageCount: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bookmark,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$page / $pageCount",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
    }
}
