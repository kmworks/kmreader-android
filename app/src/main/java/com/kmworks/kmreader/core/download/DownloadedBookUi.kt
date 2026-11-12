package com.kmworks.kmreader.core.download

/** 已下载书籍 UI 模型（文件扫描 + meta 文件）。 */
data class DownloadedBookUi(
    val bookDtoId: String,
    val title: String,
    val pageCount: Int,
    val sizeBytes: Long,
)
