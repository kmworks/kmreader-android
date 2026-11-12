package com.kmworks.kmreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kmworks.kmreader.ui.AppRoot
import com.kmworks.kmreader.ui.theme.KMReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KMReaderTheme {
                AppRoot()
            }
        }
    }
}
