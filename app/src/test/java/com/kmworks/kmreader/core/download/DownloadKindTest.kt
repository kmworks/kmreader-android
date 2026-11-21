package com.kmworks.kmreader.core.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The download kind decides what the worker fetches and what "downloaded" means afterwards, so the
 * media-type mapping and the meta round-trip (including legacy directories) are pinned down here.
 */
class DownloadKindTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `pdf media downloads the whole pdf file`() {
        val spec = DownloadSpec.forMedia("application/pdf", epubDivinaCompatible = false)

        assertEquals(DownloadKind.Pdf, spec.kind)
        assertEquals("pdf", spec.format)
    }

    @Test
    fun `epub reads pages only when Komga renders it`() {
        val divina = DownloadSpec.forMedia("application/epub+zip", epubDivinaCompatible = true)
        val webPub = DownloadSpec.forMedia("application/epub+zip", epubDivinaCompatible = false)

        assertEquals(DownloadKind.Pages, divina.kind)
        assertEquals(DownloadSpec.FORMAT_IMAGES, divina.format)
        assertEquals(DownloadKind.Epub, webPub.kind)
        assertEquals("epub", webPub.format)
    }

    @Test
    fun `zip comic archives download as one archive file`() {
        val spec = DownloadSpec.forMedia("application/vnd.comicbook+zip", epubDivinaCompatible = false)

        assertEquals(DownloadKind.Archive, spec.kind)
        assertEquals("cbz", spec.format)
    }

    @Test
    fun `rar archives keep page downloads because java zip cannot read them`() {
        val spec = DownloadSpec.forMedia("application/vnd.comicbook-rar", epubDivinaCompatible = false)

        assertEquals(DownloadKind.Pages, spec.kind)
        assertEquals(DownloadSpec.FORMAT_IMAGES, spec.format)
    }

    @Test
    fun `unknown media falls back to page downloads`() {
        assertEquals(DownloadKind.Pages, DownloadSpec.forMedia("", false).kind)
        assertEquals(DownloadKind.Pages, DownloadSpec.forMedia("application/octet-stream", false).kind)
    }

    @Test
    fun `kind meta values round trip and unknown values are legacy pages`() {
        DownloadKind.entries.forEach { kind ->
            assertEquals(kind, DownloadKind.fromMeta(kind.metaValue))
        }
        assertEquals(DownloadKind.Pages, DownloadKind.fromMeta(null))
        assertEquals(DownloadKind.Pages, DownloadKind.fromMeta("something-new"))
    }

    @Test
    fun `legacy directory without kind reads as completed page download`() {
        val dir = temp.newFolder("book-legacy")
        File(dir, DownloadWorker.META_FILE).writeText("title=Legacy\npageCount=2\n")
        File(dir, "001.jpg").writeText("a")
        File(dir, "002.jpg").writeText("b")

        val meta = DownloadMeta.read(dir)!!

        assertEquals(DownloadKind.Pages, meta.kind)
        assertEquals(DownloadSpec.FORMAT_IMAGES, meta.fileFormat)
        assertTrue(meta.isComplete(dir))
    }

    @Test
    fun `legacy directory with missing pages is incomplete`() {
        val dir = temp.newFolder("book-partial")
        File(dir, DownloadWorker.META_FILE).writeText("title=Partial\npageCount=3\n")
        File(dir, "001.jpg").writeText("a")

        assertFalse(DownloadMeta.read(dir)!!.isComplete(dir))
    }

    @Test
    fun `single file download is complete only when the container exists`() {
        val dir = temp.newFolder("book-archive")
        DownloadMeta.write(dir, "Archive Book", 4, DownloadKind.Archive, "cbz")

        val meta = DownloadMeta.read(dir)!!

        assertEquals(DownloadKind.Archive, meta.kind)
        assertEquals("cbz", meta.fileFormat)
        assertFalse(meta.isComplete(dir))

        File(dir, DownloadMeta.fileName("cbz")).writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(meta.isComplete(dir))
    }

    @Test
    fun `partial single file download is not a completed book`() {
        val dir = temp.newFolder("book-pdf")
        DownloadMeta.write(dir, "PDF Book", 10, DownloadKind.Pdf, "pdf")
        File(dir, "book.pdf.${DownloadMeta.PART_SUFFIX}").writeBytes(byteArrayOf(1))

        assertFalse(DownloadMeta.read(dir)!!.isComplete(dir))
    }
}
