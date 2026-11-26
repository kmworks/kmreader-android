package com.kmworks.kmreader.ui.reader

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Window geometry the three readers need, already converted to CSS px.
 *
 * The readers are immersive, so `*IgnoringVisibility` is intentional: showing the bars for a
 * moment must not reflow the page. Values are CSS px (1 CSS px == 1 dp here), which is the unit
 * the EPUB page CSS consumes — handing it raw pixels made 24dp become 77 "CSS px" on a 3.2x
 * screen (see the inset contract in EpubThemeConfig).
 */
internal data class ReaderWindowInsetsCss(
    val cutoutTop: Int = 0,
    val cutoutBottom: Int = 0,
    val cutoutLeft: Int = 0,
    val cutoutRight: Int = 0,
    val statusBarTop: Int = 0,
    val navigationBarBottom: Int = 0,
) {

    /** True when the device actually has a notch / punch-hole on one of its edges. */
    val hasCutout: Boolean
        get() = cutoutTop > 0 || cutoutBottom > 0 || cutoutLeft > 0 || cutoutRight > 0
}

/** Reads the cutout, status-bar and navigation-bar insets of the window, in CSS px. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberReaderWindowInsetsCss(): ReaderWindowInsetsCss {
    val density = LocalDensity.current
    // getLeft/getRight require the layout direction; the values themselves stay PHYSICAL, so the
    // CSS gets "notch on the left edge" no matter how the page is mirrored.
    val layoutDirection = LocalLayoutDirection.current
    val cutout = WindowInsets.displayCutout
    val statusBars = WindowInsets.statusBarsIgnoringVisibility
    val navigationBars = WindowInsets.navigationBarsIgnoringVisibility
    fun cssPx(px: Int): Int = with(density) { px.toDp().value.roundToInt() }
    return ReaderWindowInsetsCss(
        cutoutTop = cssPx(cutout.getTop(density)),
        cutoutBottom = cssPx(cutout.getBottom(density)),
        cutoutLeft = cssPx(cutout.getLeft(density, layoutDirection)),
        cutoutRight = cssPx(cutout.getRight(density, layoutDirection)),
        statusBarTop = cssPx(statusBars.getTop(density)),
        navigationBarBottom = cssPx(navigationBars.getBottom(density)),
    )
}

/**
 * Keeps the screen awake while [enabled] (shared by DIVINA, EPUB and PDF).
 *
 * Called ONCE, by the navigation host, for the page the stack draws on top -- never by the readers
 * themselves. [com.kmworks.kmreader.ui.AppRoot] keeps departing and covered readers composed on the
 * same view, so a per-reader effect would let an outgoing reader clear the flag the incoming reader
 * just set, and let a reader covered by a library page keep the device awake. With one writer the
 * flag simply follows "is the page on top a reader, and is the shared preference on".
 *
 * The flag is set on the view rather than on the activity window so it dies with the host: leaving
 * the reader can never leave the device awake, and a WebView recreated mid-chapter inherits it.
 */
@Composable
internal fun KeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Padding that keeps page content out of the display cutout when [drawUnderCutout] is off.
 *
 * This is the whole cutout policy: the app is edge-to-edge and Android 15+ forces the window
 * into the cutout regardless of `layoutInDisplayCutoutMode`, so "hide from the notch" can only
 * be expressed as content padding. Off gives a band of the reader's own background; on gives the
 * page the full screen (Koharia's drawUnderCutout semantics).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun readerCutoutPadding(drawUnderCutout: Boolean): PaddingValues {
    if (drawUnderCutout) return PaddingValues(0.dp)
    val density = LocalDensity.current
    // PaddingValues resolves start/end against the layout direction, while the cutout is a
    // physical edge: swap the two sides under RTL so a notch on the left edge keeps padding the
    // left edge instead of the mirrored one.
    val layoutDirection = LocalLayoutDirection.current
    val cutout = WindowInsets.displayCutout
    val left = with(density) { cutout.getLeft(density, layoutDirection).toDp() }
    val right = with(density) { cutout.getRight(density, layoutDirection).toDp() }
    val rtl = layoutDirection == LayoutDirection.Rtl
    return PaddingValues(
        start = if (rtl) right else left,
        top = with(density) { cutout.getTop(density).toDp() },
        end = if (rtl) left else right,
        bottom = with(density) { cutout.getBottom(density).toDp() },
    )
}
