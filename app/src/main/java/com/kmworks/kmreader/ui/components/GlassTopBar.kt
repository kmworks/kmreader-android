package com.kmworks.kmreader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
 * The bar itself takes no layout space (it is drawn on top of the page), so pages reserve this
 * amount of content padding instead and let the rest scroll underneath the glass.
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

/** Scroll distance (in content pixels) over which the glass fades in once content slides under the bar. */
private val GlassFadeDistance: Dp = 48.dp

/** Backdrop blur radius behind the bar. */
private val GlassBlurRadius: Dp = 40.dp

/**
 * Tint alphas of the real blur path: the sheet is thickest behind the status bar and thins out
 * towards the bottom edge.
 *
 * The ramp is what makes the bar read as smoked glass instead of a flat band: a single alpha lets
 * the blurred shapes stay recognisable everywhere (they only get darker), while a descending one
 * keeps the top opaque enough to sit on and lets the hue of the page glow through lower down.
 * Keep the middle stop at 0.5 or above — below that the blurred headlines behind the bar become
 * legible again, which defeats the point of a frosted bar.
 */
private const val GlassBlurTintAlphaTop = 0.80f
private const val GlassBlurTintAlphaMid = 0.68f
private const val GlassBlurTintAlphaBottom = 0.55f

/** Position (fraction of the bar's height) where the tint has thinned to [GlassBlurTintAlphaMid]. */
private const val GlassTintMidStop = 0.55f

/**
 * Fraction of the bar's height where the glass starts dissolving into the page.
 *
 * A rectangular blur layer ends on a razor-sharp edge, which cuts whatever artwork is sliding under
 * it in half and is what makes a frosted bar look like a pasted-on band. Fading the effect out over
 * the last stretch lets the sheet melt into the content instead.
 */
private const val GlassEdgeFadeStart = 0.80f

/** Opaque-white to transparent mask that drives [GlassEdgeFadeStart]. */
private val GlassEdgeFadeMask: Brush = Brush.verticalGradient(
    0f to Color.White,
    GlassEdgeFadeStart to Color.White,
    0.94f to Color.White.copy(alpha = 0.55f),
    1f to Color.White.copy(alpha = 0f),
)

/** Sheet alpha used when the platform cannot blur (Android 12+ has RenderEffect, older ones do not). */
private const val GlassFallbackTintAlpha = 0.92f

/**
 * How opaque the glass should be: 0 when the page is at its top, 1 once the first row has scrolled
 * fully under the bar.
 *
 * Derived from the lazy list/grid scroll state instead of nested-scroll deltas so it stays correct
 * when the list is restored or programmatically scrolled.
 */
@Composable
fun rememberGlassBarProgress(
    firstVisibleItemIndex: () -> Int,
    firstVisibleItemScrollOffset: () -> Int,
    fadeDistance: Dp = GlassFadeDistance,
): Float {
    val fadePx = with(LocalDensity.current) { fadeDistance.toPx() }
    // Read the latest lambdas: the caller's scroller can switch (list/grid) without recreating the state
    val index by rememberUpdatedState(firstVisibleItemIndex)
    val offset by rememberUpdatedState(firstVisibleItemScrollOffset)
    return remember(fadePx) {
        derivedStateOf {
            if (index() > 0) {
                1f
            } else {
                (offset() / fadePx).coerceIn(0f, 1f)
            }
        }
    }.value
}

/**
 * Frosted, edge-to-edge top bar for the tab roots.
 *
 * It draws over the page content instead of reserving layout space, so pages scroll underneath it and
 * the glass only fades in with [progress] — at rest the page looks full-bleed with just the title
 * floating over it. The sheet has no hard lower edge: it is darkest behind the status bar, thins out
 * downwards and dissolves into the page over its last stretch, so scrolling artwork slides under a
 * smoked panel rather than under a pasted-on band with a visible seam.
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
 *   [glassTopBarTopPadding] as its top content padding.
 */
@Composable
fun GlassTopBar(
    title: String,
    progress: Float,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val glass by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180),
        label = "glassTopBarAlpha",
    )
    val colors = MaterialTheme.colorScheme

    Box(modifier = modifier.fillMaxWidth()) {
        GlassPlate(glass = glass, hazeState = hazeState, blurStyle = rememberGlassBlurStyle())
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

/** Blur (or the pre-Android 12 tint fallback), filling the bar and dissolving at its lower edge. */
@Composable
private fun BoxScope.GlassPlate(
    glass: Float,
    hazeState: HazeState?,
    blurStyle: HazeStyle,
) {
    val colors = MaterialTheme.colorScheme
    if (hazeState != null) {
        // Blur (or the tint fallback below Android 12) fades with the scroll offset.
        Box(
            modifier = Modifier
                .matchParentSize()
                .hazeEffect(state = hazeState, style = blurStyle) {
                    // The layer is only partially transparent while the bar fades in, and a partial
                    // layer shows the sharp, unblurred backdrop at full strength underneath. Ramp it
                    // up faster than the scroll offset so the backdrop is fully frosted within the
                    // first part of the fade.
                    alpha = (glass * 2f).coerceIn(0f, 1f)
                    // Melt the sheet into the page instead of ending on a hard edge that cuts the
                    // artwork scrolling under the bar in half.
                    mask = GlassEdgeFadeMask
                },
        )
    } else {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to colors.surface.copy(alpha = 0.97f * glass),
                        GlassEdgeFadeStart to colors.surface.copy(alpha = 0.90f * glass),
                        0.94f to colors.surface.copy(alpha = 0.55f * glass),
                        1f to colors.surface.copy(alpha = 0f * glass),
                    ),
                ),
        )
    }
}

/** Vertical ramp of [surface] used as the sheet tint: thick behind the status bar, thinner below. */
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
