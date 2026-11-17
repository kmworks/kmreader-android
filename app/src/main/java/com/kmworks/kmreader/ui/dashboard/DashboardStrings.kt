package com.kmworks.kmreader.ui.dashboard

import androidx.annotation.StringRes
import com.kmworks.kmreader.R
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.data.ReadListsInProgressSectionId

/**
 * Maps a dashboard section id to its localized title resource.
 * Returns null for ids unknown to the dashboard (callers should fall back to the stored title).
 */
@StringRes
fun dashboardSectionTitleRes(id: String): Int? = when (id) {
    ReadListsInProgressSectionId -> R.string.dashboard_section_readlists_in_progress
    "keepReading" -> R.string.dashboard_section_keep_reading
    "onDeck" -> R.string.dashboard_section_on_deck
    "recentlyReleasedBooks" -> R.string.dashboard_section_recently_released_books
    "recentlyAddedBooks" -> R.string.dashboard_section_recently_added_books
    "recentlyAddedSeries" -> R.string.dashboard_section_recently_added_series
    "recentlyUpdatedSeries" -> R.string.dashboard_section_recently_updated_series
    "recentlyReadBooks" -> R.string.dashboard_section_recently_read_books
    else -> null
}

/** Maps a dashboard card kind to its localized label resource. */
@StringRes
fun dashboardCardKindLabelRes(kind: DashboardCardKind): Int = when (kind) {
    DashboardCardKind.Large -> R.string.dashboard_card_kind_large
    DashboardCardKind.Medium -> R.string.dashboard_card_kind_medium
    DashboardCardKind.Small -> R.string.dashboard_card_kind_small
    DashboardCardKind.Horizontal -> R.string.dashboard_card_kind_horizontal
}
