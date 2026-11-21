package com.kmworks.kmreader.core.reading

import kotlinx.serialization.Serializable

/**
 * One read list the user is reading through (mirrors the original read_list_reading_states row).
 *
 * [readAtMs] is the moment of the last change on any device, a read or a stop: the sync merge keeps
 * whichever change happened last, so an offline stop never deletes a read another device made after it.
 * A stopped row is a tombstone: it deletes the remote key and is removed locally once that succeeded.
 */
@Serializable
data class ReadListReadingEntry(
    val readListId: String,
    /** The book last opened from the list; empty for a stop. */
    val bookId: String = "",
    val readAtMs: Long,
    val stopped: Boolean = false,
    /** True while this device's change has not been uploaded yet. */
    val dirty: Boolean = false,
)

/** The persisted set of read lists being read (one JSON document in DataStore). */
@Serializable
data class ReadListReadingDocument(
    val entries: List<ReadListReadingEntry> = emptyList(),
)

/**
 * The read-list continuation wire format: Komga per-user client settings, one key per read list, the
 * value the same JSON object the original writes, so both apps read each other's state.
 *
 * Komga requires every key segment to be lowercase alphanumeric; read list ids are case-insensitive
 * base32, so lowercasing is lossless and the original id also travels inside the value.
 */
object ReadListReadingWire {

    const val KEY_PREFIX = "kmreader.readlist."

    @Serializable
    private data class RemoteState(
        val readListId: String = "",
        val bookId: String = "",
        /** Seconds since the epoch, the same unit the original writes. */
        val readAt: Double = 0.0,
    )

    /** The client-settings key of a read list, or null when the id cannot be a valid key segment. */
    fun key(readListId: String): String? {
        val segment = readListId.lowercase()
        if (segment.isEmpty()) return null
        if (segment.any { it !in 'a'..'z' && it !in '0'..'9' }) return null
        return KEY_PREFIX + segment
    }

    fun encode(entry: ReadListReadingEntry): String = Json.encodeToString(
        RemoteState.serializer(),
        RemoteState(
            readListId = entry.readListId,
            bookId = entry.bookId,
            readAt = entry.readAtMs / 1000.0,
        ),
    )

    /**
     * Decode one remote key into a change; null when it is not a read-list key, is malformed, or its key
     * does not belong to the read list it names (the same consistency check the original makes).
     */
    fun decode(key: String, value: String): ReadListReadingEntry? {
        if (!key.startsWith(KEY_PREFIX)) return null
        val state = runCatching { Json.decodeFromString(RemoteState.serializer(), value) }.getOrNull()
            ?: return null
        if (state.readListId.isEmpty() || state.bookId.isEmpty()) return null
        if (key(state.readListId) != key) return null
        return ReadListReadingEntry(
            readListId = state.readListId,
            bookId = state.bookId,
            readAtMs = (state.readAt * 1000.0).toLong(),
            dirty = false,
        )
    }

    private val Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }
}

/** Pull-side merge: per read list the newer of the two changes wins; the server never undoes a newer local one. */
object ReadListReadingMerge {

    fun applyRemote(
        local: List<ReadListReadingEntry>,
        remote: List<ReadListReadingEntry>,
    ): List<ReadListReadingEntry> {
        val merged = local.associateBy { it.readListId }.toMutableMap()
        remote.forEach { incoming ->
            val current = merged[incoming.readListId]
            if (current == null || incoming.readAtMs > current.readAtMs) {
                merged[incoming.readListId] = incoming.copy(dirty = false, stopped = false)
            }
        }
        return merged.values.sortedByDescending { it.readAtMs }
    }
}
