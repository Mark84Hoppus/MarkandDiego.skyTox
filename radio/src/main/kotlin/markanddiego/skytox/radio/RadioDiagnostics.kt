// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

internal class RadioDiagnostics(context: Context) {
    private val accepting = AtomicBoolean(true)
    private val startedAt = SystemClock.elapsedRealtime()
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val gauges = ConcurrentHashMap<String, AtomicLong>()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(512),
        { task -> Thread(task, "skytox-radio-log").apply { isDaemon = true } },
        { _, pool ->
            counters.computeIfAbsent("log_queue_overflow") { AtomicLong() }.incrementAndGet()
            if (!pool.isShutdown) pool.queue.poll()
        },
    )
    private val directory = prepareDirectory(context)
    private var part = 1
    private var file = File(directory, "radio-diagnostics-$stamp-part$part.log")
    private val notifyLatencies = LongArray(128)
    private var notifyLatencyCount = 0

    init {
        cleanupOldFiles()
        event("session_start android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}_${Build.MODEL} abi=${Build.SUPPORTED_ABIS.joinToString("|")}")
    }

    fun count(name: String, amount: Long = 1) {
        counters.computeIfAbsent(safe(name)) { AtomicLong() }.addAndGet(amount)
    }

    fun gauge(name: String, value: Long) {
        gauges.computeIfAbsent(safe(name)) { AtomicLong() }.set(value)
    }

    fun event(message: String) = enqueue("EVENT ${safe(message)}")

    fun exception(label: String, error: Throwable) {
        val trace = error.stackTraceToString().replace(Regex("[\\r\\n]+"), " | ").take(MAX_TRACE_CHARS)
        enqueue("EVENT ${safe(label)} type=${safe(error.javaClass.name)} trace=$trace")
    }

    @Synchronized
    fun serverNotifyLatency(elapsedMillis: Long) {
        notifyLatencies[notifyLatencyCount % notifyLatencies.size] = elapsedMillis
        notifyLatencyCount++
        gauge("notify_callback_latency_latest_ms", elapsedMillis)
        if (notifyLatencyCount % 32 == 0) {
            val size = minOf(notifyLatencyCount, notifyLatencies.size)
            val sorted = notifyLatencies.copyOf(size).sortedArray()
            gauge("notify_callback_latency_p50_ms", sorted[(size - 1) * 50 / 100])
            gauge("notify_callback_latency_p95_ms", sorted[(size - 1) * 95 / 100])
        }
    }

    fun stats(extra: String = "") {
        val counts = counters.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.get()}" }
        val current = gauges.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.get()}" }
        enqueue("STATS uptime_ms=${SystemClock.elapsedRealtime() - startedAt} $counts $current ${safe(extra)}")
    }

    fun close(extra: String = "") {
        if (!accepting.compareAndSet(true, false)) return
        append("STATS uptime_ms=${SystemClock.elapsedRealtime() - startedAt} ${safe(extra)}")
        append("EVENT session_stop")
        executor.shutdown()
        runCatching { executor.awaitTermination(1500, TimeUnit.MILLISECONDS) }
    }

    private fun enqueue(message: String) {
        if (accepting.get() && !executor.isShutdown) executor.execute { append(message) }
    }

    @Synchronized
    private fun append(message: String) {
        runCatching {
            if (file.length() >= MAX_FILE_BYTES) {
                part++
                file = File(directory, "radio-diagnostics-$stamp-part$part.log")
                cleanupOldFiles()
            }
            file.parentFile?.mkdirs()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$time $message\n", Charsets.UTF_8)
        }
    }

    private fun cleanupOldFiles() {
        runCatching {
            val files = directory.listFiles { value ->
                value.isFile && value.name.startsWith("radio-diagnostics-") && value.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() }.orEmpty()
            var deleted = 0
            var bytes = 0L
            files.drop(MAX_FILES - 1).forEach {
                val size = it.length()
                if (it.delete()) { deleted++; bytes += size }
            }
            if (deleted > 0) append("log_cleanup deleted=$deleted bytes=$bytes")
        }
    }

    @Suppress("DEPRECATION")
    private fun prepareDirectory(context: Context): File = directoryFor(context)

    private fun safe(value: String): String = value.replace(Regex("[\\r\\n]+"), " ").take(1000)

    companion object {
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private const val MAX_FILES = 8
        private const val MAX_TRACE_CHARS = 8_000

        @Suppress("DEPRECATION")
        private fun directoryFor(context: Context): File {
            val public = File(Environment.getExternalStorageDirectory(), "skyTox files/skyTox logs")
            if (public.mkdirs() || public.isDirectory) return public
            return File(context.filesDir, "skyTox logs").apply { mkdirs() }
        }
    }
}
