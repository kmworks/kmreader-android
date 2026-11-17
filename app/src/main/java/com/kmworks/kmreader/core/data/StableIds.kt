package com.kmworks.kmreader.core.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Assigns a process-unique Int stableId to each DTO (kind + string id).
 *
 * Hashing a Komga string id straight to 32 bits collides (two distinct books observed with the same hash —
 * after a collision LazyGrid keys duplicate and crash on scroll, and cover registry entries overwrite each
 * other). On collision, deterministically re-hash with salt until free; repeated mapping of the same DTO
 * reuses the assigned id, keeping it stable within a session.
 */
object StableIds {
    const val KIND_SERIES = "series"
    const val KIND_BOOK = "book"
    const val KIND_COLLECTION = "collection"
    const val KIND_READLIST = "readlist"
    const val KIND_SMARTLIST = "smartlist"

    private data class Owner(val kind: String, val dtoId: String)

    private val owners = ConcurrentHashMap<Int, Owner>()
    private val assignments = ConcurrentHashMap<String, Int>()

    fun of(kind: String, dtoId: String): Int {
        val key = "$kind:$dtoId"
        assignments[key]?.let { assigned ->
            val owner = owners[assigned]
            if (owner != null && owner.kind == kind && owner.dtoId == dtoId) return assigned
        }
        var candidate = dtoId.hashCode()
        var salt = 0
        while (true) {
            val owner = owners[candidate]
            if (owner == null || (owner.kind == kind && owner.dtoId == dtoId)) break
            salt++
            candidate = 31 * candidate + salt
        }
        owners[candidate] = Owner(kind, dtoId)
        assignments[key] = candidate
        return candidate
    }

    fun series(dtoId: String): Int = of(KIND_SERIES, dtoId)
    fun book(dtoId: String): Int = of(KIND_BOOK, dtoId)
    fun collection(dtoId: String): Int = of(KIND_COLLECTION, dtoId)
    fun readList(dtoId: String): Int = of(KIND_READLIST, dtoId)
    fun smartList(dtoId: String): Int = of(KIND_SMARTLIST, dtoId)
}
