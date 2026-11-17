package com.kmworks.kmreader.ui.reader

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** 页面布局（对齐原作 PageLayout：single/auto/dual-forced；双页只在横屏生效，由阅读器按屏幕方向门控）。 */
enum class PageLayout(val label: String) {
    Single("单页"),
    Auto("自动"),
    Dual("双页");

    val next: PageLayout get() = entries[(ordinal + 1) % entries.size]
}

/** 菜单「当前页」区的独显动作（对应原作 ReaderPageSoloActions；null = 当前页不可独显）。 */
data class SoloMenuAction(
    val isSolo: Boolean,
    val displayPage: Int,
    val onToggle: () -> Unit,
)

/**
 * 阅读器顶栏「更多」菜单（对应 DivinaControlsOverlayView+Menu）：
 * Current Reading Options（方向/布局/拆分宽页/旋转循环）→ 阅读器设置 → 当前页（分享/独显）→ 跳页。
 * 原作菜单里还有目录，但 DIVINA（图片）书籍没有目录数据源，故不显示（对齐 hasTOC 门控）。
 */
@Composable
fun ReaderOptionsMenu(
    expanded: Boolean,
    direction: ReadingDirection,
    pageLayout: PageLayout,
    splitWideMode: SplitWidePageMode,
    rotationDegrees: Int,
    soloAction: SoloMenuAction?,
    soloCoverPage: Boolean,
    onDirectionChange: (ReadingDirection) -> Unit,
    onPageLayoutChange: (PageLayout) -> Unit,
    onToggleSoloCover: () -> Unit,
    onSplitWideModeChange: (SplitWidePageMode) -> Unit,
    onRotationChange: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onSharePage: () -> Unit,
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
                value = pageLayout.label,
                onClick = { onPageLayoutChange(pageLayout.next) },
            )
            // 独显封面（对齐原作 Solo Cover Page：仅自动/双页布局且非垂直滚动时可选）
            if (direction != ReadingDirection.Vertical && pageLayout != PageLayout.Single) {
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("独显封面", modifier = Modifier.weight(1f))
                            if (soloCoverPage) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onDismiss()
                        onToggleSoloCover()
                    },
                )
            }
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
        HorizontalDivider()
        // Current Page（对齐原作：分享 + 双页时的单页独显切换）
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("分享页面") },
            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
            onClick = {
                onDismiss()
                onSharePage()
            },
        )
        if (soloAction != null) {
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Text(
                        if (soloAction.isSolo) "取消独显"
                        else "独显第 ${soloAction.displayPage} 页",
                    )
                },
                onClick = {
                    onDismiss()
                    soloAction.onToggle()
                },
            )
        }
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("跳页") },
            onClick = {
                onDismiss()
                onOpenPageJump()
            },
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
