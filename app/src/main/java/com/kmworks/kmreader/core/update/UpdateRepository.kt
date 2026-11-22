package com.kmworks.kmreader.core.update

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads the project's latest GitHub release.
 *
 * Deliberately standalone: this is not the server session's client, so the check works without a
 * configured instance and never carries the user's server credentials to github.com. The repository
 * is public, so no token is needed; GitHub still wants a User-Agent.
 */
@Singleton
class UpdateRepository @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** The latest published release, or the failure that stopped the lookup. */
    suspend fun latestRelease(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "GitHub responded ${response.code}" }
                val body = response.body.string()
                val release = UpdateJson.decodeFromString<UpdateReleaseDto>(body)
                check(!release.draft) { "No published release" }
                release.toUpdateInfo() ?: error("Unreadable release tag")
            }
        }
    }

    private companion object {
        const val OWNER_REPO = "kmworks/kmreader-android"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
        const val USER_AGENT = "KMReader-Android"

        /** Long enough for a slow mobile connection, short enough that a launch check never hangs. */
        const val TIMEOUT_SECONDS = 15L
    }
}
