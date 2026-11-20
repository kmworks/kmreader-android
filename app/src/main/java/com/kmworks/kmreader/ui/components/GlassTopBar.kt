package com.kmworks.kmreader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Content height of the frosted tab-root bar, excluding the status bar inset.
 *
 * The bar is deliberately slimmer than a stock [androidx.compose.material3.TopAppBar] (64.dp) so the
 * content-first tab pages get a few extra rows of usable height.
 */
val GlassTopBarContentHeight: Dp = 52.dp

/** Status bar inset the bar paints under on edge-to-edge screens. */
@Composable
fun glassTopBarStatusInset(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * Top padding a scrolling page reserves so its first row starts below [GlassTopBar].
 *
 * The bar takes no layout space (it is drawn over the page), so pages reserve this much content
 * padding instead and let everything below scroll underneath the glass. The reserved height is
 * exactly the plate: the sheet ends on a straight line at the bottom of the row, so any extra padding
 * would show up as a strip of bare background between the bar and the first row.
 */
@Composable
fun glassTopBarTopPadding(): Dp = glassTopBarStatusInset() + GlassTopBarContentHeight

/**
 * Window insets a frosted-bar page hands to its `Scaffold`.
 *
 * The status bar is deliberately left out: the page content then starts at the very top of the
 * screen and scrolls underneath the status bar strip, which is exactly the strip the bar has to
 * take over once the glass fades in. Keeping the status bar in the padding instead clips every
 * scroller below the strip, so the bar sits on a hole and a hard seam shows up along the status bar.
 *
 * The navigation bar and any display cutout still inset the page, so its last row and the snackbar
 * stay clear of the system gesture area.
 */
@Composable
fun glassPageContentWindowInsets(): WindowInsets =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

/**
 * Backdrop blur radius behind the bar.
 *
 * The radius has exactly one job here: taking the structure out of the backdrop. A manga cover is a
 * high-contrast picture — a volume's title covers a quarter of the card, and the card's own edge sits
 * right under the bar — so what has to disappear inside the sheet is shape and lettering, not colour.
 * Nullgram's action bar is the reference: measured inside the strip, its average difference between
 * neighbouring pixels is 0.07, i.e. a single smooth field with no edge left in it. At 48.dp our cover
 * still showed its card boundary and the head of the headline through the glass; 64.dp is where the
 * last of that structure stops resolving.
 *
 * A radius this wide does average the strip towards one tone, and that is accepted on purpose instead
 * of paid for with a smaller radius: the tone is put back where it belongs by [GlassSaturation]. The
 * two are separate channels — the radius removes structure, the saturation keeps colour — so adjust
 * them independently. Buying a livelier sheet by lowering this number only buys a legible cover back.
 */
private val GlassBlurRadius: Dp = 64.dp

/**
 * Colour pass applied to the blurred backdrop before the tint.
 *
 * This is what makes the glass read as coloured glass, and at this radius it carries the colour on
 * its own. Blurring averages the backdrop towards its own mean, so the wider the radius the flatter
 * and greyer the sheet gets; the chroma has to be pushed back up afterwards. Telegram's action-bar
 * blur (`BlurringShader.StoryBlurDrawer`, `BLUR_TYPE_ACTION_BACKGROUND`) does exactly that with
 * `colorMatrix.setSaturation(1.6f)` under a 35dp radius; here the radius is nearly twice as wide, so
 * the pass is taken to 1.8f to keep the cover's chroma (17-19 inside the target bar) from washing out
 * into grey. Telegram also scales and lifts the brightness (`x0.92`, `+0.12`); that part is dropped
 * here because it exists for a dark theme surface behind the bar, while this one sits over white manga
 * paper and the lift would undo the darkness the tint provides.
 *
 * Saturation is the colour channel and the tint below is the darkness channel: one restores the hue
 * the long blur washes out, the other sets how dark the sheet sits over the page. Tune them together
 * but never trade one for the other — tinting harder to get colour back only blackens the page, and
 * cutting the blur to keep colour brings the cover's shapes back.
 */
private const val GlassSaturation = 1.8f

/**
 * Tint alphas of the real blur path: the sheet is thickest behind the status bar and thins out
 * slightly towards the bottom edge.
 *
 * The blur hides the shapes and [GlassSaturation] restores the colour, so the tint only has to set
 * how dark the sheet sits over the page: bright manga paper behind a light tint turned the bar into
 * fog that was lighter than the page around it, while a heavy tint turned it into a flat panel. The
 * ramp keeps the top (status bar icons) a touch thicker than the row.
 *
 * These numbers were calibrated one radius ago and are deliberately left alone here. The wider
 * average does let more of the page's brightness through, and that is exactly the point of this
 * pass: the strip should read as glass rather than as a darker band. If it ever reads too pale at
 * [GlassBlurRadius], raise these; if it reads grey, that is [GlassSaturation]'s job, not theirs.
 */
private const val GlassBlurTintAlphaTop = 0.78f
private const val GlassBlurTintAlphaMid = 0.74f
private const val GlassBlurTintAlphaBottom = 0.70f

/** Position (fraction of the plate) where the tint has thinned to [GlassBlurTintAlphaMid]. */
private const val GlassTintMidStop = 0.55f

/** Sheet alpha used when the platform cannot blur (Android 12+ has RenderEffect, older ones do not). */
private const val GlassFallbackTintAlpha = 0.92f

/**
 * Fade the sheet uses when the page starts or stops being scrolled.
 *
 * The sheet is either off (page at the top, nothing behind the bar) or on, so this only softens the
 * switch instead of animating a value every frame: shorter than a blink, long enough that the bar
 * does not appear as a flash on the first pixel of a fling.
 */
private const val GlassSheetFadeMillis = 140

/**
 * Frosted, edge-to-edge top bar for the tab roots and the detail pages.
 *
 * It draws over the page content instead of reserving layout space, so pages scroll underneath it and
 * whatever passes under the sheet gets frosted. The sheet is opaque behind the status bar and the title
 * row and stops on a straight line at the bottom of that row: the bar has a boundary you can see, and
 * the boundary is the row's own edge instead of a band smeared over the artwork.
 *
 * The sheet is only drawn while the page is scrolled ([sheetVisible]). At rest the content starts
 * below the row (see [glassTopBarTopPadding]), so nothing is behind the bar and a plate there would
 * only darken the page background and end on an edge that belongs to no content. Pages whose header
 * never slides under the bar pass false and keep a bare title.
 *
 * Pass a [HazeState] that the scrolling page feeds through `Modifier.hazeSource` to get a real
 * backdrop blur (Haze). Without a state the bar keeps the older translucent-surface look: blur needs
 * Haze to capture the backdrop, because a Compose graphics layer cannot both be replayed and blurred.
 *
 * The page contract that makes the glass cover the status bar instead of ending on a seam:
 * - the bar is the first child of a full-screen [Box] and is not offset;
 * - the `Scaffold` of the page passes [glassPageContentWindowInsets] as its `contentWindowInsets`, so
 *   the content box starts under the status bar instead of below it;
 * - the scrolling element (or the `Row`/`Column` wrapping the panes of a wide layout) is
 *   `Modifier.hazeSource(state)` and fills the whole content box, and it reserves
 *   [glassTopBarTopPadding] as its top content padding;
 * - the page passes [sheetVisible] from the scroll position of that element.
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    sheetVisible: Boolean = true,
    hazeState: HazeState? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val sheetAlpha by animateFloatAsState(
        targetValue = if (sheetVisible) 1f else 0f,
        animationSpec = tween(durationMillis = GlassSheetFadeMillis),
        label = "glassSheetAlpha",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        GlassPlate(
            sheetAlpha = sheetAlpha,
            hazeState = hazeState,
            blurStyle = rememberGlassBlurStyle(),
        )
        // The bar floats over the page instead of a Material Surface, so nothing provides a content
        // color here: without this the action icons (IconButton defaults to LocalContentColor) would
        // keep the black default and disappear on a dark bar.
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(GlassTopBarContentHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (navigationIcon != null) {
                    navigationIcon()
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (navigationIcon == null) 16.dp else 0.dp, end = 4.dp),
                )
                actions()
            }
        }
    }
}

/** Saturation pass applied to the blurred backdrop, see [GlassSaturation]. */
@Composable
private fun rememberGlassColorFilter(): ColorFilter = remember {
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(GlassSaturation) })
}

/**
 * Colour-filters everything the modified node draws.
 *
 * Compose has no colour-filter modifier, so the subtree is drawn into an offscreen layer whose paint
 * carries the filter: [GlassSaturation] has to be applied to the finished blurred backdrop, and the
 * haze effect itself only exposes a tint. The paint is passed in so a scrolling list does not
 * allocate one per frame.
 */
private fun Modifier.glassColorFilter(paint: Paint): Modifier = drawWithContent {
    drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), paint) }
    drawContent()
    drawIntoCanvas { canvas -> canvas.restore() }
}

/**
 * Blur (or the pre-Android 12 tint fallback), filling the status bar strip and the title row.
 *
 * The plate is exactly as tall as the bar: its lower edge is the row's lower edge, so the sheet ends
 * on the line where the content starts sliding under it and never paints over a row that is merely
 * sitting there.
 */
@Composable
private fun BoxScope.GlassPlate(
    sheetAlpha: Float,
    hazeState: HazeState?,
    blurStyle: HazeStyle,
) {
    val colors = MaterialTheme.colorScheme
    val plateModifier = Modifier
        .align(Alignment.TopCenter)
        .fillMaxWidth()
        .height(glassTopBarStatusInset() + GlassTopBarContentHeight)
    if (hazeState != null) {
        val colorFilter = rememberGlassColorFilter()
        val filterPaint = remember(colorFilter) { Paint().apply { this.colorFilter = colorFilter } }
        // Blur, then saturation, then the tint: the effect draws into its own layer, so the colour
        // pass wraps it.
        Box(modifier = plateModifier.glassColorFilter(filterPaint)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .hazeEffect(state = hazeState, style = blurStyle) {
                        alpha = sheetAlpha.coerceIn(0f, 1f)
                    },
            )
        }
    } else {
        Box(
            modifier = plateModifier.background(
                colors.surface.copy(alpha = GlassFallbackTintAlpha * sheetAlpha),
            ),
        )
    }
}

/**
 * Vertical ramp of [surface] used as the sheet tint: thick behind the status bar and thinned towards
 * the bottom of the row, so the bar does not read as one flat slab.
 */
private fun glassTintBrush(surface: Color): Brush = Brush.verticalGradient(
    0f to surface.copy(alpha = GlassBlurTintAlphaTop),
    GlassTintMidStop to surface.copy(alpha = GlassBlurTintAlphaMid),
    1f to surface.copy(alpha = GlassBlurTintAlphaBottom),
)

/** The blur style is pure theme data, so it is shared by every glass surface. */
@Composable
private fun rememberGlassBlurStyle(): HazeStyle {
    val surface = MaterialTheme.colorScheme.surface
    return remember(surface) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = listOf(HazeTint(glassTintBrush(surface))),
            blurRadius = GlassBlurRadius,
            noiseFactor = 0f,
            fallbackTint = HazeTint(surface.copy(alpha = GlassFallbackTintAlpha)),
        )
    }
}

/**
 * Whether [GlassTopBar] should be showing its sheet: true as soon as the page has left its very top.
 *
 * The bar hides at rest because the content it would frost only arrives with the first scroll — a
 * sheet over an untouched page is an edge with nothing on either side of it.
 */
fun LazyListState.hasScrolled(): Boolean =
    firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

/** [hasScrolled] for a grid-backed page. */
fun LazyGridState.hasScrolled(): Boolean =
    firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

/** [hasScrolled] for a page whose hero and body share a plain vertical scroller. */
fun ScrollState.hasScrolled(): Boolean = value > 0
