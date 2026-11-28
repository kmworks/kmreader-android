package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.BookDto
import com.kmworks.kmreader.data.Book

/**
 * Books adjacent to the one being read, resolved for the end-of-book page (mirrors the original
 * ReaderSegment.previousBook/nextBook, filled from the deferred resolveAdjacentBooks lookup).
 *
 * Shared by both readers (DIVINA, EPUB), so it lives next to the resolver instead of the
 * DIVINA view model.
 */
data class ReaderAdjacentBooks(
    val previous: Book? = null,
    val next: Book? = null,
    /** The lookup finished, so a null [next] means "nothing after this book", not "still unknown". */
    val loaded: Boolean = false,
    /** The lookup failed (offline): the end page offers a retry instead of claiming the series is finished. */
    val failed: Boolean = false,
)

/**
 * Resolves a sibling pair into the end-page state: a null pair means the lookup failed, while a null
 * side means "nothing on that side" (the lookup itself loaded).
 */
fun <T> Pair<T?, T?>?.toReaderAdjacentBooks(map: (T) -> Book): ReaderAdjacentBooks =
    if (this == null) {
        ReaderAdjacentBooks(failed = true)
    } else {
        ReaderAdjacentBooks(
            previous = first?.let(map),
            next = second?.let(map),
            loaded = true,
        )
    }

/** The sibling lookup pair ([ReaderRepository.adjacentBooks]) as end-page state. */
fun Pair<BookDto?, BookDto?>?.toReaderAdjacentBooks(): ReaderAdjacentBooks =
    toReaderAdjacentBooks { it.toUiBook() }
