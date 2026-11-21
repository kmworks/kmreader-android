package com.kmworks.kmreader.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.ui.components.AppModalDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.kmworks.kmreader.R
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Reading direction (mirrors the original ReadingDirection: ltr/rtl/vertical/webtoon). */
enum class ReadingDirection(@StringRes val labelRes: Int) {
    Ltr(R.string.reader_direction_ltr), Rtl(R.string.reader_direction_rtl), Vertical(R.string.reader_direction_vertical), Webtoon(R.string.reader_direction_webtoon);

    val next: ReadingDirection get() = entries[(ordinal + 1) % entries.size]
}

/**
 * Wide-page (spread) handling, mirroring the original SplitWidePageMode (Models/SplitWidePageMode.swift),
 * minus its `scroll` mode: none = no split / auto = automatic / ltr·rtl = split into two halves by
 * direction. Whole-spread panning was removed because it never rendered reliably on Android.
 */
enum class SplitWidePageMode(@StringRes val labelRes: Int, @StringRes val hintRes: Int) {
    None(R.string.reader_split_none, R.string.reader_split_hint_none),
    Auto(R.string.reader_split_auto, R.string.reader_split_hint_auto),
    Ltr(R.string.reader_split_ltr, R.string.reader_split_hint_ltr),
    Rtl(R.string.reader_split_rtl, R.string.reader_split_hint_rtl);

    val isEnabled: Boolean get() = this != None
    val next: SplitWidePageMode get() = entries[(ordinal + 1) % entries.size]

    /**
     * Reading direction the halves follow (mirrors the original `effectiveReadingDirection`): ltr/rtl
     * override the reader direction, every other mode follows it.
     */
    fun effectiveReadingDirection(readingDirection: ReadingDirection): ReadingDirection = when (this) {
        None, Auto -> readingDirection
        Ltr -> ReadingDirection.Ltr
        Rtl -> ReadingDirection.Rtl
    }
}

/** Page layout (mirrors the original PageLayout: single/auto/dual-forced; dual pages only take effect in landscape, gated by the reader based on screen orientation). */
enum class PageLayout(@StringRes val labelRes: Int) {
    Single(R.string.reader_page_layout_single),
    Auto(R.string.reader_page_layout_auto),
    Dual(R.string.reader_page_layout_dual);

    val next: PageLayout get() = entries[(ordinal + 1) % entries.size]
}

/** Solo action in the menu's "Current Page" section (mirrors the original ReaderPageSoloActions; null = current page cannot be solo). */
data class SoloMenuAction(
    val isSolo: Boolean,
    val displayPage: Int,
    val onToggle: () -> Unit,
)

/** Menu value picker dialog (mirrors the submenu semantics of the iOS menu picker). */
private enum class ReaderMenuPicker { Direction, Layout, Split, Rotation }

/**
 * Reader top-bar "More" menu (mirrors DivinaControlsOverlayView+Menu):
 * Current Reading Options (direction/layout/split wide pages/rotation, opening option dialogs like iOS picker submenus) →
 * Reader Settings → Current Page (share/solo) → Go to Page.
 * The original menu also has a table of contents, but DIVINA (image) books have no TOC data source, so it is not shown (mirrors the hasTOC gate).
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
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // Fixed width: value rows (label + current value + chevron) need breathing room; content-sized width would feel cramped
        modifier = Modifier.width(280.dp),
    ) {
        // The book detail entry lives in the top-bar title (mirrors the iOS reader title button); not repeated in the menu
        // Current Reading Options (webtoon mode keeps only the reading direction, mirroring the original gating)
        MenuSectionHeader(stringResource(R.string.reader_menu_current_options))
        MenuValueItem(
            label = stringResource(R.string.reader_menu_direction),
            value = stringResource(direction.labelRes),
        ) {
            onDismiss()
            picker = ReaderMenuPicker.Direction
        }
        if (direction != ReadingDirection.Webtoon) {
            MenuValueItem(
                label = stringResource(R.string.reader_menu_page_layout),
                value = stringResource(pageLayout.labelRes),
            ) {
                onDismiss()
                picker = ReaderMenuPicker.Layout
            }
            // Solo cover page (mirrors the original Solo Cover Page: only available for auto/dual layouts and non-vertical scrolling)
            if (direction != ReadingDirection.Vertical && pageLayout != PageLayout.Single) {
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.reader_menu_solo_cover), modifier = Modifier.weight(1f))
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
            MenuValueItem(
                label = stringResource(R.string.reader_menu_split_wide),
                value = stringResource(splitWideMode.labelRes),
            ) {
                onDismiss()
                picker = ReaderMenuPicker.Split
            }
            MenuValueItem(
                label = stringResource(R.string.reader_menu_rotation),
                value = if (rotationDegrees == 0) stringResource(R.string.common_none) else "$rotationDegrees°",
            ) {
                onDismiss()
                picker = ReaderMenuPicker.Rotation
            }
        }
        HorizontalDivider()
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.reader_settings_title)) },
            onClick = {
                onDismiss()
                onOpenSettings()
            },
        )
        HorizontalDivider()
        // Current Page (mirrors the original: share + solo toggle for single pages in dual-page display)
        MenuSectionHeader(stringResource(R.string.reader_menu_current_page))
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.reader_share_page)) },
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
                        if (soloAction.isSolo) stringResource(R.string.reader_cancel_solo)
                        else stringResource(R.string.reader_show_page_solo, soloAction.displayPage),
                    )
                },
                onClick = {
                    onDismiss()
                    soloAction.onToggle()
                },
            )
        }
        HorizontalDivider()
        MenuSectionHeader(stringResource(R.string.reader_menu_page_nav))
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.reader_jump_to_page)) },
            leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenPageJump()
            },
        )
    }

    // Option dialogs (mirror iOS picker submenus: all options + checkmark on the current value)
    when (picker) {
        ReaderMenuPicker.Direction -> {
            val labels = ReadingDirection.entries.associateWith { stringResource(it.labelRes) }
            OptionPickerDialog(
                title = stringResource(R.string.reader_menu_direction),
                options = labels.values.toList(),
                selected = labels[direction].orEmpty(),
                onSelect = { label ->
                    labels.entries.firstOrNull { it.value == label }?.key?.let(onDirectionChange)
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }
        ReaderMenuPicker.Layout -> {
            val labels = PageLayout.entries.associateWith { stringResource(it.labelRes) }
            OptionPickerDialog(
                title = stringResource(R.string.reader_menu_page_layout),
                options = labels.values.toList(),
                selected = labels[pageLayout].orEmpty(),
                onSelect = { label ->
                    labels.entries.firstOrNull { it.value == label }?.key?.let(onPageLayoutChange)
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }
        ReaderMenuPicker.Split -> {
            val labels = SplitWidePageMode.entries.associateWith { stringResource(it.labelRes) }
            OptionPickerDialog(
                title = stringResource(R.string.reader_menu_split_wide),
                options = labels.values.toList(),
                selected = labels[splitWideMode].orEmpty(),
                hints = SplitWidePageMode.entries.associate {
                    stringResource(it.labelRes) to stringResource(it.hintRes)
                },
                onSelect = { label ->
                    labels.entries.firstOrNull { it.value == label }?.key?.let(onSplitWideModeChange)
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }
        ReaderMenuPicker.Rotation -> {
            val noneLabel = stringResource(R.string.common_none)
            val options = listOf(noneLabel, "90°", "180°", "270°")
            OptionPickerDialog(
                title = stringResource(R.string.reader_menu_rotation),
                options = options,
                selected = if (rotationDegrees == 0) noneLabel else "$rotationDegrees°",
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

/** Menu section header (mirrors the iOS Menu Section header). */
@Composable
private fun MenuSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Menu value item: label + current value + chevron (opens an option dialog, mirroring the iOS picker row). */
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
                    modifier = Modifier.padding(start = 16.dp),
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

/** Option dialog: single-choice list of all options, checkmark on the current value (mirrors the iOS menu picker submenu). */
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
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
 * Page-jump panel vertical sizing.
 *
 * The preview card takes the height left over after the fixed chrome around it (title, spacer,
 * page label, slider, counter row, spacer, actions row and bottom padding). Deriving the preview
 * height from the available height keeps the whole panel inside the modal card on short screens,
 * instead of a fixed 300dp preview pushing the slider and jump button out of the visible card.
 */
private val PageJumpFixedChromeHeight = 214.dp
private val PageJumpMinPreviewHeight = 120.dp
private val PageJumpMaxPreviewHeight = 340.dp

/**
 * Go to Page (mirrors the original PageJumpSheetView: horizontally snapping large thumbnail cards + slider + jump button):
 * - cards scroll horizontally and snap to center (iOS scrollTargetBehavior(.viewAligned)); the centered item is the target page;
 * - the selected card has scale 1.0 + border + deep shadow, unselected 0.9 (iOS PageJumpPreviewCard);
 * - the slider syncs with rounding (iOS sliderBinding); jump is disabled when the target equals the current page;
 * - the horizontal layout mirrors for RTL direction (iOS layoutDirection right-to-left);
 * - the preview height follows the available height so the panel is never clipped (the body also
 *   scrolls as a last resort on very short screens).
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
    AppModalDialog(onDismissRequest = onDismiss) {
        BoxWithConstraints {
            // Card width = panel width * 0.72 (mirrors iOS PageJumpPreviewCard.widthRatio)
            val cardWidth = this.maxWidth * 0.72f
            val sidePadding = (this.maxWidth - cardWidth) / 2
            // Preview height = what the panel can afford after its fixed chrome (see the constants
            // above); clamped so it stays a usable preview on short screens and does not grow
            // absurdly tall on tablets.
            val previewHeight = (this.maxHeight - PageJumpFixedChromeHeight)
                .coerceIn(PageJumpMinPreviewHeight, PageJumpMaxPreviewHeight)
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = (currentPage - 1).coerceAtLeast(0),
            )
            // Centered item → target page sync (mirrors the iOS scrollPosition binding)
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
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp),
            ) {
                Text(
                    stringResource(R.string.reader_go_to_page),
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
                                            .height(previewHeight)
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
                                                contentDescription = stringResource(R.string.common_page_number, pageNumber),
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
                        // Slider (iOS sliderBinding: snaps to whole pages with rounding)
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
                    // Share (mirrors the share button in the controls area of the iOS page-jump panel; hidden when no share channel exists)
                    if (onShare != null) {
                        androidx.compose.material3.OutlinedButton(onClick = { onShare(target) }) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.reader_share_this_page))
                        }
                    }
                    androidx.compose.material3.Button(
                        onClick = { onJump(target) },
                        enabled = target != currentPage,
                    ) { Text(stringResource(R.string.reader_jump)) }
                }
            }
        }
    }
}

/** Bottom-bar page capsule (mirrors the bottom-center capsule of DivinaControlsOverlayView: bookmark icon + current/total pages, opens PageJumpSheetView). */
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
