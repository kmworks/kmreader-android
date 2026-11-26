package com.kmworks.kmreader.ui.detail

import androidx.compose.runtime.Composable
import com.kmworks.kmreader.ui.components.secondaryButtonColors

/**
 * Secondary action inside the detail action card: peek, download, download-series.
 *
 * Filled with the same neutral the browse chips use, so these read as the quieter siblings of the
 * primary continue-reading button instead of as outlined boxes. Kept at
 * [androidx.compose.material3.FilledTonalButton] so ripple, disabled alpha and touch target stay
 * stock.
 */
@Composable
internal fun detailSecondaryButtonColors() = secondaryButtonColors()
