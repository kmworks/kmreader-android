package com.kmworks.kmreader.core.epub

import org.json.JSONObject

/**
 * In-place footnote payload reported by the page JS when a noteref is tapped.
 *
 * The JS extracts and sanitises the note body inside the WebView (it already holds the styled DOM,
 * so `getComputedStyle` can recover the emphasis a publisher expressed through classes), then sends
 * one JSON record per tap. Everything here is defensive: the bridge is a plain string channel and a
 * malformed or oversized payload must never crash the reader.
 */
internal object EpubFootnote {

    /** Hard payload caps, mirrored on the JS side (a cap on one side only is no cap at all). */
    const val MAX_HTML_CHARS = 16_000
    const val MAX_TEXT_CHARS = 2_000
    const val MAX_IMAGES = 8

    /**
     * Parses one bridge payload.
     *
     * @param expectedToken the reader's current page load token: a payload from the previous
     *   chapter's dying JS must be dropped, or its bubble would float over the new chapter.
     */
    fun parse(json: String, expectedToken: Int): EpubFootnoteNote? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        if (root.optInt("token", -1) != expectedToken) return null

        val href = root.optString("href").trim()
        val fragment = root.optString("frag").trim()
        if (href.isEmpty() || fragment.isEmpty()) return null

        val html = root.optString("html")
            .let { if (it.length > MAX_HTML_CHARS) it.take(MAX_HTML_CHARS) else it }
        val text = root.optString("text")
            .let { if (it.length > MAX_TEXT_CHARS) it.take(MAX_TEXT_CHARS) else it }
            .trim()

        val images = buildList {
            val array = root.optJSONArray("images") ?: return@buildList
            for (i in 0 until minOf(array.length(), MAX_IMAGES)) {
                val item = array.optJSONObject(i) ?: continue
                val src = item.optString("src").trim()
                if (src.isEmpty()) continue
                add(EpubFootnoteImage(src = src, alt = item.optString("alt").trim()))
            }
        }

        val anchor = root.optJSONObject("anchor")
        return EpubFootnoteNote(
            href = href,
            fragment = fragment,
            html = html,
            text = text,
            truncated = root.optBoolean("truncated", false),
            images = images,
            anchorX = anchor?.optDouble("x", 0.5)?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f,
            anchorY = anchor?.optDouble("y", 0.5)?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f,
        )
    }
}

internal data class EpubFootnoteImage(val src: String, val alt: String)

internal data class EpubFootnoteNote(
    /** Absolute in-book href of the reference (`chapter.xhtml#fn3`), used by the fallback jump. */
    val href: String,
    val fragment: String,
    /** Sanitised inner HTML of the note body, ready for HtmlCompat. */
    val html: String,
    val text: String,
    val truncated: Boolean,
    val images: List<EpubFootnoteImage>,
    /** Tap position as a 0..1 ratio of the viewport, so the bubble pops up where the finger was. */
    val anchorX: Float,
    val anchorY: Float,
) {

    /** Nothing to show (image-only notes before the image pass lands are handled by the caller). */
    val isRenderable: Boolean get() = text.isNotEmpty() || images.isNotEmpty()
}

/** Resolved popup placement in window pixels. */
internal data class EpubFootnotePosition(val x: Int, val y: Int, val above: Boolean)

/**
 * Places the note bubble next to the tapped reference.
 *
 * Kept as a pure function (no Compose types) so the layout rules — prefer above, flip below when
 * there is no room, clamp inside the edge margins — are unit-testable without a device. Notes sit
 * near the bottom of the page in most books, which is exactly where "above" is the only option that
 * does not cover the paragraph the reader is looking at.
 */
internal fun computeEpubFootnotePosition(
    anchorX: Float,
    anchorY: Float,
    windowWidthPx: Int,
    windowHeightPx: Int,
    popupWidthPx: Int,
    popupHeightPx: Int,
    edgeMarginPx: Int,
    anchorGapPx: Int,
): EpubFootnotePosition {
    val margin = edgeMarginPx.coerceAtLeast(0)
    val anchorPx = (anchorX.coerceIn(0f, 1f) * windowWidthPx).toInt()
    val anchorPy = (anchorY.coerceIn(0f, 1f) * windowHeightPx).toInt()

    val maxX = (windowWidthPx - popupWidthPx - margin).coerceAtLeast(margin)
    val x = (anchorPx - popupWidthPx / 2).coerceIn(margin, maxX)

    val aboveY = anchorPy - anchorGapPx - popupHeightPx
    val belowY = anchorPy + anchorGapPx
    val above = aboveY >= margin
    val maxY = (windowHeightPx - popupHeightPx - margin).coerceAtLeast(margin)
    val y = (if (above) aboveY else belowY).coerceIn(margin, maxY)

    return EpubFootnotePosition(x = x, y = y, above = above)
}
