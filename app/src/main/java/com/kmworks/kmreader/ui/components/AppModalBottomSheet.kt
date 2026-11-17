package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

/**
 * Shared ModalBottomSheet wrapper for the whole app.
 *
 * Works around the material3 1.4.0 drag jitter: the default contentWindowInsets
 * includes Top, so once the sheet's top edge enters the status-bar area the content
 * height changes with offset on every frame → draggableAnchors updates on every
 * measurement → anchoredDrag's restartable cancels and restarts the current drag
 * each frame, producing a "drag-cancel-bounce-redrag" loop.
 * Here the Top inset is dropped (content height is decoupled from offset, so anchors
 * stay constant while dragging), and the half-expanded anchor is skipped by default
 * (menu/edit sheets never use half expansion; the drag path is shorter and steadier).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // Key: no Top — the content's top padding no longer varies with offset
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
        content = content,
    )
}
