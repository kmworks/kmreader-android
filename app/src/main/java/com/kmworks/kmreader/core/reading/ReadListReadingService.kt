package com.kmworks.kmreader.core.reading

import com.kmworks.kmreader.core.data.DetailRepository
import com.kmworks.kmreader.core.data.ReaderReadListContext
import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.logging.AppLog
import com.kmworks.kmreader.core.network.ApiClients
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.settings.AppSettingsStore
import com.kmworks.kmreader.core.settings.ClientSettingWriteDto
import com.kmworks.kmreader.core.settings.ClientSettingsApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the read lists the user is reading through, so a read list continues across series the way a
 * series continues across its books (mirrors the original ReadListReadingService).
 *
 * Opt-in ([AppSettingsStore.readListContinuationEnabled]): while off nothing is recorded, synced,
 * resolved, or shown. State is local and syncs through Komga's per-user client settings, one key per
 * read list, so it follows the user across devices.
 */
@Singleton
class ReadListReadingService @Inject constructor(
    private val sessionManager: SessionManager,
    private val appSettings: AppSettingsStore,
    private val detailRepository: DetailRepository,
    private val store: ReadListReadingStore,
    private val scope: CoroutineScope,
) {

    private val syncMutex = Mutex()

    init {
        scope.launch {
            sessionManager.clients.collect { clients ->
                if (clients != null) sync()
            }
        }
        // Turning the setting on pulls what other devices recorded while it was off.
        scope.launch {
            appSettings.readListContinuationEnabled.collect { enabled ->
                if (enabled) sync()
            }
        }
    }

    val enabled: Flow<Boolean> get() = appSettings.readListContinuationEnabled

    /** Read lists being read, most recently read first. */
    fun continuations(): List<ReadListReadingEntry> =
        store.snapshot().entries.filterNot { it.stopped }

    /**
     * Records opening [bookId] from [readListId] (and uploads it). No-op while the setting is off.
     */
    suspend fun record(readListId: String, bookId: String) {
        if (readListId.isBlank() || bookId.isBlank()) return
        if (!appSettings.readListContinuationEnabled.first()) return
        store.put(
            ReadListReadingEntry(
                readListId = readListId,
                bookId = bookId,
                readAtMs = System.currentTimeMillis(),
                dirty = true,
            ),
        )
        scope.launch { sync() }
    }

    /** Stops continuing [readListId]: the next sync deletes its remote key. */
    suspend fun stop(readListId: String) {
        if (!appSettings.readListContinuationEnabled.first()) return
        store.stop(readListId, System.currentTimeMillis())
        scope.launch { sync() }
    }

    /**
     * The read list a book continues in when opened without one, if the user is reading through a list
     * that contains it: the membership lookup is the local half of the original snapshot, and the most
     * recently read list wins when several contain the book.
     *
     * Null while the setting is off, when the book belongs to no active list, or when the lookup fails
     * (reading must never wait for it).
     */
    suspend fun ownerContext(dtoId: String): ReaderReadListContext? {
        if (dtoId.isBlank()) return null
        if (!appSettings.readListContinuationEnabled.first()) return null
        val entries = continuations()
        if (entries.isEmpty()) return null
        val memberships = runCatching { detailRepository.bookReadLists(dtoId) }.getOrNull().orEmpty()
        if (memberships.isEmpty()) return null
        val byId = memberships.associateBy { it.id }
        return entries.firstNotNullOfOrNull { entry ->
            byId[entry.readListId]?.let { ReaderReadListContext(readListId = it.id, name = it.name) }
        }
    }

    /**
     * Pulls the server's copy first and keeps, per read list, whichever change happened last on any
     * device, a read or a stop; then pushes the local changes that won (mirrors the original reconcile).
     * Best effort: failures leave the local state for the next round.
     */
    suspend fun sync() {
        if (!appSettings.readListContinuationEnabled.first()) return
        val clients = sessionManager.clients.value ?: return
        syncMutex.withLock {
            val api = api(clients)
            val remote = withContext(Dispatchers.IO) {
                runCatching { api.getUserSettings().execute() }.getOrNull()
            }
            if (remote != null && remote.isSuccessful) {
                val parsed = remote.body().orEmpty()
                    .mapNotNull { (key, setting) -> ReadListReadingWire.decode(key, setting.value) }
                if (parsed.isNotEmpty()) {
                    store.replace(ReadListReadingMerge.applyRemote(store.snapshot().entries, parsed))
                }
            }
            upload(api)
        }
    }

    private suspend fun upload(api: ClientSettingsApi) {
        val dirty = store.snapshot().entries.filter { it.dirty }
        if (dirty.isEmpty()) return
        val updates = mutableMapOf<String, ClientSettingWriteDto>()
        val pushedIds = mutableSetOf<String>()
        dirty.filterNot { it.stopped }.forEach { entry ->
            val key = ReadListReadingWire.key(entry.readListId) ?: return@forEach
            updates[key] = ClientSettingWriteDto(ReadListReadingWire.encode(entry))
            pushedIds += entry.readListId
        }
        if (updates.isNotEmpty()) {
            val ok = withContext(Dispatchers.IO) {
                runCatching { api.saveUserSettings(updates).execute() }.getOrNull()?.isSuccessful == true
            }
            if (ok) {
                store.markClean(pushedIds)
            } else {
                AppLog.w(TAG, "read list reading upload failed: ${updates.size} keys")
            }
        }
        val stopped = dirty.filter { it.stopped }
        if (stopped.isNotEmpty()) {
            val keys = stopped.mapNotNull { ReadListReadingWire.key(it.readListId) }.toSet()
            val ok = keys.isNotEmpty() && withContext(Dispatchers.IO) {
                runCatching { api.deleteUserSettings(keys).execute() }.getOrNull()?.isSuccessful == true
            }
            if (ok) {
                store.dropStopped(stopped.map { it.readListId }.toSet())
            } else if (keys.isNotEmpty()) {
                AppLog.w(TAG, "read list reading stop upload failed: ${keys.size} keys")
            }
        }
    }

    private fun api(clients: ApiClients): ClientSettingsApi =
        LocalApiFactory.create(clients, ClientSettingsApi::class.java)

    private companion object {
        const val TAG = "ReadListReading"
    }
}
