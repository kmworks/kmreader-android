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
 * 单本 PDF 的 PdfRenderer 封装（原生方案，API 26+）。
 * PdfRenderer 非线程安全：所有访问经 lock 串行化；close 后渲染返回 null。
 */
class PdfDocumentHolder(file: File) : Closeable {

    private val lock = ReentrantLock()
    private var fd: ParcelFileDescriptor? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private var renderer: PdfRenderer? = PdfRenderer(fd!!)

    val pageCount: Int
        get() = lock.withLock { renderer?.pageCount ?: 0 }

    /**
     * 渲染一页为 Bitmap（0-based index）。targetWidth 为像素宽，高度按页面纵横比推导。
     * 白底填充（PDF 内容区默认透明，直接渲染会黑底）。
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
