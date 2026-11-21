package com.kmworks.kmreader.data

/** Section id of the in-progress read lists strip (aligned with DashboardSection.readListsInProgress). */
const val ReadListsInProgressSectionId = "readListsInProgress"

/** Label for the card kind picker (aligned with DashboardCardKind.title). */
val DashboardCardKind.label: String
    get() = when (this) {
        DashboardCardKind.Large -> "Large"
        DashboardCardKind.Medium -> "Medium"
        DashboardCardKind.Small -> "Small"
        DashboardCardKind.Horizontal -> "Horizontal"
    }

/** Where a read list the user is reading continues (aligned with ReadListContinuation). */
data class ReadListContinuationUi(
    val readListId: Int,
    val readListName: String,
    /** 1-based position of the next book inside the list. */
    val nextBookIndex: Int,
    val nextBookTitle: String,
    val hue: Float,
    /** Progress of the next book while it is in progress; null while unread. */
    val bookProgress: Float?,
    val booksRead: Int,
    val bookCount: Int,
) {
    /** Progress line: book % while in progress, then list counts (aligned with ReadListContinuationProgressText). */
    val progressLabel: String
        get() {
            val listLabel = "$booksRead/$bookCount read"
            val percent = bookProgress?.let { "${(it * 100).toInt()}%" }
            return if (percent != null) "$percent • $listLabel" else listLabel
        }
}

/** Per-section customization entry (aligned with DashboardConfiguration: order + visibility + cardKind override). */
data class DashboardSectionSetting(
    val id: String,
    val title: String,
    val defaultKind: DashboardCardKind,
    val seriesContent: Boolean = false,
    val enabled: Boolean = true,
) {
    /** Series sections have no horizontal card (aligned with DashboardSection.availableCardKinds). */
    val availableKinds: List<DashboardCardKind>
        get() = if (seriesContent) {
            listOf(DashboardCardKind.Large, DashboardCardKind.Medium, DashboardCardKind.Small)
        } else {
            listOf(
                DashboardCardKind.Large,
                DashboardCardKind.Medium,
                DashboardCardKind.Small,
                DashboardCardKind.Horizontal,
            )
        }
}

object SampleDashboardData {

    /** Real dashboard sections: ids / titles / default card kinds match what DashboardViewModel produces
     *  (aligned with the iOS DashboardSection.allCases order and defaultCardKind). */
    val dashboardSections: List<DashboardSectionSetting> = listOf(
        DashboardSectionSetting(ReadListsInProgressSectionId, "Read Lists in Progress", DashboardCardKind.Horizontal),
        DashboardSectionSetting("keepReading", "Keep Reading", DashboardCardKind.Horizontal),
        DashboardSectionSetting("onDeck", "On Deck", DashboardCardKind.Large),
        DashboardSectionSetting("recentlyReleasedBooks", "Recently Released Books", DashboardCardKind.Large),
        DashboardSectionSetting("recentlyAddedBooks", "Recently Added Books", DashboardCardKind.Large),
        DashboardSectionSetting(
            "recentlyAddedSeries", "Recently Added Series", DashboardCardKind.Large, seriesContent = true,
        ),
        DashboardSectionSetting(
            "recentlyUpdatedSeries", "Recently Updated Series", DashboardCardKind.Large, seriesContent = true,
        ),
        DashboardSectionSetting("recentlyReadBooks", "Recently Read Books", DashboardCardKind.Small),
    )

    fun section(id: String): DashboardSectionSetting? = dashboardSections.firstOrNull { it.id == id }
}
