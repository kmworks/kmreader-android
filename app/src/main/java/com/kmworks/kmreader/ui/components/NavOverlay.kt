package com.kmworks.kmreader.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bottom space each page's scroll container must reserve while the floating tablet
 * navigation bar overlays the tab content (so the last row can scroll above the
 * floating bar). Zero on phones.
 */
val LocalNavOverlaySpace = compositionLocalOf<Dp> { 0.dp }
