package com.kmworks.kmreader.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Decodes the GitHub release payload; every field the check does not use is ignored. */
internal val UpdateJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/** The subset of `GET /repos/{owner}/{repo}/releases/latest` the update check reads. */
@Serializable
internal data class UpdateReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<UpdateAssetDto> = emptyList(),
) {
    @Serializable
    data class UpdateAssetDto(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String = "",
    )
}

/** A published release newer than the installed build: what to show and where to get it. */
data class UpdateInfo(
    /** Release version without the tag's `v`, e.g. `1.3.0`. */
    val version: String,
    val title: String? = null,
    /** Release notes as the release body carries them (markdown). */
    val notes: String? = null,
    /** GitHub release page; the fallback when the release has no APK asset. */
    val releaseUrl: String? = null,
    /** Direct APK download; the release publishes `kmreader-android-<version>.apk`. */
    val downloadUrl: String? = null,
    val publishedAt: String? = null,
)

/** Maps a release payload to [UpdateInfo], or null when its tag is not a readable version. */
internal fun UpdateReleaseDto.toUpdateInfo(): UpdateInfo? {
    val version = AppVersion.parse(tagName) ?: return null
    val apk = assets.firstOrNull { asset ->
        asset.name.startsWith("kmreader-android", ignoreCase = true) &&
            asset.name.endsWith(".apk", ignoreCase = true) &&
            asset.downloadUrl.isNotBlank()
    }
    return UpdateInfo(
        version = tagName.trim().removePrefix("v").removePrefix("V"),
        title = name?.takeIf { it.isNotBlank() },
        notes = body?.takeIf { it.isNotBlank() },
        releaseUrl = htmlUrl?.takeIf { it.isNotBlank() },
        downloadUrl = apk?.downloadUrl,
        publishedAt = publishedAt,
    ).takeIf { version.segments.isNotEmpty() }
}
