package com.kmworks.kmreader.ui.library

import androidx.annotation.StringRes
import com.kmworks.kmreader.R

/** Which library list the screen is showing: series or books (mirrors the iOS fixedContent of the
 *  browse page pushed by a metadata chip). */
enum class LibraryContentType(@param:StringRes val labelRes: Int) {
    Series(R.string.common_series),
    Books(R.string.browse_scope_books),
}

/**
 * A metadata-chip jump from a detail page onto the library list.
 *
 * The detail pages only emit the internal route ("author · X", "notable · kind · X", …); the list the
 * route applies to is decided by the page it was tapped on (mirrors the iOS NavDestination cases
 * `.browseSeriesWith…` / `.browseBooksWith…`, each pushing a BrowseView with a fixed content type).
 *
 * [requestId] is bumped on every jump, even for the same chip, so that a repeated jump re-applies the
 * filter instead of being treated as an unchanged state.
 */
data class LibraryFilterRequest(
    val route: String,
    val contentType: LibraryContentType = LibraryContentType.Series,
    val requestId: Long = 0L,
)
