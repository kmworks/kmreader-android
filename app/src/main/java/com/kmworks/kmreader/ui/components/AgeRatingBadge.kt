package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Age-rating pill shown next to a detail hero title, mirroring iOS AgeRatingBadge: green below 12,
 * orange from 12, red from 16, and an inverted pill from 18 (black on light, white on dark).
 *
 * Call sites only pass a rating above zero, so the neutral "unrated" case renders nothing.
 */
@Composable
fun AgeRatingBadge(ageRating: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    val adult = ageRating >= 18
    val background = when {
        adult -> MaterialTheme.colorScheme.inverseSurface
        ageRating >= 16 -> Color(0xFFFF3B30)
        ageRating >= 12 -> Color(0xFFFF9500)
        else -> Color(0xFF34C759)
    }
    val foreground = if (adult) MaterialTheme.colorScheme.inverseOnSurface else Color.White
    Text(
        text = "$ageRating+",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
        color = foreground,
        modifier = modifier
            .shadow(2.dp, shape)
            .clip(shape)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
