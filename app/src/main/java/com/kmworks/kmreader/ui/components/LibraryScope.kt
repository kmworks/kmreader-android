package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.LibraryDto
import com.kmworks.kmreader.core.settings.AppSettingsStore

/**
 * Library scope string (pinned/all/library:{id}) → server libraryIds filter list.
 * Mirrors iOS LibraryBrowseScope.resolvedIds: all → no filter; empty pinned → no filter.
 */
fun resolveLibraryScopeIds(scope: String, pinnedIds: Set<String>): List<String>? = when {
    scope == AppSettingsStore.SCOPE_ALL -> null
    scope.startsWith("library:") -> listOf(scope.removePrefix("library:"))
    else -> pinnedIds.toList().takeIf { it.isNotEmpty() }
}

/** Display name of a library scope (mirrors the iOS LibraryScopeMenu labels). */
@Composable
fun libraryScopeLabel(scope: String, libraries: List<LibraryDto>): String = when {
    scope == AppSettingsStore.SCOPE_PINNED -> stringResource(R.string.library_scope_pinned)
    scope.startsWith("library:") -> {
        val id = scope.removePrefix("library:")
        libraries.firstOrNull { it.id == id }?.name ?: stringResource(R.string.library_scope_single)
    }
    else -> stringResource(R.string.common_all_libraries)
}

/** Pin-editing sheet (mirrors iOS LibraryPickerSheet: check the libraries to pin; the "Pinned" scope takes effect after saving). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPinSheet(
    libraries: List<LibraryDto>,
    pinnedIds: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selection by remember { mutableStateOf(pinnedIds) }
    AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                // Many libraries can exceed the screen; scrollable and height-capped so the sheet stays in bounds
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                stringResource(R.string.library_pin_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                stringResource(R.string.library_pin_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            libraries.forEach { library ->
                val checked = library.id in selection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selection = if (checked) selection - library.id else selection + library.id
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        library.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    if (checked) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.library_scope_pinned))
                    }
                }
            }
            TextButton(
                onClick = { onSave(selection) },
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(vertical = 8.dp),
            ) {
                Text(stringResource(R.string.common_done))
            }
        }
    }
}
