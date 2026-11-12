package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.network.ApiClients
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for cover images, keyed by the UI stable id.
 *
 * The registry stores only the Komga source (kind + DTO id) and builds the absolute URL lazily
 * from the current [ApiClients]. Every DTO -> UI mapper registers here, so screens never need to
 * assemble their own `stableId -> url` maps and a base-url/login change can never leave a stale
 * absolute URL behind.
 */
object CoverUrlRegistry {

    enum class Kind { Series, Book, Collection, ReadList, SmartList }

    private data class CoverRef(val kind: Kind, val dtoId: String)

    private val refs = ConcurrentHashMap<Int, CoverRef>()

    fun registerSeries(stableId: Int, dtoId: String) = put(stableId, CoverRef(Kind.Series, dtoId))

    fun registerBook(stableId: Int, dtoId: String) = put(stableId, CoverRef(Kind.Book, dtoId))

    fun registerCollection(stableId: Int, dtoId: String) =
        put(stableId, CoverRef(Kind.Collection, dtoId))

    fun registerReadList(stableId: Int, dtoId: String) =
        put(stableId, CoverRef(Kind.ReadList, dtoId))

    fun registerSmartList(stableId: Int, dtoId: String) =
        put(stableId, CoverRef(Kind.SmartList, dtoId))

    /** Absolute cover URL for a UI stable id, or null when unknown / not logged in. */
    fun url(stableId: Int, clients: ApiClients?): String? {
        val ref = refs[stableId] ?: return null
        if (clients == null) return null
        return when (ref.kind) {
            Kind.Series -> clients.seriesThumbnailUrl(ref.dtoId)
            Kind.Book -> clients.bookThumbnailUrl(ref.dtoId)
            Kind.Collection -> clients.collectionThumbnailUrl(ref.dtoId)
            Kind.ReadList -> clients.readListThumbnailUrl(ref.dtoId)
            Kind.SmartList -> clients.smartListThumbnailUrl(ref.dtoId)
        }
    }

    /** Drop everything; used when the active server changes so old ids cannot leak across servers. */
    fun clear() {
        refs.clear()
    }

    private fun put(stableId: Int, ref: CoverRef) {
        refs[stableId] = ref
    }
}
