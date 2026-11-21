package com.kmworks.kmreader.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.data.DetailIdRegistry
import com.kmworks.kmreader.core.download.DownloadMark
import com.kmworks.kmreader.core.download.DownloadedIndex
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Series

/**
 * Completed-download state for card badges, provided once at the app root from
 * [com.kmworks.kmreader.core.download.DownloadRepository.downloadedIndex]. Cards read it instead of
 * scanning files, so a list of downloaded items costs no disk I/O per row.
 */
val LocalDownloadedIndex = compositionLocalOf { DownloadedIndex() }

/** Downloaded marker for a book card (mirrors iOS BookCardView downloadIcon; books have no partial state). */
@Composable
internal fun bookDownloadedMark(book: Book): DownloadMark =
    LocalDownloadedIndex.current.bookMark(book.id)

/** Downloaded marker for a series card (mirrors iOS SeriesDownloadStatus: full or partial). */
@Composable
internal fun seriesDownloadedMark(series: Series): DownloadMark =
    LocalDownloadedIndex.current.seriesMark(DetailIdRegistry.seriesDtoId(series.id))

/**
 * Small downloaded-state glyph drawn at the trailing edge of a card's detail line, matching the
 * placement of iOS `DownloadStatusIcon` in GridCardView.
 *
 * iOS lets the glyph inherit the detail label's font, so the icon is always exactly as tall as the
 * text beside it. Passing [textSize] here keeps that coupling: the badge cannot drift larger than
 * the line, and it follows the user's font scale instead of being a fixed dp that eats card width.
 */
@Composable
internal fun DownloadedMarkIcon(
    mark: DownloadMark,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val (icon, description) = when (mark) {
        DownloadMark.Full -> Icons.Filled.DownloadDone to stringResource(R.string.download_badge_downloaded)
        DownloadMark.Partial -> Icons.Filled.Download to stringResource(R.string.download_badge_partial)
        DownloadMark.None -> return
    }
    val size = with(LocalDensity.current) { textSize.toDp() }
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = modifier.size(size),
    )
}
