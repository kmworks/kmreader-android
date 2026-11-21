package com.kmworks.kmreader.ui.settings

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
