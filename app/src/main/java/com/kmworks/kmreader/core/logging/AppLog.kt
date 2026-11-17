package com.kmworks.kmreader.core.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Log level (kept as the visual filter dimension of LogsScreen). */
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
 * App log bus: in-memory ring buffer (read live by LogsScreen) + persistence to filesDir/logs/app.log.
 * Global object (mirrors the global reachability of iOS AppLogger); the file sink is installed by LogInitProvider.
 */
object AppLog {

    private const val CAPACITY = 2000
    private const val MAX_FILE_BYTES = 512L * 1024L
    /** Bytes of the previous session's log tail read back at startup. */
    private const val TAIL_LOAD_BYTES = 96 * 1024

    /** app.log line format: yyyy-MM-dd HH:mm:ss.SSS LEVEL [tag] message. */
    private val LINE_REGEX =
        Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) (\w+) \[(.*?)] (.*)$""")

    private val buffer = ArrayDeque<AppLogEntry>(CAPACITY)
    private val lock = Any()

    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = _entries.asStateFlow()

    @Volatile
    private var logFile: File? = null

    private val fileWriter = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-log-writer").apply { isDaemon = true }
    }

    /** Install the file output (called by LogInitProvider at app startup). */
    fun install(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, "app.log")
        trimIfNeeded(file)
        logFile = file
        loadPreviousTail(file)
        i("AppLog", "log file ready: ${file.absolutePath}")
    }

    /**
     * Read the previous session's log tail back into the in-memory buffer: after a crash (fatal writes go
     * straight to the file) and restart, the logs page can show the crash stack directly; otherwise the
     * in-memory entries would be lost with the process.
     */
    private fun loadPreviousTail(file: File) {
        if (!file.isFile) return
        runCatching {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return
            val tailBytes = if (bytes.size > TAIL_LOAD_BYTES) {
                bytes.copyOfRange(bytes.size - TAIL_LOAD_BYTES, bytes.size)
            } else {
                bytes
            }
            var text = String(tailBytes, Charsets.UTF_8)
            // The truncation point may land mid-line; drop the first incomplete line
            if (bytes.size > TAIL_LOAD_BYTES) text = text.substringAfter('\n', "")
            val parsed = mutableListOf<AppLogEntry>()
            text.lineSequence().forEach { line ->
                val match = LINE_REGEX.matchEntire(line)
                if (match != null) {
                    val millis = runCatching {
                        LocalDateTime.parse(
                            match.groupValues[1],
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                        ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                    parsed += AppLogEntry(
                        atMillis = millis,
                        level = runCatching { AppLogLevel.valueOf(match.groupValues[2]) }
                            .getOrDefault(AppLogLevel.INFO),
                        tag = match.groupValues[3],
                        message = match.groupValues[4],
                    )
                } else if (parsed.isNotEmpty() && line.isNotEmpty()) {
                    // Multi-line messages such as stack traces: continuation lines merge into the previous entry
                    val last = parsed.removeLast()
                    parsed += last.copy(message = last.message + "\n" + line)
                }
            }
            if (parsed.isEmpty()) return
            val seeded = parsed.takeLast(CAPACITY / 2)
            synchronized(lock) {
                seeded.forEach {
                    if (buffer.size >= CAPACITY) buffer.removeFirst()
                    buffer.addLast(it)
                }
                _entries.value = buffer.toList()
            }
        }
    }

    fun d(tag: String, message: String) = append(AppLogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = append(AppLogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = append(AppLogLevel.WARN, tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) = append(
        AppLogLevel.ERROR,
        tag,
        if (error != null) "$message (${error.javaClass.simpleName}: ${error.message})" else message,
    )

    /** The current log file (when it exists and is installed), for share/export. */
    fun shareableFile(): File? = logFile?.takeIf { it.isFile }

    /**
     * Crash-level logging: besides the regular buffer/logcat, writes synchronously to the file (bypassing
     * the async queue), guaranteeing the log is on disk before the process is reclaimed. Used only by the
     * uncaught-exception handler.
     */
    fun fatal(tag: String, message: String) {
        append(AppLogLevel.ERROR, tag, message)
        val file = logFile ?: return
        runCatching {
            trimIfNeeded(file)
            file.appendText(
                AppLogEntry(System.currentTimeMillis(), AppLogLevel.ERROR, tag, message).line + "\n",
            )
        }
    }

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
