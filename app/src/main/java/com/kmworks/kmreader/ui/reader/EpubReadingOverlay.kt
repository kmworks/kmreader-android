package com.kmworks.kmreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.reader.EpubOverlayContent
import com.kmworks.kmreader.core.reader.EpubOverlayEntry
import com.kmworks.kmreader.core.reader.EpubOverlayPreferences
import com.kmworks.kmreader.core.reader.EpubOverlayStrings
import com.kmworks.kmreader.core.reader.resolveEpubOverlay

/** Localized templates for the overlay resolver (built from string resources). */
@Composable
private fun epubOverlayStrings() = EpubOverlayStrings(
    bookProgress = stringResource(R.string.epub_overlay_book_progress),
    remaining = stringResource(R.string.epub_overlay_remaining),
    chapterProgress = stringResource(R.string.epub_overlay_chapter_progress),
    chapterPosition = stringResource(R.string.epub_overlay_chapter_position),
    pagesLeft = stringResource(R.string.epub_overlay_pages_left),
    lastPage = stringResource(R.string.epub_overlay_last_page),
)

/**
 * Margin notes around the page (mirrors the iOS WebPubInfoOverlay): six configurable text slots
 * (header/footer × leading/center/trailing) plus an optional thin progress bar along the bottom
 * edge. While the reader chrome is visible, only the two "controls" slots show (top/bottom
 * center). Pure text — never intercepts touches, so taps pass through to the WebView below.
 */
@Composable
internal fun EpubReadingOverlay(
    prefs: EpubOverlayPreferences,
    showingControls: Boolean,
    paged: Boolean,
    bookTitle: String?,
    chapterTitle: String?,
    totalProgression: Float?,
    currentPage: Int,
    totalPages: Int,
    chapterProgressFraction: Float?,
    isRtl: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val content = resolveEpubOverlay(
        prefs = prefs,
        showingControls = showingControls,
        paged = paged,
        bookTitle = bookTitle,
        chapterTitle = chapterTitle,
        totalProgression = totalProgression,
        currentPage = currentPage,
        totalPages = totalPages,
        chapterProgressFraction = chapterProgressFraction,
        isRtl = isRtl,
        strings = epubOverlayStrings(),
    )
    Box(modifier = modifier.fillMaxSize()) {
        OverlayRow(
            leading = content.topLeading,
            center = content.topCenter,
            trailing = content.topTrailing,
            textColor = textColor,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        OverlayRow(
            leading = content.bottomLeading,
            center = content.bottomCenter,
            trailing = content.bottomTrailing,
            textColor = textColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        content.bottomProgress?.let { progress ->
            // Thin bar flush against the bottom screen edge (mirrors the iOS 3pt progress bar).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(textColor.copy(alpha = 0.15f)),
            ) {
                Box(
                    modifier = Modifier
                        .align(if (content.isRtl) Alignment.CenterEnd else Alignment.CenterStart)
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(textColor.copy(alpha = 0.6f)),
                )
            }
        }
    }
}

@Composable
private fun OverlayRow(
    leading: EpubOverlayEntry,
    center: EpubOverlayEntry,
    trailing: EpubOverlayEntry,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    if (!leading.isVisible && !center.isVisible && !trailing.isVisible) return
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OverlaySlot(leading, textColor, TextAlign.Start, Modifier.weight(1f))
        OverlaySlot(center, textColor, TextAlign.Center, Modifier.weight(1f))
        OverlaySlot(trailing, textColor, TextAlign.End, Modifier.weight(1f))
    }
}

@Composable
private fun OverlaySlot(
    entry: EpubOverlayEntry,
    textColor: Color,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    if (entry.isVisible && entry.text != null) {
        Text(
            entry.text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = modifier,
        )
    } else {
        Spacer(modifier = modifier)
    }
}
