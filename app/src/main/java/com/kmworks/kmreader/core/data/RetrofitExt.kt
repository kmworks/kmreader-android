package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Call

/**
 * Execute a Retrofit [Call] on IO and report whether the server accepted it.
 * For action endpoints whose success carries no body (DELETE/POST returning 204 or an empty body),
 * where [awaitBody] cannot distinguish "no content" from "failed".
 */
internal suspend fun Call<*>.awaitSuccess(): Boolean = withContext(Dispatchers.IO) {
    try {
        val response = execute()
        if (!response.isSuccessful) {
            AppLog.w("API", "HTTP ${response.code()} ${request().method} ${request().url}")
        }
        response.isSuccessful
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.e("API", "${request().method} ${request().url}", e)
        false
    }
}

/** Execute a Retrofit [Call] on IO and return the body, or null on HTTP/decode/network error. */
internal suspend fun <T> Call<T>.awaitBody(): T? = withContext(Dispatchers.IO) {
    try {
        val response = execute()
        if (response.isSuccessful) {
            response.body()
        } else {
            AppLog.w("API", "HTTP ${response.code()} ${request().method} ${request().url}")
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Decode errors (server omits a field the generated DTO marks required)
        // and network errors both degrade to null, never crash the caller.
        AppLog.e("API", "${request().method} ${request().url}", e)
        null
    }
}

/**
 * Execute a Retrofit [Call] on IO keeping the failure: `success(null)` means the server answered
 * without a body (a missing neighbour), `failure` means the lookup itself failed (offline).
 */
internal suspend fun <T> Call<T>.awaitBodyResult(): Result<T?> = withContext(Dispatchers.IO) {
    try {
        val response = execute()
        if (response.isSuccessful) {
            Result.success(response.body())
        } else {
            AppLog.w("API", "HTTP ${response.code()} ${request().method} ${request().url}")
            Result.success(null)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.e("API", "${request().method} ${request().url}", e)
        Result.failure(e)
    }
}
