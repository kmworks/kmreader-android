package com.kmworks.kmreader.core.download

/** 下载队列行 UI 模型（WorkInfo 聚合）。 */
data class DownloadTaskUi(
    val bookDtoId: String,
    val title: String,
    val pageDone: Int,
    val pageTotal: Int,
    val status: Status,
) {
    val progress: Float get() = if (pageTotal > 0) pageDone / pageTotal.toFloat() else 0f

    enum class Status(val label: String) {
        Downloading("下载中"),
        Failed("失败"),
    }
}
