package com.kmworks.kmreader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.pdf.PdfTocEntry
import com.kmworks.kmreader.ui.components.AppModalDialog

/**
 * Flat PDF outline sheet, mirroring the EPUB chapter dialog: entries are pre-flattened
 * with a depth indent, the entry covering [currentPage] (1-based) is highlighted, and the
 * list opens scrolled to it. Selecting an entry jumps via [onJumpToPage] (1-based).
 */
@Composable
fun PdfTocSheet(
    entries: List<PdfTocEntry>,
    currentPage: Int,
    onDismiss: () -> Unit,
    onJumpToPage: (Int) -> Unit,
) {
    val currentIndex = entries.indexOfLast { it.pageIndex <= currentPage - 1 }
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (currentIndex > 0) listState.scrollToItem(currentIndex)
    }

    AppModalDialog(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.pdf_toc),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
            itemsIndexed(entries) { index, entry ->
                PdfTocEntryRow(
                    entry = entry,
                    isCurrent = index == currentIndex,
                    onClick = {
                        onJumpToPage(entry.pageIndex + 1)
                        onDismiss()
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PdfTocEntryRow(
    entry: PdfTocEntry,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (20 + entry.depth * 16).dp, end = 20.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (isCurrent) FontWeight.Bold else null,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.Filled.Bookmark,
                contentDescription = stringResource(R.string.epub_toc_current),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(18.dp),
            )
        }
    }
}
