package com.kmworks.kmreader.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs
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

/** 菜单值选择弹层（对齐 iOS 菜单 picker 的子菜单语义）。 */
private enum class ReaderMenuPicker { Direction, Layout, Split, Rotation }

/**
 * 阅读器顶栏「更多」菜单（对应 DivinaControlsOverlayView+Menu）：
 * Current Reading Options（方向/布局/拆分宽页/旋转，点开选项弹层，对齐 iOS picker 子菜单）→
 * 阅读器设置 → 当前页（分享/独显）→ 跳页。
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
    var picker by remember { mutableStateOf<ReaderMenuPicker?>(null) }
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Current Reading Options（条漫模式只保留阅读方向，对齐原作的门控）
        MenuSectionHeader("当前阅读选项")
        MenuValueItem(label = "阅读方向", value = direction.label) {
            onDismiss()
            picker = ReaderMenuPicker.Direction
        }
        if (direction != ReadingDirection.Webtoon) {
            MenuValueItem(label = "页面布局", value = pageLayout.label) {
                onDismiss()
                picker = ReaderMenuPicker.Layout
            }
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
            MenuValueItem(label = "拆分宽页", value = splitWideMode.label) {
                onDismiss()
                picker = ReaderMenuPicker.Split
            }
            MenuValueItem(
                label = "页面旋转",
                value = if (rotationDegrees == 0) "无" else "$rotationDegrees°",
            ) {
                onDismiss()
                picker = ReaderMenuPicker.Rotation
            }
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
        MenuSectionHeader("当前页面")
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
        HorizontalDivider()
        MenuSectionHeader("页面导航")
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("跳页") },
            leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenPageJump()
            },
        )
    }

    // 选项弹层（对齐 iOS picker 子菜单：全选项 + 当前值勾选）
    when (picker) {
        ReaderMenuPicker.Direction -> OptionPickerDialog(
            title = "阅读方向",
            options = ReadingDirection.entries.map { it.label },
            selected = direction.label,
            onSelect = { label ->
                ReadingDirection.entries.firstOrNull { it.label == label }?.let(onDirectionChange)
                picker = null
            },
            onDismiss = { picker = null },
        )
        ReaderMenuPicker.Layout -> OptionPickerDialog(
            title = "页面布局",
            options = PageLayout.entries.map { it.label },
            selected = pageLayout.label,
            onSelect = { label ->
                PageLayout.entries.firstOrNull { it.label == label }?.let(onPageLayoutChange)
                picker = null
            },
            onDismiss = { picker = null },
        )
        ReaderMenuPicker.Split -> OptionPickerDialog(
            title = "拆分宽页",
            options = SplitWidePageMode.entries.map { it.label },
            selected = splitWideMode.label,
            hints = SplitWidePageMode.entries.associate { it.label to it.hint },
            onSelect = { label ->
                SplitWidePageMode.entries.firstOrNull { it.label == label }?.let(onSplitWideModeChange)
                picker = null
            },
            onDismiss = { picker = null },
        )
        ReaderMenuPicker.Rotation -> {
            val options = listOf("无", "90°", "180°", "270°")
            OptionPickerDialog(
                title = "页面旋转",
                options = options,
                selected = if (rotationDegrees == 0) "无" else "$rotationDegrees°",
                onSelect = { label ->
                    onRotationChange(
                        when (label) {
                            "90°" -> 90
                            "180°" -> 180
                            "270°" -> 270
                            else -> 0
                        },
                    )
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }
        null -> Unit
    }
}

/** 菜单分节标题（对齐 iOS Menu Section header）。 */
@Composable
private fun MenuSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 菜单值项：标签 + 当前值 + 箭头（点开选项弹层，对齐 iOS picker 行）。 */
@Composable
private fun MenuValueItem(label: String, value: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, modifier = Modifier.weight(1f))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        onClick = onClick,
    )
}

/** 选项弹层：全选项单选列表，当前值尾部勾选（对齐 iOS 菜单 picker 子菜单）。 */
@Composable
private fun OptionPickerDialog(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    hints: Map<String, String> = emptyMap(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(option)
                            hints[option]?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (option == selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * 跳页（对齐原作 PageJumpSheetView：横向吸附的大缩略图卡 + 滑杆 + 跳转按钮）：
 * - 卡片横向滚动、吸附居中（iOS scrollTargetBehavior(.viewAligned)），居中项即目标页；
 * - 选中卡 scale 1.0 + 描边 + 深阴影，未选 0.9（iOS PageJumpPreviewCard）；
 * - 滑杆四舍五入同步（iOS sliderBinding），跳转在目标等于当前页时禁用；
 * - RTL 方向时横向布局镜像（iOS layoutDirection 右到左）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderPageJumpSheet(
    currentPage: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
    imageLoader: ImageLoader? = null,
    isRtl: Boolean = false,
    pageThumbnailUrl: (Int) -> String? = { null },
    onShare: ((Int) -> Unit)? = null,
) {
    var target by remember { mutableIntStateOf(currentPage) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        BoxWithConstraints {
            // 卡片宽 = 面板宽 0.72（对齐 iOS PageJumpPreviewCard.widthRatio）
            val cardWidth = this.maxWidth * 0.72f
            val sidePadding = (this.maxWidth - cardWidth) / 2
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = (currentPage - 1).coerceAtLeast(0),
            )
            // 居中项 → 目标页同步（对齐 iOS scrollPosition 绑定）
            val centeredPage by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val visible = info.visibleItemsInfo
                    if (visible.isEmpty()) {
                        target
                    } else {
                        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                        (visible.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: 0) + 1
                    }
                }
            }
            LaunchedEffect(centeredPage) {
                if (centeredPage != target) target = centeredPage
            }
            Column(modifier = Modifier.padding(bottom = 28.dp)) {
                Text(
                    "跳页",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(16.dp))
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Column {
                        LazyRow(
                            state = listState,
                            flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                            contentPadding = PaddingValues(horizontal = sidePadding),
                        ) {
                            items(pageCount) { index ->
                                val pageNumber = index + 1
                                val selected = pageNumber == target
                                val scale by animateFloatAsState(
                                    targetValue = if (selected) 1f else 0.9f,
                                    label = "jumpCardScale",
                                )
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(cardWidth)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .clickable {
                                            target = pageNumber
                                            scope.launch { listState.animateScrollToItem(index) }
                                        },
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 8.dp)
                                            .fillMaxWidth()
                                            .height(300.dp)
                                            .shadow(
                                                elevation = if (selected) 8.dp else 4.dp,
                                                shape = RoundedCornerShape(8.dp),
                                                ambientColor = Color.Black.copy(alpha = if (selected) 0.3f else 0.15f),
                                                spotColor = Color.Black.copy(alpha = if (selected) 0.3f else 0.15f),
                                            )
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .then(
                                                if (selected) {
                                                    Modifier.border(
                                                        3.dp,
                                                        MaterialTheme.colorScheme.primary,
                                                        RoundedCornerShape(8.dp),
                                                    )
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val thumb = pageThumbnailUrl(pageNumber)
                                        if (thumb != null && imageLoader != null) {
                                            AsyncImage(
                                                model = thumb,
                                                contentDescription = "第 $pageNumber 页",
                                                imageLoader = imageLoader,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Text(
                                                "$pageNumber",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Text(
                                        "$pageNumber",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                        // 滑杆（iOS sliderBinding：四舍五入吸附整页）
                        Slider(
                            value = target.toFloat(),
                            onValueChange = {
                                val next = it.roundToInt().coerceIn(1, pageCount)
                                if (next != target) {
                                    target = next
                                    scope.launch { listState.animateScrollToItem(next - 1) }
                                }
                            },
                            valueRange = 1f..pageCount.coerceAtLeast(2).toFloat(),
                            enabled = pageCount > 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "1",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "$target / $pageCount",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "$pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 分享（对齐 iOS 跳页面板 controls 区的分享按钮；无分享通道时不显示）
                    if (onShare != null) {
                        androidx.compose.material3.OutlinedButton(onClick = { onShare(target) }) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("分享此页")
                        }
                    }
                    androidx.compose.material3.Button(
                        onClick = { onJump(target) },
                        enabled = target != currentPage,
                    ) { Text("跳转") }
                }
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
