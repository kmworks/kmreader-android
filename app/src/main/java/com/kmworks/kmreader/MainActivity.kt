package com.kmworks.kmreader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            KMReaderTheme {
                // 浏览卡片显示选项是应用级 UI 设置，在根部注入一次，卡片组件直接消费。
                val prefs: SettingsPrefsViewModel = hiltViewModel()
                val cardDisplay by prefs.cardDisplay.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalCardDisplay provides cardDisplay) {
                    AppRoot()
                }
            }
        }
    }
}
