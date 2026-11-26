package com.kmworks.kmreader.ui.components

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Secondary action of a dialog or detail card: the quieter sibling of the filled primary button.
 *
 * Filled with the same neutral the browse chips use instead of being outlined -- an outlined box
 * next to a full-strength filled button reads as a different control family, not as the second
 * option of the same pair.
 */
@Composable
fun secondaryButtonColors(
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor = contentColor,
)
