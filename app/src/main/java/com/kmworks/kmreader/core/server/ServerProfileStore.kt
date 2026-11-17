package com.kmworks.kmreader.core.server

import com.kmworks.kmreader.core.network.ApiClients
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Capability probing backbone (mirrors iOS ServerProfileService): with a session, probes /api/v1/server/info
 * once; 404/network errors are treated as stock Komga (empty feature set, no error surfaced); cleared when
 * the session disappears.
 */
@Singleton
class ServerProfileStore @Inject constructor(
    private val sessionManager: SessionManager,
    scope: CoroutineScope,
) {

    private val _profile = MutableStateFlow<ServerProfile?>(null)
    val profile: StateFlow<ServerProfile?> = _profile.asStateFlow()

    init {
        scope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    _profile.value = null
                } else {
                    probe(clients)
                }
            }
        }
    }

    /** No profile (stock Komga or not yet probed) → everything false. */
    fun hasFeature(feature: ServerFeature): Boolean =
        _profile.value?.features?.contains(feature) == true

    /** Cutoff of the kmrs recent-reading window; null on stock Komga / when the window is off. */
    fun recentReadingCutoff(): java.time.Instant? = _profile.value?.recentReadingCutoff()

    /** Probe once again (mirrors iOS ServerProfileService.refresh(); called on capability changes such as komf 409). */
    suspend fun refresh() {
        sessionManager.clients.value?.let { probe(it) }
    }

    private suspend fun probe(clients: ApiClients) = withContext(Dispatchers.IO) {
        _profile.value = try {
            val response = clients.serverApi.getServerInfo().execute()
            if (response.isSuccessful) {
                val info = response.body()
                // kmrs settings (skipReadOnNext / recentReadingDays): on failure keep the previous values
                // (mirrors the degradation semantics of iOS ServerProfileService.fetchSettings).
                val previous = _profile.value
                val settings = runCatching {
                    clients.serverApi.getServerSettings().execute().takeIf { it.isSuccessful }?.body()
                }.getOrNull()
                ServerProfile(
                    isKmrs = true,
                    features = info?.features.orEmpty()
                        .mapNotNull(ServerFeature::fromRaw)
                        .toSet(),
                    version = info?.version,
                    skipReadOnNext = settings?.skipReadOnNext ?: previous?.skipReadOnNext ?: false,
                    recentReadingDays = (settings?.recentReadingDays ?: previous?.recentReadingDays ?: 0)
                        .coerceAtLeast(0),
                )
            } else {
                stockProfile()
            }
        } catch (_: Exception) {
            // Network errors are likewise treated as stock; no error surfaces to the UI
            stockProfile()
        }
    }

    private fun stockProfile() = ServerProfile(
        isKmrs = false,
        features = emptySet(),
        version = null,
    )
}
