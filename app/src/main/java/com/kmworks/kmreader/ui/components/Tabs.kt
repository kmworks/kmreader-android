package com.kmworks.kmreader.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

/** 手机端五个标签：仪表盘 / 书库 / 列表 / 下载 / 搜索。 */
enum class AppTab(val label: String, val icon: ImageVector) {
    Home("仪表盘", Icons.Filled.Dashboard),
    Library("书库", Icons.Filled.AutoStories),
    Lists("列表", Icons.Filled.Bookmarks),
    Offline("下载", Icons.Filled.Download),
    Browse("搜索", Icons.Filled.Search),
}
