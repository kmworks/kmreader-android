package com.kmworks.kmreader.data

/** 三态筛选值：关闭 → 包含 → 排除，循环切换（对齐原作 FilterChipState）。 */
enum class TriState {
    Off, Include, Exclude;

    val next: TriState
        get() = when (this) {
            Off -> Include
            Include -> Exclude
            Exclude -> Off
        }
}

enum class ReadStatus(val label: String) {
    Unread("未读"), InProgress("阅读中"), Read("已读")
}

/** 已激活的筛选条件（负向 = 排除态，chip 用反色变体渲染）。 */
data class ActiveFilter(val label: String, val negative: Boolean)

/** 对齐原作 BookBrowseOptions：排序 + 阅读状态包含/排除 + 标记 + 作者/标签 ∧∨。 */
data class BookBrowseOptions(
    val sortField: String = BookSortFields.first(),
    val sortAscending: Boolean = true,
    val readStatuses: Map<ReadStatus, TriState> = emptyMap(),
    val oneshot: TriState = TriState.Off,
    val deleted: TriState = TriState.Off,
    val authors: List<String> = emptyList(),
    val authorLogicAnd: Boolean = true,
    val tags: List<String> = emptyList(),
    val tagLogicAnd: Boolean = true,
) {
    val activeFilters: List<ActiveFilter>
        get() = buildList {
            readStatuses.forEach { (status, state) ->
                when (state) {
                    TriState.Include -> add(ActiveFilter(status.label, negative = false))
                    TriState.Exclude -> add(ActiveFilter(status.label, negative = true))
                    TriState.Off -> Unit
                }
            }
            if (oneshot != TriState.Off) add(ActiveFilter("单发", oneshot == TriState.Exclude))
            if (deleted != TriState.Off) add(ActiveFilter("已删除", deleted == TriState.Exclude))
            if (authors.isNotEmpty()) {
                add(
                    ActiveFilter(
                        authors.joinToString(if (authorLogicAnd) " ∧ " else " ∨ "),
                        negative = false,
                    )
                )
            }
            if (tags.isNotEmpty()) {
                add(
                    ActiveFilter(
                        tags.joinToString(if (tagLogicAnd) " ∧ " else " ∨ "),
                        negative = false,
                    )
                )
            }
        }
}

val BookSortFields = listOf("卷号", "标题", "发布日期", "添加日期", "更新日期", "文件大小", "页数")

/** 跨页面复用的筛选预设（原作存 savedFilter 表）。 */
data class SavedFilter(val name: String, val options: BookBrowseOptions)

val SampleSavedFilters = listOf(
    SavedFilter(
        "未读新卷",
        BookBrowseOptions(readStatuses = mapOf(ReadStatus.Unread to TriState.Include)),
    ),
    SavedFilter(
        "连载中未读",
        BookBrowseOptions(
            readStatuses = mapOf(
                ReadStatus.Unread to TriState.Include,
                ReadStatus.InProgress to TriState.Include,
            ),
            tags = listOf("少年"),
        ),
    ),
)
