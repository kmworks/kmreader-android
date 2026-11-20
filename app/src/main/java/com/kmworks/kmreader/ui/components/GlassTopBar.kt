package com.kmworks.kmreader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Scroll distance (in content pixels) over which the glass fades in once content slides under the bar. */
private val GlassFadeDistance: Dp = 48.dp

/** Backdrop blur radius behind the bar. */
private val GlassBlurRadius: Dp = 24.dp

/** How much of the surface color the blurred backdrop is tinted with (real blur path). */
private const val GlassBlurTintAlpha = 0.34f

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
 * floating over it. A hairline separator appears together with the sheet.
 *
 * Pass a [HazeState] that the scrolling page feeds through `Modifier.hazeSource` to get a real
 * backdrop blur (Haze). Without a state the bar keeps the older translucent-surface look: blur needs
 * Haze to capture the backdrop, because a Compose graphics layer cannot both be replayed and blurred.
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
    val blurStyle = remember(colors.surface) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tint = HazeTint(colors.surface.copy(alpha = GlassBlurTintAlpha)),
            blurRadius = GlassBlurRadius,
            noiseFactor = 0f,
            fallbackTint = HazeTint(colors.surface.copy(alpha = GlassFallbackTintAlpha)),
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (hazeState != null) {
            // Blur (or the tint fallback below Android 12) fades with the scroll offset.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .hazeEffect(state = hazeState, style = blurStyle) {
                        alpha = glass
                    },
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                colors.surface.copy(alpha = 0.97f * glass),
                                colors.surface.copy(alpha = 0.90f * glass),
                            ),
                        ),
                    ),
            )
        }
        // Hairline that appears only once content is actually behind the bar.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.outlineVariant.copy(alpha = 0.55f * glass)),
        )
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
