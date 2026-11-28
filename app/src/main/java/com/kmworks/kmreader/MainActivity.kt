package com.kmworks.kmreader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
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
import com.kmworks.kmreader.ui.reader.ReaderVolumeKeyDispatcher
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

    /**
     * Volume-key page turning for the readers whose page is an Android view (the EPUB WebView and
     * the PDF PdfView): those views are focusable and take the window focus on touch, so a Compose
     * preview-key handler never sees the press. The readers register their handler while open and
     * a handled volume key is consumed here, before the view hierarchy — and the system — can act
     * on it, so the volume stays untouched instead of being raised and lowered again.
     */
    // RestrictedApi: ComponentActivity.dispatchKeyEvent is library-group restricted, but the
    // interception must happen before the view hierarchy sees the key, which is what this
    // override is the only hook for.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ReaderVolumeKeyDispatcher.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
