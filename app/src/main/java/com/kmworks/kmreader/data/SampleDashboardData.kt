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

    /** 仪表盘真实分区：与 DashboardViewModel 产出的 id / 标题 / 默认卡片形态一致。 */
    val dashboardSections: List<DashboardSectionSetting> = listOf(
        DashboardSectionSetting(ReadListsInProgressSectionId, "进行中的阅读列表", DashboardCardKind.Horizontal),
        DashboardSectionSetting("keepReading", "继续阅读", DashboardCardKind.Large),
        DashboardSectionSetting("onDeck", "待读", DashboardCardKind.Large),
        DashboardSectionSetting("recentlyAddedBooks", "最近更新书籍", DashboardCardKind.Large),
        DashboardSectionSetting(
            "recentlyAddedSeries", "最近更新系列", DashboardCardKind.Large, seriesContent = true,
        ),
        DashboardSectionSetting(
            "recentlyUpdatedSeries", "最近更新系列（内容）", DashboardCardKind.Large, seriesContent = true,
        ),
    )

    fun section(id: String): DashboardSectionSetting? = dashboardSections.firstOrNull { it.id == id }
}
