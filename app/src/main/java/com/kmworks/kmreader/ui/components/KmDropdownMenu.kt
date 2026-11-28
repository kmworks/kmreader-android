package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/**
 * Dropdown menu with a popup surface that stays visible on this app's monochrome palette.
 *
 * M3 1.4 paints menus with `surfaceContainer` and applies no tonal elevation
 * (`MenuDefaults.TonalElevation == 0.dp`), so the only built-in separation is a 3dp shadow. That is
 * enough on the light scheme, where `Theme.kt` sets `surfaceContainer` to white and it sits on white
 * content: the shadow does the separating, so the light theme keeps M3's default container untouched.
 *
 * On the near-black schemes the shadow is invisible, and `Theme.kt` gives `surface` and
 * `surfaceContainer` the same value, so a menu opened over a list row (which uses `surface`) had no
 * visible edge at all. There the container steps up to `surfaceContainerHighest`, which is a visible
 * step above both the page `surface` and the dialog `surfaceContainerHigh` background in both dark
 * schemes. `surfaceContainerHigh` alone would collide with dialog backgrounds in the light theme, and
 * the app already uses `surfaceContainerHighest` for cards floating inside a dialog (see
 * BrowseFilter.kt), so menus read as "above everything".
 */
@Composable
fun KmDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = if (MaterialTheme.colorScheme.surface.luminance() >= 0.5f) {
        MenuDefaults.containerColor
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    },
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}
