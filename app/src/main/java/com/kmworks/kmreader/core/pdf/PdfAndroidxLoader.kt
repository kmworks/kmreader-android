package com.kmworks.kmreader.core.pdf

import android.content.Context
import android.net.Uri
import androidx.pdf.PdfDocument
import androidx.pdf.SandboxedPdfLoader
import java.io.File

/** androidx.pdf 沙箱文档加载器封装：调用方负责关闭返回的文档。 */
class PdfAndroidxLoader(context: Context) {

    private val loader = SandboxedPdfLoader(context.applicationContext)

    /** 打开本地文件，失败返回 null。 */
    suspend fun open(file: File): PdfDocument? =
        runCatching { loader.openDocument(Uri.fromFile(file), null) }.getOrNull()
}
