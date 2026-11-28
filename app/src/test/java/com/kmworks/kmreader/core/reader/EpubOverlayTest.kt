package com.kmworks.kmreader.core.reader

import com.kmworks.kmreader.core.epub.EpubBookPageProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the iOS WebPubInfoOverlaySupport contract: per-slot text, controls-vs-reading mode,
 * RTL progress-bar alignment and the JSON persistence round trip.
 */
class EpubOverlayTest {

    private val strings = EpubOverlayStrings(
        bookProgress = "Book Progress %s",
        remaining = "%s left",
        chapterProgress = "%s",
        chapterPosition = "Chapter %d / %d",
        bookPosition = "Book %d / %d",
        pagesLeft = "%d pages left",
        lastPage = "Last page",
    )

    private fun resolve(
        prefs: EpubOverlayPreferences = EpubOverlayPreferences(),
        showingControls: Boolean = false,
        paged: Boolean = true,
        bookTitle: String? = "The Book",
        chapterTitle: String? = "Chapter 3",
        totalProgression: Float? = 0.1234f,
        currentPage: Int = 2,
        totalPages: Int = 12,
        chapterProgressFraction: Float? = null,
        bookPage: EpubBookPageProgress? = EpubBookPageProgress(currentPage = 421, totalPages = 1204),
        isRtl: Boolean = false,
    ) = resolveEpubOverlay(
        prefs = prefs,
        showingControls = showingControls,
        paged = paged,
        bookTitle = bookTitle,
        chapterTitle = chapterTitle,
        totalProgression = totalProgression,
        currentPage = currentPage,
        totalPages = totalPages,
        chapterProgressFraction = chapterProgressFraction,
        bookPage = bookPage,
        isRtl = isRtl,
        strings = strings,
    )

    @Test
    fun `defaults mirror the iOS overlay defaults`() {
        val content = resolve()
        // header center = book title, footer leading = chapter title, footer trailing = remaining
        assertEquals("The Book", content.topCenter.text)
        assertTrue(content.topCenter.isVisible)
        assertEquals("Chapter 3", content.bottomLeading.text)
        assertEquals("9 pages left", content.bottomTrailing.text)
        assertNull(content.topLeading.text)
        assertFalse(content.topLeading.isVisible)
        assertNull(content.topTrailing.text)
        assertNull(content.bottomCenter.text)
        assertNull(content.bottomProgress)
        assertFalse(content.isRtl)
    }

    @Test
    fun `percentages follow the iOS precision rules`() {
        val prefs = EpubOverlayPreferences(
            readerHeaderLeading = EpubOverlayItem.BOOK_PROGRESS_PERCENT,
            readerHeaderTrailing = EpubOverlayItem.BOOK_REMAINING_PERCENT,
            readerFooterCenter = EpubOverlayItem.CHAPTER_PROGRESS_PERCENT,
        )
        val content = resolve(prefs = prefs)
        // Whole-book progress is formatted with two decimals, remaining mirrors it
        assertEquals("Book Progress 12.34%", content.topLeading.text)
        assertEquals("87.66% left", content.topTrailing.text)
        // Chapter progress uses one decimal: page 3 of 12 = 25.0%
        assertEquals("25.0%", content.bottomCenter.text)
    }

    @Test
    fun `last page reports the iOS wording`() {
        val prefs = EpubOverlayPreferences(readerFooterTrailing = EpubOverlayItem.CHAPTER_REMAINING)
        val content = resolve(prefs = prefs, currentPage = 11)
        assertEquals("Last page", content.bottomTrailing.text)
    }

    @Test
    fun `scrolled mode reports chapter percentage instead of pages`() {
        val prefs = EpubOverlayPreferences(readerFooterTrailing = EpubOverlayItem.CHAPTER_REMAINING)
        val scrolled = resolve(prefs = prefs, paged = false, totalPages = 0, chapterProgressFraction = 0.25f)
        assertEquals("75.0% left", scrolled.bottomTrailing.text)

        val noFraction = resolve(prefs = prefs, paged = false, totalPages = 0, chapterProgressFraction = null)
        assertNull(noFraction.bottomTrailing.text)
        assertFalse(noFraction.bottomTrailing.isVisible)
    }

    @Test
    fun `chapter position differs between paged and scrolled`() {
        val prefs = EpubOverlayPreferences(controlsFooterCenter = EpubOverlayItem.CHAPTER_POSITION)
        val paged = resolve(prefs = prefs, showingControls = true)
        assertEquals("Chapter 3 / 12", paged.bottomCenter.text)

        val scrolled = resolve(
            prefs = prefs,
            showingControls = true,
            paged = false,
            totalPages = 0,
            chapterProgressFraction = 0.5f,
        )
        assertEquals("50.0%", scrolled.bottomCenter.text)
    }

    @Test
    fun `controls mode shows only the controls slots and hides the progress bar`() {
        val prefs = EpubOverlayPreferences(showsReaderProgressBar = true)
        val content = resolve(prefs = prefs, showingControls = true)
        assertNull(content.topLeading.text)
        assertNull(content.bottomLeading.text)
        assertNull(content.bottomTrailing.text)
        // controlsHeaderCenter defaults to the book progress
        assertEquals("Book Progress 12.34%", content.topCenter.text)
        // controlsFooterCenter defaults to the whole-book page position (mirrors the iOS controls bar)
        assertEquals("Book 421 / 1204", content.bottomCenter.text)
        assertNull(content.bottomProgress)
    }

    @Test
    fun `book position needs page counts and has no fraction fallback`() {
        val prefs = EpubOverlayPreferences(controlsFooterCenter = EpubOverlayItem.BOOK_POSITION)
        val withoutCounts = resolve(prefs = prefs, showingControls = true, bookPage = null)
        assertFalse(withoutCounts.bottomCenter.isVisible)
        assertNull(withoutCounts.bottomCenter.text)

        // A scrolling chapter can still name its book page: the counts cover the whole book.
        val scrolled = resolve(
            prefs = prefs,
            showingControls = true,
            paged = false,
            totalPages = 0,
            chapterProgressFraction = 0.5f,
        )
        assertEquals("Book 421 / 1204", scrolled.bottomCenter.text)
    }

    @Test
    fun `progress bar clamps and flips in RTL`() {
        val prefs = EpubOverlayPreferences(showsReaderProgressBar = true)
        val content = resolve(prefs = prefs, isRtl = true)
        assertEquals(0.1234f, content.bottomProgress!!, 0.0001f)
        assertTrue(content.isRtl)

        val clamped = resolve(prefs = prefs, totalProgression = 1.7f)
        assertEquals(1f, clamped.bottomProgress!!, 0.0001f)
    }

    @Test
    fun `blank entries collapse`() {
        val content = resolve(bookTitle = "   ")
        assertFalse(content.topCenter.isVisible)
        assertNull(content.topCenter.text)
    }

    @Test
    fun `missing progression hides percent items`() {
        val prefs = EpubOverlayPreferences(
            readerHeaderLeading = EpubOverlayItem.BOOK_PROGRESS_PERCENT,
            readerHeaderTrailing = EpubOverlayItem.BOOK_REMAINING_PERCENT,
        )
        val content = resolve(prefs = prefs, totalProgression = null)
        assertFalse(content.topLeading.isVisible)
        assertFalse(content.topTrailing.isVisible)
    }

    @Test
    fun `json round trip preserves every field`() {
        val prefs = EpubOverlayPreferences(
            readerHeaderLeading = EpubOverlayItem.CHAPTER_TITLE,
            readerHeaderCenter = EpubOverlayItem.NONE,
            readerHeaderTrailing = EpubOverlayItem.BOOK_REMAINING_PERCENT,
            readerFooterLeading = EpubOverlayItem.CHAPTER_PROGRESS_PERCENT,
            readerFooterCenter = EpubOverlayItem.CHAPTER_POSITION,
            readerFooterTrailing = EpubOverlayItem.NONE,
            showsReaderProgressBar = true,
            controlsHeaderCenter = EpubOverlayItem.BOOK_TITLE,
            controlsFooterCenter = EpubOverlayItem.CHAPTER_REMAINING,
        )
        assertEquals(prefs, EpubOverlayPreferences.fromJson(prefs.toJson()))
    }

    @Test
    fun `unknown and missing json falls back to defaults`() {
        assertEquals(EpubOverlayPreferences(), EpubOverlayPreferences.fromJson(null))
        assertEquals(EpubOverlayPreferences(), EpubOverlayPreferences.fromJson("not json"))
        assertEquals(EpubOverlayPreferences(), EpubOverlayPreferences.fromJson("{}"))
        // Unknown wire values degrade to the field default, unknown keys are ignored
        val parsed = EpubOverlayPreferences.fromJson("""{"readerHeaderCenter":"bogus","extra":1}""")
        assertEquals(EpubOverlayItem.BOOK_TITLE, parsed.readerHeaderCenter)
    }
}
