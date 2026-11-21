package com.kmworks.kmreader.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * `PdfRenderer` wrapper for one PDF (the native reading engine, API 26+).
 *
 * `PdfRenderer` is not thread-safe, so every access is serialized on a lock; after [close] rendering
 * returns null. Rendering paints a white background because PDF content areas are transparent and
 * would otherwise show through as black.
 */
class PdfDocumentHolder(file: File) : Closeable {

    private val lock = ReentrantLock()
    private var fd: ParcelFileDescriptor? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private var renderer: PdfRenderer? = PdfRenderer(fd!!)

    val pageCount: Int
        get() = lock.withLock { renderer?.pageCount ?: 0 }

    /**
     * Renders the page at [index] (0-based) into a bitmap [targetWidth] pixels wide; the height follows
     * the page aspect ratio. Returns null when the document is closed or the index is out of range.
     */
    fun renderPage(index: Int, targetWidth: Int): Bitmap? = lock.withLock {
        val r = renderer ?: return null
        if (index !in 0 until r.pageCount) return null
        val page = r.openPage(index)
        try {
            val width = targetWidth.coerceAtLeast(64)
            val height = (width * page.height.toFloat() / page.width)
                .roundToInt()
                .coerceAtLeast(64)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            page.close()
        }
    }

    override fun close() = lock.withLock {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        renderer = null
        fd = null
    }
}
