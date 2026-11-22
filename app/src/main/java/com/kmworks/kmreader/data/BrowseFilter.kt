package com.kmworks.kmreader.data

import androidx.annotation.StringRes
import com.kmworks.kmreader.R
import kotlinx.serialization.Serializable

/** Tri-state filter value: Off → Include → Exclude, cycling (mirrors the original FilterChipState). */
enum class TriState {
    Off, Include, Exclude;

    val next: TriState
        get() = when (this) {
            Off -> Include
            Include -> Exclude
            Exclude -> Off
        }
}

enum class ReadStatus(@param:StringRes val labelRes: Int) {
    Unread(R.string.read_status_unread),
    InProgress(R.string.read_status_in_progress),
    Read(R.string.read_status_read),
}

/** Series publication status (mirrors the original SeriesStatus search condition). */
enum class SeriesStatus(@param:StringRes val labelRes: Int, val apiName: String) {
    Ongoing(R.string.series_status_ongoing, "ONGOING"),
    Ended(R.string.series_status_ended, "ENDED"),
    Abandoned(R.string.series_status_abandoned, "ABANDONED"),
    Hiatus(R.string.series_status_hiatus, "HIATUS");

    companion object {
        /** Resolve a server status token (ENDED/ONGOING/…) to its enum; custom server statuses return null. */
        fun fromApiName(token: String): SeriesStatus? = entries.firstOrNull { it.apiName == token }
    }
}

/**
 * Which list a [BrowseOptions] set belongs to.
 *
 * The filter sheet renders different sections per content type and the sort vocabulary differs
 * completely (a series has a release date, a book count and a random order; a book has a page count
 * and a file name), mirroring the separate `SeriesBrowseOptions` / `BrowseOptions` of the
 * original app.
 */
enum class BrowseContentType(@param:StringRes val labelRes: Int) {
    Series(R.string.common_series),
    Books(R.string.browse_scope_books),
}

/** An activated filter condition (negative = excluded; chips render with the inverted variant). */
data class ActiveFilter(val label: String, val negative: Boolean)

/**
 * Browse filters + sort for one content type (series or books).
 *
 * One class carries both vocabularies because the sheet, the saved-preset store and the paging
 * screens are shared; [BrowseContentType] decides which fields are rendered and turned into server
 * conditions. A series field left over from a book list (or the reverse) is simply ignored.
 */
@Serializable
data class BrowseOptions(
    val sortField: String = BookSortFields.first(),
    val sortAscending: Boolean = true,
    val readStatuses: Map<ReadStatus, TriState> = emptyMap(),
    val oneshot: TriState = TriState.Off,
    // Deleted is excluded by default (mirrors the original deletedStatusRaw default "no")
    val deleted: TriState = TriState.Exclude,
    // Series only: the "completed" publication flag (mirrors completeFilter of SeriesBrowseOptions)
    val complete: TriState = TriState.Off,
    val authors: List<String> = emptyList(),
    val authorLogicAnd: Boolean = true,
    val tags: List<String> = emptyList(),
    val tagLogicAnd: Boolean = true,
    // Notable-entity dimension (shared by book/series search; mirrors the original
    // MetadataFilterConfig.notableEntitiesLogic, default ∧)
    val notableEntities: List<NotableEntity> = emptyList(),
    val notableEntitiesAllMatch: Boolean = true,
    // Series metadata dimensions (series only; mirrors the publisher/genre/… conditions of the original SeriesSearch)
    val publishers: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val ageRatings: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val releaseYears: List<String> = emptyList(),
    // Series status tri-state: included statuses are one ∧/∨ group, excluded ones are separate ≠ leaves
    // (mirrors includeSeriesStatuses / excludeSeriesStatuses + seriesStatusLogic of SeriesBrowseOptions).
    val seriesStatuses: List<SeriesStatus> = emptyList(),
    val excludedSeriesStatuses: List<SeriesStatus> = emptyList(),
    val seriesStatusAllMatch: Boolean = true,
)

/**
 * Book sort tokens, in the order the filter sheet lists them (mirrors BookSortField of the original
 * app: 卷号 is the default so that books of one series stay in reading order).
 */
val BookSortFields = listOf(
    "series",
    "metadata.title",
    "createdDate",
    "lastModifiedDate",
    "metadata.releaseDate",
    "readProgress.readDate",
    "fileSize",
    "name",
    "media.pagesCount",
)

/**
 * Series sort tokens, in the order the filter sheet lists them. The keys are the ones Komga's own
 * web UI sends (next-ui/src/types/sort.ts, sortSeries), so the server sorts by exactly what the
 * picker says. The title-ish tokens are distinct: metadata.titleSort is the custom sort key,
 * metadata.title is the (possibly customised) title, and name is the directory name on disk
 * ("Directory name" in the web UI).
 */
val SeriesSortFields = listOf(
    "metadata.titleSort",
    "metadata.title",
    "name",
    "createdDate",
    "lastModifiedDate",
    "readDate",
    "booksMetadata.releaseDate",
    "booksCount",
    "random",
)

/** Sort tokens of [contentType], in sheet order; the first entry is that type's default. */
fun sortFieldsFor(contentType: BrowseContentType): List<String> = when (contentType) {
    BrowseContentType.Series -> SeriesSortFields
    BrowseContentType.Books -> BookSortFields
}

/**
 * Sort tokens persisted by older builds (before the iOS token vocabulary) mapped onto the book
 * equivalents; presets saved then still resolve to a real server sort field.
 */
private val LegacyBookSortFields = mapOf(
    "number" to "series",
    "title" to "metadata.title",
    "releaseDate" to "metadata.releaseDate",
    "lastModified" to "lastModifiedDate",
    "pageCount" to "media.pagesCount",
)

/** Same for series presets: the old build reused the book token list for the series tab. */
private val LegacySeriesSortFields = mapOf(
    "number" to "metadata.titleSort",
    "title" to "metadata.titleSort",
    "titleSort" to "metadata.titleSort",
    "releaseDate" to "booksMetadata.releaseDate",
    "metadata.releaseDate" to "booksMetadata.releaseDate",
    "release_date" to "booksMetadata.releaseDate",
    "added_date" to "createdDate",
    "created" to "createdDate",
    "updated_date" to "lastModifiedDate",
    "lastModified" to "lastModifiedDate",
    "fileLastModified" to "readDate",
    "readProgress.readDate" to "readDate",
    "fileSize" to "booksCount",
    "pageCount" to "booksCount",
)

/**
 * Canonical sort token for [contentType]: an already-valid token is kept, a legacy token is mapped,
 * and anything else falls back to the type default (never a token the server cannot sort by).
 */
fun normalizeSortField(field: String, contentType: BrowseContentType): String {
    val fields = sortFieldsFor(contentType)
    if (field in fields) return field
    val legacy = when (contentType) {
        BrowseContentType.Series -> LegacySeriesSortFields
        BrowseContentType.Books -> LegacyBookSortFields
    }
    val mapped = legacy[field]
    if (mapped != null && mapped in fields) return mapped
    return fields.first()
}

/** Fresh options for [contentType] with that type's default sort (series default is 标题, books 卷号). */
fun defaultBrowseOptions(contentType: BrowseContentType = BrowseContentType.Books): BrowseOptions =
    BrowseOptions(sortField = sortFieldsFor(contentType).first())

/**
 * Whether a sort field has a direction. 随机 of the series list is an unordered shuffle, so the
 * direction picker is hidden for it (mirrors supportsDirection of SeriesSortField).
 */
fun supportsSortDirection(field: String, contentType: BrowseContentType): Boolean =
    !(contentType == BrowseContentType.Series && field == "random")

/**
 * Filter preset shared across pages (the original stores a savedFilter table; persisted locally in
 * FilterPresetStore). [contentType] keeps a book preset from being applied to the series list, whose
 * sort tokens and dimensions do not exist there.
 */
@Serializable
data class SavedFilter(
    val name: String,
    val contentType: BrowseContentType = BrowseContentType.Books,
    val options: BrowseOptions,
)
