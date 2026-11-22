package com.kmworks.kmreader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.AuthorRole
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.BrowseContentType
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

/**
 * Display label for a book sort-field token (see BookSortFields in data/BrowseFilter.kt); the
 * pre-iOS tokens (number/title/…) stay mapped so older saved presets still show a real name.
 */
@Composable
fun bookSortFieldLabel(field: String): String = when (field) {
    "series", "number" -> stringResource(R.string.sort_number)
    "metadata.title", "title" -> stringResource(R.string.common_title)
    "metadata.releaseDate", "releaseDate" -> stringResource(R.string.sort_release_date)
    "createdDate" -> stringResource(R.string.sort_added_date)
    "lastModifiedDate", "lastModified" -> stringResource(R.string.sort_updated_date)
    "readProgress.readDate" -> stringResource(R.string.sort_read_date)
    "fileSize" -> stringResource(R.string.sort_file_size)
    "name" -> stringResource(R.string.sort_file_name)
    "media.pagesCount", "pageCount" -> stringResource(R.string.sort_page_count)
    else -> field
}

/** Display label for a series sort-field token (see SeriesSortFields in data/BrowseFilter.kt). */
@Composable
fun seriesSortFieldLabel(field: String): String = when (field) {
    // The title-ish series fields must not read the same: metadata.titleSort is the sort key
    // (排序标题), metadata.title is the title itself (标题), and name is the folder on disk
    // (文件夹名称, "Directory name" in the Komga web UI). Labels reuse the metadata editor's wording
    // so the sort list, the chip and the edit form agree.
    "metadata.titleSort" -> stringResource(R.string.detail_field_title_sort)
    "name" -> stringResource(R.string.sort_folder_name)
    "created" -> stringResource(R.string.sort_added_date)
    "lastModified" -> stringResource(R.string.sort_updated_date)
    "fileLastModified" -> stringResource(R.string.sort_read_date)
    "booksMetadata.releaseDate" -> stringResource(R.string.sort_release_date)
    "metadata.title" -> stringResource(R.string.common_title)
    "booksCount" -> stringResource(R.string.sort_books_count)
    "random" -> stringResource(R.string.sort_random)
    else -> field
}

/** Sort-field label of [contentType] (the series and book vocabularies share almost no tokens). */
@Composable
fun browseSortFieldLabel(field: String, contentType: BrowseContentType): String =
    when (contentType) {
        BrowseContentType.Series -> seriesSortFieldLabel(field)
        BrowseContentType.Books -> bookSortFieldLabel(field)
    }
