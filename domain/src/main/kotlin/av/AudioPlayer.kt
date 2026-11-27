// SPDX-FileCopyrightText: 2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.av

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import ltd.evilcorp.domain.feature.SkyToxCrashLogger

private fun intToChannel(channels: Int) = when (channels) {
    1 -> AudioFormat.CHANNEL_OUT_MONO
    else -> AudioFormat.CHANNEL_OUT_STEREO
}

class AudioPlayer(private val sampleRate: Int, private val channels: Int) {
    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ShortArray>(MAX_QUEUED_FRAMES)
    private var playbackThread: Thread? = null
    private val stableBufferSize = sampleRate * channels * 2 / 5
    private val minBufferSize =
        AudioTrack.getMinBufferSize(sampleRate, intToChannel(channels), AudioFormat.ENCODING_PCM_16BIT)
    private val bufferSize = max(minBufferSize, stableBufferSize)
    private val audioTrack = createAudioTrack()
    private var lastFrameSize = sampleRate * channels * 20 / 1000
    private var consecutiveUnderruns = 0
    private var queueDepthMin = Int.MAX_VALUE
    private var queueDepthMax = 0
    private var queueDepthTotal = 0L
    private var queueDepthSamples = 0L

    private fun buildAudioTrack(lowLatency: Boolean): AudioTrack = if (Build.VERSION.SDK_INT < 23) {
        // TODO(robinlinden): Verify that this works on old devices.
        @Suppress("DEPRECATION") // I can't find a non-deprecated alternative for lower SDK versions.
        AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            sampleRate,
            intToChannel(channels),
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM,
        )
    } else {
        AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(intToChannel(channels))
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .apply {
                if (lowLatency && Build.VERSION.SDK_INT >= 26) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()
    }

    private fun createAudioTrack(): AudioTrack {
        if (Build.VERSION.SDK_INT >= 26) {
            val lowLatency = runCatching { buildAudioTrack(true) }.getOrNull()
            if (lowLatency?.state == AudioTrack.STATE_INITIALIZED) return lowLatency
            lowLatency?.release()
        }
        return buildAudioTrack(false)
    }
    private var droppedFrames = 0L
    private var underruns = 0L
    private var writtenFrames = 0L
    private var lastDiagnosticAt = SystemClock.elapsedRealtime()
    private var needsPrebuffer = true
    private var needsRamp = true

    fun buffer(data: ShortArray) {
        val frame = data.copyOf()
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
            droppedFrames++
        }
        logDiagnostics()
    }

    private fun write(data: ShortArray) {
        var written = 0
        while (written < data.size) {
            val result = audioTrack.write(data, written, data.size - written)
            if (result <= 0) {
                SkyToxCrashLogger.av("audio.playback write_error=$result queued=${queue.size}")
                return
            }
            written += result
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        audioTrack.play()
        playbackThread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            while (running.get()) {
                if (needsPrebuffer && queue.size < PREBUFFER_FRAMES) {
                    try {
                        Thread.sleep(PREBUFFER_WAIT_MS)
                    } catch (_: InterruptedException) {
                        continue
                    }
                    continue
                }
                needsPrebuffer = false
                val frame = try {
                    queue.poll(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (frame == null) {
                    underruns++
                    consecutiveUnderruns++
                    needsRamp = true
                    write(ShortArray(lastFrameSize))
                    if (consecutiveUnderruns >= HARD_RESYNC_UNDERRUNS) needsPrebuffer = true
                    logDiagnostics()
                    continue
                }
                consecutiveUnderruns = 0
                lastFrameSize = frame.size
                if (needsRamp) {
                    applyRamp(frame)
                    needsRamp = false
                }
                write(frame)
                writtenFrames++
                logDiagnostics()
            }
        }, "skyTox-audio-player").also { it.start() }
    }

    fun stop() {
        running.set(false)
        val thread = playbackThread
        thread?.interrupt()
        if (thread !== Thread.currentThread()) runCatching { thread?.join(STOP_JOIN_MS) }
        playbackThread = null
        queue.clear()
        needsPrebuffer = true
        needsRamp = true
        audioTrack.pause()
        audioTrack.flush()
    }

    fun release() {
        stop()
        audioTrack.release()
    }

    private companion object {
        private const val MAX_QUEUED_FRAMES = 12
        private const val PREBUFFER_FRAMES = 2
        private const val PREBUFFER_WAIT_MS = 5L
        private const val FRAME_WAIT_MS = 30L
        private const val HARD_RESYNC_UNDERRUNS = 3
        private const val STOP_JOIN_MS = 300L
        private const val DIAGNOSTIC_INTERVAL_MS = 5_000L
        private const val RAMP_SAMPLES = 96
    }

    private fun applyRamp(frame: ShortArray) {
        val count = minOf(RAMP_SAMPLES, frame.size)
        for (index in 0 until count) {
            frame[index] = (frame[index] * (index + 1) / count).toShort()
        }
    }

    private fun logDiagnostics() {
        val depth = queue.size
        queueDepthMin = minOf(queueDepthMin, depth)
        queueDepthMax = maxOf(queueDepthMax, depth)
        queueDepthTotal += depth
        queueDepthSamples++
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagnosticAt < DIAGNOSTIC_INTERVAL_MS) return
        val averageDepth = if (queueDepthSamples > 0) queueDepthTotal / queueDepthSamples else 0
        val nativeUnderruns = if (Build.VERSION.SDK_INT >= 24) audioTrack.underrunCount else -1
        SkyToxCrashLogger.av(
            "audio.playback queued=${queue.size} written=$writtenFrames dropped=$droppedFrames " +
                "underruns=$underruns nativeUnderruns=$nativeUnderruns prebuffer=$needsPrebuffer " +
                "depthMin=${if (queueDepthMin == Int.MAX_VALUE) 0 else queueDepthMin} " +
                "depthMax=$queueDepthMax depthAvg=$averageDepth bufferBytes=$bufferSize",
        )
        queueDepthMin = Int.MAX_VALUE
        queueDepthMax = 0
        queueDepthTotal = 0
        queueDepthSamples = 0
        lastDiagnosticAt = now
    }
}
