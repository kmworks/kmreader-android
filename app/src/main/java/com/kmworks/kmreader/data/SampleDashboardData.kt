package com.kmworks.kmreader.data

/** Section id of the in-progress read lists strip (aligned with DashboardSection.readListsInProgress). */
const val ReadListsInProgressSectionId = "readListsInProgress"

/** Chinese label for the card kind picker (aligned with DashboardCardKind.title). */
val DashboardCardKind.label: String
    get() = when (this) {
        DashboardCardKind.Large -> "大"
        DashboardCardKind.Medium -> "中"
        DashboardCardKind.Small -> "小"
        DashboardCardKind.Horizontal -> "横向"
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
            val listLabel = "已读 $booksRead/$bookCount 本"
            val percent = bookProgress?.let { "${(it * 100).toInt()}%" }
            return if (percent != null) "$percent · $listLabel" else listLabel
        }

    val listProgress: Float get() = booksRead.toFloat() / bookCount.coerceAtLeast(1)
}

/** Per-section customization entry (aligned with DashboardConfiguration: order + visibility + cardKind override). */
data class DashboardSectionSetting(
    val id: String,
    val title: String,
    val defaultKind: DashboardCardKind,
    val seriesContent: Boolean = false,
    val enabled: Boolean = true,
    /** null = follow the section default. */
    val kindOverride: DashboardCardKind? = null,
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

    /** Default section order and card kinds, 1:1 with DashboardSection.allCases. */
    val sectionSettings: List<DashboardSectionSetting> = listOf(
        DashboardSectionSetting(ReadListsInProgressSectionId, "进行中的阅读列表", DashboardCardKind.Horizontal),
        DashboardSectionSetting("keepReading", "继续阅读", DashboardCardKind.Horizontal),
        DashboardSectionSetting("onDeck", "待读", DashboardCardKind.Large),
        DashboardSectionSetting("recentlyReleasedBooks", "最近发布书籍", DashboardCardKind.Large),
        DashboardSectionSetting("recentlyAddedBooks", "最近添加书籍", DashboardCardKind.Large),
        DashboardSectionSetting(
            "recentlyAddedSeries", "最近添加系列", DashboardCardKind.Small, seriesContent = true,
        ),
        DashboardSectionSetting(
            "recentlyUpdatedSeries", "最近更新系列", DashboardCardKind.Large, seriesContent = true,
        ),
        DashboardSectionSetting("recentlyReadBooks", "最近阅读", DashboardCardKind.Small),
    )

    /** Fake continuations for the in-progress read lists strip. */
    val readListContinuations: List<ReadListContinuationUi> = listOf(
        ReadListContinuationUi(11, "本月计划", 3, "葬送的芙莉莲 第 4 卷", 210f, 0.38f, 2, 8),
        ReadListContinuationUi(12, "周末补完", 5, "链锯人 第 9 卷", 25f, null, 4, 12),
        ReadListContinuationUi(13, "长篇连载", 1, "王者天下 第 63 卷", 55f, 0.12f, 62, 72),
        ReadListContinuationUi(14, "推荐给朋友", 2, "迷宫饭 第 2 卷", 120f, null, 1, 14),
    )
}
