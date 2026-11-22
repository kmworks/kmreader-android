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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.ui.components.AppModalDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    /** Content the picker acts on; a list created from here is born holding it (Komga rejects empty bodies). */
    selection: ListSelection = ListSelection(),
    /** Create a read list seeded with [selection]: on success the new entry is reported back (and immediately
     *  selected). When null — or when [selection] carries no content — the "New Read List" button is hidden. */
    onCreateReadList: ((
        name: String,
        summary: String,
        ordered: Boolean,
        selection: ListSelection,
        onDone: (ListEntry?) -> Unit,
    ) -> Unit)? = null,
    /** Create a collection seeded with the selected series. When null — or without series in [selection] — the
     *  "New Collection" button is hidden; a read-list picker therefore never offers it. */
    onCreateCollection: ((
        name: String,
        ordered: Boolean,
        selection: ListSelection,
        onDone: (ListEntry?) -> Unit,
    ) -> Unit)? = null,
) {
    val showCreateCollection = onCreateCollection != null && selection.seriesIds.isNotEmpty()
    val showCreateReadList = onCreateReadList != null &&
        (selection.bookIds.isNotEmpty() || selection.seriesIds.isNotEmpty())

    // Create sub-flow: "readlist" / "collection" / null. A failed create keeps the sheet open with an error
    // instead of silently returning to the picker (the request used to fail with an invisible HTTP 400).
    var createMode by remember { mutableStateOf<String?>(null) }
    var createBusy by remember { mutableStateOf(false) }
    var createFailed by remember { mutableStateOf(false) }
    val closeCreate: () -> Unit = {
        createMode = null
        createBusy = false
        createFailed = false
    }

    // Mirrors the original picker's .searchable: the query filters the candidates by name, while the
    // create actions stay available — a search miss is often exactly when a new list is wanted.
    var query by remember { mutableStateOf("") }
    val visibleCollections = remember(collections, query) { collections.filterByQuery(query) }
    val visibleReadLists = remember(readLists, query) { readLists.filterByQuery(query) }
    val noMatches = query.isNotBlank() && visibleCollections.isEmpty() && visibleReadLists.isEmpty()

    if (createMode == "readlist") {
        ReadListEditSheet(
            onSave = { name, summary, ordered ->
                createFailed = false
                createBusy = true
                onCreateReadList?.invoke(name, summary, ordered, selection) { entry ->
                    createBusy = false
                    if (entry != null) {
                        closeCreate()
                        onSelect(entry)
                        onDismiss()
                    } else {
                        createFailed = true
                    }
                }
            },
            onDismiss = closeCreate,
            busy = createBusy,
            errorText = if (createFailed) stringResource(R.string.lists_create_failed) else null,
        )
        return
    }
    if (createMode == "collection") {
        CollectionEditSheet(
            onSave = { name, ordered ->
                createFailed = false
                createBusy = true
                onCreateCollection?.invoke(name, ordered, selection) { entry ->
                    createBusy = false
                    if (entry != null) {
                        closeCreate()
                        onSelect(entry)
                        onDismiss()
                    } else {
                        createFailed = true
                    }
                }
            },
            onDismiss = closeCreate,
            busy = createBusy,
            errorText = if (createFailed) stringResource(R.string.lists_create_failed) else null,
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

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.common_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )

            if (visibleCollections.isNotEmpty()) {
                GroupLabel(stringResource(R.string.lists_group_collections))
                visibleCollections.forEach { entry ->
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
            if (visibleReadLists.isNotEmpty()) {
                GroupLabel(stringResource(R.string.lists_group_readlists))
                visibleReadLists.forEach { entry ->
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

            if (noMatches) {
                Text(
                    text = stringResource(R.string.lists_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            if (showCreateCollection) {
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
            if (showCreateReadList) {
                OutlinedButton(
                    onClick = { createMode = "readlist" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (showCreateCollection) 8.dp else 16.dp),
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

/** Case-insensitive name filter for the picker (mirrors the original .searchable predicate). */
private fun List<ListEntry>.filterByQuery(query: String): List<ListEntry> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter { it.title.contains(trimmed, ignoreCase = true) }
}
