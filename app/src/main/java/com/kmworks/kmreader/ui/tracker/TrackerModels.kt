package com.kmworks.kmreader.ui.tracker

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.tracker.TrackerLinkDto
import com.kmworks.kmreader.core.tracker.TrackerRemoteStateDto

/** Tracker provider (aligned with the original TrackerProvider: anilist/mal/bangumi/mangabaka). */
enum class TrackerProvider(
    val displayName: String,
    val entryUrlPattern: String,
    /** Raw value used in API paths/DTOs. */
    val apiName: String,
    /** Provider brand logo asset (kept in original colors, matching iOS Assets.xcassets). */
    @DrawableRes val logoRes: Int,
) {
    AniList("AniList", "https://anilist.co/manga/{id}", "anilist", R.drawable.tracker_anilist),
    MyAnimeList("MyAnimeList", "https://myanimelist.net/manga/{id}", "mal", R.drawable.tracker_mal),
    Bangumi("Bangumi", "https://bgm.tv/subject/{id}", "bangumi", R.drawable.tracker_bangumi),
    MangaBaka("MangaBaka", "https://mangabaka.org/{id}", "mangabaka", R.drawable.tracker_mangabaka),
    ;

    fun entryUrl(trackId: String): String = entryUrlPattern.replace("{id}", trackId)

    /** Resolves this provider's entry id from a series web link URL (domain matching aligned with komf parseProviderLink). */
    fun trackIdFromUrl(url: String): String? {
        val lower = url.lowercase()
        val regex = when (this) {
            AniList -> if (lower.contains("anilist.co")) AniListEntry else return null
            MyAnimeList -> if (lower.contains("myanimelist.net")) AniListEntry else return null
            Bangumi -> if (lower.contains("bgm.tv") || lower.contains("bangumi.tv")) BangumiSubject else return null
            MangaBaka -> if (lower.contains("mangabaka.org")) MangaBakaEntry else return null
        }
        return regex.find(lower)?.groupValues?.get(1)
    }

    private companion object {
        val AniListEntry = Regex("""/(?:anime|manga)/(\d+)""")
        val BangumiSubject = Regex("""/subject/(\d+)""")
        val MangaBakaEntry = Regex("""mangabaka\.org/(?:series/)?([a-z0-9-]+)""")
    }
}

/** Progress mapping mode (aligned with TrackerMode). */
enum class TrackerMode(@StringRes val labelRes: Int) {
    Auto(R.string.tracker_mode_auto), Chapter(R.string.tracker_mode_chapter), Volume(R.string.tracker_mode_volume)
}

/** Remote reading status (aligned with TrackerStatus). */
enum class TrackerStatus(@StringRes val labelRes: Int) {
    Reading(R.string.tracker_status_reading), Planning(R.string.tracker_status_planning),
    Completed(R.string.tracker_status_completed), Paused(R.string.tracker_status_paused),
    Dropped(R.string.tracker_status_dropped), Rereading(R.string.tracker_status_rereading),
}

/** An established link (aligned with TrackerLink). */
data class TrackerLink(
    val provider: TrackerProvider,
    val trackId: String,
    val title: String,
    val mode: TrackerMode = TrackerMode.Auto,
    val chapterOffset: Int = 0,
    val remoteStatus: TrackerStatus = TrackerStatus.Reading,
    val lastReadChapter: Double? = null,
    val totalChapters: Int? = null,
)

/** Provider account connection state (settings page). */
data class TrackerAccount(
    val provider: TrackerProvider,
    val connectedUser: String?,
)

val SampleTrackerAccounts = listOf(
    TrackerAccount(TrackerProvider.AniList, connectedUser = "kmuser"),
    TrackerAccount(TrackerProvider.MyAnimeList, connectedUser = null),
    TrackerAccount(TrackerProvider.Bangumi, connectedUser = "kmuser"),
    TrackerAccount(TrackerProvider.MangaBaka, connectedUser = null),
)

/** API raw value → provider (unknown values fall back to AniList to avoid dropping links). */
fun trackerProviderFromApi(raw: String): TrackerProvider =
    TrackerProvider.entries.firstOrNull { it.apiName == raw } ?: TrackerProvider.AniList

/** API raw value → status (unknown values fall back to Reading). */
fun trackerStatusFromApi(raw: String): TrackerStatus =
    TrackerStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: TrackerStatus.Reading

/** DTO → UI link (merges remote state for display). */
fun TrackerLinkDto.toUiTrackerLink(state: TrackerRemoteStateDto? = null): TrackerLink =
    TrackerLink(
        provider = trackerProviderFromApi(provider),
        trackId = trackId,
        title = title ?: trackId,
        mode = TrackerMode.entries.firstOrNull { it.name.equals(trackMode, ignoreCase = true) }
            ?: TrackerMode.Auto,
        chapterOffset = chapterOffset,
        remoteStatus = state?.status?.let(::trackerStatusFromApi) ?: TrackerStatus.Reading,
        lastReadChapter = state?.lastReadChapter,
        totalChapters = state?.totalChapters,
    )
