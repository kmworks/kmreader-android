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
    Hiatus(R.string.series_status_hiatus, "HIATUS"),
}

/** An activated filter condition (negative = excluded; chips render with the inverted variant). */
data class ActiveFilter(val label: String, val negative: Boolean)

/** Mirrors the original BookBrowseOptions: sort + read-status include/exclude + flags + authors/tags ∧∨. */
@Serializable
data class BookBrowseOptions(
    val sortField: String = BookSortFields.first(),
    val sortAscending: Boolean = true,
    val readStatuses: Map<ReadStatus, TriState> = emptyMap(),
    val oneshot: TriState = TriState.Off,
    // Deleted is excluded by default (mirrors the original deletedStatusRaw default "no")
    val deleted: TriState = TriState.Exclude,
    val authors: List<String> = emptyList(),
    val authorLogicAnd: Boolean = true,
    val tags: List<String> = emptyList(),
    val tagLogicAnd: Boolean = true,
    // Notable-entity dimension (shared by book/series search; mirrors the original
    // MetadataFilterConfig.notableEntitiesLogic, default ∧)
    val notableEntities: List<NotableEntity> = emptyList(),
    val notableEntitiesAllMatch: Boolean = true,
    // Series metadata dimensions (series search only; mirrors the publisher/genre/… conditions of the original SeriesSearch)
    val publishers: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val ageRatings: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val releaseYears: List<String> = emptyList(),
    val seriesStatuses: List<SeriesStatus> = emptyList(),
)

/**
 * Stable sort-field tokens; display labels resolve via bookSortFieldLabel() in ui.components.
 * Persisted in saved presets, so tokens must never change.
 */
val BookSortFields = listOf("number", "title", "releaseDate", "createdDate", "lastModified", "fileSize", "pageCount")

/** Filter preset shared across pages (the original stores a savedFilter table; persisted locally in FilterPresetStore). */
@Serializable
data class SavedFilter(val name: String, val options: BookBrowseOptions)
