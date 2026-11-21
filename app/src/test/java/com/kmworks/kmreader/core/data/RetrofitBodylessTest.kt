package com.kmworks.kmreader.core.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.DELETE
import retrofit2.http.PATCH

/**
 * Pins the contract [awaitSuccess] exists for: Retrofit answers `Response.success(null, …)` for a
 * 204/205 *before* any converter runs, so `awaitBody() != null` reads a successful DELETE or PATCH
 * as a failure. A repository that asks "did the body decode" therefore reports "deleted failed"
 * even though the server already deleted the list.
 */
class RetrofitBodylessTest {

    @Suppress("unused")
    private interface BodylessApi {
        @DELETE("collections/{id}")
        fun deleteCollection(): Call<Unit>

        @PATCH("readlists/{id}")
        fun updateReadList(): Call<Unit>
    }

    @Test
    fun noContentDeleteSucceedsWithoutABody() = runBlocking {
        val api = api().create(BodylessApi::class.java)

        // The mistake that reported every delete as failed:
        assertNull(api.deleteCollection().awaitBody())

        // What the repositories check instead:
        assertTrue(api.deleteCollection().awaitSuccess())
    }

    @Test
    fun noContentPatchSucceedsWithoutABody() = runBlocking {
        val api = api().create(BodylessApi::class.java)

        assertNull(api.updateReadList().awaitBody())
        assertTrue(api.updateReadList().awaitSuccess())
    }

    /** Every request is answered with 204 No Content, the way Komga answers delete/update. */
    private fun api(): Retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(204)
                        .message("No Content")
                        .build()
                }
                .build(),
        )
        .build()
}
