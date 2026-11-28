package com.kmworks.kmreader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tones for a surface drawn *on the page* — the floating reader chrome, the quick-action capsules
 * and the footnote bubble.
 *
 * These cannot use the app theme's surface roles: the page carries its own palette (white, sepia,
 * ink green, and their dark counterparts) while the app is grey, and the reader can be dark-themed
 * while the app is not. Painting the chrome in the page colour itself — the previous rule — is what
 * made it disappear in dark mode: a pill filled with exactly the colour of the black page behind it
 * is a pill no one can see, leaving only a 10% hairline that the page's own text out-shouts.
 *
 * So the surface is the page colour stepped toward the page's own foreground: white on a dark page,
 * black on a light one. That is the "raised material" rule iOS applies with its secondary and
 * quaternary fills, expressed on the reader's palette — which also keeps a sepia page's pill warm
 * instead of tinting it grey.
 *
 * @property background the fill: the page colour stepped toward the page's foreground by `lift`.
 * @property border hairline in the foreground colour. It is nearly invisible on its own (that was
 *   the old 0.10 alpha); it only works because the fill underneath now steps away from the page.
 * @property shadowElevation raised-material shadow. Silently absent on a dark page — black on black
 *   — which is why the fill step, not the shadow, carries dark mode. On a light page the shadow is
 *   what separates an almost-white pill from an almost-white page.
 */
internal data class ReaderChromeSurface(
    val background: Color,
    val border: Color,
    val shadowElevation: Dp,
)

/** Step the chrome's fill away from the page; a touch more for the return pill (see [_RETURN_LIFT]). */
private const val CHROME_LIFT = 0.10f

/** The one affordance that has to win the eye: the return pill offered after an explicit jump. */
internal const val RETURN_LIFT = 0.18f

/** Nothing on the page should ever be filled with more than this much foreground — that reads as a dialog. */
private const val MAX_LIFT = 0.4f

/**
 * Builds the floating surface for [pageBackground].
 *
 * The step is a plain per-channel sRGB mix on purpose: interpolating in Oklab (Compose's `lerp`)
 * makes 10% toward white land near #090909 on a black page — a step you cannot see, which is the
 * bug this replaces. A channel mix of 0.10 is 10% of the channel range, i.e. #1A1A1A on black.
 *
 * @param contentColor the page's foreground colour: the direction the fill steps in.
 * @param lift how far to step (clamped to [MAX_LIFT]).
 * @param shadowElevation elevation for `Modifier.shadow`; the caller owns the shape.
 */
internal fun readerChromeSurface(
    pageBackground: Color,
    contentColor: Color,
    lift: Float = CHROME_LIFT,
    shadowElevation: Dp = 6.dp,
): ReaderChromeSurface {
    val dark = pageBackground.luminance() < 0.5f
    val fraction = lift.coerceIn(0f, MAX_LIFT)
    return ReaderChromeSurface(
        background = Color(
            red = pageBackground.red + (contentColor.red - pageBackground.red) * fraction,
            green = pageBackground.green + (contentColor.green - pageBackground.green) * fraction,
            blue = pageBackground.blue + (contentColor.blue - pageBackground.blue) * fraction,
            alpha = pageBackground.alpha,
        ),
        // A dark page can afford a stronger hairline: its fill step is subtle against black, and
        // the eye needs the edge more than it does on a light page where the shadow already reads.
        border = contentColor.copy(alpha = if (dark) 0.20f else 0.12f),
        shadowElevation = shadowElevation,
    )
}
