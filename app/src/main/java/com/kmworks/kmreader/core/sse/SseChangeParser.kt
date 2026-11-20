package com.kmworks.kmreader.core.sse

import com.kmworks.kmreader.core.data.ContentChange
import com.kmworks.kmreader.core.data.ContentChangeKind
import com.kmworks.kmreader.core.data.booksChanged
import com.kmworks.kmreader.core.data.collectionsChanged
import com.kmworks.kmreader.core.data.readListsChanged
import com.kmworks.kmreader.core.data.seriesChanged
import com.kmworks.kmreader.core.data.smartListsChanged
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One SSE event turned into something a screen can act on.
 *
 * [change] is null for events that need no reaction (task queue, session, keep-alives). [coverDtoIds] names the
 * entities whose cover cache must be dropped; a cover is fetched again by URL, so the payload only says "this
 * picture changed", never what it looks like now.
 */
data class ParsedSseChange(
    val change: ContentChange?,
    val coverDtoIds: Set<String> = emptySet(),
)

/**
 * Turns SSE payloads into [ContentChange]s.
 *
 * Komga's payloads are deliberately thin — ids only, no field values (`{"bookId": "...", "seriesId": "..."}`) — so
 * nothing here can fabricate a value-level patch. The event name says what happened to the id: added, deleted, or
 * merely "changed". "Changed" becomes [ContentChangeKind.Touched]: screens re-read just the rows they show, one
 * request per row, and patch them. Nothing here reaches the network.
 *
 * Payloads that fail to decode and event names this app does not model become [ContentChange.Unknown], which every
 * screen handles by re-reading its query once (debounced) instead of guessing.
 */
@Singleton
class SseChangeParser @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(event: SseEvent): ParsedSseChange? {
        val name = event.name
        // Nothing to act on: the long connection's keep-alive, the task queue counter and the session expiry
        // (RootViewModel logs out on that one). Libraries are re-read by the dashboard only.
        if (name.startsWith("Library") || name.startsWith("Task") || name == "SessionExpired") return null
        if (CONTENT_EVENT_PREFIXES.none { name.startsWith(it) }) return null

        return when (name) {
            // Books ------------------------------------------------------------------------------------------
            "BookAdded", "BookImported" -> decodable<BookPayload>(event.data) { payload ->
                payload.bookId?.let { ParsedSseChange(booksChanged(listOf(it), ContentChangeKind.Created)) }
            }

            "BookDeleted" -> decodable<BookPayload>(event.data) { payload ->
                payload.bookId?.let { ParsedSseChange(booksChanged(listOf(it), ContentChangeKind.Deleted)) }
            }

            "BookChanged" -> decodable<BookPayload>(event.data) { payload ->
                payload.bookId?.let { ParsedSseChange(booksChanged(listOf(it), ContentChangeKind.Touched)) }
            }

            "ThumbnailBookAdded", "ThumbnailBookDeleted" -> decodable<BookPayload>(event.data) { payload ->
                payload.bookId?.let {
                    ParsedSseChange(
                        change = booksChanged(listOf(it), ContentChangeKind.Touched),
                        coverDtoIds = setOf(it),
                    )
                }
            }

            // Series -----------------------------------------------------------------------------------------
            "SeriesAdded" -> decodable<SeriesPayload>(event.data) { payload ->
                payload.seriesId?.let { ParsedSseChange(seriesChanged(listOf(it), ContentChangeKind.Created)) }
            }

            "SeriesDeleted" -> decodable<SeriesPayload>(event.data) { payload ->
                payload.seriesId?.let { ParsedSseChange(seriesChanged(listOf(it), ContentChangeKind.Deleted)) }
            }

            "SeriesChanged" -> decodable<SeriesPayload>(event.data) { payload ->
                payload.seriesId?.let { ParsedSseChange(seriesChanged(listOf(it), ContentChangeKind.Touched)) }
            }

            "ThumbnailSeriesAdded", "ThumbnailSeriesDeleted" -> decodable<SeriesPayload>(event.data) { payload ->
                payload.seriesId?.let {
                    ParsedSseChange(
                        change = seriesChanged(listOf(it), ContentChangeKind.Touched),
                        coverDtoIds = setOf(it),
                    )
                }
            }

            // Reading progress -------------------------------------------------------------------------------
            "ReadProgressChanged" -> decodable<BookPayload>(event.data) { payload ->
                payload.bookId?.let { ParsedSseChange(booksChanged(listOf(it), ContentChangeKind.Touched)) }
            }

            "ReadProgressDeleted" -> decodable<BookPayload>(event.data) { payload ->
                payload.bookId?.let {
                    ParsedSseChange(booksChanged(listOf(it), ContentChangeKind.ReadStateChanged, read = false))
                }
            }

            "ReadProgressSeriesChanged", "ReadProgressSeriesDeleted" ->
                decodable<SeriesPayload>(event.data) { payload ->
                    payload.seriesId?.let { ParsedSseChange(seriesChanged(listOf(it), ContentChangeKind.Touched)) }
                }

            // Collections ------------------------------------------------------------------------------------
            "CollectionAdded" -> decodable<CollectionPayload>(event.data) { payload ->
                payload.collectionId?.let {
                    ParsedSseChange(collectionsChanged(listOf(it), ContentChangeKind.Created))
                }
            }

            "CollectionDeleted" -> decodable<CollectionPayload>(event.data) { payload ->
                payload.collectionId?.let {
                    ParsedSseChange(collectionsChanged(listOf(it), ContentChangeKind.Deleted))
                }
            }

            "CollectionChanged" -> decodable<CollectionPayload>(event.data) { payload ->
                payload.collectionId?.let {
                    ParsedSseChange(collectionsChanged(listOf(it), ContentChangeKind.Touched))
                }
            }

            "ThumbnailSeriesCollectionAdded", "ThumbnailSeriesCollectionDeleted" ->
                decodable<CollectionPayload>(event.data) { payload ->
                    payload.collectionId?.let {
                        ParsedSseChange(
                            change = collectionsChanged(listOf(it), ContentChangeKind.Touched),
                            coverDtoIds = setOf(it),
                        )
                    }
                }

            // Read lists -------------------------------------------------------------------------------------
            "ReadListAdded" -> decodable<ReadListPayload>(event.data) { payload ->
                payload.readListId?.let { ParsedSseChange(readListsChanged(listOf(it), ContentChangeKind.Created)) }
            }

            "ReadListDeleted" -> decodable<ReadListPayload>(event.data) { payload ->
                payload.readListId?.let { ParsedSseChange(readListsChanged(listOf(it), ContentChangeKind.Deleted)) }
            }

            "ReadListChanged" -> decodable<ReadListPayload>(event.data) { payload ->
                payload.readListId?.let { ParsedSseChange(readListsChanged(listOf(it), ContentChangeKind.Touched)) }
            }

            "ThumbnailReadListAdded", "ThumbnailReadListDeleted" -> decodable<ReadListPayload>(event.data) { payload ->
                payload.readListId?.let {
                    ParsedSseChange(
                        change = readListsChanged(listOf(it), ContentChangeKind.Touched),
                        coverDtoIds = setOf(it),
                    )
                }
            }

            // Smart lists ------------------------------------------------------------------------------------
            "SmartListAdded" -> decodable<SmartListPayload>(event.data) { payload ->
                payload.smartListId?.let {
                    ParsedSseChange(smartListsChanged(listOf(it), ContentChangeKind.Created))
                }
            }

            "SmartListDeleted" -> decodable<SmartListPayload>(event.data) { payload ->
                payload.smartListId?.let {
                    ParsedSseChange(smartListsChanged(listOf(it), ContentChangeKind.Deleted))
                }
            }

            "SmartListChanged", "SmartListThumbnailChanged" -> decodable<SmartListPayload>(event.data) { payload ->
                payload.smartListId?.let {
                    ParsedSseChange(smartListsChanged(listOf(it), ContentChangeKind.Touched))
                }
            }

            else -> ParsedSseChange(ContentChange.Unknown)
        }
    }

    /** Decode [data], or fall back to "reload everything" when the payload is not what we expect. */
    private inline fun <reified T> decodable(data: String, consume: (T) -> ParsedSseChange?): ParsedSseChange =
        runCatching { json.decodeFromString<T>(data) }.getOrNull()?.let(consume)
            ?: ParsedSseChange(ContentChange.Unknown)

    private companion object {
        /** Event names whose payload this class understands; anything else is a keep-alive or a category we skip. */
        val CONTENT_EVENT_PREFIXES = setOf(
            "Book", "Series", "ReadProgress", "Collection", "ReadList", "SmartList", "Thumbnail",
        )
    }
}

@Serializable
private data class BookPayload(
    val bookId: String? = null,
    val seriesId: String? = null,
    val libraryId: String? = null,
)

@Serializable
private data class SeriesPayload(
    val seriesId: String? = null,
    val libraryId: String? = null,
)

@Serializable
private data class CollectionPayload(
    val collectionId: String? = null,
    val seriesIds: List<String> = emptyList(),
)

@Serializable
private data class ReadListPayload(
    val readListId: String? = null,
    val bookIds: List<String> = emptyList(),
)

@Serializable
private data class SmartListPayload(
    val smartListId: String? = null,
)
