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

object SampleData {

    val series: List<Series> = listOf(
        Series(
            1, "葬送的芙莉莲", "山田钟人 / アベツカサ", 210f, 12, 3, 0.75f,
            publisher = "小学馆", status = "连载中", language = "日语", releaseYear = 2020,
            totalBookCount = 14,
            creators = listOf(
                Creator("山田钟人", AuthorRole.Writer),
                Creator("アベツカサ", AuthorRole.Penciller),
            ),
            notableEntities = listOf(
                NotableEntity("芙莉莲", "character"),
                NotableEntity("辛美尔", "character"),
                NotableEntity("菲伦", "character"),
                NotableEntity("休塔尔克", "character"),
                NotableEntity("北部高原", "location"),
            ),
            genres = listOf("奇幻", "冒险", "治愈"),
            tags = listOf("年度榜单", "已动画化", "治愈系"),
            links = listOf("Bangumi" to "https://bgm.tv/subject/285267"),
            summary = "打倒魔王的勇者一行人中的精灵魔法使芙莉莲，在勇者死后踏上了追忆往昔、理解人类的旅途。千年寿命的她与新的伙伴们一起，沿着当年的冒险之路缓缓前行。",
        ),
        Series(2, "咒术回战", "芥见下下", 150f, 26, 0, 1f),
        Series(3, "间谍过家家", "远藤达哉", 320f, 14, 5, 0.6f),
        Series(4, "怪兽8号", "松本直也", 90f, 13, 13, null),
        Series(5, "链锯人", "藤本树", 25f, 18, 2, 0.9f),
        Series(6, "蓝色监狱", "金城宗幸 / 野村优介", 190f, 28, 28, null),
        Series(7, "我推的孩子", "赤坂明 / 横枪萌果", 275f, 16, 4, 0.72f),
        Series(8, "地狱乐", "贺来ゆうじ", 40f, 13, 0, 1f),
        Series(9, "王者天下", "原泰久", 55f, 72, 10, 0.86f),
        Series(10, "迷宫饭", "九井谅子", 120f, 14, 0, 1f),
    )

    fun booksOf(s: Series): List<Book> =
        (1..s.bookCount).map { n ->
            val p = s.progress?.let { if (n.toFloat() / s.bookCount <= it) 1f else null }
                ?: if (n == 1) 0.35f else null
            Book(
                id = s.id * 1000 + n,
                number = "第 $n 卷",
                title = "${s.title} 第 $n 卷",
                seriesTitle = s.title,
                hue = s.hue,
                pageCount = 180 + (n * 13) % 40,
                progress = p,
            )
        }

    private fun inProgressBooks(): List<Book> =
        series.filter { it.progress != null && it.progress < 1f }.map { s ->
            val current = ((s.progress ?: 0f) * s.bookCount).toInt().coerceAtLeast(1)
            Book(
                id = s.id * 1000 + current + 500,
                number = "第 ${current + 1} 卷",
                title = "${s.title} 第 ${current + 1} 卷",
                seriesTitle = s.title,
                hue = s.hue,
                pageCount = 180 + (current * 13) % 40,
                progress = 0.2f + s.id * 0.11f % 0.7f,
            )
        }

    /** 仪表盘分区：分区集合与默认卡片形态 1:1 对齐 KMReader（horizontal 横卡 / large 大卡 / small 小卡）。 */
    val dashboardSections: List<DashboardSection<out Any>> = listOf(
        DashboardSection("keepReading", "继续阅读", DashboardCardKind.Horizontal, inProgressBooks().take(6)),
        DashboardSection("onDeck", "待读", DashboardCardKind.Large, booksOf(series[0]).filter { it.progress == null }.take(6)),
        DashboardSection("recentlyAddedBooks", "最近添加书籍", DashboardCardKind.Large, series.take(6).flatMap { booksOf(it).take(1) }),
        DashboardSection("recentlyUpdatedSeries", "最近更新系列", DashboardCardKind.Large, series.take(6)),
        DashboardSection("readListsInProgress", "进行中的阅读列表", DashboardCardKind.Horizontal, inProgressBooks().reversed().take(4)),
    )

    val recentlyRead: List<Book> = inProgressBooks().take(4)

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

    /** 列表页三条横向条带：收藏 / 阅读列表 / 智能列表（置顶项排前），对齐 ListsBrowseContentView。
     *  智能列表条带在服务器不支持或离线时隐藏。 */
    val lists: List<ListGroup> = listOf(
        ListGroup(
            "收藏", listOf(
                ListEntry(1, "少年漫画", 24, 210f, true),
                ListEntry(2, "已完结经典", 15, 35f, false),
                ListEntry(3, "画风向", 9, 300f, false),
            )
        ),
        ListGroup(
            "阅读列表", listOf(
                ListEntry(11, "本月计划", 8, 150f, true),
                ListEntry(12, "周末补完", 12, 260f, false),
                ListEntry(13, "长篇连载", 20, 90f, false),
                ListEntry(14, "推荐给朋友", 6, 0f, false),
            )
        ),
        ListGroup(
            "智能列表", listOf(
                ListEntry(21, "未读新卷", 18, 200f, true, targetLabel = "书籍", modifiedLabel = "今天更新"),
                ListEntry(22, "连载中·少年", 32, 150f, false, targetLabel = "系列", modifiedLabel = "昨天更新"),
                ListEntry(23, "今年新增未读", 27, 280f, false, targetLabel = "书籍", modifiedLabel = "3 天前更新"),
                ListEntry(24, "长篇（50 册以上）", 6, 40f, false, targetLabel = "系列", modifiedLabel = "上周更新"),
            )
        ),
    )

    val searchResults: List<Series> = series.take(8)

    fun seriesById(id: Int): Series = series.first { it.id == id }
}
