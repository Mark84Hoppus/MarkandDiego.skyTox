// SPDX-FileCopyrightText: 2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.av

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private fun intToChannel(channels: Int) = when (channels) {
    1 -> AudioFormat.CHANNEL_OUT_MONO
    else -> AudioFormat.CHANNEL_OUT_STEREO
}

class AudioPlayer(sampleRate: Int, channels: Int) {
    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ShortArray>(MAX_QUEUED_FRAMES)
    private var playbackThread: Thread? = null
    private val stableBufferSize = sampleRate * channels * 2 / 5
    private val minBufferSize =
        AudioTrack.getMinBufferSize(sampleRate, intToChannel(channels), AudioFormat.ENCODING_PCM_16BIT)
    private val bufferSize = max(minBufferSize, stableBufferSize)
    private val audioTrack = if (Build.VERSION.SDK_INT < 23) {
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
            .build()
    }

    fun buffer(data: ShortArray) {
        val frame = data.copyOf()
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
        }
    }

    private fun write(data: ShortArray) {
        var written = 0
        while (written < data.size) {
            val result = audioTrack.write(data, written, data.size - written)
            if (result <= 0) {
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
            while (running.get()) {
                val frame = try {
                    queue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue
                write(frame)
            }
        }, "skyTox-audio-player").also { it.start() }
    }

    fun stop() {
        running.set(false)
        playbackThread?.interrupt()
        playbackThread = null
        queue.clear()
        audioTrack.pause()
        audioTrack.flush()
    }

    fun release() {
        stop()
        audioTrack.release()
    }

    private companion object {
        private const val MAX_QUEUED_FRAMES = 12
    }
}
