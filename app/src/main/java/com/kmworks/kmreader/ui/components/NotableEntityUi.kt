package com.kmworks.kmreader.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.data.NotableEntityKnownKinds

/** Entity-kind icon (mirrors iOS NotableEntityIcon: person/person.2/mappin/tag). */
fun notableEntityIcon(kind: String): ImageVector = when (kind) {
    "character" -> Icons.Filled.Person
    "team" -> Icons.Filled.Group
    "location" -> Icons.Filled.LocationOn
    else -> Icons.Filled.Sell
}

/** Localized display name (mirrors iOS kindDisplayName: Character/Team/Location; custom kinds are kept as-is). */
@Composable
fun NotableEntity.kindLabel(): String = kindLabel(LocalContext.current)

/**
 * Context-based variant, usable inside non-composable lambdas (e.g. `map`/`joinToString`).
 */
fun NotableEntity.kindLabel(context: Context): String = when (kind) {
    "character" -> context.getString(R.string.notable_kind_character)
    "team" -> context.getString(R.string.notable_kind_team)
    "location" -> context.getString(R.string.notable_kind_location)
    else -> kind
}

/** Localized label → kind token (chip route parsing; unknown labels are lowercased as-is, mirroring iOS custom kinds). */
@Composable
fun notableEntityKindFromLabel(label: String): String = when (label) {
    stringResource(R.string.notable_kind_character) -> "character"
    stringResource(R.string.notable_kind_team) -> "team"
    stringResource(R.string.notable_kind_location) -> "location"
    else -> label.lowercase()
}

/**
 * Notable-entity editing content (mirrors iOS NotableEntitiesEditor):
 * entity rows (name + kindLabel subtitle + delete) + kind selector (Character/Team/Location/Custom)
 * + custom-kind input + name input + add button (enabled only when name is non-blank and the
 * custom kind is non-blank). Locking on add/remove is the caller's responsibility (mutations auto-lock).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotableEntitiesContent(
    entities: List<NotableEntity>,
    onRemove: (Int) -> Unit,
    onAdd: (kind: String, name: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    // kind selection: one of the three known kinds or the "custom" sentinel (mirrors the iOS kind Picker + Custom text field)
    var kindChoice by rememberSaveable { mutableStateOf(NotableEntityKnownKinds.first()) }
    var customKind by rememberSaveable { mutableStateOf("") }

    if (entities.isEmpty()) {
        Text(
            text = stringResource(R.string.notable_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    entities.forEachIndexed { index, entity ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entity.kindLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onRemove(index) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (NotableEntityKnownKinds + listOf("custom")).forEach { kind ->
            FilterChip(
                selected = kindChoice == kind,
                onClick = { kindChoice = kind },
                label = {
                    Text(
                        when (kind) {
                            "character" -> stringResource(R.string.notable_kind_character)
                            "team" -> stringResource(R.string.notable_kind_team)
                            "location" -> stringResource(R.string.notable_kind_location)
                            else -> stringResource(R.string.notable_kind_custom)
                        },
                    )
                },
            )
        }
    }
    if (kindChoice == "custom") {
        OutlinedTextField(
            value = customKind,
            onValueChange = { customKind = it },
            label = { Text(stringResource(R.string.notable_custom_kind)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.common_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // Mirrors iOS: enabled only when name is non-blank and the custom kind is non-blank
    val canAdd = name.isNotBlank() && (kindChoice != "custom" || customKind.isNotBlank())
    Button(
        onClick = {
            val kind = if (kindChoice == "custom") {
                customKind.trim().lowercase()
            } else {
                kindChoice
            }
            onAdd(kind, name.trim())
            name = ""
            customKind = ""
        },
        enabled = canAdd,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.notable_add)) }
}
