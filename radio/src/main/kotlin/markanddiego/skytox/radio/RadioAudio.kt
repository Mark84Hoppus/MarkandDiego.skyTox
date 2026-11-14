// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

internal data class RadioAudioHealthSnapshot(
    val captureEpoch: Long,
    val captureState: RadioCaptureHealthState,
    val capturedFrames: Long,
    val encodedFrames: Long,
    val encodeErrorStreak: Int,
    val lastCaptureElapsed: Long,
    val lastEncodeElapsed: Long,
    val lastReceiveElapsed: Long,
    val lastDecodeElapsed: Long,
    val lastPlayedElapsed: Long,
)

internal class RadioAudio(
    private val onEncodedFrame: (ByteArray) -> Unit,
    private val diagnostics: RadioDiagnostics,
    private val onCaptureFailure: (captureEpoch: Long, stage: String, error: Throwable) -> Unit,
    private val onCaptureState: (captureEpoch: Long, state: RadioCaptureHealthState) -> Unit,
) {
    private val captureLock = Any()
    private val decoderLock = Any()
    private val recording = AtomicBoolean(false)
    private val captureEpoch = AtomicLong(0)
    @Volatile private var captureState = RadioCaptureHealthState.STOPPED
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    private val capturedFrames = AtomicLong(0)
    private val encodedFrames = AtomicLong(0)
    private val encodeErrorStreak = AtomicInteger(0)
    private val lastCaptureElapsed = AtomicLong(0)
    private val lastEncodeElapsed = AtomicLong(0)
    private val lastReceiveElapsed = AtomicLong(0)
    private val lastDecodeElapsed = AtomicLong(0)
    private val lastPlayedElapsed = AtomicLong(0)
    private val playbackQueue = LinkedBlockingQueue<ShortArray>(MAX_PLAYBACK_FRAMES)
    private val playing = AtomicBoolean(false)
    @Volatile private var playerThread: Thread? = null
    private var opusDecoder = RadioOpusDecoder()
    private val sampleRate get() = if (RadioFeatureFlags.CODEC_OPUS_ENABLED) RadioOpusEncoder.SAMPLE_RATE else LEGACY_SAMPLE_RATE
    private val frameSamples get() = if (RadioFeatureFlags.CODEC_OPUS_ENABLED) RadioOpusEncoder.FRAME_SAMPLES else LEGACY_FRAME_SAMPLES

    /** Returns a capture epoch, or null while an older capture is still alive/stopping. */
    @SuppressLint("MissingPermission")
    fun startCapture(): Long? = synchronized(captureLock) {
        if (captureThread?.isAlive == true || captureState !in setOf(
                RadioCaptureHealthState.STOPPED, RadioCaptureHealthState.FAILED,
            )
        ) {
            diagnostics.count("audio_capture_start_rejected_busy")
            return@synchronized null
        }
        val epoch = captureEpoch.incrementAndGet()
        setCaptureState(epoch, RadioCaptureHealthState.STARTING)
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioRecord = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, frameSamples * 4))
        } catch (error: Exception) {
            failCapture(epoch, "record_create", error)
            return@synchronized null
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            failCapture(epoch, "record_init", IllegalStateException("AudioRecord not initialized"))
            return@synchronized null
        }
        recorder = audioRecord
        recording.set(true)
        val worker = thread(start = false, name = "skytox-radio-capture-$epoch", isDaemon = true) {
            captureLoop(epoch, audioRecord)
        }
        captureThread = worker
        worker.start()
        epoch
    }

    private fun captureLoop(epoch: Long, audioRecord: AudioRecord) {
        val frame = ShortArray(frameSamples)
        // Encoder ownership never crosses this worker boundary.
        val encoder = if (RadioFeatureFlags.CODEC_OPUS_ENABLED) RadioOpusEncoder() else null
        try {
            audioRecord.startRecording()
            setCaptureState(epoch, RadioCaptureHealthState.RUNNING)
            diagnostics.event("audio_capture_started epoch=$epoch")
            while (recording.get() && captureEpoch.get() == epoch) {
                var offset = 0
                while (offset < frame.size && recording.get() && captureEpoch.get() == epoch) {
                    val read = audioRecord.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                    if (read <= 0) {
                        if (recording.get()) failCapture(epoch, "record_read_$read",
                            IllegalStateException("AudioRecord.read=$read"))
                        break
                    }
                    offset += read
                }
                if (offset != frame.size || !recording.get() || captureEpoch.get() != epoch) continue
                capturedFrames.incrementAndGet()
                lastCaptureElapsed.set(SystemClock.elapsedRealtime())
                diagnostics.count("audio_frames_captured")
                val encoded = try {
                    if (encoder != null) encoder.encode(frame) else ImaAdpcm.encode(frame)
                } catch (error: AssertionError) {
                    handleEncodeFailure(epoch, error); break
                } catch (error: Exception) {
                    handleEncodeFailure(epoch, error); break
                }
                encodeErrorStreak.set(0)
                encodedFrames.incrementAndGet()
                lastEncodeElapsed.set(SystemClock.elapsedRealtime())
                diagnostics.count("audio_frames_encoded")
                try {
                    onEncodedFrame(encoded)
                } catch (error: Exception) {
                    failCapture(epoch, "outbound_enqueue", error); break
                }
            }
        } catch (error: AssertionError) {
            failCapture(epoch, "capture_assertion", error)
        } catch (error: Exception) {
            if (recording.get()) failCapture(epoch, "capture_runtime", error)
        } finally {
            frame.fill(0)
            recording.compareAndSet(true, false)
            runCatching { audioRecord.stop() }
            audioRecord.release()
            synchronized(captureLock) {
                if (recorder === audioRecord) recorder = null
                if (captureThread === Thread.currentThread()) captureThread = null
                if (captureEpoch.get() == epoch && captureState != RadioCaptureHealthState.FAILED) {
                    setCaptureState(epoch, RadioCaptureHealthState.STOPPED)
                }
            }
            diagnostics.event("audio_capture_stopped epoch=$epoch")
        }
    }

    private fun handleEncodeFailure(epoch: Long, error: Throwable) {
        val streak = encodeErrorStreak.incrementAndGet()
        diagnostics.count("audio_encode_errors")
        if (streak == 1) diagnostics.exception("audio_encode_error epoch=$epoch", error)
        else diagnostics.count("audio_encode_error_suppressed")
        failCapture(epoch, "opus_encode", error, traceAlreadyWritten = true)
    }

    fun stopCapture(joinTimeoutMs: Long = CAPTURE_JOIN_MS): Boolean {
        val epoch: Long
        val worker: Thread?
        val currentRecorder: AudioRecord?
        synchronized(captureLock) {
            epoch = captureEpoch.get()
            worker = captureThread
            currentRecorder = recorder
            if (worker == null) {
                recording.set(false)
                if (captureState != RadioCaptureHealthState.FAILED) setCaptureState(epoch, RadioCaptureHealthState.STOPPED)
                return true
            }
            setCaptureState(epoch, RadioCaptureHealthState.STOP_REQUESTED)
            recording.set(false)
        }
        runCatching { currentRecorder?.stop() }
        if (worker !== Thread.currentThread()) runCatching { worker?.join(joinTimeoutMs) }
        val stopped = worker == null || worker === Thread.currentThread() || worker?.isAlive == false
        if (!stopped) {
            setCaptureState(epoch, RadioCaptureHealthState.FAILED)
            diagnostics.count("audio_capture_stop_timeout")
            diagnostics.event("audio_capture_stop_timeout epoch=$epoch")
        }
        return stopped
    }

    fun beginIncomingTransmission() {
        diagnostics.event("audio_incoming_begin")
        ensurePlayer()
        clearPlaybackQueue()
        synchronized(decoderLock) { if (RadioFeatureFlags.CODEC_OPUS_ENABLED) opusDecoder = RadioOpusDecoder() }
        if (RadioFeatureFlags.STATIC_NOISE_ENABLED) playbackQueue.offer(staticNoise())
    }

    fun playEncodedFrame(data: ByteArray) {
        ensurePlayer()
        lastReceiveElapsed.set(SystemClock.elapsedRealtime())
        diagnostics.count("audio_frames_decode")
        val decoded = try {
            synchronized(decoderLock) {
                if (RadioFeatureFlags.CODEC_OPUS_ENABLED) opusDecoder.decode(data)
                else ImaAdpcm.decode(data, LEGACY_FRAME_SAMPLES)
            }
        } catch (error: AssertionError) {
            diagnostics.count("audio_decode_errors"); diagnostics.exception("audio_decode_error", error); return
        } catch (error: Exception) {
            diagnostics.count("audio_decode_errors"); diagnostics.exception("audio_decode_error", error); return
        }
        lastDecodeElapsed.set(SystemClock.elapsedRealtime())
        if (!playbackQueue.offer(decoded)) {
            diagnostics.count("audio_playback_queue_drops")
            playbackQueue.poll()?.fill(0)
            playbackQueue.offer(decoded)
        }
    }

    fun endIncomingTransmission() {
        diagnostics.event("audio_incoming_end queue_frames=${playbackQueue.size}")
        // TALK_END describes the network stream, not the speaker buffer. Keep already
        // decoded speech queued behind the 500 ms radio hiss and let it drain normally.
        synchronized(decoderLock) { if (RadioFeatureFlags.CODEC_OPUS_ENABLED) opusDecoder = RadioOpusDecoder() }
    }

    fun stop() {
        diagnostics.event("audio_stop queue_frames=${playbackQueue.size}")
        stopCapture()
        playing.set(false)
        playerThread?.interrupt()
        playerThread?.let { runCatching { it.join(750) } }
        playerThread = null
        clearPlaybackQueue()
    }

    fun healthSnapshot() = RadioAudioHealthSnapshot(
        captureEpoch.get(), captureState, capturedFrames.get(), encodedFrames.get(),
        encodeErrorStreak.get(), lastCaptureElapsed.get(), lastEncodeElapsed.get(),
        lastReceiveElapsed.get(), lastDecodeElapsed.get(), lastPlayedElapsed.get())

    fun diagnosticState(): String =
        "playback_q_frames=${playbackQueue.size} capture_state=$captureState capture_epoch=${captureEpoch.get()} recording=${recording.get()} playing=${playing.get()}"

    private fun ensurePlayer() {
        if (!playing.compareAndSet(false, true)) return
        playerThread = thread(name = "skytox-radio-playback", isDaemon = true) {
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, frameSamples * 8)).setTransferMode(AudioTrack.MODE_STREAM).build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                diagnostics.event("audio_track_init_failed"); track.release(); playing.set(false); return@thread
            }
            try {
                track.play(); diagnostics.event("audio_track_started")
                while (playing.get()) {
                    val samples = playbackQueue.take()
                    var offset = 0
                    while (offset < samples.size && playing.get()) {
                        val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) {
                            diagnostics.count("audio_track_write_errors")
                            break
                        }
                        offset += written
                    }
                    samples.fill(0)
                    if (offset > 0) {
                        lastPlayedElapsed.set(SystemClock.elapsedRealtime()); diagnostics.count("audio_frames_played")
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { track.stop() }; track.release(); playing.set(false)
                if (playerThread === Thread.currentThread()) playerThread = null
                diagnostics.event("audio_track_stopped")
            }
        }
    }

    private fun failCapture(epoch: Long, stage: String, error: Throwable, traceAlreadyWritten: Boolean = false) {
        if (captureEpoch.get() != epoch) return
        setCaptureState(epoch, RadioCaptureHealthState.FAILED)
        recording.set(false)
        if (!traceAlreadyWritten) diagnostics.exception("audio_capture_failure epoch=$epoch stage=$stage", error)
        onCaptureFailure(epoch, stage, error)
    }

    private fun setCaptureState(epoch: Long, state: RadioCaptureHealthState) {
        if (epoch != captureEpoch.get()) return
        captureState = state
        onCaptureState(epoch, state)
    }

    private fun clearPlaybackQueue() { while (true) (playbackQueue.poll() ?: break).fill(0) }
    private fun staticNoise(): ShortArray = ShortArray(sampleRate / 2) { Random.nextInt(-1800, 1801).toShort() }

    companion object {
        const val LEGACY_SAMPLE_RATE = 8_000
        const val LEGACY_FRAME_SAMPLES = 240
        // 500 ms of hiss plus enough buffered Opus speech to survive scheduler jitter.
        private const val MAX_PLAYBACK_FRAMES = 32
        private const val CAPTURE_JOIN_MS = 1_250L
    }
}
