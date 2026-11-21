package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
 * Alternate titles of a series/one-shot (mirrors the iOS `SeriesAlternateTitlesView`): a plain heading plus
 * non-tappable "label: title" rows, collapsed to [collapsedLimit] pairs with a "+N" chip that expands them.
 *
 * Shared by the series detail page and the one-shot branch of the book detail page so both read the same.
 */
@Composable
fun DetailAlternateTitles(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    collapsedLimit: Int = 2,
) {
    if (items.isEmpty()) return
    var expanded by remember(items) { mutableStateOf(false) }
    val visible = if (expanded || items.size <= collapsedLimit) items else items.take(collapsedLimit)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.detail_section_alternate_titles),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            visible.forEach { (label, title) ->
                Row {
                    Text(
                        text = "$label:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (!expanded && items.size > collapsedLimit) {
            Box(modifier = Modifier.padding(top = 8.dp)) {
                MetaChip(
                    text = "+${items.size - collapsedLimit}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { expanded = true },
                )
            }
        }
    }
}
