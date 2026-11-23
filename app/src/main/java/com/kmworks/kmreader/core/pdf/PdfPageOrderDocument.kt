package com.kmworks.kmreader.core.pdf

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.util.Size
import android.util.SparseArray
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.RenderParams
import androidx.pdf.annotation.content.KeyedPdfAnnotation
import androidx.pdf.annotation.content.KeyedPdfObject
import androidx.pdf.annotation.content.PdfObject
import androidx.pdf.content.PageMatchBounds
import androidx.pdf.content.PageSelection
import androidx.pdf.content.SelectionBoundary
import androidx.pdf.models.FormWidgetInfo
import java.util.concurrent.Executor

/**
 * [PdfDocument] decorator that presents [delegate] in a different display order (see
 * [PdfPageOrder]): positions handed to and from androidx.pdf are remapped, while every
 * consumer of physical page numbers (TOC, search results, progress, Komga) keeps talking
 * to the delegate's numbering.
 *
 * The virtual solo-cover blank is served locally: cover-sized [PageInfo], an empty text /
 * link / annotation surface and a transparent bitmap, so the reader background shows
 * through where iOS `displaysAsBook` leaves the cover unpaired.
 *
 * Ownership: the wrapper does not own [delegate]; `close` forwards, and PdfView never
 * closes replaced documents (verified in the pdf-viewer 1.0.0-beta01 sources), so swapping
 * wrappers on rotation or settings changes is safe.
 */
@SuppressLint("RestrictedApi") // abstract PdfDocument members hidden @RestrictTo(LIBRARY*) must still be implemented
@OptIn(ExperimentalPdfApi::class)
class PdfPageOrderDocument(
    private val delegate: PdfDocument,
    private val order: PdfPageOrder,
) : PdfDocument {

    override val uri: Uri
        get() = delegate.uri

    override val pageCount: Int
        get() = order.displayCount

    override val linearizationStatus: Int
        get() = delegate.linearizationStatus

    override val renderParams: RenderParams
        get() = delegate.renderParams

    override val formType: Int
        get() = delegate.formType

    /** Cover page dimensions, cached to size the virtual blank like the cover it precedes. */
    private var coverInfo: PdfDocument.PageInfo? = null

    private suspend fun virtualPageInfo(position: Int): PdfDocument.PageInfo {
        val info = coverInfo ?: delegate.getPageInfo(0).also { coverInfo = it }
        return PdfDocument.PageInfo(position, info.height, info.width, emptyList())
    }

    override suspend fun getPageInfo(pageNumber: Int): PdfDocument.PageInfo =
        getPageInfo(pageNumber, PdfDocument.PAGE_INFO_EXCLUDE_FORM_WIDGETS)

    override suspend fun getPageInfo(pageNumber: Int, pageInfoFlags: Long): PdfDocument.PageInfo {
        val physical = order.positionToPhysical(pageNumber)
        if (physical < 0) return virtualPageInfo(pageNumber)
        val info = delegate.getPageInfo(physical, pageInfoFlags)
        return PdfDocument.PageInfo(pageNumber, info.height, info.width, info.formWidgetInfos)
    }

    override suspend fun getPageInfos(pageRange: IntRange): List<PdfDocument.PageInfo> =
        getPageInfos(pageRange, PdfDocument.PAGE_INFO_EXCLUDE_FORM_WIDGETS)

    override suspend fun getPageInfos(
        pageRange: IntRange,
        pageInfoFlags: Long,
    ): List<PdfDocument.PageInfo> = pageRange.map { getPageInfo(it, pageInfoFlags) }

    override suspend fun searchDocument(
        query: String,
        pageRange: IntRange,
    ): SparseArray<List<PageMatchBounds>> {
        // The mapping only permutes pages within each row, so the positions covered by
        // [pageRange] map to one contiguous physical range.
        val physicalFirst = order.positionToPhysical(pageRange.first).coerceAtLeast(0)
        val physicalLast = order.positionToPhysical(pageRange.last).coerceAtLeast(0)
        if (physicalLast < physicalFirst) return SparseArray()
        val results = delegate.searchDocument(query, physicalFirst..physicalLast)
        val remapped = SparseArray<List<PageMatchBounds>>(results.size())
        for (i in 0 until results.size()) {
            remapped.put(order.physicalToPosition(results.keyAt(i)), results.valueAt(i))
        }
        return remapped
    }

    override suspend fun getSelectionBounds(
        pageNumber: Int,
        start: PointF,
        stop: PointF,
    ): PageSelection? {
        val physical = order.positionToPhysical(pageNumber)
        if (physical < 0) return null
        return delegate.getSelectionBounds(physical, start, stop)
    }

    override suspend fun getSelectionBounds(
        pageNumber: Int,
        start: SelectionBoundary,
        stop: SelectionBoundary,
    ): PageSelection? {
        val physical = order.positionToPhysical(pageNumber)
        if (physical < 0) return null
        return delegate.getSelectionBounds(physical, start, stop)
    }

    override suspend fun getSelectAllSelectionBounds(pageNumber: Int): PageSelection? {
        val physical = order.positionToPhysical(pageNumber)
        if (physical < 0) return null
        return delegate.getSelectAllSelectionBounds(physical)
    }

    override suspend fun getPageContent(pageNumber: Int): PdfDocument.PdfPageContent? {
        val physical = order.positionToPhysical(pageNumber)
        if (physical < 0) return PdfDocument.PdfPageContent(emptyList(), emptyList())
        return delegate.getPageContent(physical)
    }

    override suspend fun getPageLinks(pageNumber: Int): PdfDocument.PdfPageLinks {
        val physical = order.positionToPhysical(pageNumber)
        if (physical < 0) return PdfDocument.PdfPageLinks(emptyList(), emptyList())
        return delegate.getPageLinks(physical)
    }

    override suspend fun getAnnotationsForPage(pageNum: Int): List<KeyedPdfAnnotation> {
        val physical = order.positionToPhysical(pageNum)
        if (physical < 0) return emptyList()
        return delegate.getAnnotationsForPage(physical)
    }

    override suspend fun getPageObjects(pageNum: Int, types: Long): List<KeyedPdfObject> {
        val physical = order.positionToPhysical(pageNum)
        if (physical < 0) return emptyList()
        return delegate.getPageObjects(physical, types)
    }

    override fun getPageBitmapSource(pageNumber: Int): PdfDocument.BitmapSource {
        val physical = order.positionToPhysical(pageNumber)
        if (physical < 0) return BlankBitmapSource(pageNumber)
        return RemappedBitmapSource(delegate.getPageBitmapSource(physical), pageNumber)
    }

    override suspend fun getFormWidgetInfos(pageNum: Int, types: Long): List<FormWidgetInfo> {
        val physical = order.positionToPhysical(pageNum)
        if (physical < 0) return emptyList()
        return delegate.getFormWidgetInfos(physical, types)
    }

    override suspend fun getTopPageObjectAtPosition(pageNum: Int, point: PointF): PdfObject? {
        val physical = order.positionToPhysical(pageNum)
        if (physical < 0) return null
        return delegate.getTopPageObjectAtPosition(physical, point)
    }

    // The delegate reports physical pages; the view's listener expects display positions.
    private val invalidationListeners = mutableMapOf<
        PdfDocument.OnPdfContentInvalidatedListener,
        PdfDocument.OnPdfContentInvalidatedListener,
        >()

    override fun addOnPdfContentInvalidatedListener(
        executor: Executor,
        listener: PdfDocument.OnPdfContentInvalidatedListener,
    ) {
        val translated =
            object : PdfDocument.OnPdfContentInvalidatedListener {
                override fun onPdfContentInvalidated(
                    pageNumber: Int,
                    dirtyAreas: List<Rect>,
                ) {
                    listener.onPdfContentInvalidated(
                        order.physicalToPosition(pageNumber),
                        dirtyAreas,
                    )
                }
            }
        synchronized(invalidationListeners) { invalidationListeners[listener] = translated }
        delegate.addOnPdfContentInvalidatedListener(executor, translated)
    }

    override fun removeOnPdfContentInvalidatedListener(
        listener: PdfDocument.OnPdfContentInvalidatedListener,
    ) {
        val translated =
            synchronized(invalidationListeners) { invalidationListeners.remove(listener) }
                ?: return
        delegate.removeOnPdfContentInvalidatedListener(translated)
    }

    override fun addOnEditAppliedListener(
        executor: Executor,
        listener: PdfDocument.OnEditAppliedListener,
    ) = delegate.addOnEditAppliedListener(executor, listener)

    override fun removeOnEditAppliedListener(listener: PdfDocument.OnEditAppliedListener) =
        delegate.removeOnEditAppliedListener(listener)

    override fun isFeatureSupported(feature: PdfFeature): Boolean =
        delegate.isFeatureSupported(feature)

    override fun close() = delegate.close()

    /** Re-labels the delegate's bitmap source with the display position PdfView asked for. */
    private class RemappedBitmapSource(
        private val delegate: PdfDocument.BitmapSource,
        override val pageNumber: Int,
    ) : PdfDocument.BitmapSource {
        override suspend fun getBitmap(scaledPageSizePx: Size, tileRegion: Rect?): Bitmap =
            delegate.getBitmap(scaledPageSizePx, tileRegion)

        override fun close() = delegate.close()
    }

    /**
     * Bitmap source for the virtual solo-cover blank: a fully transparent bitmap (fresh
     * [Bitmap.createBitmap] memory is zeroed), so the reader background shows through.
     */
    private class BlankBitmapSource(
        override val pageNumber: Int,
    ) : PdfDocument.BitmapSource {
        override suspend fun getBitmap(scaledPageSizePx: Size, tileRegion: Rect?): Bitmap =
            Bitmap.createBitmap(
                (tileRegion?.width() ?: scaledPageSizePx.width).coerceAtLeast(1),
                (tileRegion?.height() ?: scaledPageSizePx.height).coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )

        override fun close() {}
    }
}
