package com.kmworks.kmreader.core.download

import androidx.annotation.StringRes
import com.kmworks.kmreader.R

/** Download queue row UI model (aggregated from WorkInfo). */
data class DownloadTaskUi(
    val bookDtoId: String,
    val title: String,
    val pageDone: Int,
    val pageTotal: Int,
    val status: Status,
) {
    val progress: Float get() = if (pageTotal > 0) pageDone / pageTotal.toFloat() else 0f

    enum class Status(@StringRes val labelRes: Int) {
        Downloading(R.string.download_status_downloading),
        Paused(R.string.download_status_paused),
        Failed(R.string.download_status_failed),
    }
}
