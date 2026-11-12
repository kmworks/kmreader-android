package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.api.model.SeriesDto
import com.kmworks.kmreader.core.api.model.SeriesSearch
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Series feeds (latest / new / updated / paged list); empty without a session. */
@Singleton
class SeriesRepository @Inject constructor(
    private val sessionManager: SessionManager,
) {

    private val api get() = sessionManager.clients.value?.series

    /** Thumbnail URL for a series id; null without a session. */
    fun thumbnailUrl(seriesId: String): String? =
        sessionManager.clients.value?.seriesThumbnailUrl(seriesId)

    /** Recently added or updated series (GET api/v1/series/latest). */
    suspend fun latest(size: Int = 12): List<SeriesDto> {
        val api = api ?: return emptyList()
        return api.getSeriesLatest(size = size).awaitBody()?.content.orEmpty()
    }

    /** Newly added series (GET api/v1/series/new). */
    suspend fun newlyAdded(size: Int = 12): List<SeriesDto> {
        val api = api ?: return emptyList()
        return api.getSeriesNew(size = size).awaitBody()?.content.orEmpty()
    }

    /** Recently updated (not newly added) series (GET api/v1/series/updated). */
    suspend fun recentlyUpdated(size: Int = 12): List<SeriesDto> {
        val api = api ?: return emptyList()
        return api.getSeriesUpdated(size = size).awaitBody()?.content.orEmpty()
    }

    /** Paged series list for the library grid (POST api/v1/series/list). */
    suspend fun list(
        page: Int = 0,
        size: Int = 60,
        sort: List<String> = listOf("metadata.titleSort,asc"),
    ): List<SeriesDto> {
        val api = api ?: return emptyList()
        return api.getSeries(SeriesSearch(), page = page, size = size, sort = sort)
            .awaitBody()?.content.orEmpty()
    }
}
