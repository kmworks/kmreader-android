package com.kmworks.kmreader.ui.library

import androidx.annotation.StringRes
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.BrowseContentType
import com.kmworks.kmreader.data.BrowseOptions
import com.kmworks.kmreader.data.NotableEntity
import com.kmworks.kmreader.data.filterKey

/** Which library list the screen is showing: series or books (mirrors the iOS fixedContent of the
 *  browse page pushed by a metadata chip). */
enum class LibraryContentType(@param:StringRes val labelRes: Int) {
    Series(R.string.common_series),
    Books(R.string.browse_scope_books),
}

/** The filter panel type matching this tab: each tab edits its own options. */
fun LibraryContentType.toBrowseContentType(): BrowseContentType = when (this) {
    LibraryContentType.Series -> BrowseContentType.Series
    LibraryContentType.Books -> BrowseContentType.Books
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

/** Wire tokens of the metadata-filter route shared with the detail screens (they emit, the library
 *  screen consumes). Internal routing values only; display labels are localized at render time. */
internal const val FILTER_KIND_AUTHOR = "author"
internal const val FILTER_KIND_TAG = "tag"
internal const val FILTER_KIND_PUBLISHER = "publisher"
internal const val FILTER_KIND_GENRE = "genre"
internal const val FILTER_KIND_AGE_RATING = "ageRating"
internal const val FILTER_KIND_LANGUAGE = "language"
internal const val FILTER_KIND_RELEASE_YEAR = "releaseYear"
internal const val FILTER_KIND_NOTABLE = "notable"

/** The condition a chip route injected into the browse options, kept around so it can be revoked again
 *  ([added] is false when the list already carried that value, in which case revoking must leave it alone). */
internal data class AppliedMetadataRoute(
    val kind: String,
    val value: String,
    val added: Boolean,
)

/**
 * Applies a chip route ("author · X", "publisher · X", "notable · kind · X") on top of [base].
 *
 * Switching chips replaces the previous condition wholesale (mirrors iOS metadataFilter), which the caller
 * does by revoking its previous result with [revokeFilterRoute] first. Returns null for a route the list does
 * not understand, so an unknown token is ignored instead of wiping the current filter.
 */
internal fun applyFilterRoute(
    base: BrowseOptions,
    route: String,
): Pair<BrowseOptions, AppliedMetadataRoute>? {
    val parts = route.split("·").map { it.trim() }
    val kind = parts.getOrNull(0).orEmpty()
    return when {
        // Three-part: "notable · kind · value" (kind is the entity kind token, e.g. character/team/location)
        parts.size == 3 && kind == FILTER_KIND_NOTABLE && parts[2].isNotBlank() -> {
            val entity = NotableEntity(name = parts[2], kind = parts[1])
            val present = entity in base.notableEntities
            injected(base, kind, entity.filterKey, present) {
                copy(notableEntities = notableEntities + entity)
            }
        }
        // Two-part: "author · X" etc.
        parts.size == 2 && parts[1].isNotBlank() -> {
            val value = parts[1]
            when (kind) {
                FILTER_KIND_AUTHOR -> injected(base, kind, value, value in base.authors) {
                    copy(authors = authors + value)
                }
                FILTER_KIND_TAG -> injected(base, kind, value, value in base.tags) {
                    copy(tags = tags + value)
                }
                FILTER_KIND_PUBLISHER -> injected(base, kind, value, value in base.publishers) {
                    copy(publishers = publishers + value)
                }
                FILTER_KIND_GENRE -> injected(base, kind, value, value in base.genres) {
                    copy(genres = genres + value)
                }
                FILTER_KIND_AGE_RATING -> injected(base, kind, value, value in base.ageRatings) {
                    copy(ageRatings = ageRatings + value)
                }
                FILTER_KIND_LANGUAGE -> injected(base, kind, value, value in base.languages) {
                    copy(languages = languages + value)
                }
                FILTER_KIND_RELEASE_YEAR -> injected(base, kind, value, value in base.releaseYears) {
                    copy(releaseYears = releaseYears + value)
                }
                else -> null
            }
        }
        else -> null
    }
}

/** Removes the condition [applyFilterRoute] injected, leaving every other browse option untouched. */
internal fun revokeFilterRoute(
    options: BrowseOptions,
    applied: AppliedMetadataRoute,
): BrowseOptions {
    // The value was already part of the list before the jump: revoking must not drop the user's own filter
    if (!applied.added) return options
    return when (applied.kind) {
        FILTER_KIND_AUTHOR -> options.copy(authors = options.authors - applied.value)
        FILTER_KIND_TAG -> options.copy(tags = options.tags - applied.value)
        FILTER_KIND_PUBLISHER -> options.copy(publishers = options.publishers - applied.value)
        FILTER_KIND_GENRE -> options.copy(genres = options.genres - applied.value)
        FILTER_KIND_AGE_RATING -> options.copy(ageRatings = options.ageRatings - applied.value)
        FILTER_KIND_LANGUAGE -> options.copy(languages = options.languages - applied.value)
        FILTER_KIND_RELEASE_YEAR -> options.copy(releaseYears = options.releaseYears - applied.value)
        FILTER_KIND_NOTABLE -> options.copy(
            notableEntities = options.notableEntities.filterNot { it.filterKey == applied.value },
        )
        else -> options
    }
}

private fun injected(
    base: BrowseOptions,
    kind: String,
    value: String,
    present: Boolean,
    add: BrowseOptions.() -> BrowseOptions,
): Pair<BrowseOptions, AppliedMetadataRoute> =
    (if (present) base else base.add()) to AppliedMetadataRoute(kind = kind, value = value, added = !present)
