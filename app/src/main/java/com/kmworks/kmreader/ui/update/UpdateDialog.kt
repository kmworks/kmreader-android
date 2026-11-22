package com.kmworks.kmreader.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R

/**
 * The "new version" dialog, shared by the launch check and the About page.
 *
 * "Download" prefers the release's APK asset and falls back to the release page; the browser does the
 * download, so the app needs no storage permission and no installer plumbing of its own.
 */
@Composable
fun UpdateAvailableDialog(
    state: AppUpdateState,
    onDismiss: () -> Unit,
) {
    val update = state.update ?: return
    val uriHandler = LocalUriHandler.current
    val notes = update.notes?.trim().orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_updates_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.settings_updates_dialog_message,
                        update.version,
                        state.currentVersion,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (notes.isNotEmpty()) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 40,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val url = update.downloadUrl ?: update.releaseUrl
                    if (url != null) runCatching { uriHandler.openUri(url) }
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.settings_updates_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_updates_later))
            }
        },
    )
}
