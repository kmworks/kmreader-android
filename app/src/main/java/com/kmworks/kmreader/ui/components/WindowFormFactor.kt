package com.kmworks.kmreader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Narrowest window width (dp) from which the app switches to tablet-sized content (the Material
 * compact/medium breakpoint, same threshold the settings split layout uses).
 */
internal const val TabletContentWidthDp = 600

/**
 * True on phone-sized windows: the content then uses compact card widths and the normal browse
 * grid instead of the large one. Tablets and wide windows keep the large card/grid sizes.
 */
@Composable
internal fun isPhoneWindow(): Boolean =
    LocalConfiguration.current.smallestScreenWidthDp < TabletContentWidthDp

/** Browse-layout default for the current window: [phone] on phones, [tablet] everywhere else. */
@Composable
internal fun deviceBrowseLayoutDefault(
    tablet: BrowseLayout,
    phone: BrowseLayout = BrowseLayout.Grid,
): BrowseLayout = if (isPhoneWindow()) phone else tablet
