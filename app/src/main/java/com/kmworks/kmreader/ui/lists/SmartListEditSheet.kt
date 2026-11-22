package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.ui.components.AppModalDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.LibraryRepository
import com.kmworks.kmreader.core.data.ReferentialRepository
import com.kmworks.kmreader.core.data.SmartListFilterMapper
import com.kmworks.kmreader.data.SmartListTarget
import com.kmworks.kmreader.data.defaultBrowseOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** A single match rule: field + operator + value (one row of the condition builder in SmartListEditSheet). */
data class SmartListRule(
    val field: String,
    val operator: String,
    val value: String,
)

/** Stable rule field keys persisted into rules; display labels resolved via [smartListRuleFieldLabelRes].
 *  ("Pages" has no counterpart in the original condition DSL, so it is not offered.) */
val SmartListRuleFields = listOf("readStatus", "tag", "author", "releaseYear")

/** Stable rule operator keys persisted into rules; display labels resolved via [smartListRuleOperatorLabelRes]. */
val SmartListRuleOperators = listOf("eq", "neq", "contains", "gt", "lt")

/** Stable read-status rule value keys persisted into rules; display labels resolved via [smartListRuleReadStatusLabelRes]. */
val SmartListReadStatusValues = listOf("unread", "read", "inProgress")

@StringRes
fun smartListRuleFieldLabelRes(field: String): Int = when (field) {
    "readStatus" -> R.string.smartlist_rule_field_read_status
    "tag" -> R.string.common_tag
    "author" -> R.string.common_author
    "releaseYear" -> R.string.smartlist_rule_field_release_year
    else -> R.string.smartlist_rule_value
}

@StringRes
fun smartListRuleOperatorLabelRes(operator: String): Int = when (operator) {
    "eq" -> R.string.smartlist_rule_operator_is
    "neq" -> R.string.smartlist_rule_operator_is_not
    "contains" -> R.string.smartlist_rule_operator_contains
    "gt" -> R.string.smartlist_rule_operator_greater_than
    else -> R.string.smartlist_rule_operator_less_than
}

@StringRes
fun smartListRuleReadStatusLabelRes(value: String): Int = when (value) {
    "read" -> R.string.smartlist_rule_value_read
    "inProgress" -> R.string.smartlist_rule_value_in_progress
    else -> R.string.common_unread
}

/** Smart list rule editor (mirrors SmartListEditSheet.swift: name / summary / target / condition builder / save). */
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
    /** Edit mode only: the server search document, reverse-parsed to seed and describe the filters. */
    initialSearch: JsonObject? = null,
    initialMatchAll: Boolean = true,
    initialRules: List<SmartListRule> = emptyList(),
    isEdit: Boolean = false,
    onDelete: (() -> Unit)? = null,
    suggestionsViewModel: SmartListEditSuggestionsViewModel = hiltViewModel(),
) {
    val valueSuggestions by suggestionsViewModel.suggestions.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(initialName) }
    var summary by remember { mutableStateOf(initialSummary) }
    var target by remember { mutableStateOf(initialTarget) }
    var matchAll by remember { mutableStateOf(initialMatchAll) }
    val rules = remember {
        mutableStateListOf<SmartListRule>().also {
            it.addAll(initialRules.ifEmpty { listOf(SmartListRule("readStatus", "eq", "unread")) })
        }
    }
    val canSave = name.isNotBlank() && (isEdit || (rules.isNotEmpty() && rules.all { it.value.isNotBlank() }))
    // Edit mode: seed full text + library scope from the stored document; the other conditions are
    // displayed read-only until the browse filter panel replaces the rule builder.
    val filterState = remember(initialSearch, initialTarget) {
        initialSearch?.let {
            SmartListFilterMapper.filterState(SmartListFilterMapper.browseContentType(initialTarget), it)
        }
    }
    val filterCount = filterState?.activeFilterCount ?: 0
    val initialFullTextSearch = filterState?.fullTextSearch ?: ""
    val initialLibraryIds = filterState?.libraryIds?.toSet() ?: emptySet()
    var fullTextSearch by remember { mutableStateOf(initialFullTextSearch) }
    var selectedLibraryIds by remember { mutableStateOf(initialLibraryIds) }
    var librariesExpanded by remember { mutableStateOf(false) }
    // Mirrors the iOS baseline: a document is only resubmitted when the user actually changed
    // something, so an untouched save (or a rename) cannot rewrite the stored filters.
    val filtersModified = fullTextSearch.trim() != initialFullTextSearch ||
        selectedLibraryIds != initialLibraryIds

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

            if (isEdit) {
                Text(
                    text = if (filterCount == 0) {
                        stringResource(R.string.smartlist_filters_none)
                    } else {
                        stringResource(R.string.smartlist_filters_active, filterCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (filterState?.lossy == true) {
                    Text(
                        text = stringResource(R.string.smartlist_filters_lossy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else {
                SheetLabel(stringResource(R.string.smartlist_conditions))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = matchAll,
                        onClick = { matchAll = true },
                        label = { Text(stringResource(R.string.smartlist_match_all)) },
                    )
                    FilterChip(
                        selected = !matchAll,
                        onClick = { matchAll = false },
                        label = { Text(stringResource(R.string.smartlist_match_any)) },
                    )
                }

                rules.forEachIndexed { index, rule ->
                    RuleRow(
                        rule = rule,
                        onRuleChange = { rules[index] = it },
                        onRemove = { rules.removeAt(index) },
                        valueSuggestions = when (rule.field) {
                            "tag" -> valueSuggestions.tags
                            "author" -> valueSuggestions.authors
                            else -> emptyList()
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                TextButton(
                    onClick = { rules.add(SmartListRule("tag", "contains", "")) },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.smartlist_add_condition))
                }
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
                        // Create always submits a fresh document; edit only when the filters actually changed,
                        // so untouched documents (including lossy ones) are left exactly as stored.
                        val search = if (isEdit) {
                            if (!filtersModified) {
                                null
                            } else {
                                SmartListFilterMapper.searchDocument(
                                    contentType = SmartListFilterMapper.browseContentType(target),
                                    options = filterState?.options ?: defaultBrowseOptions(
                                        SmartListFilterMapper.browseContentType(target),
                                    ),
                                    libraryIds = selectedLibraryIds.sorted(),
                                    fullTextSearch = fullTextSearch,
                                )
                            }
                        } else {
                            buildSmartListSearchDocument(
                                matchAll = matchAll,
                                rules = rules.toList(),
                                libraryIds = selectedLibraryIds.sorted(),
                                fullTextSearch = fullTextSearch,
                            )
                        }
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
}

/** Condition row: field dropdown + operator dropdown + value input + trailing delete;
 *  tag/author fields show Referential suggestion chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleRow(
    rule: SmartListRule,
    onRuleChange: (SmartListRule) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    valueSuggestions: List<String> = emptyList(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuleDropdown(
                value = rule.field,
                options = SmartListRuleFields,
                onSelect = { onRuleChange(rule.copy(field = it)) },
                labelOf = { stringResource(smartListRuleFieldLabelRes(it)) },
                modifier = Modifier.width(112.dp),
            )
            RuleDropdown(
                value = rule.operator,
                options = SmartListRuleOperators,
                onSelect = { onRuleChange(rule.copy(operator = it)) },
                labelOf = { stringResource(smartListRuleOperatorLabelRes(it)) },
                modifier = Modifier.width(104.dp),
            )
            if (rule.field == "readStatus") {
                RuleDropdown(
                    value = rule.value,
                    options = SmartListReadStatusValues,
                    onSelect = { onRuleChange(rule.copy(value = it)) },
                    labelOf = { stringResource(smartListRuleReadStatusLabelRes(it)) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                OutlinedTextField(
                    value = rule.value,
                    onValueChange = { onRuleChange(rule.copy(value = it)) },
                    placeholder = { Text(stringResource(R.string.smartlist_rule_value)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.smartlist_rule_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        // Value suggestions (mirrors the original condition builder's hints; tap a chip to fill)
        if (valueSuggestions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                valueSuggestions
                    .filter { it != rule.value }
                    .take(MAX_VALUE_SUGGESTIONS)
                    .forEach { suggestion ->
                        AssistChip(
                            onClick = { onRuleChange(rule.copy(value = suggestion)) },
                            label = {
                                Text(
                                    suggestion,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
            }
        }
    }
}

private const val MAX_VALUE_SUGGESTIONS = 10

/** Dropdown selector button (shared by field/operator/value). */
@Composable
private fun RuleDropdown(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    labelOf: @Composable (String) -> String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = labelOf(value),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelOf(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
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

/** Rule value suggestions (tags/authors from Referential; empty without a session) + library scope. */
data class SmartListValueSuggestions(
    val tags: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val libraries: List<SmartListLibraryOption> = emptyList(),
)

/** Self-contained suggestion source for the editor; callers need no changes. */
@HiltViewModel
class SmartListEditSuggestionsViewModel @Inject constructor(
    private val referentialRepository: ReferentialRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _suggestions = MutableStateFlow(SmartListValueSuggestions())
    val suggestions: StateFlow<SmartListValueSuggestions> = _suggestions.asStateFlow()

    init {
        viewModelScope.launch {
            _suggestions.value = SmartListValueSuggestions(
                tags = referentialRepository.tags(),
                authors = referentialRepository.authors(),
                libraries = runCatching { libraryRepository.libraries() }
                    .getOrDefault(emptyList())
                    .map { SmartListLibraryOption(id = it.id, name = it.name) },
            )
        }
    }
}
