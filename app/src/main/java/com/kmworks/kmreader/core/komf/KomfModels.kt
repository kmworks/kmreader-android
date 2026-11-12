package com.kmworks.kmreader.core.komf

import kotlinx.serialization.Serializable

/** komf 搜索结果（对齐 KomfSearchResult）。 */
@Serializable
data class KomfSearchResultDto(
    val url: String? = null,
    val imageUrl: String? = null,
    val title: String,
    val provider: String,
    val resultId: String,
    val mediaType: String? = null,
    val language: String? = null,
) {
    /** provider:resultId（对齐 result.id）。 */
    val key: String get() = "$provider:$resultId"

    /** metaText = mediaType · language（对齐 result.metaText）。 */
    val metaText: String get() = listOfNotNull(mediaType, language).joinToString(" · ")
}

/** identify 请求体（对齐 KomfIdentifyRequest：libraryId 为空时省略该键）。 */
@Serializable
data class KomfIdentifyRequestDto(
    val seriesId: String,
    val provider: String,
    val providerSeriesId: String,
    val libraryId: String? = null,
)

/** 返回 jobId 的任务响应（对齐 KomfMetadataJobResponse）。 */
@Serializable
data class KomfMetadataJobResponseDto(val id: String)

/** komf 任务（对齐 KomfJob）。 */
@Serializable
data class KomfJobDto(
    val id: String,
    val status: String,
    val seriesId: String? = null,
    val message: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

/** komf provider 直链（对齐 KomfProviderLink，SCREAMING_SNAKE provider id）。 */
data class KomfProviderLink(
    val provider: String,
    val providerSeriesId: String,
    val url: String,
) {
    val key: String get() = "$provider:$providerSeriesId"

    val displayName: String
        get() = when (provider) {
            "BANGUMI" -> "Bangumi"
            "EHENTAI" -> "E-Hentai"
            "ANILIST" -> "AniList"
            "MAL" -> "MAL"
            "MANGADEX" -> "MangaDex"
            "MANGA_UPDATES" -> "MangaUpdates"
            else -> provider
        }
}

private val BangumiSubject = Regex("""\/subject\/([^/?]+)""")
private val EHentaiGallery = Regex("""\/g\/([^/]+)\/([^/?]+)""")
private val AniListEntry = Regex("""\/(?:anime|manga)\/(\d+)""")
private val MangadexTitle = Regex("""\/title\/([^/?]+)""")
private val MangaUpdatesSeries = Regex("""\/series\/([^/?]+)""")
private val MangaUpdatesLegacy = Regex("""series\.html\?id=(\d+)""")

private fun parseProviderLink(url: String): KomfProviderLink? {
    val lower = url.lowercase()
    if ((lower.contains("bgm.tv") || lower.contains("bangumi.tv")) && lower.contains("/subject/")) {
        BangumiSubject.find(lower)?.groupValues?.get(1)?.let {
            return KomfProviderLink("BANGUMI", it, url)
        }
    }
    if (lower.contains("e-hentai.org") || lower.contains("exhentai.org")) {
        EHentaiGallery.find(lower)?.let { m ->
            return KomfProviderLink("EHENTAI", "${m.groupValues[1]};${m.groupValues[2]}", url)
        }
    }
    if (lower.contains("anilist.co")) {
        AniListEntry.find(lower)?.groupValues?.get(1)?.let {
            return KomfProviderLink("ANILIST", it, url)
        }
    }
    if (lower.contains("myanimelist.net")) {
        AniListEntry.find(lower)?.groupValues?.get(1)?.let {
            return KomfProviderLink("MAL", it, url)
        }
    }
    if (lower.contains("mangadex.org")) {
        MangadexTitle.find(lower)?.groupValues?.get(1)?.let {
            return KomfProviderLink("MANGADEX", it, url)
        }
    }
    if (lower.contains("mangaupdates.com")) {
        val id = MangaUpdatesSeries.find(lower)?.groupValues?.get(1)
            ?: MangaUpdatesLegacy.find(lower)?.groupValues?.get(1)
        id?.let { return KomfProviderLink("MANGA_UPDATES", it, url) }
    }
    return null
}

/** 从系列 web links（label, url）反解 provider 直链，去重保序（对齐 parseAll）。 */
fun parseKomfProviderLinks(links: List<Pair<String, String>>): List<KomfProviderLink> {
    val seen = mutableSetOf<String>()
    return links.mapNotNull { parseProviderLink(it.second) }.filter { seen.add(it.key) }
}
