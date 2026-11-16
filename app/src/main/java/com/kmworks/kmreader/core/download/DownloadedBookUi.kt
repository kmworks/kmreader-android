package com.kmworks.kmreader.core.download

/** 已下载书籍 UI 模型（文件扫描 + meta 文件）。 */
data class DownloadedBookUi(
    val bookDtoId: String,
    val title: String,
    val pageCount: Int,
    val sizeBytes: Long,
    /** 所属库 id（入队时记录；无记录的历史下载为 null，仅在「全部书库」范围显示）。 */
    val libraryId: String? = null,
)
