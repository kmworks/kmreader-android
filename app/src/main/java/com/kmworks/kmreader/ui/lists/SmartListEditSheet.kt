package com.kmworks.kmreader.ui.lists

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kmworks.kmreader.ui.components.AppModalBottomSheet
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
import com.kmworks.kmreader.core.data.ReferentialRepository
import com.kmworks.kmreader.data.SmartListTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

/** Smart list rule editor (mirrors SmartListEditSheet.swift: name / target / condition builder / save). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartListEditSheet(
    onSave: (name: String, target: SmartListTarget, matchAll: Boolean, rules: List<SmartListRule>) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialTarget: SmartListTarget = SmartListTarget.Book,
    initialMatchAll: Boolean = true,
    initialRules: List<SmartListRule> = emptyList(),
    isEdit: Boolean = false,
    onDelete: (() -> Unit)? = null,
    suggestionsViewModel: SmartListEditSuggestionsViewModel = hiltViewModel(),
) {
    val valueSuggestions by suggestionsViewModel.suggestions.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(initialName) }
    var target by remember { mutableStateOf(initialTarget) }
    var matchAll by remember { mutableStateOf(initialMatchAll) }
    val rules = remember {
        mutableStateListOf<SmartListRule>().also {
            it.addAll(initialRules.ifEmpty { listOf(SmartListRule("readStatus", "eq", "unread")) })
        }
    }
    val canSave = name.isNotBlank() && rules.isNotEmpty() && rules.all { it.value.isNotBlank() }

    AppModalBottomSheet(onDismissRequest = onDismiss) {
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
                    onClick = { onSave(name.trim(), target, matchAll, rules.toList()) },
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

/** Rule value suggestions (tags/authors from Referential; empty without a session). */
data class SmartListValueSuggestions(
    val tags: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
)

/** Self-contained suggestion source for the editor; callers need no changes. */
@HiltViewModel
class SmartListEditSuggestionsViewModel @Inject constructor(
    private val referentialRepository: ReferentialRepository,
) : ViewModel() {

    private val _suggestions = MutableStateFlow(SmartListValueSuggestions())
    val suggestions: StateFlow<SmartListValueSuggestions> = _suggestions.asStateFlow()

    init {
        viewModelScope.launch {
            _suggestions.value = SmartListValueSuggestions(
                tags = referentialRepository.tags(),
                authors = referentialRepository.authors(),
            )
        }
    }
}
