package com.kmworks.kmreader.core.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 日志级别（沿用 LogsScreen 的视觉过滤维度）。 */
enum class AppLogLevel(val label: String) {
    DEBUG("DEBUG"), INFO("INFO"), WARN("WARN"), ERROR("ERROR"),
}

data class AppLogEntry(
    val atMillis: Long,
    val level: AppLogLevel,
    val tag: String,
    val message: String,
) {
    val timeLabel: String
        get() = timeFormatter.format(Instant.ofEpochMilli(atMillis))

    val line: String
        get() = "${fileTimeFormatter.format(Instant.ofEpochMilli(atMillis))} ${level.label} [$tag] $message"

    private companion object {
        val timeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        val fileTimeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

/**
 * 应用日志总线：内存环形缓冲（LogsScreen 实时读）+ filesDir/logs/app.log 持久化。
 * 全局 object（对齐 iOS AppLogger 的全局可达性）；file sink 由 LogInitProvider 安装。
 */
object AppLog {

    private const val CAPACITY = 2000
    private const val MAX_FILE_BYTES = 512L * 1024L

    private val buffer = ArrayDeque<AppLogEntry>(CAPACITY)
    private val lock = Any()

    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = _entries.asStateFlow()

    @Volatile
    private var logFile: File? = null

    private val fileWriter = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-log-writer").apply { isDaemon = true }
    }

    /** 安装文件输出（应用启动时由 LogInitProvider 调用）。 */
    fun install(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, "app.log")
        trimIfNeeded(file)
        logFile = file
        i("AppLog", "log file ready: ${file.absolutePath}")
    }

    fun d(tag: String, message: String) = append(AppLogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = append(AppLogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = append(AppLogLevel.WARN, tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) = append(
        AppLogLevel.ERROR,
        tag,
        if (error != null) "$message (${error.javaClass.simpleName}: ${error.message})" else message,
    )

    /** 当前日志文件（存在且已安装时），供分享导出。 */
    fun shareableFile(): File? = logFile?.takeIf { it.isFile }

    private fun append(level: AppLogLevel, tag: String, message: String) {
        val entry = AppLogEntry(System.currentTimeMillis(), level, tag, message)
        val snapshot: List<AppLogEntry>
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            snapshot = buffer.toList()
        }
        _entries.value = snapshot
        when (level) {
            AppLogLevel.DEBUG -> Log.d(tag, message)
            AppLogLevel.INFO -> Log.i(tag, message)
            AppLogLevel.WARN -> Log.w(tag, message)
            AppLogLevel.ERROR -> Log.e(tag, message)
        }
        val file = logFile ?: return
        fileWriter.execute {
            runCatching {
                trimIfNeeded(file)
                file.appendText(entry.line + "\n")
            }
        }
    }

    private fun trimIfNeeded(file: File) {
        if (!file.isFile || file.length() <= MAX_FILE_BYTES) return
        runCatching {
            val keep = file.readBytes().copyOfRange(
                (file.length() - MAX_FILE_BYTES / 2).toInt().coerceAtLeast(0),
                file.length().toInt(),
            )
            file.writeBytes(keep)
        }
    }
}
