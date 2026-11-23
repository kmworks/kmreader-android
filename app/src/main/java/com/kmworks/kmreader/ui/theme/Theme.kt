package com.kmworks.kmreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// KMReader accent colors from the original: pure black in light mode / pure white in dark mode (monochrome scheme), with no brand color and no gradients.
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
    surfaceContainerLow = Color(0xFFF5F5F6),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F0F2),
    // One monochrome step past High for cards that float on a High surface (e.g. group cards
    // inside AppModalDialog). In the light theme elevation reads darker, in dark themes lighter.
    surfaceContainerHighest = Color(0xFFE8E8EB),
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
    surfaceContainerLow = Color(0xFF0E0E10),
    surfaceContainer = Color(0xFF141416),
    surfaceContainerHigh = Color(0xFF1C1C1F),
    surfaceContainerHighest = Color(0xFF242428),
    outline = Color(0xFF2E2E33),
)

// Non-pure-black dark mode (when the pure-black switch is off): background/containers move up to dark gray, everything else matches the pure-black scheme.
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
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = Color(0xFF1C1C1F),
    surfaceContainerHigh = Color(0xFF242428),
    surfaceContainerHighest = Color(0xFF2C2C31),
    outline = Color(0xFF34343A),
)

@Composable
fun KMReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    trueBlack: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        !darkTheme -> LightColors
        trueBlack -> DarkColors
        else -> DarkSoftColors
    }
    MaterialTheme(colorScheme = colorScheme) {
        // Material3 only derives LocalContentColor from a Surface/Scaffold. This app paints the window background
        // itself (edge-to-edge root + pages that own no Scaffold), so any Text without an explicit color used to
        // fall back to Compose's default black content color and disappear against a dark background. Painting the
        // background here and providing its matching content color makes every page - including future ones - get
        // readable text for free instead of repeating `color = onBackground` at each call site.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colorScheme.background,
            contentColor = colorScheme.onBackground,
            content = content,
        )
    }
}
