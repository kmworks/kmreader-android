package com.kmworks.kmreader.ui.reader

import android.util.Size
import androidx.pdf.PdfDocument
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse

/**
 * Coil key for a PDF page thumbnail: the physical (0-based) page number. Used by the jump sheet
 * preview cards; physical numbering keeps the strip aligned with the page capsule regardless of
 * the RTL/cover page-order decoration.
 */
data class PdfPageThumb(val page: Int)

/**
 * Renders a PDF page through androidx.pdf's [PdfDocument.getPageBitmapSource] (the same pipeline
 * PdfView uses), so jump-sheet cards show real page thumbnails like the iOS PDFKit-based strip.
 * Bitmaps live in Coil's memory cache, keyed by [PdfPageThumb].
 */
class PdfPageThumbFetcher(
    private val document: PdfDocument,
    private val data: PdfPageThumb,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val width = options.size.width.pxOrElse { FALLBACK_SIZE }
        val height = options.size.height.pxOrElse { FALLBACK_SIZE }
        val bitmap = document.getPageBitmapSource(data.page).getBitmap(Size(width, height), null)
        return ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val document: PdfDocument) : Fetcher.Factory<PdfPageThumb> {
        override fun create(data: PdfPageThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            PdfPageThumbFetcher(document, data, options)
    }

    private companion object {
        /** When the layout did not resolve a concrete target size. */
        const val FALLBACK_SIZE = 512
    }
}
