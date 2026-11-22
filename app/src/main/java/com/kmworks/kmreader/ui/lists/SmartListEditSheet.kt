package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.SmartListFilterMapper
import com.kmworks.kmreader.data.SmartListTarget
import com.kmworks.kmreader.data.defaultBrowseOptions
import com.kmworks.kmreader.ui.components.AppModalDialog
import com.kmworks.kmreader.ui.components.BrowseOptionsSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Smart list editor (mirrors the iOS SmartListEditSheet): name / summary / target / filters / save.
 * Filters are edited through the shared browse panel ([BrowseOptionsSheet], sort hidden), plus the
 * full-text field and the library scope row; the resulting search document is built by
 * [SmartListFilterMapper]. In edit mode the stored document seeds every field and is only resubmitted
 * when something actually changed, so renaming cannot erase filters the panel cannot represent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartListEditSheet(
    onSave: (
        name: String,
        summary: String,
        target: SmartListTarget,
        search: JsonObject?,
    ) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialSummary: String = "",
    initialTarget: SmartListTarget = SmartListTarget.Book,
    /** Edit mode only: the server search document, reverse-parsed to seed the filters. */
    initialSearch: JsonObject? = null,
    isEdit: Boolean = false,
    onDelete: (() -> Unit)? = null,
    suggestionsViewModel: SmartListEditSuggestionsViewModel = hiltViewModel(),
) {
    val valueSuggestions by suggestionsViewModel.suggestions.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(initialName) }
    var summary by remember { mutableStateOf(initialSummary) }
    var target by remember { mutableStateOf(initialTarget) }
    val contentType = SmartListFilterMapper.browseContentType(target)
    val filterState = remember(initialSearch, initialTarget) {
        initialSearch?.let {
            SmartListFilterMapper.filterState(SmartListFilterMapper.browseContentType(initialTarget), it)
        }
    }
    val initialOptions = remember(target) {
        filterState?.options ?: defaultBrowseOptions(SmartListFilterMapper.browseContentType(target))
    }
    val initialFullTextSearch = filterState?.fullTextSearch ?: ""
    val initialLibraryIds = filterState?.libraryIds?.toSet() ?: emptySet()
    var options by remember(target) { mutableStateOf(initialOptions) }
    var fullTextSearch by remember { mutableStateOf(initialFullTextSearch) }
    var selectedLibraryIds by remember { mutableStateOf(initialLibraryIds) }
    var librariesExpanded by remember { mutableStateOf(false) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    val canSave = name.isNotBlank()
    // Mirrors the iOS filtersModified baseline: unless the user changed the filters, save leaves the
    // stored document untouched (a rename must not rewrite conditions the panel cannot represent).
    val filtersModified = fullTextSearch.trim() != initialFullTextSearch ||
        selectedLibraryIds != initialLibraryIds ||
        options != initialOptions
    val filterCount = SmartListFilterMapper.activeFilterCount(contentType, options)

    AppModalDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // Header: title + close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (isEdit) R.string.smartlist_edit_title else R.string.smartlist_new_title,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = { Text(stringResource(R.string.lists_summary)) },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            SheetLabel(stringResource(R.string.smartlist_target_type))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartListTarget.entries.forEach { option ->
                    // Target is immutable after creation (mirrors the original: locked in edit mode)
                    FilterChip(
                        selected = target == option,
                        onClick = { if (!isEdit) target = option },
                        label = {
                            Text(
                                stringResource(
                                    if (option == SmartListTarget.Book) {
                                        R.string.common_book
                                    } else {
                                        R.string.common_series
                                    },
                                ),
                            )
                        },
                        enabled = !isEdit,
                    )
                }
            }

            SheetLabel(stringResource(R.string.smartlist_filters))
            OutlinedTextField(
                value = fullTextSearch,
                onValueChange = { fullTextSearch = it },
                label = { Text(stringResource(R.string.smartlist_filters_search_text)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = { librariesExpanded = !librariesExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.smartlist_libraries))
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (selectedLibraryIds.isEmpty()) {
                        stringResource(R.string.smartlist_libraries_all)
                    } else {
                        stringResource(R.string.smartlist_libraries_count, selectedLibraryIds.size)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (librariesExpanded) {
                if (valueSuggestions.libraries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.smartlist_libraries_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    valueSuggestions.libraries.forEach { library ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedLibraryIds = if (library.id in selectedLibraryIds) {
                                        selectedLibraryIds - library.id
                                    } else {
                                        selectedLibraryIds + library.id
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = library.id in selectedLibraryIds,
                                onCheckedChange = null,
                            )
                            Text(
                                text = library.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
            // Opens the shared browse filter panel; the summary mirrors the iOS filterSummary line.
            OutlinedButton(
                onClick = { filterSheetOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.smartlist_filter_row))
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (filterCount == 0) {
                        stringResource(R.string.smartlist_filters_none)
                    } else {
                        stringResource(R.string.smartlist_filters_active, filterCount)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isEdit && filterState?.lossy == true) {
                Text(
                    text = stringResource(R.string.smartlist_filters_lossy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
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
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = {
                        // Create always submits a fresh document; edit only when the filters actually
                        // changed, so untouched documents (including lossy ones) stay exactly as stored.
                        val document = SmartListFilterMapper.searchDocument(
                            contentType = contentType,
                            options = options,
                            libraryIds = selectedLibraryIds.sorted(),
                            fullTextSearch = fullTextSearch,
                        )
                        val search = if (isEdit && !filtersModified) null else document
                        onSave(name.trim(), summary.trim(), target, search)
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }

    if (filterSheetOpen) {
        BrowseOptionsSheet(
            initialOptions = options,
            onConfirm = {
                options = it
                filterSheetOpen = false
            },
            onDismiss = { filterSheetOpen = false },
            contentType = contentType,
            // A smart list stores conditions only; sorting stays a view preference.
            showsSort = false,
        )
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** Library scope row (only the fields the editor needs). */
data class SmartListLibraryOption(val id: String, val name: String)

/** Library scope source for the editor; empty without a session. */
data class SmartListValueSuggestions(
    val libraries: List<SmartListLibraryOption> = emptyList(),
)

/** Self-contained suggestion source for the editor; callers need no changes. */
@HiltViewModel
class SmartListEditSuggestionsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _suggestions = MutableStateFlow(SmartListValueSuggestions())
    val suggestions: StateFlow<SmartListValueSuggestions> = _suggestions.asStateFlow()

    init {
        viewModelScope.launch {
            _suggestions.value = SmartListValueSuggestions(
                libraries = runCatching { libraryRepository.libraries() }
                    .getOrDefault(emptyList())
                    .map { SmartListLibraryOption(id = it.id, name = it.name) },
            )
        }
    }
}
