package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Metadata chip: an optional leading icon plus the label on a neutral fill (mirrors the iOS DetailChip /
 * FilterChip). One component for every chip in the app, so the same piece of metadata — a tag on a detail
 * page, the condition a metadata-chip jump put on the library — always reads the same way.
 *
 * [onClick] keeps the chip tappable; [onClickLabel] names that action for accessibility instead of drawing a
 * trailing close glyph inside the chip (the iOS chips carry none either).
 */
@Composable
fun MetaChip(
    text: String,
    icon: ImageVector? = null,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    // Pill instead of the previous 8dp rect: detail and library chips now share the filled, borderless
    // geometry of the browse chip rows, so metadata reads as part of the same surface family.
    val shape = CircleShape
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = if (onClick != null) {
            Modifier
                .clip(shape)
                .clickable(onClickLabel = onClickLabel, onClick = onClick)
        } else {
            Modifier
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = style, color = color)
        }
    }
}
