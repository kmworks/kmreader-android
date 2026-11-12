package com.kmworks.kmreader.core.data

import com.kmworks.kmreader.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Call

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
