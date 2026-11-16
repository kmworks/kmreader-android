package com.kmworks.kmreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// KMReader 原作强调色：浅色纯黑 / 深色纯白（单色方案），无品牌彩色、无渐变。
private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E0E4),
    onPrimaryContainer = Color(0xFF111111),
    secondaryContainer = Color(0xFFECECEF),
    onSecondaryContainer = Color(0xFF1A1A1C),
    background = Color(0xFFF7F7F8),
    onBackground = Color(0xFF1A1A1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1C),
    surfaceVariant = Color(0xFFECECEF),
    onSurfaceVariant = Color(0xFF5B5B60),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F0F2),
    outline = Color(0xFFD6D6DA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF2E2E33),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF232326),
    onSecondaryContainer = Color(0xFFE8E8EA),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8E8EA),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFE8E8EA),
    surfaceVariant = Color(0xFF232326),
    onSurfaceVariant = Color(0xFFA5A5AA),
    surfaceContainer = Color(0xFF141416),
    surfaceContainerHigh = Color(0xFF1C1C1F),
    outline = Color(0xFF2E2E33),
)

// 非纯黑深色（纯黑开关关闭时）：背景/容器上移为深灰，其余与纯黑方案一致。
private val DarkSoftColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF2E2E33),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF232326),
    onSecondaryContainer = Color(0xFFE8E8EA),
    background = Color(0xFF141416),
    onBackground = Color(0xFFE8E8EA),
    surface = Color(0xFF1C1C1F),
    onSurface = Color(0xFFE8E8EA),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Color(0xFFA5A5AA),
    surfaceContainer = Color(0xFF1C1C1F),
    surfaceContainerHigh = Color(0xFF242428),
    outline = Color(0xFF34343A),
)

@Composable
fun KMReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    trueBlack: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = when {
            !darkTheme -> LightColors
            trueBlack -> DarkColors
            else -> DarkSoftColors
        },
        content = content,
    )
}
