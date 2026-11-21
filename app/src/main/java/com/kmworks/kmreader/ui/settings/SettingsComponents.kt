package com.kmworks.kmreader.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
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
            Text(text = row.title, style = MaterialTheme.typography.bodyLarge)
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
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
