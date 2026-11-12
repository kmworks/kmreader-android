package com.kmworks.kmreader.core.komf

import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.data.awaitBody
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** komf 任务轮询状态（对齐 KomfJobTracker 的终态语义）。 */
sealed interface KomfJobState {
    data object Running : KomfJobState
    data object Completed : KomfJobState
    data class Failed(val message: String?) : KomfJobState
    /** 超时或连续错误退出：任务可能仍在服务端执行，调用方应尽力刷新。 */
    data object TimedOut : KomfJobState
}

/** komf 调用（kmrs 私有，管理员专用；失败返回 null/false，由界面门控隐藏）。 */
@Singleton
class KomfRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    private fun api(): KomfApi? =
        sessionManager.clients.value?.let { LocalApiFactory.create(it, KomfApi::class.java) }

    /** null = 请求失败（搜索失败态）。 */
    suspend fun search(name: String, libraryId: String, seriesId: String): List<KomfSearchResultDto>? =
        api()?.search(name, libraryId, seriesId)?.awaitBody()

    /** 返回 jobId；null = 提交失败。 */
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
    )?.awaitBody()?.id

    suspend fun match(libraryId: String, seriesId: String): String? =
        api()?.match(libraryId, seriesId)?.awaitBody()?.id

    /** reset 非任务：成功即完成，调用方直接刷新。 */
    suspend fun reset(libraryId: String, seriesId: String): Boolean =
        api()?.reset(libraryId, seriesId)?.awaitBody() != null

    suspend fun job(jobId: String): KomfJobDto? = api()?.job(jobId)?.awaitBody()

    /**
     * 轮询任务状态（对齐 KomfJobTracker：2 秒间隔、最多 300 次、连续 5 次错误退出）。
     * 仅发射状态迁移：起始 Running，终态 Completed / Failed / TimedOut 后结束。
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
                    else -> Unit // RUNNING：继续轮询
                }
            }
        }
        emit(KomfJobState.TimedOut)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MAX_POLL_ATTEMPTS = 300
        const val MAX_CONSECUTIVE_ERRORS = 5
    }
}
