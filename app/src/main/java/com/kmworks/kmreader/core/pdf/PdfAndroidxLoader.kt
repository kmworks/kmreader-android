package com.kmworks.kmreader.core.pdf

import android.content.Context
import android.net.Uri
import androidx.pdf.PdfDocument
import androidx.pdf.SandboxedPdfLoader
import java.io.File

/** androidx.pdf sandboxed document loader wrapper: the caller closes the returned document. */
class PdfAndroidxLoader(context: Context) {

    private val loader = SandboxedPdfLoader(context.applicationContext)

    /** Open a local file; null on failure. */
    suspend fun open(file: File): PdfDocument? =
        runCatching { loader.openDocument(Uri.fromFile(file), null) }.getOrNull()
}
