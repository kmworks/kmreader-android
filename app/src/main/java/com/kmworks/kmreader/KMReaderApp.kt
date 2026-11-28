package com.kmworks.kmreader

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class KMReaderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The removed native PDF reader used to keep whole-book copies under filesDir/pdf. Nothing
        // reads that directory any more (downloaded PDFs live in the download store, which the
        // Offline screen owns), so drop it once at startup instead of leaving the space behind.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { File(filesDir, "pdf").deleteRecursively() }
        }
    }
}
