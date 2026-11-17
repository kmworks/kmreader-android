package com.kmworks.kmreader.ui.util

import android.content.Context
import android.content.Intent
import com.kmworks.kmreader.R

/** System share sheet (mirrors iOS ShareLink: shares a Komga web link). */
fun shareText(context: Context, text: String, chooserTitle: String = context.getString(R.string.common_share)) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle))
}
