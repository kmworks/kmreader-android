package com.kmworks.kmreader.data

/** 智能列表目标类型（对齐 SmartList.target：book/series）。 */
enum class SmartListTarget(val label: String) {
    Book("书籍"), Series("系列")
}

/** 收藏详情假数据（对齐 CollectionDetailView 所需字段）。 */
data class CollectionDetail(
    val id: Int,
    val name: String,
    val summary: String?,
    val ordered: Boolean,
    val seriesIds: List<Int>,
) {
    /** 按收录顺序解析出系列。 */
    val series: List<Series>
        get() = seriesIds.mapNotNull { id -> SampleData.series.firstOrNull { it.id == id } }
}

/** 阅读列表详情假数据；bookIds 有序，顺序即阅读顺序（对齐 ReadListDetailView）。 */
data class ReadListDetail(
    val id: Int,
    val name: String,
    val summary: String?,
    val ordered: Boolean,
    val bookIds: List<Int>,
) {
    /** 按列表顺序解析出书籍。 */
    val books: List<Book>
        get() = bookIds.mapNotNull(SampleListDetailData::bookById)

    /** 续读位置：第一本未读完的下标（0-based），全部读完时为 size。 */
    val continuationIndex: Int
        get() = books.indexOfFirst { it.progress == null || it.progress < 1f }
            .let { if (it < 0) books.size else it }
}

/** 智能列表详情假数据（对齐 SmartListDetailView：名称/目标类型/更新时间/简介/命中）。 */
data class SmartListDetail(
    val id: Int,
    val name: String,
    val summary: String?,
    val target: SmartListTarget,
    val modifiedLabel: String,
    val bookIds: List<Int> = emptyList(),
    val seriesIds: List<Int> = emptyList(),
)

/** 书籍详情补充元数据（原作 BookDetailView 元信息行与媒体信息区，由 id 派生保证稳定）。 */
data class BookDetailMeta(
    val releaseDate: String,
    val sizeLabel: String,
    val format: String,
    val filePath: String,
    val summary: String,
    val authors: List<Creator>,
    val tags: List<String>,
)

object SampleListDetailData {

    private val allBooks: List<Book> by lazy { SampleData.series.flatMap(SampleData::booksOf) }

    fun bookById(id: Int): Book? = allBooks.firstOrNull { it.id == id }

    /** 收藏（id 与 ListsScreen 的 ListEntry 对应）。 */
    val collections: List<CollectionDetail> = listOf(
        CollectionDetail(1, "少年漫画", "热血少年向连载与完结作品合集。", false, listOf(2, 4, 5, 6, 9)),
        CollectionDetail(2, "已完结经典", "已完结、可一口气补完的长青作品。", false, listOf(2, 8, 10)),
        CollectionDetail(3, "画风向", "以独特画风收录的系列。", false, listOf(1, 5, 7)),
    )

    fun collectionById(id: Int): CollectionDetail? = collections.firstOrNull { it.id == id }

    /** 阅读列表（有序，含未读卷便于演示续读提示）。 */
    val readLists: List<ReadListDetail> = listOf(
        ReadListDetail(
            11, "本月计划", "本月要追平的卷数，按顺序读。", true,
            listOf(1009, 1010, 1011, 3009, 3010, 5017, 5018, 1008),
        ),
        ReadListDetail(
            12, "周末补完", "周末一口气读完的短篇列表。", true,
            listOf(10001, 10002, 4001, 4002, 4003, 4004),
        ),
        ReadListDetail(
            13, "长篇连载", null, true,
            listOf(9058, 9059, 9060, 9061, 9062, 9063),
        ),
        ReadListDetail(
            14, "推荐给朋友", null, true,
            listOf(10001, 3001, 3002, 3003, 3004, 3005),
        ),
    )

    fun readListById(id: Int): ReadListDetail? = readLists.firstOrNull { it.id == id }

    /** 智能列表（命中内容由目标类型决定取 bookIds 或 seriesIds）。 */
    val smartLists: List<SmartListDetail> = listOf(
        SmartListDetail(
            21, "未读新卷", "最近新增且尚未开读的卷。", SmartListTarget.Book, "今天更新",
            bookIds = listOf(1010, 1011, 1012, 3009, 3010, 3011, 3012, 3013, 5017, 5018, 7012, 7013),
        ),
        SmartListDetail(
            22, "连载中·少年", "仍在连载的少年向系列。", SmartListTarget.Series, "昨天更新",
            seriesIds = listOf(2, 4, 5, 6, 9),
        ),
        SmartListDetail(
            23, "今年新增未读", "今年入库且未读的书籍。", SmartListTarget.Book, "3 天前更新",
            bookIds = listOf(4001, 4002, 4003, 4004, 4005, 4006, 6001, 6002, 6003),
        ),
        SmartListDetail(
            24, "长篇（50 册以上）", "卷数超多的长篇系列。", SmartListTarget.Series, "上周更新",
            seriesIds = listOf(9, 2),
        ),
    )

    fun smartListById(id: Int): SmartListDetail? = smartLists.firstOrNull { it.id == id }

    fun smartListBooks(detail: SmartListDetail): List<Book> = detail.bookIds.mapNotNull(::bookById)

    fun smartListSeries(detail: SmartListDetail): List<Series> =
        detail.seriesIds.mapNotNull { id -> SampleData.series.firstOrNull { it.id == id } }

    /** 详情页元信息（发布日期/大小/格式/路径），系列归属用于复用简介、作者与标签。 */
    fun bookMetaOf(book: Book): BookDetailMeta {
        val series = SampleData.series.firstOrNull { it.title == book.seriesTitle }
        val month = ((book.id % 12) + 1).toString().padStart(2, '0')
        // 三种格式各给一批样本，用于演示 Divina / EPUB / PDF 三种阅读器路由
        val format = when {
            book.id % 7 == 0 -> "PDF"
            book.id % 3 == 0 -> "EPUB"
            else -> "CBZ"
        }
        return BookDetailMeta(
            releaseDate = "2021-$month-15",
            sizeLabel = "${40 + book.id % 60} MB",
            format = format,
            filePath = "/komga/${book.seriesTitle}/${book.number}.${format.lowercase()}",
            summary = series?.summary ?: "${book.seriesTitle} ${book.number}，共 ${book.pageLabel}。",
            authors = series?.creators ?: emptyList(),
            tags = series?.tags ?: emptyList(),
        )
    }
}
