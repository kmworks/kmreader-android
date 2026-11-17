package com.kmworks.kmreader.data

/** 作者角色，对齐 KMReader AuthorRole（icon 映射一致）。token 为服务端 role 原文。 */
enum class AuthorRole(val label: String, val token: String) {
    Writer("原作", "writer"), Penciller("作画", "penciller"), Inker("勾线", "inker"),
    Colorist("上色", "colorist"), Letterer("嵌字", "letterer"), Cover("封面", "cover"),
    Editor("编辑", "editor"), Translator("翻译", "translator"), Custom("其他", "custom"),
    ;

    companion object {
        /** 编辑表单可选的预定义角色（对齐 iOS AuthorRole.predefinedCases）。 */
        val predefined: List<AuthorRole>
            get() = listOf(Writer, Penciller, Inker, Colorist, Letterer, Cover, Editor, Translator)
    }
}

/**
 * 详情页创作者条目（按角色排序展示，对齐 SeriesHeroInfoView.creatorItems）。
 * [customRole] 保留服务端自定义角色原文（AuthorRole.Custom 时用作显示名，对齐 iOS AuthorRole.custom(String)）。
 */
data class Creator(
    val name: String,
    val role: AuthorRole,
    val customRole: String? = null,
) {
    /** 角色中文名；自定义角色优先用服务端原文，缺失时回退「其他」。 */
    val roleLabel: String
        get() = if (role == AuthorRole.Custom) {
            customRole?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
                ?: AuthorRole.Custom.label
        } else {
            role.label
        }
}

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
    /**
     * 卡片副标题，对齐 iOS SeriesCardView.statusContent：
     * 部分已读 → 「共 N 册 · 已读 xx%」；全部读完 → 「共 N 册 · 已读完」；未开始 → 「共 N 册」。
     * 任何状态都不出现「未读」字样。
     */
    val subtitle: String
        get() = when {
            progress != null && progress > 0f && progress < 1f ->
                "共 $bookCount 册 · 已读 ${(progress * 100).toInt()}%"
            progress == 1f || (unreadCount == 0 && bookCount > 0) -> "共 $bookCount 册 · 已读完"
            else -> "共 $bookCount 册"
        }

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

    /** 对齐 iOS Book.bookTitleLine："number - title"。 */
    val titleLine: String get() = "$number - $title"
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
        /** 编辑 sheet 初始值；来自真实 DTO，避免以空白覆盖服务端字段。 */
        val summary: String? = null,
        val ordered: Boolean? = null,
        val smartListTarget: SmartListTarget? = null,
    )
}
