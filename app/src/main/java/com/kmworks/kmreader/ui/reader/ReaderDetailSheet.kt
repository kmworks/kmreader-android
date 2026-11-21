package com.kmworks.kmreader.ui.reader

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.ui.components.AppModalDialog
import com.kmworks.kmreader.ui.detail.BookDetailBody

/**
 * In-reader book detail sheet (mirrors the iOS ReaderDetailSheets.swift readerDetailSheet):
 * the sheet shows the full book detail content inline (reusing BookDetailBody, the Android
 * counterpart of BookDetailContentView); passing seriesTitle=null for a oneshot yields the
 * single-volume form (mirrors iOS OneShotDetailContentView + hidden series switch button,
 * and BookHero does not render the series entry row when seriesTitle is null).
 *
 * "Visit Series", "Read Lists", and "metadata filter" are cross-page navigation: the reader is
 * an overlay on top of the AppRoot stack with no local navigation container, so these actions
 * close the reader and open the target page (on iOS a NavigationStack outside the reader can
 * push in place — an intentional difference imposed by the platform navigation structure).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderDetailSheet(
    book: Book,
    onDismiss: () -> Unit,
    onVisitSeries: (Book) -> Unit,
    onOpenReadList: (Int) -> Unit,
    onBrowseFilter: (String) -> Unit,
) {
    // Full page content (hero + tabs + scrolling body): the card takes the whole available height,
    // which is the default now that a card grows with its content instead of being square-capped.
    AppModalDialog(onDismissRequest = onDismiss) {
        BookDetailBody(
            book = book,
            seriesTitle = if (book.oneshot) null else book.seriesTitle,
            // Top-bar back = collapse the sheet (no back stack inside the sheet)
            onBack = onDismiss,
            // The sheet is already inside this book's reader: tapping "Read/Continue Reading" again = collapse the sheet back to the current page
            onRead = { onDismiss() },
            onOpenSeries = { onVisitSeries(book) },
            // Mark-read/download/add-to-list/menu actions are handled by BookDetailBody's own ViewModel; no external callbacks needed
            onMarkReadToggle = {},
            onDownload = {},
            onAddToReadList = {},
            onMenuAction = {},
            onOpenReadList = onOpenReadList,
            onReadIncognito = { onDismiss() },
            onBrowseFilter = onBrowseFilter,
        )
    }
}
