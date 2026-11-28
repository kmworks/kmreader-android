package com.kmworks.kmreader.core.reader

import com.kmworks.kmreader.core.epub.EpubBookPageProgress
import org.json.JSONObject

/**
 * Margin-note ("info overlay") model for the EPUB reader (mirrors the iOS EpubOverlayTextItem /
 * EpubOverlayPreferences / WebPubInfoOverlaySupport trio): six configurable text slots around the
 * page (header/footer × leading/center/trailing) plus an optional thin progress bar, and a
 * separate pair of slots shown while the chrome is visible.
 */
enum class EpubOverlayItem(val wire: String) {
    NONE("none"),
    BOOK_TITLE("bookTitle"),
    CHAPTER_TITLE("chapterTitle"),
    BOOK_PROGRESS_PERCENT("bookProgressPercent"),
    BOOK_REMAINING_PERCENT("bookRemainingPercent"),
    CHAPTER_PROGRESS_PERCENT("chapterProgressPercent"),
    CHAPTER_REMAINING("chapterRemaining"),
    CHAPTER_POSITION("chapterPosition"),

    /**
     * The reader's position in the whole book, counted in pages ("132 of 1,204"). Needs measured or
     * estimated per-chapter page counts, which the reader builds as it walks the book — see
     * [com.kmworks.kmreader.core.epub.EpubBookPages].
     */
    BOOK_POSITION("bookPosition"),
    ;

    companion object {
        fun fromWire(value: String?): EpubOverlayItem? = entries.firstOrNull { it.wire == value }
    }
}

/** Persisted as a JSON string (mirrors the iOS RawRepresentable dictionary). */
data class EpubOverlayPreferences(
    val readerHeaderLeading: EpubOverlayItem = EpubOverlayItem.NONE,
    val readerHeaderCenter: EpubOverlayItem = EpubOverlayItem.BOOK_TITLE,
    val readerHeaderTrailing: EpubOverlayItem = EpubOverlayItem.NONE,
    val readerFooterLeading: EpubOverlayItem = EpubOverlayItem.CHAPTER_TITLE,
    val readerFooterCenter: EpubOverlayItem = EpubOverlayItem.NONE,
    val readerFooterTrailing: EpubOverlayItem = EpubOverlayItem.CHAPTER_REMAINING,
    val showsReaderProgressBar: Boolean = false,
    val controlsHeaderCenter: EpubOverlayItem = EpubOverlayItem.BOOK_PROGRESS_PERCENT,
    /**
     * The chrome-visible footer shows the whole-book page position by default (mirrors the iOS
     * reader, where the controls bar footer is the "X of Y" book page and no longer configurable).
     * Still a configurable slot here, so a reader who prefers the percentage can keep it.
     */
    val controlsFooterCenter: EpubOverlayItem = EpubOverlayItem.BOOK_POSITION,
) {
    fun toJson(): String = JSONObject().apply {
        put("readerHeaderLeading", readerHeaderLeading.wire)
        put("readerHeaderCenter", readerHeaderCenter.wire)
        put("readerHeaderTrailing", readerHeaderTrailing.wire)
        put("readerFooterLeading", readerFooterLeading.wire)
        put("readerFooterCenter", readerFooterCenter.wire)
        put("readerFooterTrailing", readerFooterTrailing.wire)
        put("showsReaderProgressBar", showsReaderProgressBar)
        put("controlsHeaderCenter", controlsHeaderCenter.wire)
        put("controlsFooterCenter", controlsFooterCenter.wire)
    }.toString()

    companion object {
        fun fromJson(json: String?): EpubOverlayPreferences {
            if (json.isNullOrBlank()) return EpubOverlayPreferences()
            return try {
                val dict = JSONObject(json)
                val defaults = EpubOverlayPreferences()
                EpubOverlayPreferences(
                    readerHeaderLeading = EpubOverlayItem.fromWire(dict.optString("readerHeaderLeading"))
                        ?: defaults.readerHeaderLeading,
                    readerHeaderCenter = EpubOverlayItem.fromWire(dict.optString("readerHeaderCenter"))
                        ?: defaults.readerHeaderCenter,
                    readerHeaderTrailing = EpubOverlayItem.fromWire(dict.optString("readerHeaderTrailing"))
                        ?: defaults.readerHeaderTrailing,
                    readerFooterLeading = EpubOverlayItem.fromWire(dict.optString("readerFooterLeading"))
                        ?: defaults.readerFooterLeading,
                    readerFooterCenter = EpubOverlayItem.fromWire(dict.optString("readerFooterCenter"))
                        ?: defaults.readerFooterCenter,
                    readerFooterTrailing = EpubOverlayItem.fromWire(dict.optString("readerFooterTrailing"))
                        ?: defaults.readerFooterTrailing,
                    showsReaderProgressBar = dict.optBoolean("showsReaderProgressBar", false),
                    controlsHeaderCenter = EpubOverlayItem.fromWire(dict.optString("controlsHeaderCenter"))
                        ?: defaults.controlsHeaderCenter,
                    controlsFooterCenter = EpubOverlayItem.fromWire(dict.optString("controlsFooterCenter"))
                        ?: defaults.controlsFooterCenter,
                )
            } catch (_: Exception) {
                EpubOverlayPreferences()
            }
        }
    }
}

/** Localized templates used by the resolver (built from string resources at the UI layer). */
data class EpubOverlayStrings(
    /** "Book Progress %1$s" — %1$s is a 2-decimal percent. */
    val bookProgress: String,
    /** "%1$s left" — %1$s is a percent. */
    val remaining: String,
    /** "Chapter Progress %1$s" — %1$s is a 1-decimal percent. */
    val chapterProgress: String,
    /** "Chapter Progress %1$d / %2$d" — current page (1-based) / total pages. */
    val chapterPosition: String,
    /** "Book %1$d / %2$d" — the reader's page in the whole book (1-based) / the book's pages. */
    val bookPosition: String,
    /** "%1$d pages left". */
    val pagesLeft: String,
    /** "Last page". */
    val lastPage: String,
)

data class EpubOverlayEntry(val text: String?, val isVisible: Boolean) {
    companion object {
        val HIDDEN = EpubOverlayEntry(text = null, isVisible = false)
    }
}

/** One frame of overlay content (mirrors WebPubInfoOverlaySupport.Content). */
data class EpubOverlayContent(
    val showingControls: Boolean,
    val topLeading: EpubOverlayEntry,
    val topCenter: EpubOverlayEntry,
    val topTrailing: EpubOverlayEntry,
    val bottomLeading: EpubOverlayEntry,
    val bottomCenter: EpubOverlayEntry,
    val bottomTrailing: EpubOverlayEntry,
    val bottomProgress: Float?,
    val isRtl: Boolean,
)

private fun visibleEntry(text: String?): EpubOverlayEntry {
    val trimmed = text?.trim()
    return if (trimmed.isNullOrEmpty()) EpubOverlayEntry.HIDDEN else EpubOverlayEntry(trimmed, true)
}

private fun clamped(value: Float): Float = value.coerceIn(0f, 1f)

private fun percent2(value: Float): String = "%.2f%%".format(clamped(value) * 100f)

private fun percent1(value: Float): String = "%.1f%%".format(clamped(value) * 100f)

/**
 * Resolves one slot (mirrors WebPubInfoOverlaySupport.entry). [chapterProgressFraction] is the
 * in-chapter reading fraction used when page counts are unavailable (scrolled mode reports no
 * pages); page-based items fall back to it, percent-based items need one source or stay hidden.
 * [bookPage] carries the whole-book page position; without it that slot stays hidden.
 */
private fun resolveEntry(
    item: EpubOverlayItem,
    paged: Boolean,
    bookTitle: String?,
    chapterTitle: String?,
    totalProgression: Float?,
    currentPage: Int,
    totalPages: Int,
    chapterProgressFraction: Float?,
    bookPage: EpubBookPageProgress?,
    strings: EpubOverlayStrings,
): EpubOverlayEntry {
    fun chapterProgress(): Float? = when {
        totalPages > 0 -> clamped((currentPage + 1).toFloat() / totalPages.toFloat())
        chapterProgressFraction != null -> clamped(chapterProgressFraction)
        else -> null
    }
    return when (item) {
        EpubOverlayItem.NONE -> EpubOverlayEntry.HIDDEN
        EpubOverlayItem.BOOK_TITLE -> visibleEntry(bookTitle)
        EpubOverlayItem.CHAPTER_TITLE -> visibleEntry(chapterTitle)
        EpubOverlayItem.BOOK_PROGRESS_PERCENT -> {
            val progression = totalProgression ?: return EpubOverlayEntry.HIDDEN
            EpubOverlayEntry(String.format(strings.bookProgress, percent2(progression)), true)
        }
        EpubOverlayItem.BOOK_REMAINING_PERCENT -> {
            val progression = totalProgression ?: return EpubOverlayEntry.HIDDEN
            EpubOverlayEntry(String.format(strings.remaining, percent2(1f - clamped(progression))), true)
        }
        EpubOverlayItem.CHAPTER_PROGRESS_PERCENT -> {
            val progress = chapterProgress() ?: return EpubOverlayEntry.HIDDEN
            EpubOverlayEntry(String.format(strings.chapterProgress, percent1(progress)), true)
        }
        EpubOverlayItem.CHAPTER_REMAINING -> {
            if (paged && totalPages > 0) {
                val remainingPages = totalPages - (currentPage + 1)
                val text = if (remainingPages > 0) {
                    String.format(strings.pagesLeft, remainingPages)
                } else {
                    strings.lastPage
                }
                EpubOverlayEntry(text, true)
            } else {
                val progress = chapterProgress() ?: return EpubOverlayEntry.HIDDEN
                EpubOverlayEntry(String.format(strings.remaining, percent1(1f - progress)), true)
            }
        }
        EpubOverlayItem.CHAPTER_POSITION -> {
            if (paged && totalPages > 0) {
                EpubOverlayEntry(String.format(strings.chapterPosition, currentPage + 1, totalPages), true)
            } else {
                val progress = chapterProgress() ?: return EpubOverlayEntry.HIDDEN
                EpubOverlayEntry(String.format(strings.chapterProgress, percent1(progress)), true)
            }
        }
        EpubOverlayItem.BOOK_POSITION -> {
            // Unlike the chapter items this one has no fraction fallback: "page 421 of 8,431" is a
            // page claim, and a scrolling chapter cannot honour it. Hidden until counts exist.
            if (bookPage == null || bookPage.totalPages <= 0) {
                EpubOverlayEntry.HIDDEN
            } else {
                EpubOverlayEntry(
                    String.format(
                        strings.bookPosition,
                        bookPage.currentPage.coerceIn(1, bookPage.totalPages),
                        bookPage.totalPages,
                    ),
                    true,
                )
            }
        }
    }
}

/**
 * Builds the whole overlay frame (mirrors WebPubInfoOverlaySupport.content): while the chrome is
 * visible only the two "controls" slots show (top/bottom center) and the progress bar hides.
 */
fun resolveEpubOverlay(
    prefs: EpubOverlayPreferences,
    showingControls: Boolean,
    paged: Boolean,
    bookTitle: String?,
    chapterTitle: String?,
    totalProgression: Float?,
    currentPage: Int,
    totalPages: Int,
    chapterProgressFraction: Float?,
    bookPage: EpubBookPageProgress?,
    isRtl: Boolean,
    strings: EpubOverlayStrings,
): EpubOverlayContent {
    if (showingControls) {
        return EpubOverlayContent(
            showingControls = true,
            topLeading = EpubOverlayEntry.HIDDEN,
            topCenter = resolveEntry(
                prefs.controlsHeaderCenter, paged, bookTitle, chapterTitle,
                totalProgression, currentPage, totalPages, chapterProgressFraction, bookPage, strings,
            ),
            topTrailing = EpubOverlayEntry.HIDDEN,
            bottomLeading = EpubOverlayEntry.HIDDEN,
            bottomCenter = resolveEntry(
                prefs.controlsFooterCenter, paged, bookTitle, chapterTitle,
                totalProgression, currentPage, totalPages, chapterProgressFraction, bookPage, strings,
            ),
            bottomTrailing = EpubOverlayEntry.HIDDEN,
            bottomProgress = null,
            isRtl = isRtl,
        )
    }
    fun reader(item: EpubOverlayItem) = resolveEntry(
        item, paged, bookTitle, chapterTitle,
        totalProgression, currentPage, totalPages, chapterProgressFraction, bookPage, strings,
    )
    return EpubOverlayContent(
        showingControls = false,
        topLeading = reader(prefs.readerHeaderLeading),
        topCenter = reader(prefs.readerHeaderCenter),
        topTrailing = reader(prefs.readerHeaderTrailing),
        bottomLeading = reader(prefs.readerFooterLeading),
        bottomCenter = reader(prefs.readerFooterCenter),
        bottomTrailing = reader(prefs.readerFooterTrailing),
        bottomProgress = if (prefs.showsReaderProgressBar) totalProgression?.let(::clamped) else null,
        isRtl = isRtl,
    )
}
