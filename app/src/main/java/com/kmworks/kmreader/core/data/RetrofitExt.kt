package com.kmworks.kmreader.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Call

/** Execute a Retrofit [Call] on IO and return the body, or null on HTTP error. */
internal suspend fun <T> Call<T>.awaitBody(): T? = withContext(Dispatchers.IO) {
    val response = execute()
    if (response.isSuccessful) response.body() else null
}
