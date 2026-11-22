package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

/**
 * Per-field lock components for metadata editing (mirrors iOS SeriesEditSheet/BookEditSheet semantics):
 * - the initial lock state comes from the server DTO's xxxLock fields;
 * - editing content locks automatically; the lock icon can be toggled manually (including unlock);
 * - on save, the current value is submitted explicitly only when the lock state changed relative
 *   to its initial value (either direction) (true = lock / false = unlock); unchanged submits null
 *   (leaves the server lock state untouched) — see [lockUpdate].
 */

/** Editing state of a single text field: content + lock; editing locks automatically. */
internal class LockedField(initial: String, initialLocked: Boolean = false) {
    var value by mutableStateOf(initial)
    var locked by mutableStateOf(initialLocked)

    fun edit(newValue: String) {
        value = newValue
        locked = true
    }
}

@Composable
internal fun rememberLockedField(initial: String, initialLocked: Boolean = false): LockedField =
    remember(initial, initialLocked) { LockedField(initial, initialLocked) }

/**
 * Lock submit value: lock state changed relative to its initial value (either direction) → submit
 * the current value explicitly; unchanged → null. All three edit sheets follow this rule for
 * book/series update bodies.
 */
internal fun lockUpdate(current: Boolean, initial: Boolean): Boolean? =
    current.takeIf { it != initial }

/** Lock toggle icon (filled = locked, outlined translucent = unlocked). */
@Composable
internal fun LockToggle(locked: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = stringResource(if (locked) R.string.lock_locked else R.string.lock_unlocked),
            tint = if (locked) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Lockable text field: lock toggle on the right; editing locks automatically (mirrors lockToggle + onChange auto-lock).
 * [isError]/[supportingText] surface a format problem (e.g. a release date that is not ISO) without hiding the value.
 */
@Composable
internal fun LockableTextField(
    label: String,
    field: LockedField,
    singleLine: Boolean = true,
    placeholder: String = "",
    isError: Boolean = false,
    supportingText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = field.value,
            onValueChange = { field.edit(it) },
            label = { Text(label) },
            placeholder = placeholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            isError = isError,
            supportingText = supportingText?.let { text -> { Text(text) } },
            modifier = Modifier.weight(1f),
        )
        LockToggle(locked = field.locked, onToggle = { field.locked = !field.locked })
    }
}

/**
 * Enum-typed field: label + a row of selectable chips (status/reading direction; values are the
 * server enum tokens). Selecting a new option locks automatically; the lock toggle on the right
 * can be switched manually.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LockableChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    optionLabel: (String) -> String,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        LockToggle(locked = locked, onToggle = onToggleLock)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(optionLabel(option)) },
            )
        }
    }
}

/**
 * List section (genres/tags/links/aliases/sharing labels): title + lock toggle, rows + delete + add.
 * Adding/removing entries is the caller's responsibility for setting the matching lock (edits auto-lock).
 */
@Composable
internal fun LockableListSection(
    title: String,
    locked: Boolean,
    onToggleLock: () -> Unit,
    entries: List<String>,
    onRemove: (Int) -> Unit,
    addLabel: String,
    onAdd: (String, String) -> Unit,
    primaryLabel: String? = null,
    secondaryLabel: String? = null,
    primaryPlaceholder: String = "",
    secondaryPlaceholder: String = "",
) {
    var primary by remember { mutableStateOf("") }
    var secondary by remember { mutableStateOf("") }
    val needsValue = primaryLabel != null
    val canAdd = primary.isNotBlank() && (!needsValue || secondary.isNotBlank())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        LockToggle(locked = locked, onToggle = onToggleLock)
    }
    entries.forEachIndexed { index, entry ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onRemove(index) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    if (primaryLabel == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = primary,
                onValueChange = { primary = it },
                label = { Text(title) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        OutlinedTextField(
            value = primary,
            onValueChange = { primary = it },
            label = { Text(primaryLabel) },
            placeholder = primaryPlaceholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secondary,
            onValueChange = { secondary = it },
            label = { Text(secondaryLabel ?: "") },
            placeholder = secondaryPlaceholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            addLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                onAdd(primary.trim(), secondary.trim())
                primary = ""
                secondary = ""
            },
            enabled = canAdd,
        ) {
            Icon(Icons.Filled.Add, contentDescription = addLabel, modifier = Modifier.size(18.dp))
        }
    }
}
