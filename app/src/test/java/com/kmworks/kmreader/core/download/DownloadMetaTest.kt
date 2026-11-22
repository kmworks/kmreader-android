package com.kmworks.kmreader.core.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A saved cover lives in the download directory but is neither a page nor the content file, so it
 * must not make a page download look complete or break a single-file download's integrity check.
 */
class DownloadMetaTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `saved cover is not counted as a page`() {
        val dir = temporaryFolder.newFolder()
        File(dir, "001.jpg").writeText("page")
        File(dir, DownloadMeta.COVER_FILE).writeText("cover")
        val meta = DownloadMeta(
            title = "Book",
            pageCount = 1,
            kind = DownloadKind.Pages,
            format = DownloadSpec.FORMAT_IMAGES,
        )

        assertEquals(1, meta.pageFiles(dir).size)
        assertEquals(1, DownloadMeta.pageFileCount(dir))
        assertTrue(meta.isComplete(dir))
        assertNotNull(meta.coverFile(dir))
    }

    @Test
    fun `missing cover does not affect page completeness`() {
        val dir = temporaryFolder.newFolder()
        File(dir, "001.jpg").writeText("page")
        val meta = DownloadMeta(
            title = "Book",
            pageCount = 1,
            kind = DownloadKind.Pages,
            format = DownloadSpec.FORMAT_IMAGES,
        )

        assertTrue(meta.isComplete(dir))
        assertNull(meta.coverFile(dir))
    }

    @Test
    fun `cover does not stand in for a single-file download`() {
        val dir = temporaryFolder.newFolder()
        File(dir, DownloadMeta.COVER_FILE).writeText("cover")
        val meta = DownloadMeta(
            title = "Book",
            pageCount = 1,
            kind = DownloadKind.Pdf,
            format = "pdf",
        )

        assertFalse(meta.isComplete(dir))
        assertNotNull(meta.coverFile(dir))
    }
}
