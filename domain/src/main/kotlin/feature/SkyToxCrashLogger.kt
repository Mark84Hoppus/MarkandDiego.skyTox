// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SkyToxCrashLogger {
    private const val MAX_RECENT_EVENTS = 200
    private const val MAX_DIAGNOSTIC_BYTES = 512 * 1024

    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    private val recentEvents = ArrayDeque<String>()
    private var installed = false
    private var logsDir: File? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context, versionName: String, versionCode: Int) {
        synchronized(lock) {
            if (installed) {
                return
            }
            installed = true
            logsDir = prepareLogsDir(context)
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                writeCrash(thread, throwable, versionName, versionCode)
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun event(message: String) {
        val line = "${humanTime()} EVENT $message"
        synchronized(lock) {
            remember(line)
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        val line = "${humanTime()} ERROR $message"
        synchronized(lock) {
            remember(line)
            if (throwable != null) {
                remember(stackTrace(throwable))
            }
        }
    }

    fun diagnostic(message: String) {
        val line = "${humanTime()} DIAG $message"
        synchronized(lock) {
            remember(line)
            val dir = logsDir ?: return
            val file = File(dir, "push-keepalive-diagnostics.log")
            trimDiagnosticLog(file)
            appendLine(file, line)
        }
    }

    private fun writeCrash(
        thread: Thread,
        throwable: Throwable,
        versionName: String,
        versionCode: Int,
    ) {
        synchronized(lock) {
            val file = File(logsDir, "crash-${timestamp()}-pid${Process.myPid()}.log")
            writeHeader("skyTox crash log", versionName, versionCode, file)
            appendLine(file, "thread=${thread.name}")
            appendLine(file, "")
            appendLine(file, "recent_events:")
            recentEvents.forEach { appendLine(file, it) }
            appendLine(file, "")
            appendLine(file, "stacktrace:")
            appendLine(file, stackTrace(throwable))
        }
    }

    private fun prepareLogsDir(context: Context): File {
        val publicDir = SkyToxPublicFolders.logsDir
        if (publicDir.mkdirs() || publicDir.isDirectory) {
            return publicDir
        }

        val fallback = File(context.filesDir, "skyTox logs")
        fallback.mkdirs()
        return fallback
    }

    private fun writeHeader(title: String, versionName: String, versionCode: Int, file: File?) {
        appendLine(file, title)
        appendLine(file, "created=${humanTime()}")
        appendLine(file, "version=$versionName ($versionCode)")
        appendLine(file, "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        appendLine(file, "device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine(file, "abi=${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine(file, "pid=${Process.myPid()}")
        appendLine(file, "")
    }

    private fun remember(line: String) {
        recentEvents.addLast(line)
        while (recentEvents.size > MAX_RECENT_EVENTS) {
            recentEvents.removeFirst()
        }
    }

    private fun appendLine(file: File?, text: String) {
        runCatching {
            if (file == null) {
                return
            }
            file.parentFile?.mkdirs()
            file.appendText("$text\n")
        }
    }

    private fun trimDiagnosticLog(file: File) {
        runCatching {
            if (!file.exists() || file.length() <= MAX_DIAGNOSTIC_BYTES) {
                return
            }
            file.writeText("")
        }
    }

    private fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun timestamp(): String = dateFormat.format(Date())

    private fun humanTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
