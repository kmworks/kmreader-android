package com.kmworks.kmreader.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The top-level destinations of the settings hub.
 *
 * The hub rows only name the destination they open, so the host decides how it is presented: a pushed
 * full-screen page on a phone, or the detail pane next to the hub on a tablet.
 */
enum class SettingsPage {
    Appearance,
    Browse,
    Dashboard,
    ReaderDefaults,
    Trackers,
    Stats,
    CustomFonts,
    Cache,
    Network,
    Sse,
    Logs,
    Account,
    Server,
    About,
    Licenses,
}

/** True while a settings page is drawn as the detail pane of the tablet split. */
internal val LocalSettingsPane = staticCompositionLocalOf { false }

/**
 * The back arrow of a settings page.
 *
 * The root of the detail pane has no arrow: the hub's own arrow leaves settings, a hub row switches the
 * pane, and system back clears the pane first, so a second arrow would only repeat those. A page that is not
 * the pane root (see [paneRoot]) keeps its arrow, because it was opened from inside the pane and the hub
 * cannot take the user back out of it.
 */
@Composable
internal fun settingsBackIcon(
    paneRoot: Boolean = true,
    arrow: @Composable () -> Unit,
): @Composable () -> Unit {
    // Material3's TopAppBar takes a non-null navigation slot, so hiding the arrow means an empty one.
    if (paneRoot && LocalSettingsPane.current) return {}
    return arrow
}
