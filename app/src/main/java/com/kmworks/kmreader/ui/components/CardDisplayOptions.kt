package com.kmworks.kmreader.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 浏览卡片显示选项，对应 KMReader SettingsBrowseView 的 AppStorage 项。
 *
 * 由 AppRoot 按 [com.kmworks.kmreader.core.settings.AppSettingsStore] 的持久化设置注入，
 * 卡片组件从这里读取，所以设置页的开关真实影响浏览/列表/仪表盘的卡片渲染。
 */
data class CardDisplayOptions(
    val coverOnly: Boolean = false,
    val textOverlay: Boolean = false,
    val showBookSeriesTitle: Boolean = true,
    val preserveAspectRatio: Boolean = true,
    val showShadow: Boolean = true,
    val showUnreadIndicator: Boolean = true,
    val showProgressBar: Boolean = true,
    val blurUnreadCovers: Boolean = false,
)

val LocalCardDisplay = staticCompositionLocalOf { CardDisplayOptions() }
