package com.kmworks.kmreader.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R

/** Theme options (stored value to display label resource). */
private val ThemeOptions = listOf(
    "system" to R.string.appearance_theme_system,
    "light" to R.string.appearance_theme_light,
    "dark" to R.string.appearance_theme_dark,
)

/** App icon options (stored value to display label resource; mirrors iOS AppIconOption). */
private val AppIconOptions = listOf(
    "primary" to R.string.appearance_icon_default,
    "classic" to R.string.appearance_icon_classic,
    "legacy" to R.string.appearance_icon_legacy,
)

/** Appearance settings (mirrors SettingsAppearanceView + AppIconOption). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    prefsViewModel: SettingsPrefsViewModel = hiltViewModel(),
) {
    // Theme/true black/app icon are persisted via AppSettingsStore (theme and true black get client-settings cloud sync automatically)
    val themeMode by prefsViewModel.themeMode.collectAsStateWithLifecycle()
    val trueBlack by prefsViewModel.trueBlackDark.collectAsStateWithLifecycle()
    val appIcon by prefsViewModel.appIcon.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance_title)) },
                navigationIcon = SettingsBackIcon {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                AppearanceSectionTitle(stringResource(R.string.appearance_theme))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeOptions.forEach { option ->
                        FilterChip(
                            selected = themeMode == option.first,
                            onClick = { prefsViewModel.setThemeMode(option.first) },
                            label = { Text(stringResource(option.second)) },
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.appearance_true_black_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.appearance_true_black_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = trueBlack,
                        onCheckedChange = { prefsViewModel.setTrueBlackDark(it) },
                    )
                }
            }
            item {
                AppearanceSectionTitle(stringResource(R.string.appearance_app_icon))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Mirrors AppIconOption: primary / classic / legacy
                    AppIconOptions.forEach { (value, label) ->
                        AppIconCell(
                            value = value,
                            label = label,
                            selected = appIcon == value,
                            onClick = { prefsViewModel.setAppIcon(value) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Icon preview mirroring iOS AppIconPreview: the real launcher icon foreground glyph on its background fill.
 * primary/legacy use a white/dark-gray background; classic uses a blue gradient (iOS collapses it to solid dark gray in dark mode).
 */
@Composable
private fun AppIconCell(value: String, @StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val foreground = when (value) {
        "classic" -> R.mipmap.ic_launcher_classic_foreground
        "legacy" -> R.mipmap.ic_launcher_legacy_foreground
        else -> R.mipmap.ic_launcher_foreground
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (value == "classic" && !dark) {
                        Modifier.background(
                            Brush.verticalGradient(
                                // iOS appIconBackgroundClassicTop/Bottom
                                listOf(Color(0xFF275CC9), Color(0xFF0088FF)),
                            ),
                        )
                    } else {
                        Modifier.background(if (dark) Color(0xFF131316) else Color.White)
                    },
                )
                .then(
                    if (selected) {
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(14.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            if (selected) {
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppearanceSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
