package com.kmworks.kmreader.data

/** 智能列表目标类型（对齐 SmartList.target：book/series）。 */
enum class SmartListTarget(val label: String) {
    Book("书籍"), Series("系列")
}

/** 收藏详情导航模型（真实内容由 CollectionDetailViewModel 经注册表加载覆盖）。 */
data class CollectionDetail(
    val id: Int,
    val name: String,
    val summary: String?,
    val ordered: Boolean,
    val seriesIds: List<Int>,
)

/** 阅读列表详情导航模型；bookIds 有序，顺序即阅读顺序（真实内容由 VM 覆盖）。 */
data class ReadListDetail(
    val id: Int,
    val name: String,
    val summary: String?,
    val ordered: Boolean,
    val bookIds: List<Int>,
)

/** 智能列表详情导航模型（真实内容由 SmartListDetailViewModel 经注册表加载覆盖）。 */
data class SmartListDetail(
    val id: Int,
    val name: String,
    val summary: String?,
    val target: SmartListTarget,
    val modifiedLabel: String,
    val bookIds: List<Int> = emptyList(),
    val seriesIds: List<Int> = emptyList(),
)

/** 书籍详情补充元数据（发布日期/大小/格式/路径/简介/作者/标签，由 BookDto 映射）。 */
data class BookDetailMeta(
    val releaseDate: String,
    val sizeLabel: String,
    val format: String,
    val filePath: String,
    val summary: String,
    val authors: List<Creator>,
    val tags: List<String>,
    /** 可编辑元数据原始值（对齐 BookEditSheet 的 General/Links 字段）。 */
    val number: String = "",
    val numberSort: Float? = null,
    val isbn: String? = null,
    val links: List<Pair<String, String>> = emptyList(),
)

/**
 * 路由占位：演示假数据已移除，byId 返回空模型；详情页 ViewModel 按注册表反查 DTO id
 * 加载真实内容覆盖显示。
 */
object SampleListDetailData {

    fun collectionById(id: Int): CollectionDetail? = null

    fun readListById(id: Int): ReadListDetail? = null

    fun smartListById(id: Int): SmartListDetail =
        SmartListDetail(id, "", null, SmartListTarget.Book, "")
}
