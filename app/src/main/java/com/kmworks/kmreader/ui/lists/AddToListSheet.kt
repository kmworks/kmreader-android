package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.ui.components.AppModalDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.SampleData.ListEntry

/** "Add to collection/read list" picker (mirrors CollectionPickerSheet/ReadListPickerSheet; tap = done). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToListSheet(
    collections: List<ListEntry>,
    readLists: List<ListEntry>,
    onSelect: (ListEntry) -> Unit,
    onDismiss: () -> Unit,
    alreadyInIds: Set<Int> = emptySet(),
    /** Create a read list: on success the new entry is reported back (and immediately selected).
     *  When null, the "New Read List" button is hidden. */
    onCreateReadList: ((name: String, summary: String, ordered: Boolean, onDone: (ListEntry?) -> Unit) -> Unit)? = null,
    /** Create a collection: on success the new entry is reported back.
     *  When null, the "New Collection" button is hidden. */
    onCreateCollection: ((name: String, ordered: Boolean, onDone: (ListEntry?) -> Unit) -> Unit)? = null,
) {
    // Create sub-flow: "readlist" / "collection" / null
    var createMode by remember { mutableStateOf<String?>(null) }
    if (createMode == "readlist") {
        ReadListEditSheet(
            onSave = { name, summary, ordered ->
                onCreateReadList?.invoke(name, summary, ordered) { entry ->
                    createMode = null
                    if (entry != null) {
                        onSelect(entry)
                        onDismiss()
                    }
                }
            },
            onDismiss = { createMode = null },
        )
        return
    }
    if (createMode == "collection") {
        CollectionEditSheet(
            onSave = { name, ordered ->
                onCreateCollection?.invoke(name, ordered) { entry ->
                    createMode = null
                    if (entry != null) {
                        onSelect(entry)
                        onDismiss()
                    }
                }
            },
            onDismiss = { createMode = null },
        )
        return
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
                    text = stringResource(R.string.lists_add_to_list_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                }
            }

            if (collections.isNotEmpty()) {
                GroupLabel(stringResource(R.string.lists_group_collections))
                collections.forEach { entry ->
                    ListTargetRow(
                        entry = entry,
                        icon = { tint ->
                            Icon(
                                Icons.Outlined.CollectionsBookmark,
                                contentDescription = null,
                                tint = tint,
                            )
                        },
                        countLabel = pluralStringResource(
                            R.plurals.lists_series_count,
                            entry.count,
                            entry.count,
                        ),
                        alreadyIn = entry.id in alreadyInIds,
                        onClick = {
                            onSelect(entry)
                            onDismiss()
                        },
                    )
                }
            }
            if (readLists.isNotEmpty()) {
                GroupLabel(stringResource(R.string.lists_group_readlists))
                readLists.forEach { entry ->
                    ListTargetRow(
                        entry = entry,
                        icon = { tint ->
                            Icon(
                                Icons.AutoMirrored.Outlined.ListAlt,
                                contentDescription = null,
                                tint = tint,
                            )
                        },
                        countLabel = pluralStringResource(
                            R.plurals.book_count,
                            entry.count,
                            entry.count,
                        ),
                        alreadyIn = entry.id in alreadyInIds,
                        onClick = {
                            onSelect(entry)
                            onDismiss()
                        },
                    )
                }
            }

            if (onCreateCollection != null) {
                OutlinedButton(
                    onClick = { createMode = "collection" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.collection_new_action))
                }
            }
            if (onCreateReadList != null) {
                OutlinedButton(
                    onClick = { createMode = "readlist" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (onCreateCollection != null) 8.dp else 16.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.readlist_new_action))
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** Selectable target row: icon + name/count; disabled with a check when already a member
 *  (mirrors the EntityPickerSheet row). */
@Composable
private fun ListTargetRow(
    entry: ListEntry,
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    countLabel: String,
    alreadyIn: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (alreadyIn) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !alreadyIn, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(tint)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (alreadyIn) tint else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = countLabel,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
            )
        }
        if (alreadyIn) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.lists_already_in_list),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
