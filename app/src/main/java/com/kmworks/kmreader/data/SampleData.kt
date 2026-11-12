package com.kmworks.kmreader.data

/** 作者角色，对齐 KMReader AuthorRole（icon 映射一致）。 */
enum class AuthorRole(val label: String) {
    Writer("原作"), Penciller("作画"), Inker("勾线"), Colorist("上色"),
    Letterer("嵌字"), Cover("封面"), Editor("编辑"), Translator("翻译"), Custom("其他"),
}

/** 详情页创作者条目（按角色排序展示，对齐 SeriesHeroInfoView.creatorItems）。 */
data class Creator(val name: String, val role: AuthorRole)

/** 书籍元数据中聚合出的知名实体（角色/团队/地点），对齐 NotableEntity。 */
data class NotableEntity(val name: String, val kind: String)

/** 系列（Series）。progress 为该系列整体已读比例，null 表示未开始。 */
data class Series(
    val id: Int,
    val title: String,
    val author: String,
    val hue: Float,
    val bookCount: Int,
    val unreadCount: Int,
    val progress: Float?,
    val publisher: String? = null,
    val status: String? = null,
    val language: String? = null,
    val directionRtl: Boolean = true,
    val releaseYear: Int? = null,
    val totalBookCount: Int? = null,
    val ageRating: Int? = null,
    val creators: List<Creator> = emptyList(),
    val notableEntities: List<NotableEntity> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val links: List<Pair<String, String>> = emptyList(),
    val summary: String? = null,
) {
    val subtitle: String
        get() = if (unreadCount > 0) "共 $bookCount 册 · $unreadCount 册未读" else "共 $bookCount 册 · 已读完"

    /** 详情页操作卡的册数行，对齐 SeriesBookCountView。 */
    val bookCountLine: String
        get() = if (totalBookCount != null) "$bookCount / $totalBookCount 册" else "$bookCount 册"
}

/** 书籍（Book）。progress null=未读，1f=已读完。 */
data class Book(
    val id: Int,
    val number: String,
    val title: String,
    val seriesTitle: String,
    val hue: Float,
    val pageCount: Int,
    val progress: Float?,
) {
    val pageLabel: String get() = "$pageCount 页"
}

/** 对齐 KMReader LayoutConfig（iPhone 值，对照 Apple Books 校准）：large 152 / medium 116 / small 80 / horizontal 250。 */
enum class DashboardCardKind(val widthDp: Int) {
    Large(152), Medium(116), Small(80), Horizontal(250)
}

data class DashboardSection<T>(
    val id: String,
    val title: String,
    val kind: DashboardCardKind,
    val items: List<T>,
)

/** 共享 UI 模型容器（演示假数据已全部移除，内容一律来自服务器）。 */
object SampleData {

    data class ListGroup(val title: String, val items: List<ListEntry>)
    data class ListEntry(
        val id: Int,
        val title: String,
        val count: Int,
        val hue: Float,
        val pinned: Boolean,
        /** 智能列表用：目标类型（书籍/系列）与更新日期；普通列表为 null。 */
        val targetLabel: String? = null,
        val modifiedLabel: String? = null,
    )
}
