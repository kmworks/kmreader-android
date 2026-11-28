package com.kmworks.kmreader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Tones for a surface drawn *on the page* — the floating reader chrome, the quick-action capsules
 * and the footnote bubble.
 *
 * These cannot use the app theme's surface roles: the page carries its own palette (white, sepia,
 * ink green, and their dark counterparts) while the app is grey, and the reader can be dark-themed
 * while the app is not. Painting them in the page colour — the original rule — works on a light
 * page, where white on white is still carried by a hairline, but it fails completely on the dark
 * themes: the default dark page is #000000, so a disc filled with exactly that colour is a disc no
 * one can see, and the chrome reads as bare glyphs over the text.
 *
 * So the fill steps toward the page's own foreground, and only on a dark page: white on black is
 * the raised material iOS shows with its secondary fills, while a light page keeps the page colour
 * (a grey disc on white paper is a heavier look than this reader wants). The step is expressed on
 * the reader's palette rather than the app theme's, so a dark sepia page keeps a warm bubble.
 *
 * @property background the fill: the page colour on a light page, stepped toward the page's
 *   foreground on a dark one.
 * @property border hairline in the foreground colour — the separation on a light page, and the edge
 *   that defines the shape on a dark one.
 */
internal data class ReaderChromeSurface(
    val background: Color,
    val border: Color,
)

/** Step the chrome's fill away from a dark page. */
private const val CHROME_LIFT = 0.10f

/** The one affordance that has to win the eye: the return pill offered after an explicit jump. */
private const val RETURN_LIFT = 0.18f

/** Nothing on the page should ever be filled with more than this much foreground — that reads as a dialog. */
private const val MAX_LIFT = 0.4f

/**
 * Builds the floating surface for [pageBackground].
 *
 * On a dark page the fill is a plain per-channel sRGB mix on purpose: interpolating in Oklab
 * (Compose's `lerp`) makes 10% toward white land near #090909 on black — a step you cannot see,
 * which is the bug this replaces. A channel mix of 0.10 is 10% of the channel range, i.e. #1A1A1A.
 *
 * @param contentColor the page's foreground colour: the direction the fill steps in, and the
 *   hairline's colour.
 * @param prominent the return pill: on a dark page it steps twice as far and gets a heavier
 *   hairline; on a light page, where the fill stays the page colour, only the hairline is heavier —
 *   the pill earns its weight from that edge and from its type, not from a fill.
 */
internal fun readerChromeSurface(
    pageBackground: Color,
    contentColor: Color,
    prominent: Boolean = false,
): ReaderChromeSurface {
    val dark = pageBackground.luminance() < 0.5f
    val fraction = if (dark) (if (prominent) RETURN_LIFT else CHROME_LIFT).coerceIn(0f, MAX_LIFT) else 0f
    return ReaderChromeSurface(
        background = Color(
            red = pageBackground.red + (contentColor.red - pageBackground.red) * fraction,
            green = pageBackground.green + (contentColor.green - pageBackground.green) * fraction,
            blue = pageBackground.blue + (contentColor.blue - pageBackground.blue) * fraction,
            alpha = pageBackground.alpha,
        ),
        border = contentColor.copy(
            alpha = when {
                // A dark page can afford a stronger hairline: its fill step is subtle against black.
                dark && prominent -> 0.30f
                dark -> 0.20f
                // Light pages keep the original 0.10 chrome hairline; the pill gets a visible edge
                // because on that palette the edge is all it has.
                prominent -> 0.20f
                else -> 0.10f
            },
        ),
    )
}
