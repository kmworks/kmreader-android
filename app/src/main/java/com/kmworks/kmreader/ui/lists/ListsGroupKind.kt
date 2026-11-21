package com.kmworks.kmreader.ui.lists

import androidx.annotation.StringRes
import com.kmworks.kmreader.R

/**
 * The three Lists-tab groups.
 *
 * The tab renders each kind as one horizontal strip, and a strip header opens that same kind as a
 * full page. The page is a route, so its identity has to be stable across process death and locale
 * changes: the kind (not the localized label) is what a route carries.
 */
enum class ListsGroupKind(@StringRes val labelRes: Int) {
    Collections(R.string.lists_group_collections),
    ReadLists(R.string.lists_group_readlists),
    SmartLists(R.string.lists_group_smartlists),
}
