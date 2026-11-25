package com.kmworks.kmreader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note payload arrives as a plain string from the page JS: every field here is one malformed
 * message away from a crash, and a stale token must never reach the screen.
 */
class EpubFootnoteTest {

    private fun payload(
        token: Int = 7,
        href: String = "https://kmreader-epub.local/ch1.xhtml#fn1",
        frag: String = "fn1",
        html: String = "<p>Note text</p>",
        text: String = "Note text",
        anchorX: Double = 0.5,
        anchorY: Double = 0.5,
        truncated: Boolean = false,
        images: String = "[]",
    ): String = """
        {"v":1,"href":"$href","frag":"$frag","html":"$html","text":"$text",
         "truncated":$truncated,"images":$images,
         "anchor":{"x":$anchorX,"y":$anchorY},"token":$token}
    """.trimIndent()

    @Test
    fun `parse accepts a payload from the current page load`() {
        val note = EpubFootnote.parse(payload(), expectedToken = 7)
        assertNotNull(note)
        note!!
        assertEquals("fn1", note.fragment)
        assertEquals("<p>Note text</p>", note.html)
        assertTrue(note.isRenderable)
        assertFalse(note.truncated)
    }

    @Test
    fun `parse drops a payload from the previous chapter`() {
        // The dying JS of the chapter just left can still emit a throttled or queued tap.
        assertNull(EpubFootnote.parse(payload(token = 6), expectedToken = 7))
    }

    @Test
    fun `parse drops payloads without a resolvable target`() {
        assertNull(EpubFootnote.parse(payload(frag = ""), expectedToken = 7))
        assertNull(EpubFootnote.parse(payload(href = ""), expectedToken = 7))
        assertNull(EpubFootnote.parse("not json", expectedToken = 7))
        assertNull(EpubFootnote.parse("{}", expectedToken = 7))
    }

    @Test
    fun `parse clamps the anchor into the viewport`() {
        assertEquals(1f, EpubFootnote.parse(payload(anchorX = 1.4), 7)!!.anchorX, 0f)
        assertEquals(0f, EpubFootnote.parse(payload(anchorY = -0.2), 7)!!.anchorY, 0f)
        // Missing anchor: middle of the page rather than the top-left corner.
        val noAnchor = EpubFootnote.parse(
            """{"href":"https://kmreader-epub.local/ch1.xhtml#fn1","frag":"fn1","html":"x","text":"x","token":7}""",
            7,
        )!!
        assertEquals(0.5f, noAnchor.anchorX, 0f)
        assertEquals(0.5f, noAnchor.anchorY, 0f)
    }

    @Test
    fun `parse keeps at most the image cap`() {
        val images = (1..(EpubFootnote.MAX_IMAGES + 4)).joinToString(prefix = "[", postfix = "]") {
            """{"src":"https://kmreader-epub.local/i$it.png","alt":"$it"}"""
        }
        val note = EpubFootnote.parse(payload(images = images), 7)!!
        assertEquals(EpubFootnote.MAX_IMAGES, note.images.size)
    }

    @Test
    fun `parse caps oversized html and text`() {
        val hugeHtml = "a".repeat(EpubFootnote.MAX_HTML_CHARS + 500)
        val hugeText = "b".repeat(EpubFootnote.MAX_TEXT_CHARS + 500)
        val note = EpubFootnote.parse(payload(html = hugeHtml, text = hugeText), 7)!!
        assertEquals(EpubFootnote.MAX_HTML_CHARS, note.html.length)
        assertEquals(EpubFootnote.MAX_TEXT_CHARS, note.text.length)
    }

    @Test
    fun `image only note is renderable`() {
        val images = """[{"src":"https://kmreader-epub.local/i1.png","alt":""}]"""
        val note = EpubFootnote.parse(payload(text = "", images = images), 7)!!
        assertTrue(note.isRenderable)
        assertFalse(EpubFootnote.parse(payload(text = ""), 7)!!.isRenderable)
    }

    @Test
    fun `position prefers above when the anchor is low`() {
        val position = computeEpubFootnotePosition(
            anchorX = 0.5f,
            anchorY = 0.9f,
            windowWidthPx = 1000,
            windowHeightPx = 2000,
            popupWidthPx = 400,
            popupHeightPx = 300,
            edgeMarginPx = 24,
            anchorGapPx = 10,
        )
        assertTrue(position.above)
        assertEquals(300, position.x)
        assertEquals(1490, position.y)
    }

    @Test
    fun `position flips below when there is no room above`() {
        val position = computeEpubFootnotePosition(
            anchorX = 0.5f,
            anchorY = 0.01f,
            windowWidthPx = 1000,
            windowHeightPx = 2000,
            popupWidthPx = 400,
            popupHeightPx = 300,
            edgeMarginPx = 24,
            anchorGapPx = 10,
        )
        assertFalse(position.above)
        assertEquals(20 + 10, position.y)
    }

    @Test
    fun `position keeps the bubble inside the side margins`() {
        val left = computeEpubFootnotePosition(
            anchorX = 0f,
            anchorY = 0.9f,
            windowWidthPx = 1000,
            windowHeightPx = 2000,
            popupWidthPx = 400,
            popupHeightPx = 300,
            edgeMarginPx = 24,
            anchorGapPx = 10,
        )
        assertEquals(24, left.x)
        val right = computeEpubFootnotePosition(
            anchorX = 1f,
            anchorY = 0.9f,
            windowWidthPx = 1000,
            windowHeightPx = 2000,
            popupWidthPx = 400,
            popupHeightPx = 300,
            edgeMarginPx = 24,
            anchorGapPx = 10,
        )
        assertEquals(1000 - 400 - 24, right.x)
    }

    @Test
    fun `position survives a bubble wider than the window`() {
        // A landscape phone with a very short window: the clamps must not invert into an
        // IllegalArgumentException (coerceIn requires min <= max).
        val position = computeEpubFootnotePosition(
            anchorX = 0.5f,
            anchorY = 0.5f,
            windowWidthPx = 400,
            windowHeightPx = 300,
            popupWidthPx = 520,
            popupHeightPx = 420,
            edgeMarginPx = 12,
            anchorGapPx = 10,
        )
        assertEquals(12, position.x)
        assertEquals(12, position.y)
    }
}
