package com.kmworks.kmreader.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding tests over the release payload of v1.2.0, captured from the live `releases/latest` endpoint:
 * the API adds fields and reorders assets over time, so the DTO has to tolerate both.
 */
class UpdateReleaseTest {

    private fun decode(json: String): UpdateReleaseDto =
        UpdateJson.decodeFromString<UpdateReleaseDto>(json)

    @Test
    fun decodesTheRealReleasePayload() {
        val info = decode(releaseJson()).toUpdateInfo()

        assertEquals("1.2.0", info?.version)
        assertEquals("v1.2.0", info?.title)
        assertEquals("https://github.com/kmworks/kmreader-android/releases/tag/v1.2.0", info?.releaseUrl)
        assertTrue(info?.notes.orEmpty().startsWith("KMReader for Android 1.2.0."))
        // The sha256 file is listed first on the real release; only the APK may become the download.
        assertEquals(APK_URL, info?.downloadUrl)
    }

    @Test
    fun keepsTheReleasePageWhenTheApkAssetIsMissing() {
        val info = decode(releaseJson(assets = listOf(SHA256_NAME to SHA256_URL))).toUpdateInfo()

        assertNull(info?.downloadUrl)
        assertEquals("1.2.0", info?.version)
        assertTrue(info?.releaseUrl.orEmpty().endsWith("/tag/v1.2.0"))
    }

    @Test
    fun ignoresAssetsForOtherPlatforms() {
        val ios = releaseJson(assets = listOf("kmreader-ios-1.2.0.ipa" to "$DOWNLOAD_BASE/kmreader-ios-1.2.0.ipa"))

        assertNull(decode(ios).toUpdateInfo()?.downloadUrl)
    }

    @Test
    fun anUnreadableTagIsNotARelease() {
        assertNull(decode(releaseJson(tag = "nightly")).toUpdateInfo())
    }

    @Test
    fun draftsDecodeSoTheRepositoryCanRejectThem() {
        assertTrue(decode(releaseJson(draft = true)).draft)
    }

    /**
     * The shape of the live payload, with the used fields filled from v1.2.0 and the unrelated ones kept
     * to prove `ignoreUnknownKeys` holds.
     */
    private fun releaseJson(
        assets: List<Pair<String, String>> = listOf(SHA256_NAME to SHA256_URL, APK_NAME to APK_URL),
        tag: String = "v1.2.0",
        draft: Boolean = false,
    ): String {
        val assetJson = assets.joinToString(",\n") { (name, url) ->
            """      { "name": "$name", "browser_download_url": "$url" }"""
        }
        return """
{
  "url": "https://api.github.com/repos/kmworks/kmreader-android/releases/264000001",
  "assets_url": "https://api.github.com/repos/kmworks/kmreader-android/releases/264000001/assets",
  "html_url": "https://github.com/kmworks/kmreader-android/releases/tag/v1.2.0",
  "id": 264000001,
  "author": { "login": "kmworks", "id": 1, "type": "Organization" },
  "node_id": "RE_kwDOJ",
  "tag_name": "$tag",
  "target_commitish": "main",
  "name": "v1.2.0",
  "draft": $draft,
  "immutable": false,
  "prerelease": false,
  "created_at": "2026-11-21T03:16:20Z",
  "updated_at": "2026-11-21T03:16:28Z",
  "published_at": "2026-11-21T03:16:28Z",
  "assets": [
$assetJson
  ],
  "tarball_url": "https://api.github.com/repos/kmworks/kmreader-android/tarball/v1.2.0",
  "zipball_url": "https://api.github.com/repos/kmworks/kmreader-android/zipball/v1.2.0",
  "body": "KMReader for Android 1.2.0.\n\n- versionName `1.2.0`, versionCode `9`\n- Package `com.kmworks.kmreader`, Android 8.0 (API 28) and newer\n- Release-signed APK (minified with R8, no debug overrides)\n"
}
"""
    }

    private companion object {
        const val DOWNLOAD_BASE = "https://github.com/kmworks/kmreader-android/releases/download/v1.2.0"
        const val SHA256_NAME = "kmreader-android-1.2.0.apk.sha256"
        const val SHA256_URL = "$DOWNLOAD_BASE/kmreader-android-1.2.0.apk.sha256"
        const val APK_NAME = "kmreader-android-1.2.0.apk"
        const val APK_URL = "$DOWNLOAD_BASE/kmreader-android-1.2.0.apk"
    }
}
