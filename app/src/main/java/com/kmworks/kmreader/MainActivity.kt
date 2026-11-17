package com.kmworks.kmreader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.ui.AppRoot
import com.kmworks.kmreader.ui.components.LocalCardDisplay
import com.kmworks.kmreader.ui.settings.SettingsPrefsViewModel
import com.kmworks.kmreader.ui.theme.KMReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Theme/true-black/browse cards are app-wide UI settings; observe the
            // persisted flows at the root and apply them immediately.
            val prefs: SettingsPrefsViewModel = hiltViewModel()
            val themeMode by prefs.themeMode.collectAsStateWithLifecycle()
            val trueBlack by prefs.trueBlackDark.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            KMReaderTheme(darkTheme = darkTheme, trueBlack = trueBlack) {
                // Browse card display options are app-wide UI settings; provide once
                // at the root so card components consume them directly.
                val cardDisplay by prefs.cardDisplay.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalCardDisplay provides cardDisplay) {
                    AppRoot()
                }
            }
        }
    }
}
