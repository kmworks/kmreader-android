package com.kmworks.kmreader.ui.detail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.Series

/**
 * komf dialogs shared by the dashboard/library series context menus: the identify sheet (same UI as the
 * detail page) and the reset confirmation. Both are only reachable when komf is available (admin + feature).
 */
@Composable
fun KomfSeriesContextDialogs(
    identifyTarget: Series?,
    resetTarget: Series?,
    state: KomfSeriesUiState,
    viewModel: KomfSeriesViewModel,
    onRefresh: () -> Unit,
    onDismissIdentify: () -> Unit,
    onDismissReset: () -> Unit,
) {
    identifyTarget?.let { series ->
        KomfIdentifySheet(
            series = series,
            books = state.books,
            ready = state.ready,
            searching = state.searching,
            searchFailed = state.searchFailed,
            results = state.results,
            onSearch = viewModel::komfSearch,
            onIdentify = { provider, providerSeriesId, onSubmitted, onFailed ->
                viewModel.komfIdentify(provider, providerSeriesId, onSubmitted, onFailed)
            },
            onDismiss = onDismissIdentify,
        )
    }
    resetTarget?.let { series ->
        AlertDialog(
            onDismissRequest = onDismissReset,
            title = { Text(stringResource(R.string.komf_reset)) },
            text = { Text(stringResource(R.string.komf_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissReset()
                        viewModel.komfReset(series.id) { ok -> if (ok) onRefresh() }
                    },
                ) {
                    Text(stringResource(R.string.common_reset), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissReset) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
