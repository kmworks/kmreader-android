package com.kmworks.kmreader.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WorkManager adds the worker class FQCN tag to every request, so the queue must still resolve the real book
 * dto id — otherwise every offline row disappears or a class name surfaces as a phantom book.
 */
class DownloadTagIdentityTest {

    @Test
    fun `book id is the tag that is neither shared nor synthetic`() {
        val tags = setOf(DownloadWorker::class.java.name, DownloadWorker.TAG, "book-42")

        assertEquals("book-42", DownloadRepository.bookIdFromTags(tags))
    }

    @Test
    fun `constraint tracking worker tag is not a book id`() {
        val tags = listOf(
            DownloadWorker::class.java.name,
            "androidx.work.impl.workers.ConstraintTrackingWorker",
            DownloadWorker.TAG,
            "book-42",
        )

        assertEquals("book-42", DownloadRepository.bookIdFromTags(tags))
    }

    @Test
    fun `a request without a book tag yields no row`() {
        assertNull(DownloadRepository.bookIdFromTags(listOf(DownloadWorker::class.java.name, DownloadWorker.TAG)))
    }

    @Test
    fun `unexpected extra tags are rejected instead of guessed`() {
        val tags = setOf(DownloadWorker::class.java.name, DownloadWorker.TAG, "book-42", "ff:1234")

        assertNull(DownloadRepository.bookIdFromTags(tags))
    }

    @Test
    fun `blank book id is rejected`() {
        assertNull(DownloadRepository.bookIdFromTags(setOf(DownloadWorker.TAG, "  ")))
    }
}
