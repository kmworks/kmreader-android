package com.kmworks.kmreader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.Creator
import com.kmworks.kmreader.data.Series

/**
 * Localized role label for a creator. Custom roles prefer the server-provided raw role
 * text (mirrors iOS AuthorRole.custom(String)), falling back to "Other".
 */
@Composable
fun Creator.roleLabelText(): String = if (role == AuthorRole.Custom) {
    customRole?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        ?: stringResource(AuthorRole.Custom.labelRes)
} else {
    stringResource(role.labelRes)
}

/** Context-based role label, usable inside non-composable lambdas (e.g. `buildList`/`map`). */
fun Creator.roleLabelText(context: android.content.Context): String = if (role == AuthorRole.Custom) {
    customRole?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        ?: context.getString(AuthorRole.Custom.labelRes)
} else {
    context.getString(role.labelRes)
}

/**
 * Card subtitle, mirroring iOS SeriesCardView.statusContent:
 * partially read → "N books · xx% read"; fully read → "N books · Completed"; not started → "N books".
 * No state ever shows an "unread" wording.
 */
@Composable
fun Series.subtitleText(): String {
    val count = pluralStringResource(R.plurals.book_count, bookCount, bookCount)
    return when {
        progress != null && progress > 0f && progress < 1f ->
            "$count · ${stringResource(R.string.card_read_percent, (progress * 100).toInt())}"
        progress == 1f || (unreadCount == 0 && bookCount > 0) ->
            "$count · ${stringResource(R.string.common_completed)}"
        else -> count
    }
}

/** Book-count line for the detail-page action card, mirroring SeriesBookCountView. */
@Composable
fun Series.bookCountLineText(): String =
    if (totalBookCount != null) {
        stringResource(R.string.card_book_count_total, bookCount, totalBookCount)
    } else {
        pluralStringResource(R.plurals.book_count, bookCount, bookCount)
    }

/** One-shot series line, mirroring iOS SeriesDisplayItem.oneshotLine: first author name, falling back to "One-Shot". */
@Composable
fun Series.oneshotLineText(): String = author.ifBlank { stringResource(R.string.components_oneshot) }

/** One-shot book line, mirroring iOS BookDisplayItem.oneshotLine: first author name, falling back to "One-Shot". */
@Composable
fun Book.oneshotLineText(): String = author ?: stringResource(R.string.components_oneshot)

/** Page-count label used by card status rows ("N pages"). */
@Composable
fun Book.pageLabelText(): String = pluralStringResource(R.plurals.book_pages, pageCount, pageCount)

/** Relative "when last read" label for finished books (mirrors iOS completedLastReadText); null without a read date. */
@Composable
fun Book.completedLastReadText(): String? = lastReadAtMs?.let { relativeTimeLabel(it) }

/** Display label for a book sort-field token (see BookSortFields in data/BrowseFilter.kt). */
@Composable
fun bookSortFieldLabel(field: String): String = when (field) {
    "number" -> stringResource(R.string.sort_number)
    "title" -> stringResource(R.string.common_title)
    "releaseDate" -> stringResource(R.string.sort_release_date)
    "createdDate" -> stringResource(R.string.sort_added_date)
    "lastModified" -> stringResource(R.string.sort_updated_date)
    "fileSize" -> stringResource(R.string.sort_file_size)
    "pageCount" -> stringResource(R.string.sort_page_count)
    else -> field
}
