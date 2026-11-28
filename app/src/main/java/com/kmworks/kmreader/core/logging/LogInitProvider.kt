package com.kmworks.kmreader.core.logging

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.time.Instant
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
            val logged = runCatching {
                AppLog.fatal(
                    "Crash",
                    "uncaught on ${thread.name}: ${Log.getStackTraceString(error)}",
                )
            }.isSuccess
            if (!logged) {
                // Formatting/copying the trace allocates; under an OutOfMemoryError death that is what
                // fails first, which used to leave no evidence at all. Leave a minimal marker instead.
                runCatching {
                    AppLog.fatalMinimal(
                        "Crash",
                        "logging failed; ${error.javaClass.name}: ${error.message?.take(200)}",
                    )
                }
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
     * covered by the uncaught handler, but a process can also die without any Java exception reaching the
     * handler — a native crash, a low-memory kill, or a kill by the system/user. Those leave no trace in the
     * app log, so every recorded exit is reported here, whatever its reason: a missing log line after a
     * "crash" is itself the diagnostic (it rules the Java-layer path out).
     * The tombstone trace is truncated to the first 16KB (crash signal, backtrace head). Already-reported
     * entries are deduplicated by timestamp.
     */
    private fun reportHistoricalCrashes(context: Context) {
        // ApplicationExitInfo exists only on API 30+; older devices keep no system exit log to read.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            val marker = File(context.filesDir, "logs/last_exit_report.txt")
            val lastReported = marker.takeIf { it.isFile }
                ?.runCatching { readText().trim().toLongOrNull() }?.getOrNull() ?: 0L
            var newest = lastReported
            exits.filter { it.timestamp > lastReported }
                .sortedBy { it.timestamp }
                .forEach { info ->
                    newest = maxOf(newest, info.timestamp)
                    // Report every reason: silently skipping the non-crash ones is what hides an OS kill.
                    val trace = runCatching {
                        info.traceInputStream?.bufferedReader()?.use { it.readText().take(16 * 1024) }
                    }.getOrNull()
                    val when_ = runCatching { Instant.ofEpochMilli(info.timestamp).toString() }.getOrNull()
                    AppLog.fatal(
                        "Crash",
                        "last exit: ${describeExit(info)} " +
                            "process=${info.processName} at=$when_ " +
                            "description=${info.description ?: "-"}\n" +
                            (trace ?: "(no trace; a Java exception would have been logged by the uncaught handler)"),
                    )
                }
            if (newest > lastReported) {
                marker.parentFile?.mkdirs()
                marker.writeText(newest.toString())
            }
        }.onFailure { AppLog.w("AppLog", "exit-info read failed: ${it.message}") }
    }

    /** Human-readable name for an [ApplicationExitInfo] reason, including the ones that are not crashes. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun describeExit(info: ApplicationExitInfo): String = when (info.reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java/Kotlin exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native crash)"
        ApplicationExitInfo.REASON_ANR -> "ANR (application not responding)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by the OS under memory pressure)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed by signal ${info.status})"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF (System.exit)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED (swiped away)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason=${info.reason}"
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
