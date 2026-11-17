package com.kmworks.kmreader.core.logging

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import java.io.File
import com.kmworks.kmreader.core.download.AutoDownloadUnreadTrigger
import com.kmworks.kmreader.core.sse.SseService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LogInitEntryPoint {
    fun sseService(): SseService
    fun appScope(): CoroutineScope
    fun autoDownloadUnreadTrigger(): AutoDownloadUnreadTrigger
}

/**
 * Self-initializes at startup (registered in the manifest, runs as soon as the app process is created):
 * installs the log file output and wires SSE events/connection state into AppLog.
 */
class LogInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        AppLog.install(context.applicationContext)
        // Uncaught exceptions go to the app log (a stack survives the crash, visible on the in-app logs page/export — helps locating crashes)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                AppLog.fatal(
                    "Crash",
                    "uncaught on ${thread.name}: ${Log.getStackTraceString(error)}",
                )
            }
            previousHandler?.uncaughtException(thread, error)
        }
        // The Java-layer uncaught-exception handler cannot catch native crashes (SIGSEGV/SIGABRT/native OOM):
        // read the last exit reason and tombstone back from the system ExitInfo so native crashes also show up on the in-app logs page
        reportHistoricalCrashes(context.applicationContext)
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                LogInitEntryPoint::class.java,
            )
            val sse = entryPoint.sseService()
            // Eagerly instantiate the auto-download-unread trigger (it subscribes to SSE events for good inside appScope)
            entryPoint.autoDownloadUnreadTrigger()
            entryPoint.appScope().launch {
                sse.connectionState.collect { state ->
                    AppLog.i("SSE", "connection -> $state")
                }
            }
            entryPoint.appScope().launch {
                sse.events.collect { event ->
                    AppLog.d("SSE", "${event.name}: ${event.data.take(200)}")
                }
            }
        }.onFailure { AppLog.w("AppLog", "SSE log hook failed: ${it.message}") }
        return true
    }

    /**
     * Read back the system-recorded historical exit reasons (ApplicationExitInfo): Java/Kotlin crashes are
     * covered by the uncaught handler, but native crashes (graphics stack / native OOM) bypass the Java layer
     * entirely — only this API sees them.
     * The tombstone trace is truncated to the first 16KB (crash signal, backtrace head). Already-reported
     * entries are deduplicated by timestamp.
     */
    private fun reportHistoricalCrashes(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            val marker = File(context.filesDir, "logs/last_exit_report.txt")
            val lastReported = marker.takeIf { it.isFile }
                ?.runCatching { readText().trim().toLongOrNull() }?.getOrNull() ?: 0L
            var newest = lastReported
            exits.filter { it.timestamp > lastReported }
                .sortedBy { it.timestamp }
                .forEach { info ->
                    newest = maxOf(newest, info.timestamp)
                    val reasonName = when (info.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java/Kotlin exception)"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native crash)"
                        ApplicationExitInfo.REASON_ANR -> "ANR (application not responding)"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed under memory pressure)"
                        else -> "reason=${info.reason}"
                    }
                    if (info.reason != ApplicationExitInfo.REASON_CRASH &&
                        info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE &&
                        info.reason != ApplicationExitInfo.REASON_ANR
                    ) {
                        return@forEach
                    }
                    val trace = runCatching {
                        info.traceInputStream?.bufferedReader()?.use { it.readText().take(16 * 1024) }
                    }.getOrNull()
                    AppLog.fatal(
                        "Crash",
                        "last exit: $reasonName ${info.description ?: ""}\n${trace ?: "(no trace; for Java crashes see the uncaught log above)"}",
                    )
                }
            if (newest > lastReported) {
                marker.parentFile?.mkdirs()
                marker.writeText(newest.toString())
            }
        }.onFailure { AppLog.w("AppLog", "exit-info read failed: ${it.message}") }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
