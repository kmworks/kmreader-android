package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Rows and section cards of the settings hub, styled after the iOS app's Settings form
 * (SettingsBadgeRow): a rounded color badge holding the row icon, the title with an optional second
 * line, and the trailing control (chevron, value or switch). Sections keep their outline label so the
 * grouping still reads as a form rather than a bare list.
 */

/** Accent of a row icon badge; mirrors the iOS SettingsSection palette with a light and dark variant. */
internal enum class SettingsAccent(val light: Color, val dark: Color) {
    Blue(Color(0xFF007AFF), Color(0xFF0A84FF)),
    Purple(Color(0xFFAF52DE), Color(0xFFBF5AF2)),
    Indigo(Color(0xFF5856D6), Color(0xFF5E5CE6)),
    Mint(Color(0xFF00C7BE), Color(0xFF63E6E2)),
    Teal(Color(0xFF30B0C7), Color(0xFF40C8E0)),
    Green(Color(0xFF34C759), Color(0xFF30D158)),
    Orange(Color(0xFFFF9500), Color(0xFFFF9F0A)),
    Brown(Color(0xFFA2845E), Color(0xFFAC8E68)),
    Gray(Color(0xFF8E8E93), Color(0xFF98989D)),
}

/** The theme already carries a monochrome surface set, so the badge picks its tint from the background. */
@Composable
internal fun SettingsAccent.tint(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) dark else light

/**
 * One row inside a settings card. A row shows a trailing switch when [onCheckedChange] is set, the
 * [value] text when it is not null, and otherwise a chevron for a navigating row; rows that either
 * navigate or toggle are fully clickable.
 */
internal data class SettingsRow(
    val title: String,
    val icon: ImageVector,
    val accent: SettingsAccent,
    val subtitle: String? = null,
    val value: String? = null,
    val checked: Boolean = false,
    val onCheckedChange: ((Boolean) -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
    /** Marked in the hub when the split layout already shows this row's page in the detail pane. */
    val selected: Boolean = false,
)

/** A titled group of rows rendered as one rounded card. */
internal data class SettingsGroup(
    val title: String,
    val rows: List<SettingsRow>,
)

private val BadgeSize = 29.dp
private val BadgeIconSize = 17.dp
private val BadgeCorner = 7.dp
private val BadgeGap = 12.dp
private val RowInset = 16.dp
private val RowVerticalInset = 10.dp
private val CardCorner = 16.dp

/** Rounded card holding [group]'s rows under the section label. */
@Composable
internal fun SettingsGroupCard(group: SettingsGroup, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = RowInset, bottom = 6.dp),
        )
        Surface(
            shape = RoundedCornerShape(CardCorner),
            color = MaterialTheme.colorScheme.surface,
            // The light scheme's surface sits only a hair above the background; a hairline edge keeps
            // the card legible without adding a tonal variant the design contract rules out.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        ) {
            Column {
                group.rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = RowInset + BadgeSize + BadgeGap),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        )
                    }
                    SettingsRowContent(row)
                }
            }
        }
    }
}

@Composable
private fun SettingsRowContent(row: SettingsRow) {
    val toggle = row.onCheckedChange
    val onClick: (() -> Unit)? = row.onClick ?: toggle?.let { change -> { change(!row.checked) } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (row.selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = RowInset, vertical = RowVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BadgeGap),
    ) {
        SettingsIconBadge(row.icon, row.accent)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = row.subtitle
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val value = row.value
        when {
            toggle != null -> Switch(checked = row.checked, onCheckedChange = toggle)
            value != null -> Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            row.onClick != null -> Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsIconBadge(icon: ImageVector, accent: SettingsAccent) {
    Box(
        modifier = Modifier
            .size(BadgeSize)
            .clip(RoundedCornerShape(BadgeCorner))
            .background(accent.tint()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(BadgeIconSize),
        )
    }
}

// region sub-page rows

/**
 * One titled card of a settings sub-page (iOS Form Section): an optional section label, the rounded
 * card that holds the rows, and an optional caption footer below it. Sub-pages use this instead of
 * bare text rows so they keep the same shape as the settings hub.
 */
@Composable
internal fun SettingsSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = RowInset, bottom = 6.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(CardCorner),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        ) {
            Column(content = content)
        }
        if (!footer.isNullOrEmpty()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = RowInset, end = RowInset, top = 6.dp),
            )
        }
    }
}

/** Hairline between the rows of a [SettingsSection]. */
@Composable
internal fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = RowInset),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
    )
}

/** Switch row: leading icon, title/subtitle, trailing switch; the whole row toggles (like iOS). */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = RowInset, vertical = RowVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BadgeGap),
    ) {
        if (icon != null) SettingsRowIcon(icon, enabled)
        SettingsRowText(
            title = title,
            subtitle = subtitle,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Row whose trailing side shows the current value plus a chevron, opening a picker/next page. */
@Composable
internal fun SettingsMenuRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = RowInset, vertical = RowVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BadgeGap),
    ) {
        if (icon != null) SettingsRowIcon(icon, true)
        SettingsRowText(
            title = title,
            subtitle = subtitle,
            enabled = true,
            modifier = Modifier.weight(1f),
        )
        if (!value.isNullOrEmpty()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Menu picker row (iOS menu Picker): the trailing value opens a dropdown anchored below it. */
@Composable
internal fun <T> SettingsPickerRow(
    title: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = RowInset, vertical = RowVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BadgeGap),
    ) {
        if (icon != null) SettingsRowIcon(icon, true)
        SettingsRowText(
            title = title,
            subtitle = subtitle,
            enabled = true,
            modifier = Modifier.weight(1f),
        )
        // The menu is anchored to the value (not the whole row) so it opens under the trailing
        // control, the way the iOS menu picker does.
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 200.dp),
            ) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                        trailingIcon = {
                            if (label == selectedLabel) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Read-only label/value row (iOS LabeledContent). */
@Composable
internal fun SettingsValueRow(
    title: String,
    value: String,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowInset, vertical = RowVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BadgeGap),
    ) {
        if (icon != null) SettingsRowIcon(icon, true)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/** Full-width destructive action row (iOS Button(role: .destructive)). */
@Composable
internal fun SettingsDangerRow(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = RowInset, vertical = RowVerticalInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SettingsRowIcon(icon: ImageVector, enabled: Boolean) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
    )
}

@Composable
private fun SettingsRowText(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// endregion
