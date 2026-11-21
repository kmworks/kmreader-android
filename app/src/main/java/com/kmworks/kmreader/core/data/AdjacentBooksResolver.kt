package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.reading.ReadListReadingService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the read list context and the sibling books of a book being read, shared by the three
 * readers (DIVINA, EPUB, PDF) instead of each one carrying its own copy.
 *
 * Both lookups are deferred by the caller: neither the read list context nor the sibling lookup
 * delays the first render, and only the end page / the options menu consume them. A failed sibling
 * lookup is returned as [ReaderAdjacentBooks.failed] so the reader never fails to open because the
 * neighbours could not be resolved (mirrors ReaderPresentationManager.resolvedReadListContext and
 * the original resolveAdjacentBooks).
 */
@Singleton
class AdjacentBooksResolver @Inject constructor(
    private val readerRepository: ReaderRepository,
    private val detailRepository: DetailRepository,
    private val readListReadingService: ReadListReadingService,
) {

    /**
     * The read list this session continues in: the explicit entry point wins, otherwise a read list
     * the user is reading that contains this book. Null when the continuation setting is off or
     * neither applies.
     */
    suspend fun readListContext(dtoId: String, readListStableId: Int?): ReaderReadListContext? {
        val explicit = readListStableId?.let { detailRepository.readListDtoId(it) }
            ?: return readListReadingService.ownerContext(dtoId)
        val name = runCatching { detailRepository.readListDetail(explicit)?.name }.getOrNull()
        return if (!name.isNullOrBlank()) ReaderReadListContext(explicit, name) else null
    }

    /** The previous/next sibling books; [ReaderAdjacentBooks.failed] when the lookup throws (offline). */
    suspend fun resolve(dtoId: String, readListId: String?): ReaderAdjacentBooks =
        runCatching { readerRepository.adjacentBooks(dtoId, readListId) }
            .getOrNull()
            .toReaderAdjacentBooks()
}
