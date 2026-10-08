package com.paperboy.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Crash records and the "Save logs" report.
 *
 * Three sources, because no single one sees every kind of failure:
 *  - Java/Kotlin crashes and caught emulator errors are written to files/logs/ by
 *    [install]'s handler and by [record] (survives the process dying).
 *  - Android 11's exit records (ApplicationExitInfo) also cover native crashes in the
 *    emulator core and ANRs, which never reach a Java handler.
 *  - logcat: the app may read its own lines, including those of earlier runs.
 */
object CrashLog {
    const val TAG = "Paperboy"
    private const val MAX_FILES = 10

    private lateinit var dir: File

    /** Called once from [PaperboyApp.onCreate]. */
    fun install(context: Context) {
        dir = File(context.filesDir, "logs").also { it.mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record("Crash in thread \"${thread.name}\"", error)
            } catch (t: Throwable) {
                // never let logging hide the original crash
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Logs [error] and keeps it in a file for the next report. Safe from any thread. */
    @Synchronized
    fun record(what: String, error: Throwable?) {
        Log.e(TAG, what, error)
        if (!::dir.isInitialized) return
        val text = buildString {
            append(stamp(System.currentTimeMillis())).append("  ").append(what).append('\n')
            if (error != null) append(stackTrace(error))
        }
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(text)
        // Keep only the newest few.
        crashFiles().drop(MAX_FILES).forEach { it.delete() }
    }

    /** Newest first. */
    private fun crashFiles(): List<File> =
        (dir.listFiles { f -> f.name.startsWith("crash-") }?.toList() ?: emptyList())
            .sortedByDescending { it.lastModified() }

    private fun exitReasons(context: Context): List<ApplicationExitInfo> = try {
        val am = context.getSystemService(ActivityManager::class.java)
        am?.getHistoricalProcessExitReasons(context.packageName, 0, 10) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun isFailure(e: ApplicationExitInfo) = e.reason == ApplicationExitInfo.REASON_CRASH ||
        e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_ANR

    /** Time of the newest crash we know of, or 0. */
    fun lastProblemTime(context: Context): Long {
        val fileTime = if (::dir.isInitialized) crashFiles().firstOrNull()?.lastModified() ?: 0L else 0L
        val exitTime = exitReasons(context).filter { isFailure(it) }.maxOfOrNull { it.timestamp } ?: 0L
        return maxOf(fileTime, exitTime)
    }

    /** Everything useful for a bug report, as plain text. Slow (runs logcat): call off the UI thread. */
    fun report(context: Context): String = buildString {
        append("Paperboy log, ").append(stamp(System.currentTimeMillis())).append("\n\n")

        append("== Device ==\n")
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (e: Exception) {
            "?"
        }
        append("App: ").append(version).append('\n')
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.DEVICE).append(")\n")
        append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        append("ABIs: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        val dm = context.resources.displayMetrics
        append("Screen: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
            .append(" px, density ").append(dm.density).append('\n')
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all
        append("Settings: ").append(if (prefs.isEmpty()) "defaults" else prefs.toSortedMap().toString()).append("\n\n")

        append("== Recent app exits (newest first) ==\n")
        val exits = exitReasons(context)
        if (exits.isEmpty()) append("none recorded\n")
        for (e in exits) {
            append(stamp(e.timestamp)).append("  ").append(reasonName(e.reason))
            e.description?.let { append(": ").append(it) }
            append('\n')
            if (e.reason == ApplicationExitInfo.REASON_ANR) {
                // ANR traces are text; native crash traces on Android 11 are not available.
                val trace = try {
                    e.traceInputStream?.bufferedReader()?.use { it.readText() }
                } catch (t: Exception) {
                    null
                }
                if (trace != null) append(trace.take(20_000)).append('\n')
            }
        }
        append('\n')

        append("== Recorded crashes (newest first) ==\n")
        val files = if (::dir.isInitialized) crashFiles() else emptyList()
        if (files.isEmpty()) append("none\n")
        for (f in files) append(f.readText()).append('\n')
        append('\n')

        append("== logcat ==\n")
        append(logcat())
    }

    private fun logcat(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "4000")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(5, TimeUnit.SECONDS)
        out
    } catch (e: Exception) {
        "couldn't read logcat: $e\n"
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java/Kotlin)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH (native code)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by user"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: excessive resource use"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown ($r)"
    }

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
