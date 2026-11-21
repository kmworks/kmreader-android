package com.kmworks.kmreader.core.epub

import android.content.Context
import android.webkit.WebView

/**
 * Reading WebView that reports a drag past the bottom edge of the document.
 *
 * The EPUB reader has no pages: it shows one chapter at a time and the end of the book is a scroll
 * position, so "scroll a little further" is the only gesture that can open the end page (mirrors
 * the iOS reader, where paging past the last page shows EpubEndPageView). Android delivers that as
 * an overscroll clamped at the bottom, which a plain WebView discards.
 */
class EpubScrollWebView(context: Context) : WebView(context) {

    /** Called repeatedly while the document is dragged past its bottom edge. */
    var onBottomOverscroll: (() -> Unit)? = null

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        // clampedY at the bottom edge means the drag cannot scroll any further: the reading seam
        if (clampedY && !canScrollVertically(1)) onBottomOverscroll?.invoke()
    }
}
