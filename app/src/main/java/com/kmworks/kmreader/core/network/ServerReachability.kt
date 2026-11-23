package com.kmworks.kmreader.core.network

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-wide server reachability, reported by the shared OkHttp client of the active instance
 * ([ApiClients] calls [recordSuccess]/[recordFailure] from its auth interceptor, so every API and
 * SSE request contributes — including failures that happen while the app is already running).
 *
 * Mirrors the iOS `OfflineFailureTracker` (APIClient.swift): a single failure can be a fluke, so
 * the offline flag only flips after [OFFLINE_THRESHOLD] consecutive network failures; any HTTP
 * response — even an error status — proves the server answered and immediately restores online.
 * The flag drives the global offline indicator and is otherwise advisory: view models still
 * attempt their loads and fall back to the disk cache when they fail.
 */
@Singleton
class ServerReachability @Inject constructor() {

    private val _offline = MutableStateFlow(false)

    /** True while the configured server is considered unreachable. */
    val offline: StateFlow<Boolean> = _offline

    private val consecutiveFailures = AtomicInteger(0)

    /** An HTTP response arrived (any status code): the server is reachable. */
    fun recordSuccess() {
        consecutiveFailures.set(0)
        _offline.value = false
    }

    /** A request died at the network layer (no response at all). */
    fun recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= OFFLINE_THRESHOLD) {
            _offline.value = true
        }
    }

    /** The instance (server) changed; the old verdict no longer applies. */
    fun reset() {
        consecutiveFailures.set(0)
        _offline.value = false
    }

    companion object {
        /** Consecutive network failures before the app calls the server unreachable (iOS uses 3). */
        private const val OFFLINE_THRESHOLD = 3
    }
}
