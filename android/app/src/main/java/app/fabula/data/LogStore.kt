package app.fabula.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simple rolling-file logger that the user can turn on via Settings ->
 * Diagnose. When the flag is off, all writes are no-ops, so leaving the
 * code paths in place is cheap. When on, warn/error entries are appended
 * to `filesDir/logs/fabula.log`; once that file exceeds [MaxFileBytes]
 * it's rotated to `fabula.log.1` (overwriting any previous backup).
 *
 * The store is kept in sync with [ServerPreferences.diagnosticsEnabled]
 * via a snapshot variable that the toggle UI updates through
 * [setEnabled]. Doing it this way avoids a `runBlocking` collect on
 * every log call (HTTP error path is hot enough that we don't want to
 * suspend or block).
 */
class LogStore(context: Context) {

    private val logsDir: File = File(context.filesDir, "logs").apply { mkdirs() }
    private val logFile: File = File(logsDir, FILE_NAME)
    private val backupFile: File = File(logsDir, "$FILE_NAME.1")
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.GERMAN)
    private val writeLock = Any()

    @Volatile
    private var enabled: Boolean = false

    private val _byteCount = MutableStateFlow(currentLogSize())
    /** Approximate size of the live log file. Lets the Settings UI show a
     *  hint and decide whether the share button is meaningful. */
    val byteCount: StateFlow<Long> = _byteCount.asStateFlow()

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun i(tag: String, msg: String) = append("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = append("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = append("E", tag, msg, t)

    fun clear() {
        synchronized(writeLock) {
            runCatching { logFile.delete() }
            runCatching { backupFile.delete() }
            _byteCount.value = 0L
        }
    }

    /** Returns the live log file -- caller is responsible for granting a
     *  ContentUri to share apps via FileProvider. */
    fun fileForSharing(): File = logFile

    private fun append(level: String, tag: String, msg: String, t: Throwable?) {
        if (!enabled) return
        synchronized(writeLock) {
            try {
                rotateIfNeeded()
                logFile.appendText(formatEntry(level, tag, msg, t))
                _byteCount.value = logFile.length()
            } catch (io: Throwable) {
                // Never let logging crash the app. Surface to logcat only.
                Log.w("LogStore", "Failed to append log entry", io)
            }
        }
    }

    private fun formatEntry(level: String, tag: String, msg: String, t: Throwable?): String {
        val builder = StringBuilder()
        builder.append(timestamp.format(Date()))
            .append(' ').append(level)
            .append(' ').append(tag)
            .append(": ").append(msg)
            .append('\n')
        if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            builder.append(sw.toString())
            if (!sw.toString().endsWith('\n')) builder.append('\n')
        }
        return builder.toString()
    }

    private fun rotateIfNeeded() {
        if (logFile.length() < MaxFileBytes) return
        runCatching { backupFile.delete() }
        runCatching { logFile.renameTo(backupFile) }
    }

    private fun currentLogSize(): Long = runCatching { logFile.length() }.getOrDefault(0L)

    companion object {
        private const val FILE_NAME = "fabula.log"
        private const val MaxFileBytes = 512L * 1024L
    }
}
