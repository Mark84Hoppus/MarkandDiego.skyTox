// SPDX-FileCopyrightText: 2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.av

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.max

private const val TAG = "AudioCapture"

private fun intToChannel(channels: Int) = when (channels) {
    1 -> AudioFormat.CHANNEL_IN_MONO
    else -> AudioFormat.CHANNEL_IN_STEREO
}

// The permission linting doesn't work very well unless you sprinkle
// ContextCompat.checkSelfPermission in way too many places. It doesn't even
// agree with results from ActivityResultContracts.RequestPermission, requiring
// an extra permission check in there as well.
@SuppressLint("MissingPermission")
private fun findAudioRecord(sampleRate: Int, channels: Int): AudioRecord? {
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val channelConfig = intToChannel(channels)

    val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    if (minBufferSize <= 0) {
        return null
    }
    val bufferSize = max(minBufferSize, sampleRate * channels * 2 / 5)

    // TRIfA prefers the low-latency voice recognition preset and falls back to voice communication.
    // Some Xiaomi phones still reject one of these sources, so keep MIC/DEFAULT as safety nets.
    val audioSources = arrayOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT,
    )
    for (audioSource in audioSources) {
        val recorder = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "Failed to initialize audio record $audioSource")
            recorder.release()
            continue
        }
        Log.i(TAG, "Using audio source $audioSource session=${recorder.audioSessionId} bufferSize=$bufferSize")
        return recorder
    }

    return null
}

class AudioCapture private constructor(
    private val sampleRate: Int,
    private val channels: Int,
    private val frameLengthMs: Int,
    private val audioRecord: AudioRecord,
) {
    private val audioEffects = AudioEffects.create(audioRecord.audioSessionId)

    fun start() = audioRecord.startRecording()
    fun stop() = audioRecord.stop()
    fun release() {
        audioEffects.release()
        audioRecord.release()
    }
    fun read(): ShortArray? {
        val samples = ShortArray((sampleRate * channels * frameLengthMs / 1000.0).toInt())
        var totalRead = 0
        while (totalRead < samples.size) {
            val read = audioRecord.read(samples, totalRead, samples.size - totalRead)
            if (read <= 0) {
                Log.w(TAG, "AudioRecord read failed: $read")
                return null
            }
            totalRead += read
        }
        return samples
    }

    companion object {
        fun create(sampleRate: Int, channels: Int, frameLengthMs: Int): AudioCapture? {
            val audioRecord = findAudioRecord(sampleRate, channels) ?: return null
            return AudioCapture(sampleRate, channels, frameLengthMs, audioRecord)
        }
    }
}

private class AudioEffects private constructor(
    private val echoCanceler: AcousticEchoCanceler?,
    private val noiseSuppressor: NoiseSuppressor?,
    private val gainControl: AutomaticGainControl?,
) {
    fun release() {
        echoCanceler?.release()
        noiseSuppressor?.release()
        gainControl?.release()
    }

    companion object {
        fun create(audioSessionId: Int): AudioEffects {
            val echoCanceler = runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
                } else {
                    null
                }
            }.getOrNull()
            val noiseSuppressor = runCatching {
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
                } else {
                    null
                }
            }.getOrNull()
            val gainControl = runCatching {
                if (AutomaticGainControl.isAvailable()) {
                    AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
                } else {
                    null
                }
            }.getOrNull()
            Log.i(
                TAG,
                "Audio effects: aec=${echoCanceler != null} ns=${noiseSuppressor != null} agc=${gainControl != null}",
            )
            return AudioEffects(echoCanceler, noiseSuppressor, gainControl)
        }
    }
}
