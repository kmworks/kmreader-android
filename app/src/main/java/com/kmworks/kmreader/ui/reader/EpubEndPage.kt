package com.kmworks.kmreader.ui.reader

/**
 * The gesture that opens the EPUB end page: the bottom of the last chapter.
 *
 * The WebView reports the bottom edge itself (bottom overscroll), so the trigger only has to know
 * which chapter is showing. Kept as a pure function so the decision is testable without a WebView.
 *
 * The end page itself is the shared [ReaderEndPage] transition page (previous/next book cards),
 * rendered by EpubReaderScreen — same as the DIVINA comic reader.
 */
internal object EpubEndPageTrigger {

    fun shouldShow(chapterIndex: Int, chapterCount: Int): Boolean =
        chapterCount > 0 && chapterIndex == chapterCount - 1
}
