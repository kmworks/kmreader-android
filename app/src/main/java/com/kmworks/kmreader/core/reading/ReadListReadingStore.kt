package com.kmworks.kmreader.core.reading

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmworks.kmreader.core.logging.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val Context.readListReadingStore by preferencesDataStore(name = "read_list_reading")

/**
 * The local read-list-reading states (the original keeps them in a SQLite table).
 *
 * DataStore Preferences rather than Room: the project has no other Room entity or database yet, so a
 * single JSON document needs no schema, migration, or DAO, and it is read whole (at most a handful of
 * read lists); this keeps the store on the same persistence the app already uses for settings.
 * Writes are the whole document, and the exposed state flow is updated from the write itself so a reader
 * opening a book right after a record does not read the previous document.
 */
@Singleton
class ReadListReadingStore @Inject constructor(
    @ApplicationContext context: Context,
    scope: CoroutineScope,
) {

    private val store = context.readListReadingStore

    private val _document = MutableStateFlow(ReadListReadingDocument())
    val document: StateFlow<ReadListReadingDocument> = _document.asStateFlow()

    init {
        scope.launch {
            store.data.collect { prefs ->
                _document.value = decode(prefs[Keys.DOCUMENT])
            }
        }
    }

    /** Non-suspending read of the current document (a warm snapshot, safe from the reader's hot path). */
    fun snapshot(): ReadListReadingDocument = _document.value

    /** Inserts or replaces one read list's state. */
    suspend fun put(entry: ReadListReadingEntry) {
        write(_document.value.entries.filter { it.readListId != entry.readListId } + entry)
    }

    /** Marks a read list as stopped (a tombstone uploaded by the next sync). */
    suspend fun stop(readListId: String, stoppedAtMs: Long) {
        val entries = _document.value.entries
        val current = entries.firstOrNull { it.readListId == readListId }
        if (current != null && current.stopped && current.readAtMs >= stoppedAtMs) return
        write(
            entries.filter { it.readListId != readListId } +
                ReadListReadingEntry(readListId = readListId, readAtMs = stoppedAtMs, stopped = true, dirty = true),
        )
    }

    /** Applies a merged document (pull result). */
    suspend fun replace(entries: List<ReadListReadingEntry>) {
        write(entries)
    }

    /** Marks uploaded entries clean so the next sync does not send them again. */
    suspend fun markClean(readListIds: Set<String>) {
        if (readListIds.isEmpty()) return
        write(
            _document.value.entries.map { entry ->
                if (entry.readListId in readListIds) entry.copy(dirty = false) else entry
            },
        )
    }

    /** Drops stopped tombstones whose remote key is gone. */
    suspend fun dropStopped(readListIds: Set<String>) {
        if (readListIds.isEmpty()) return
        write(_document.value.entries.filter { !(it.stopped && it.readListId in readListIds) })
    }

    private suspend fun write(entries: List<ReadListReadingEntry>) {
        val sorted = entries.sortedByDescending { it.readAtMs }
        _document.value = ReadListReadingDocument(sorted)
        store.edit { prefs -> prefs[Keys.DOCUMENT] = encode(sorted) }
    }

    private fun decode(raw: String?): ReadListReadingDocument {
        if (raw.isNullOrBlank()) return ReadListReadingDocument()
        return runCatching { Json.decodeFromString(ReadListReadingDocument.serializer(), raw) }
            .onFailure { AppLog.w(TAG, "read list reading states unreadable: ${it.message}") }
            .getOrDefault(ReadListReadingDocument())
    }

    private fun encode(document: List<ReadListReadingEntry>): String =
        Json.encodeToString(ReadListReadingDocument.serializer(), ReadListReadingDocument(document))

    private object Keys {
        val DOCUMENT = stringPreferencesKey("states")
    }

    private companion object {
        const val TAG = "ReadListReading"
        val Json = Json { ignoreUnknownKeys = true }
    }
}
