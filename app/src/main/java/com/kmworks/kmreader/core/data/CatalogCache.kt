package com.kmworks.kmreader.core.data

import android.content.Context
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.db.CachedBookEntity
import com.kmworks.kmreader.core.db.CachedDashboardSectionEntity
import com.kmworks.kmreader.core.db.CachedListEntity
import com.kmworks.kmreader.core.db.CachedListRowEntity
import com.kmworks.kmreader.core.db.CachedSeriesEntity
import com.kmworks.kmreader.core.db.CatalogDao
import com.kmworks.kmreader.core.db.ITEM_KIND_BOOK
import com.kmworks.kmreader.core.db.ITEM_KIND_COLLECTION
import com.kmworks.kmreader.core.db.ITEM_KIND_READLIST
import com.kmworks.kmreader.core.db.ITEM_KIND_SERIES
import com.kmworks.kmreader.core.db.LIST_KIND_COLLECTION
import com.kmworks.kmreader.core.db.LIST_KIND_READLIST
import com.kmworks.kmreader.core.db.LIST_KIND_SMARTLIST
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.data.Book
import com.kmworks.kmreader.data.DashboardCardKind
import com.kmworks.kmreader.data.DashboardSection
import com.kmworks.kmreader.data.SampleData.ListEntry
import com.kmworks.kmreader.data.ReadListsInProgressSectionId
import com.kmworks.kmreader.data.Series
import com.kmworks.kmreader.data.SmartListTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Local catalog cache ("data cache" parity with iOS GRDB + DashboardSectionCacheStore).
 *
 * Contract: writes happen only after a successful network load; reads only seed a screen that has
 * no data yet, so a restored list is replaced by the server response as soon as it lands. Rows store
 * Komga dto ids and card fields; the in-process Int stable ids, cover/detail registries and
 * [StableIds] assignments are rebuilt on restore (they are session-local hashes).
 */
@Singleton
class CatalogCache @Inject constructor(
    private val dao: CatalogDao,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _savedBooks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * A book page was written to the cache. Download metadata written before this app version has no series
     * id; [com.kmworks.kmreader.core.download.DownloadRepository] listens so a refreshed catalog can backfill it.
     */
    val savedBooks: SharedFlow<Unit> = _savedBooks

    /** Server instance the rows belong to (server base URL + user), or null when logged out. */
    private fun instanceKey(): String? =
        sessionManager.clients.value?.cacheNamespace?.let(ImageCachePaths::sanitize)

    // ---------------------------------------------------------------- books / series rows

    /** Persist the books currently rendered for [listKey] plus their order. */
    suspend fun saveBooks(listKey: String, books: List<Book>) {
        val instanceKey = instanceKey() ?: return
        val now = System.currentTimeMillis()
        val refs = books.mapNotNull { book ->
            DetailIdRegistry.bookDtoId(book.id)?.let { dtoId -> dtoId to book }
        }
        if (refs.isEmpty()) {
            // An empty page means the query really is empty; drop the old order so a seed cannot show stale rows.
            dao.deleteListRows(instanceKey, listKey)
            return
        }
        dao.upsertBooks(
            refs.map { (dtoId, book) ->
                CachedBookEntity(
                    instanceKey = instanceKey,
                    dtoId = dtoId,
                    title = book.title,
                    number = book.number,
                    seriesTitle = book.seriesTitle,
                    seriesId = book.seriesDtoId,
                    hue = book.hue,
                    pageCount = book.pageCount,
                    progress = book.progress,
                    oneshot = book.oneshot,
                    author = book.author,
                    mediaStatus = book.mediaStatus,
                    mediaType = book.mediaType,
                    lastReadAtMs = book.lastReadAtMs,
                    updatedAt = now,
                )
            },
        )
        writeListRows(instanceKey, listKey, refs.map { it.first }, ITEM_KIND_BOOK)
        // Newly cached rows carry seriesId; let downloads enqueued before that column exist pick it up.
        _savedBooks.tryEmit(Unit)
    }

    /** Persist the series currently rendered for [listKey] plus their order. */
    suspend fun saveSeries(listKey: String, series: List<Series>) {
        val instanceKey = instanceKey() ?: return
        val now = System.currentTimeMillis()
        val refs = series.mapNotNull { item ->
            DetailIdRegistry.seriesDtoId(item.id)?.let { dtoId -> dtoId to item }
        }
        if (refs.isEmpty()) {
            dao.deleteListRows(instanceKey, listKey)
            return
        }
        dao.upsertSeries(
            refs.map { (dtoId, item) ->
                CachedSeriesEntity(
                    instanceKey = instanceKey,
                    dtoId = dtoId,
                    title = item.title,
                    author = item.author,
                    hue = item.hue,
                    bookCount = item.bookCount,
                    unreadCount = item.unreadCount,
                    progress = item.progress,
                    updatedAt = now,
                )
            },
        )
        writeListRows(instanceKey, listKey, refs.map { it.first }, ITEM_KIND_SERIES)
    }

    /** Restore the books last written for [listKey] (empty when nothing cached). */
    suspend fun seedBooks(listKey: String): List<Book> {
        val instanceKey = instanceKey() ?: return emptyList()
        val rows = dao.listRows(instanceKey, listKey).filter { it.itemKind == ITEM_KIND_BOOK }
        if (rows.isEmpty()) return emptyList()
        val byId = dao.books(instanceKey, rows.map { it.dtoId }).associateBy { it.dtoId }
        return rows.mapNotNull { row ->
            val entity = byId[row.dtoId] ?: return@mapNotNull null
            val stableId = StableIds.book(entity.dtoId)
            DetailIdRegistry.registerBook(stableId, entity.dtoId)
            CoverUrlRegistry.registerBook(stableId, entity.dtoId)
            Book(
                id = stableId,
                number = entity.number,
                title = entity.title,
                seriesTitle = entity.seriesTitle,
                seriesDtoId = entity.seriesId,
                hue = entity.hue,
                pageCount = entity.pageCount,
                progress = entity.progress,
                oneshot = entity.oneshot,
                author = entity.author,
                mediaStatus = entity.mediaStatus,
                mediaType = entity.mediaType,
                lastReadAtMs = entity.lastReadAtMs,
            )
        }
    }

    /**
     * Series identity of downloaded books (legacy download folders were enqueued before the download
     * metadata recorded the series). Returns book dtoId → (series dtoId, known series book count or 0).
     */
    suspend fun downloadSeriesIdentity(dtoIds: Collection<String>): Map<String, Pair<String, Int>> {
        if (dtoIds.isEmpty()) return emptyMap()
        val instanceKey = instanceKey() ?: return emptyMap()
        val rows = dao.books(instanceKey, dtoIds.toList()).filter { it.seriesId != null }
        if (rows.isEmpty()) return emptyMap()
        val counts = dao.series(instanceKey, rows.mapNotNull { it.seriesId }.distinct())
            .associate { it.dtoId to it.bookCount }
        return rows.mapNotNull { row ->
            val seriesId = row.seriesId ?: return@mapNotNull null
            row.dtoId to (seriesId to (counts[seriesId] ?: 0))
        }.toMap()
    }

    /** Restore the series last written for [listKey] (empty when nothing cached). */
    suspend fun seedSeries(listKey: String): List<Series> {
        val instanceKey = instanceKey() ?: return emptyList()
        val rows = dao.listRows(instanceKey, listKey).filter { it.itemKind == ITEM_KIND_SERIES }
        if (rows.isEmpty()) return emptyList()
        val byId = dao.series(instanceKey, rows.map { it.dtoId }).associateBy { it.dtoId }
        return rows.mapNotNull { row ->
            val entity = byId[row.dtoId] ?: return@mapNotNull null
            val stableId = StableIds.series(entity.dtoId)
            DetailIdRegistry.registerSeries(stableId, entity.dtoId)
            CoverUrlRegistry.registerSeries(stableId, entity.dtoId)
            Series(
                id = stableId,
                title = entity.title,
                author = entity.author,
                hue = entity.hue,
                bookCount = entity.bookCount,
                unreadCount = entity.unreadCount,
                progress = entity.progress,
                // The cache row has no direction column: seed as LTR; the detail page refetches the real one.
                readingDirection = "LEFT_TO_RIGHT",
            )
        }
    }

    private suspend fun writeListRows(
        instanceKey: String,
        listKey: String,
        dtoIds: List<String>,
        itemKind: String,
    ) {
        dao.deleteListRows(instanceKey, listKey)
        dao.upsertListRows(
            dtoIds.mapIndexed { index, dtoId ->
                CachedListRowEntity(
                    instanceKey = instanceKey,
                    listKey = listKey,
                    position = index,
                    itemKind = itemKind,
                    dtoId = dtoId,
                )
            },
        )
    }

    // ---------------------------------------------------------------- collections / read lists

    /**
     * Persist the Lists screen rows (collections + read lists + smart lists). Each kind is replaced
     * wholesale — upserting would keep rows of entries deleted server-side around, and the next seed
     * would paint them again. A null kind leaves that kind's cached rows untouched, so a caller that
     * only knows two kinds (the library tab) cannot wipe the third.
     */
    suspend fun saveLists(
        collections: List<ListEntry>?,
        readLists: List<ListEntry>?,
        smartLists: List<ListEntry>? = null,
    ) {
        val instanceKey = instanceKey() ?: return
        val now = System.currentTimeMillis()
        suspend fun saveKind(kind: String, dtoIdOf: (Int) -> String?, entries: List<ListEntry>) {
            dao.replaceLists(
                instanceKey,
                kind,
                entries.mapIndexedNotNull { index, entry ->
                    dtoIdOf(entry.id)?.let { dtoId -> listRow(instanceKey, kind, dtoId, entry, index, now) }
                },
            )
        }
        collections?.let { saveKind(LIST_KIND_COLLECTION, DetailIdRegistry::collectionDtoId, it) }
        readLists?.let { saveKind(LIST_KIND_READLIST, DetailIdRegistry::readListDtoId, it) }
        smartLists?.let { saveKind(LIST_KIND_SMARTLIST, DetailIdRegistry::smartListDtoId, it) }
    }

    /** Restore the Lists screen rows (collections, read lists, smart lists; null when nothing cached). */
    suspend fun seedLists(): Triple<List<ListEntry>, List<ListEntry>, List<ListEntry>>? {
        val instanceKey = instanceKey() ?: return null
        val collections = dao.lists(instanceKey, LIST_KIND_COLLECTION)
        val readLists = dao.lists(instanceKey, LIST_KIND_READLIST)
        val smartLists = dao.lists(instanceKey, LIST_KIND_SMARTLIST)
        if (collections.isEmpty() && readLists.isEmpty() && smartLists.isEmpty()) return null
        return Triple(
            collections.map { it.toListEntry() },
            readLists.map { it.toListEntry() },
            smartLists.map { it.toListEntry() },
        )
    }

    private fun listRow(
        instanceKey: String,
        kind: String,
        dtoId: String,
        entry: ListEntry,
        position: Int,
        now: Long,
    ) = CachedListEntity(
        instanceKey = instanceKey,
        kind = kind,
        dtoId = dtoId,
        name = entry.title,
        count = entry.count,
        hue = entry.hue,
        pinned = entry.pinned,
        position = position,
        smartListTarget = when (entry.smartListTarget) {
            SmartListTarget.Series -> "SERIES"
            SmartListTarget.Book -> "BOOK"
            null -> null
        },
        updatedAt = now,
    )

    private fun CachedListEntity.toListEntry(): ListEntry {
        val stableId = when (kind) {
            LIST_KIND_COLLECTION -> StableIds.collection(dtoId)
            LIST_KIND_READLIST -> StableIds.readList(dtoId)
            else -> StableIds.smartList(dtoId)
        }
        when (kind) {
            LIST_KIND_COLLECTION -> {
                DetailIdRegistry.registerCollection(stableId, dtoId)
                CoverUrlRegistry.registerCollection(stableId, dtoId)
            }
            LIST_KIND_READLIST -> {
                DetailIdRegistry.registerReadList(stableId, dtoId)
                CoverUrlRegistry.registerReadList(stableId, dtoId)
            }
            else -> {
                DetailIdRegistry.registerSmartList(stableId, dtoId)
                CoverUrlRegistry.registerSmartList(stableId, dtoId)
            }
        }
        if (kind != LIST_KIND_SMARTLIST) return ListEntry(stableId, name, count, hue, pinned)
        // A smart list card shows what it groups by instead of an item count; the label is localized,
        // so the row stores the target and the restore recomputes the string.
        val target = if (smartListTarget == "SERIES") SmartListTarget.Series else SmartListTarget.Book
        return ListEntry(
            stableId,
            name,
            count,
            hue,
            pinned,
            targetLabel = context.getString(
                if (target == SmartListTarget.Series) R.string.common_series else R.string.common_book,
            ),
            smartListTarget = target,
        )
    }

    // ---------------------------------------------------------------- dashboard sections

    /** Persist the first page of every dashboard section (id list + shaped rows). */
    suspend fun saveDashboard(sections: List<DashboardSection<out Any>>) {
        val instanceKey = instanceKey() ?: return
        val now = System.currentTimeMillis()
        val sectionsWithRefs = sections.filter { it.id != ReadListsInProgressSectionId }.map { section ->
            val refs = section.items.mapNotNull { item ->
                when (item) {
                    is Book -> DetailIdRegistry.bookDtoId(item.id)?.let { SectionItemRef(ITEM_KIND_BOOK, it) }
                    is Series -> DetailIdRegistry.seriesDtoId(item.id)?.let { SectionItemRef(ITEM_KIND_SERIES, it) }
                    else -> null
                }
            }
            section to refs
        }.filter { it.second.isNotEmpty() }
        if (sectionsWithRefs.isEmpty()) {
            dao.deleteDashboardSections(instanceKey)
            return
        }
        // Keep the card fields fresh for the seeded ids as well (the same rows the lists write).
        writeItems(instanceKey, sectionsWithRefs.map { it.first }, now)
        // Replace the whole set: a section that is empty now must not survive as a stale seed.
        dao.deleteDashboardSections(instanceKey)
        dao.upsertDashboardSections(
            sectionsWithRefs.map { (section, refs) ->
                CachedDashboardSectionEntity(
                    instanceKey = instanceKey,
                    sectionId = section.id,
                    itemsJson = json.encodeToString(ListSerializer(SectionItemRef.serializer()), refs),
                    updatedAt = now,
                )
            },
        )
    }

    /** Restore the dashboard first page; empty when nothing cached. */
    suspend fun seedDashboard(): List<DashboardSection<out Any>> {
        val instanceKey = instanceKey() ?: return emptyList()
        val rows = dao.dashboardSections(instanceKey)
        if (rows.isEmpty()) return emptyList()
        return rows.mapNotNull { row ->
            val refs = runCatching {
                json.decodeFromString(ListSerializer(SectionItemRef.serializer()), row.itemsJson)
            }.getOrNull().orEmpty()
            if (refs.isEmpty()) return@mapNotNull null
            val books = refs.filter { it.kind == ITEM_KIND_BOOK }.map { it.dtoId }
            val series = refs.filter { it.kind == ITEM_KIND_SERIES }.map { it.dtoId }
            val booksById = if (books.isEmpty()) emptyMap() else
                dao.books(instanceKey, books).associateBy { it.dtoId }
            val seriesById = if (series.isEmpty()) emptyMap() else
                dao.series(instanceKey, series).associateBy { it.dtoId }
            val items: List<Any> = refs.mapNotNull { ref ->
                when (ref.kind) {
                    ITEM_KIND_BOOK -> booksById[ref.dtoId]?.toUiBook()
                    ITEM_KIND_SERIES -> seriesById[ref.dtoId]?.toUiSeries()
                    else -> null
                }
            }
            if (items.isEmpty()) return@mapNotNull null
            DashboardSection(
                id = row.sectionId,
                title = context.getString(sectionTitleRes(row.sectionId)),
                kind = sectionKind(row.sectionId),
                items = items,
            )
        }
    }

    /** Write the shaped rows of the dashboard's first page so a restore can rebuild the cards. */
    private suspend fun writeItems(
        instanceKey: String,
        sections: List<DashboardSection<out Any>>,
        now: Long,
    ) {
        val items = sections.flatMap { it.items }
        val books = items.filterIsInstance<Book>().mapNotNull { book ->
            DetailIdRegistry.bookDtoId(book.id)?.let { dtoId -> dtoId to book }
        }
        val series = items.filterIsInstance<Series>().mapNotNull { item ->
            DetailIdRegistry.seriesDtoId(item.id)?.let { dtoId -> dtoId to item }
        }
        if (books.isNotEmpty()) {
            dao.upsertBooks(
                books.map { (dtoId, book) ->
                    CachedBookEntity(
                        instanceKey = instanceKey,
                        dtoId = dtoId,
                        title = book.title,
                        number = book.number,
                        seriesTitle = book.seriesTitle,
                        hue = book.hue,
                        pageCount = book.pageCount,
                        progress = book.progress,
                        oneshot = book.oneshot,
                        author = book.author,
                        mediaStatus = book.mediaStatus,
                        mediaType = book.mediaType,
                        lastReadAtMs = book.lastReadAtMs,
                        updatedAt = now,
                    )
                },
            )
        }
        if (series.isNotEmpty()) {
            dao.upsertSeries(
                series.map { (dtoId, item) ->
                    CachedSeriesEntity(
                        instanceKey = instanceKey,
                        dtoId = dtoId,
                        title = item.title,
                        author = item.author,
                        hue = item.hue,
                        bookCount = item.bookCount,
                        unreadCount = item.unreadCount,
                        progress = item.progress,
                        updatedAt = now,
                    )
                },
            )
        }
    }

    private fun CachedBookEntity.toUiBook(): Book {
        val stableId = StableIds.book(dtoId)
        DetailIdRegistry.registerBook(stableId, dtoId)
        CoverUrlRegistry.registerBook(stableId, dtoId)
        return Book(
            id = stableId,
            number = number,
            title = title,
            seriesTitle = seriesTitle,
            hue = hue,
            pageCount = pageCount,
            progress = progress,
            oneshot = oneshot,
            author = author,
            mediaStatus = mediaStatus,
            mediaType = mediaType,
            lastReadAtMs = lastReadAtMs,
        )
    }

    private fun CachedSeriesEntity.toUiSeries(): Series {
        val stableId = StableIds.series(dtoId)
        DetailIdRegistry.registerSeries(stableId, dtoId)
        CoverUrlRegistry.registerSeries(stableId, dtoId)
        return Series(
            id = stableId,
            title = title,
            author = author,
            hue = hue,
            bookCount = bookCount,
            unreadCount = unreadCount,
            progress = progress,
            // The cache row has no direction column: LTR (lists never render the direction; the detail
            // page refetches the real value).
            readingDirection = "LEFT_TO_RIGHT",
        )
    }

    private fun sectionTitleRes(sectionId: String): Int = when (sectionId) {
        "keepReading" -> R.string.dashboard_section_keep_reading
        "onDeck" -> R.string.dashboard_section_on_deck
        "recentlyReleasedBooks" -> R.string.dashboard_section_recently_released_books
        "recentlyAddedBooks" -> R.string.dashboard_section_recently_added_books
        "recentlyAddedSeries" -> R.string.dashboard_section_recently_added_series
        "recentlyUpdatedSeries" -> R.string.dashboard_section_recently_updated_series
        "recentlyReadBooks" -> R.string.dashboard_section_recently_read_books
        else -> R.string.dashboard_section_readlists_in_progress
    }

    private fun sectionKind(sectionId: String): DashboardCardKind = when (sectionId) {
        "keepReading" -> DashboardCardKind.Horizontal
        else -> DashboardCardKind.Large
    }

    // ---------------------------------------------------------------- invalidation

    /** Drop one book's row and its list memberships (called after a successful delete). */
    suspend fun deleteBook(dtoId: String) {
        val instanceKey = instanceKey() ?: return
        dao.deleteBook(instanceKey, dtoId)
        dao.deleteListRowsForItem(instanceKey, ITEM_KIND_BOOK, dtoId)
    }

    /** Drop one series' row and its list memberships. Book rows of the series are left to their own deletes. */
    suspend fun deleteSeries(dtoId: String) {
        val instanceKey = instanceKey() ?: return
        dao.deleteSeries(instanceKey, dtoId)
        dao.deleteListRowsForItem(instanceKey, ITEM_KIND_SERIES, dtoId)
    }

    /** Wipe the cache; called when the active server changes so rows cannot leak across servers. */
    suspend fun clearAll() = dao.clearAll()

    @Serializable
    private data class SectionItemRef(val kind: String, val dtoId: String)
}
