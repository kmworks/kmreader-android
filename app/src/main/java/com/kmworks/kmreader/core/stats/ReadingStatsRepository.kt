package com.kmworks.kmreader.core.stats

import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.data.awaitBody
import com.kmworks.kmreader.core.network.SessionManager
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 阅读统计数据源（kmrs 能力；stock Komga 404 时返回 null，由上层按能力门控隐藏）。
 * 服务经 LocalApiFactory 本地构建（ApiClients 只读）。
 */
@Singleton
class ReadingStatsRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    private fun api(): ReadingStatsApi? =
        sessionManager.clients.value?.let { LocalApiFactory.create(it, ReadingStatsApi::class.java) }

    suspend fun summary(): ReadingStatsSummaryResponse? =
        api()?.summary()?.awaitBody()

    suspend fun activity(): ReadingStatsActivityResponse? =
        api()?.activity(tzOffsetMinutes = deviceTzOffsetMinutes())?.awaitBody()

    suspend fun tops(): ReadingStatsTopsResponse? =
        api()?.tops()?.awaitBody()

    private suspend fun deviceTzOffsetMinutes(): Int = withContext(Dispatchers.Default) {
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
    }
}
