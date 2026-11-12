package com.kmworks.kmreader.ui.tracker

import com.kmworks.kmreader.core.tracker.TrackerLinkDto
import com.kmworks.kmreader.core.tracker.TrackerRemoteStateDto

/** Tracker provider（对齐原作 TrackerProvider：anilist/mal/bangumi/mangabaka）。 */
enum class TrackerProvider(
    val displayName: String,
    val entryUrlPattern: String,
    /** API 路径/DTO 使用的原始值。 */
    val apiName: String,
) {
    AniList("AniList", "https://anilist.co/manga/{id}", "anilist"),
    MyAnimeList("MyAnimeList", "https://myanimelist.net/manga/{id}", "mal"),
    Bangumi("Bangumi", "https://bgm.tv/subject/{id}", "bangumi"),
    MangaBaka("MangaBaka", "https://mangabaka.org/{id}", "mangabaka"),
    ;

    fun entryUrl(trackId: String): String = entryUrlPattern.replace("{id}", trackId)
}

/** 进度映射模式（对齐 TrackerMode）。 */
enum class TrackerMode(val label: String) {
    Auto("自动"), Chapter("按章节"), Volume("按卷")
}

/** 远端阅读状态（对齐 TrackerStatus）。 */
enum class TrackerStatus(val label: String) {
    Reading("在阅读"), Planning("计划"), Completed("已完成"),
    Paused("暂停"), Dropped("弃坑"), Rereading("重读"),
}

/** 已建立的绑定（对齐 TrackerLink）。 */
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

/** 设计稿示例：葬送的芙莉莲已链接 AniList 与 Bangumi。 */
val SampleTrackerLinks = listOf(
    TrackerLink(
        provider = TrackerProvider.AniList,
        trackId = "108465",
        title = "葬送のフリーレン",
        remoteStatus = TrackerStatus.Reading,
        lastReadChapter = 120.0,
        totalChapters = null,
    ),
    TrackerLink(
        provider = TrackerProvider.Bangumi,
        trackId = "285267",
        title = "葬送的芙莉莲",
        mode = TrackerMode.Chapter,
        remoteStatus = TrackerStatus.Reading,
        lastReadChapter = 120.0,
        totalChapters = 140,
    ),
)

/** provider 账号连接状态（设置页）。 */
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

/** API 原始值 → provider（未知回退 AniList，避免丢绑定）。 */
fun trackerProviderFromApi(raw: String): TrackerProvider =
    TrackerProvider.entries.firstOrNull { it.apiName == raw } ?: TrackerProvider.AniList

/** API 原始值 → 状态（未知回退 Reading）。 */
fun trackerStatusFromApi(raw: String): TrackerStatus =
    TrackerStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: TrackerStatus.Reading

/** DTO → UI 绑定（合并远端状态展示）。 */
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
