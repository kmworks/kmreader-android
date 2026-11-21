package com.kmworks.kmreader.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
 * Download state for card badges, provided once at the app root from
 * [com.kmworks.kmreader.core.download.DownloadRepository.downloadedIndex]. Cards read it instead of
 * scanning files, so a list of downloaded items costs no disk I/O per row.
 */
val LocalDownloadedIndex = compositionLocalOf { DownloadedIndex() }

/** Download marker for a book card (mirrors iOS BookCardView downloadIcon). */
@Composable
internal fun bookDownloadedMark(book: Book): DownloadMark =
    LocalDownloadedIndex.current.bookMark(book.id)

/** Download marker for a series card (mirrors iOS SeriesDownloadStatus: full, partial, paused or failed). */
@Composable
internal fun seriesDownloadedMark(series: Series): DownloadMark =
    LocalDownloadedIndex.current.seriesMark(DetailIdRegistry.seriesDtoId(series.id))

/**
 * Downloaded-state glyph drawn at the trailing edge of a card's detail line, matching the placement of
 * iOS `DownloadStatusIcon` in GridCardView: a check when complete, a partial glyph for a partly downloaded
 * series, a still arrow while the task waits in the queue, a spinning arrow while a worker runs, a pause icon
 * while paused, and a red error mark.
 *
 * iOS lets the glyph inherit the detail label's font, so the icon is always exactly as tall as the text
 * beside it. Passing [textSize] here keeps that coupling: the badge cannot drift larger than the line, and
 * it follows the user's font scale instead of being a fixed dp that eats card width.
 */
@Composable
internal fun DownloadedMarkIcon(
    mark: DownloadMark,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val icon: ImageVector
    val description: Int
    when (mark) {
        DownloadMark.Full -> {
            icon = Icons.Filled.DownloadDone
            description = R.string.download_badge_downloaded
        }
        DownloadMark.Partial -> {
            icon = Icons.Filled.Download
            description = R.string.download_badge_partial
        }
        DownloadMark.Downloading -> {
            icon = Icons.Filled.Refresh
            description = R.string.download_badge_downloading
        }
        DownloadMark.Queued -> {
            icon = Icons.Filled.Refresh
            description = R.string.download_badge_queued
        }
        DownloadMark.Paused -> {
            icon = Icons.Filled.Pause
            description = R.string.download_badge_paused
        }
        DownloadMark.Failed -> {
            icon = Icons.Filled.Error
            description = R.string.download_badge_failed
        }
        DownloadMark.None -> return
    }
    val size = with(LocalDensity.current) { textSize.toDp() }
    // Failure stays red even on the dark overlay gradient; everything else follows the caller's tint.
    val resolvedTint = if (mark == DownloadMark.Failed) MaterialTheme.colorScheme.error else tint
    if (mark == DownloadMark.Downloading) {
        val transition = rememberInfiniteTransition(label = "download-badge")
        val angle = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_200, easing = LinearEasing)),
            label = "download-badge-rotation",
        )
        Icon(
            imageVector = icon,
            contentDescription = stringResource(description),
            tint = resolvedTint,
            modifier = modifier.size(size).rotate(angle.value),
        )
    } else {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(description),
            tint = resolvedTint,
            modifier = modifier.size(size),
        )
    }
}
