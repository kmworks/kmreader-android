package com.kmworks.kmreader.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.data.DashboardSectionSetting
import com.kmworks.kmreader.data.ReadListsInProgressSectionId
import com.kmworks.kmreader.ui.settings.SettingsBackIcon
import kotlin.math.roundToInt

/**
 * Dashboard customization (aligned with iOS SettingsDashboardView):
 * option switches + section drag reordering (iOS List .onMove) + section visibility switches,
 * all persisted to AppSettingsStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDashboardScreen(
    onBack: () -> Unit,
    viewModel: SettingsDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visibleSections = state.visibleSections
    val hiddenSections = state.hiddenSections

    // Local order for drag reordering: follows the persisted state when idle, is gesture-driven
    // while dragging, and is committed once on release
    val sectionsById = remember(visibleSections) { visibleSections.associateBy { it.id } }
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(0) }
    var dragAccumPx by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visibleSections) {
        if (draggingId == null) dragOrder = null
    }
    val orderedIds = dragOrder ?: visibleSections.map { it.id }
    val orderedSettings = orderedIds.mapNotNull { sectionsById[it] }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                navigationIcon = SettingsBackIcon {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item { GroupHeader(stringResource(R.string.dashboard_settings_options)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.dashboard_card_kind_menu),
                    subtitle = stringResource(R.string.dashboard_card_kind_menu_caption),
                    checked = state.cardKindMenu,
                    onCheckedChange = viewModel::setCardKindMenu,
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.dashboard_readlist_continuation),
                    subtitle = stringResource(R.string.dashboard_readlist_continuation_caption),
                    checked = state.continuation,
                    onCheckedChange = viewModel::setContinuation,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.dashboard_settings_scope_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item { GroupHeader(stringResource(R.string.dashboard_settings_sections)) }
            item {
                Text(
                    text = stringResource(R.string.dashboard_settings_reorder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(orderedSettings.size, key = { index -> orderedSettings[index].id }) { index ->
                val setting = orderedSettings[index]
                val isDragging = draggingId == setting.id
                val currentIndex = orderedIds.indexOf(setting.id)
                val dragTranslation = if (isDragging && rowHeightPx > 0f) {
                    dragAccumPx - (currentIndex - dragStartIndex) * rowHeightPx
                } else {
                    0f
                }
                SectionSettingRow(
                    setting = setting,
                    effectiveKind = state.overrides[setting.id] ?: setting.defaultKind,
                    dragging = isDragging,
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .onGloballyPositioned { coordinates ->
                            if (rowHeightPx == 0f) rowHeightPx = coordinates.size.height.toFloat()
                        }
                        .graphicsLayer {
                            translationY = dragTranslation
                            shadowElevation = if (isDragging) 6.dp.toPx() else 0f
                        }
                        .background(
                            if (isDragging) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    onDragStart = {
                        draggingId = setting.id
                        dragStartIndex = currentIndex
                        dragAccumPx = 0f
                    },
                    onDrag = { dy ->
                        if (rowHeightPx > 0f) {
                            dragAccumPx += dy
                            val list = (dragOrder ?: visibleSections.map { it.id }).toMutableList()
                            val from = list.indexOf(setting.id)
                            val target = (dragStartIndex + (dragAccumPx / rowHeightPx).roundToInt())
                                .coerceIn(0, list.lastIndex)
                            if (from >= 0 && target != from) {
                                list.add(target, list.removeAt(from))
                                dragOrder = list
                            }
                        }
                    },
                    onDragEnd = {
                        dragOrder?.let(viewModel::setOrder)
                        draggingId = null
                        dragAccumPx = 0f
                    },
                    onToggle = { viewModel.setVisible(setting.id, false) },
                )
            }

            if (hiddenSections.isNotEmpty()) {
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                item { GroupHeader(stringResource(R.string.dashboard_settings_hidden_sections)) }
                items(hiddenSections.size, key = { index -> hiddenSections[index].id }) { index ->
                    val setting = hiddenSections[index]
                    HiddenSectionRow(
                        setting = setting,
                        onShow = { viewModel.setVisible(setting.id, true) },
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                TextButton(
                    onClick = viewModel::reset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        stringResource(R.string.dashboard_reset_default),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** A visible-section row: icon + name + effective card kind + visibility switch + drag handle (long-press to drag). */
@Composable
private fun SectionSettingRow(
    setting: DashboardSectionSetting,
    effectiveKind: DashboardCardKind,
    dragging: Boolean,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Section icon (aligned with the row-leading section.icon in iOS SettingsDashboardView)
        Icon(
            imageVector = setting.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(24.dp)
                .size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            val defaultKindLabel = stringResource(dashboardCardKindLabelRes(setting.defaultKind))
            Text(
                text = dashboardSectionTitleRes(setting.id)?.let { stringResource(it) } ?: setting.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (effectiveKind == setting.defaultKind) {
                    stringResource(R.string.dashboard_kind_default_line, defaultKindLabel)
                } else {
                    stringResource(
                        R.string.dashboard_kind_custom_line,
                        stringResource(dashboardCardKindLabelRes(effectiveKind)),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = true, onCheckedChange = { onToggle() })
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = if (dragging) {
                stringResource(R.string.dashboard_drag_dragging)
            } else {
                stringResource(R.string.dashboard_drag_hint)
            },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .pointerInput(setting.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                },
        )
    }
}

/** A hidden-section row: icon + name + re-add button. */
@Composable
private fun HiddenSectionRow(
    setting: DashboardSectionSetting,
    onShow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = setting.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .width(24.dp)
                .size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dashboardSectionTitleRes(setting.id)?.let { stringResource(it) } ?: setting.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.dashboard_hidden_badge),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShow) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.dashboard_show_section),
            )
        }
    }
}

/** Row-leading section icon (aligned with the semantic mapping of iOS DashboardSection.icon). */
private fun DashboardSectionSetting.icon(): ImageVector = when (id) {
    ReadListsInProgressSectionId -> Icons.AutoMirrored.Filled.PlaylistPlay
    "keepReading" -> Icons.AutoMirrored.Filled.MenuBook
    "onDeck" -> Icons.Outlined.Schedule
    "recentlyReleasedBooks" -> Icons.Outlined.NewReleases
    "recentlyAddedBooks" -> Icons.Outlined.LibraryAdd
    "recentlyAddedSeries" -> Icons.Outlined.Collections
    "recentlyUpdatedSeries" -> Icons.Outlined.Update
    "recentlyReadBooks" -> Icons.Outlined.History
    else -> Icons.Outlined.Dashboard
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
