package com.kmworks.kmreader.core.komf

import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.data.awaitBody
import com.kmworks.kmreader.core.network.SessionManager
import com.kmworks.kmreader.core.server.ServerProfileStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import retrofit2.Call

/** komf job polling state (mirrors the terminal-state semantics of KomfJobTracker). */
sealed interface KomfJobState {
    data object Running : KomfJobState
    data object Completed : KomfJobState
    data class Failed(val message: String?) : KomfJobState
    /** Exited on timeout or consecutive errors: the job may still be running server-side; the caller should refresh best-effort. */
    data object TimedOut : KomfJobState
}

/** komf calls (kmrs private, admin-only; failures return null/false and the UI gates them away). */
@Singleton
class KomfRepository @Inject constructor(
    private val sessionManager: SessionManager,
    private val serverProfileStore: ServerProfileStore,
) {

    private fun api(): KomfApi? =
        sessionManager.clients.value?.let { LocalApiFactory.create(it, KomfApi::class.java) }

    /** null = request failed (search failure state). */
    suspend fun search(name: String, libraryId: String, seriesId: String): List<KomfSearchResultDto>? =
        api()?.search(name, libraryId, seriesId)?.awaitBody()

    /** Returns the jobId; null = submission failed. */
    suspend fun identify(
        libraryId: String?,
        seriesId: String,
        provider: String,
        providerSeriesId: String,
    ): String? = api()?.identify(
        KomfIdentifyRequestDto(
            seriesId = seriesId,
            provider = provider,
            providerSeriesId = providerSeriesId,
            libraryId = libraryId,
        )
    )?.executeWithConflictRetry()?.id

    suspend fun match(libraryId: String, seriesId: String): String? =
        api()?.match(libraryId, seriesId)?.executeWithConflictRetry()?.id

    /** reset is not a job: success means done, the caller refreshes directly. */
    suspend fun reset(libraryId: String, seriesId: String): Boolean =
        api()?.reset(libraryId, seriesId)?.executeWithConflictRetry() != null

    /**
     * 409 = the komf integration went offline server-side (capability state changed): re-read server/info
     * once, then clone-and-retry once; only then return null (mirrors
     * KomfIntegrationStore.handleConflictIfNeeded; at most 1 retry).
     */
    private suspend fun <T> Call<T>.executeWithConflictRetry(): T? = withContext(Dispatchers.IO) {
        try {
            val first = execute()
            if (first.isSuccessful) return@withContext first.body()
            if (first.code() != HTTP_CONFLICT) return@withContext null
            reprofileOnce()
            val second = clone().execute()
            if (second.isSuccessful) second.body() else null
        } catch (_: Exception) {
            null
        }
    }

    /** Re-probe the server profile (mirrors iOS ServerProfileService.refresh(); the result is written back to the store to update capability gating). */
    private suspend fun reprofileOnce() {
        runCatching { serverProfileStore.refresh() }
    }

    suspend fun job(jobId: String): KomfJobDto? = api()?.job(jobId)?.awaitBody()

    /**
     * Poll the job state (mirrors KomfJobTracker: 2-second interval, at most 300 polls, exit after
     * 5 consecutive errors).
     * Only state transitions are emitted: starts as Running, ends after Completed / Failed / TimedOut.
     */
    fun trackJob(jobId: String): Flow<KomfJobState> = flow {
        emit(KomfJobState.Running)
        var consecutiveErrors = 0
        repeat(MAX_POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val job = job(jobId)
            if (job == null) {
                consecutiveErrors++
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    emit(KomfJobState.TimedOut)
                    return@flow
                }
            } else {
                consecutiveErrors = 0
                when (job.status) {
                    "COMPLETED" -> {
                        emit(KomfJobState.Completed)
                        return@flow
                    }
                    "FAILED" -> {
                        emit(KomfJobState.Failed(job.message))
                        return@flow
                    }
                    else -> Unit // RUNNING: keep polling
                }
            }
        }
        emit(KomfJobState.TimedOut)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MAX_POLL_ATTEMPTS = 300
        const val MAX_CONSECUTIVE_ERRORS = 5
        const val HTTP_CONFLICT = 409
    }
}
