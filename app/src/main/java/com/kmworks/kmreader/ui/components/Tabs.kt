package com.kmworks.kmreader.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.kmworks.kmreader.R

/** Five phone tabs: dashboard / library / lists / downloads / search. */
enum class AppTab(@param:StringRes val labelRes: Int, val icon: ImageVector) {
    Home(R.string.tab_dashboard, Icons.Filled.Dashboard),
    Library(R.string.tab_library, Icons.Filled.AutoStories),
    Lists(R.string.tab_lists, Icons.Filled.Bookmarks),
    Offline(R.string.tab_downloads, Icons.Filled.Download),
    Browse(R.string.tab_search, Icons.Filled.Search),
}
