package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.ui.components.AppModalDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import com.kmworks.kmreader.ui.components.secondaryButtonColors
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

/** New/edit collection sheet (mirrors CollectionEditSheet.swift: name + ordered toggle; delete in edit mode). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionEditSheet(
    onSave: (name: String, ordered: Boolean) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialOrdered: Boolean = false,
    isEdit: Boolean = false,
    onDelete: (() -> Unit)? = null,
    /** A create/update is in flight: the save button is disabled so a double tap cannot create two lists. */
    busy: Boolean = false,
    /** Shown above the buttons after a failed create; the sheet stays open so the name is not lost. */
    errorText: String? = null,
    /** Names of the existing lists of this kind; a create is blocked when the name is taken. */
    existingNames: List<String> = emptyList(),
    /** Offers an existing list that matches (or contains) the typed name; tapping it reuses that list
     *  instead of creating a near-duplicate. */
    onUseExisting: ((name: String) -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var ordered by remember { mutableStateOf(initialOrdered) }
    val trimmedName = name.trim()
    // Creation only: renaming an existing list to a name that is already taken is the same duplicate.
    val duplicate = !isEdit && existingNames.any { it.equals(trimmedName, ignoreCase = true) }
    val supportingText: (@Composable () -> Unit)? = if (duplicate) {
        { Text(stringResource(R.string.lists_name_exists)) }
    } else {
        null
    }
    val matches = remember(existingNames, trimmedName, isEdit) {
        if (isEdit || trimmedName.isEmpty()) {
            emptyList()
        } else {
            existingNames.filter { it.contains(trimmedName, ignoreCase = true) }.take(3)
        }
    }

    AppModalDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (isEdit) R.string.collection_edit_title else R.string.collection_new_title,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.common_name)) },
                singleLine = true,
                isError = duplicate,
                supportingText = supportingText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            // Existing lists as suggestions: reuse one instead of creating a near-duplicate.
            if (!isEdit && onUseExisting != null && matches.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.lists_existing_matches),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                matches.forEach { existing ->
                    Text(
                        text = existing,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUseExisting?.invoke(existing) }
                            .padding(vertical = 6.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.lists_ordered_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = ordered, onCheckedChange = { ordered = it })
            }

            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isEdit && onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                FilledTonalButton(
                    onClick = onDismiss,
                    colors = secondaryButtonColors(),
                ) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = { onSave(name.trim(), ordered) },
                    enabled = name.isNotBlank() && !busy && !duplicate,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}
