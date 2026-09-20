// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import im.tox.tox4j.av.enums.ToxavFriendCallState
import im.tox.tox4j.av.exceptions.ToxavCallException
import im.tox.tox4j.av.exceptions.ToxavCallControlException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ltd.evilcorp.core.vo.Contact
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.av.AudioCapture
import ltd.evilcorp.domain.tox.Tox

sealed class CallState {
    object NotInCall : CallState()
    data class InCall(val publicKey: PublicKey, val startTime: Long) : CallState()
}

private const val TAG = "CallManager"

private const val AUDIO_CHANNELS = 1
private const val AUDIO_SAMPLING_RATE_HZ = 48_000
private const val AUDIO_SEND_INTERVAL_MS = 20
private const val INCOMING_VIDEO_FRAME_INTERVAL_MS = 50L
private const val OUTGOING_VIDEO_FRAME_INTERVAL_MS = 50L
private const val VIDEO_QUALITY_CHANGE_INTERVAL_MS = 20_000L
private const val VIDEO_UPGRADE_SUCCESS_FRAMES = 180
private const val VIDEO_DOWNGRADE_FAILURES = 3
private val VIDEO_HEIGHT_LADDER = listOf(144, 240, 360, 480)
private const val DEFAULT_VIDEO_HEIGHT_INDEX = 3
private const val INCOMING_VIDEO_YUV_BUFFER_COUNT = 4

data class IncomingVideoFrame(
    val publicKey: PublicKey,
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
)

private data class IncomingVideoBuffer(
    val width: Int,
    val height: Int,
    val chromaWidth: Int,
    val chromaHeight: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
)

@Singleton
class CallManager @Inject constructor(private val tox: Tox, private val scope: CoroutineScope, context: Context) {
    private val _inCall = MutableStateFlow<CallState>(CallState.NotInCall)
    val inCall: StateFlow<CallState> get() = _inCall

    private val _pendingCalls = MutableStateFlow<MutableSet<Contact>>(mutableSetOf())
    val pendingCalls: StateFlow<Set<Contact>> get() = _pendingCalls
    private val pendingVideoCalls = mutableSetOf<String>()

    private val _sendingAudio = MutableStateFlow(false)
    val sendingAudio: StateFlow<Boolean> get() = _sendingAudio
    private val _incomingVideoFrame = MutableStateFlow<IncomingVideoFrame?>(null)
    val incomingVideoFrame: StateFlow<IncomingVideoFrame?> get() = _incomingVideoFrame
    private val _localVideoEnabled = MutableStateFlow(false)
    val localVideoEnabled: StateFlow<Boolean> get() = _localVideoEnabled
    private val _remoteCallAccepted = MutableStateFlow(false)
    val remoteCallAccepted: StateFlow<Boolean> get() = _remoteCallAccepted
    private val _outgoingVideoHeight = MutableStateFlow(VIDEO_HEIGHT_LADDER[DEFAULT_VIDEO_HEIGHT_INDEX])
    val outgoingVideoHeight: StateFlow<Int> get() = _outgoingVideoHeight

    private val audioManager = ContextCompat.getSystemService(context, AudioManager::class.java)
    private var lastVideoFrameAtMs = 0L
    private var lastOutgoingVideoFrameAtMs = 0L
    private var videoHeightIndex = DEFAULT_VIDEO_HEIGHT_INDEX
    private var videoSuccessFrames = 0
    private var videoFailureFrames = 0
    private var lastVideoQualityChangeAtMs = 0L
    private var incomingVideoFramesForLog = 0
    private var incomingVideoDropsForLog = 0
    private var outgoingVideoFramesForLog = 0
    private var outgoingVideoDropsForLog = 0
    private var incomingVideoConvertMsForLog = 0L
    private var lastVideoDiagnosticAtMs = 0L
    private var incomingVideoFrameBudgetMs = INCOMING_VIDEO_FRAME_INTERVAL_MS
    private var incomingVideoYuvWidth = 0
    private var incomingVideoYuvHeight = 0
    private var incomingVideoYuvBufferIndex = 0
    private var incomingVideoYuvBuffers: Array<IncomingVideoBuffer>? = null
    @Volatile private var videoStopping = false

    fun addPendingCall(from: Contact, videoEnabled: Boolean = false) {
        val calls = mutableSetOf<Contact>().apply { addAll(_pendingCalls.value) }
        calls.addAll(_pendingCalls.value)
        if (videoEnabled) {
            pendingVideoCalls.add(from.publicKey)
        }
        if (calls.add(from)) {
            Log.i(TAG, "Added pending call ${from.publicKey.take(8)}, video=$videoEnabled")
            _pendingCalls.value = calls
        }
    }

    fun removePendingCall(pk: PublicKey) {
        val calls = mutableSetOf<Contact>().apply { addAll(_pendingCalls.value) }
        val removed = calls.firstOrNull { it.publicKey == pk.string() }
        if (removed != null) {
            Log.i(TAG, "Removed pending call ${pk.fingerprint()}")
            calls.remove(removed)
            _pendingCalls.value = calls
        }
        pendingVideoCalls.remove(pk.string())
    }

    fun startCall(publicKey: PublicKey, requestVideo: Boolean = false) {
        SkyToxCrashLogger.event(
            "call.start pk=${publicKey.fingerprint()} requestVideo=$requestVideo " +
                "pending=${pendingCalls.value.any { it.publicKey == publicKey.string() }}",
        )
        _remoteCallAccepted.value = false
        var answerVideoBitRate = if (requestVideo) videoBitRateForHeight(VIDEO_HEIGHT_LADDER[DEFAULT_VIDEO_HEIGHT_INDEX]) else 0
        try {
            if (pendingCalls.value.any { it.publicKey == publicKey.string() }) {
                answerVideoBitRate = if (pendingVideoCalls.contains(publicKey.string()) || requestVideo) {
                    videoBitRateForHeight(VIDEO_HEIGHT_LADDER[DEFAULT_VIDEO_HEIGHT_INDEX])
                } else {
                    0
                }
                tox.answerCall(publicKey, answerVideoBitRate)
                if (answerVideoBitRate > 0) {
                    runCatching { tox.showVideo(publicKey) }
                        .onFailure { Log.w(TAG, "Could not request incoming video: ${it.message}") }
                }
            } else {
                tox.startCall(publicKey, answerVideoBitRate)
            }
        } catch (e: ToxavCallException) {
            SkyToxCrashLogger.error("call.start failed pk=${publicKey.fingerprint()} code=${e.code()}", e)
            removePendingCall(publicKey)
            _inCall.value = CallState.NotInCall
            return
        } catch (e: Exception) {
            SkyToxCrashLogger.error("call.start failed pk=${publicKey.fingerprint()}", e)
            removePendingCall(publicKey)
            _inCall.value = CallState.NotInCall
            return
        }
        resetVideoQuality()
        videoStopping = false
        _inCall.value = CallState.InCall(publicKey, SystemClock.elapsedRealtime())
        _localVideoEnabled.value = answerVideoBitRate > 0
        SkyToxCrashLogger.event(
            "call.started pk=${publicKey.fingerprint()} localVideo=${_localVideoEnabled.value} bitrate=$answerVideoBitRate",
        )
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        removePendingCall(publicKey)
        if ((_incomingVideoFrame.value as IncomingVideoFrame?)?.publicKey == publicKey) {
            _incomingVideoFrame.value = null
        }
    }

    fun endCall(publicKey: PublicKey) {
        SkyToxCrashLogger.event("call.end.local pk=${publicKey.fingerprint()} state=${inCall.value}")
        stopVideoNegotiation(publicKey)
        finishCall(publicKey)
        removePendingCall(publicKey)

        try {
            tox.endCall(publicKey)
        } catch (e: ToxavCallControlException) {
            SkyToxCrashLogger.error("call.end.local toxav failed pk=${publicKey.fingerprint()} code=${e.code()}", e)
            if (e.code() != ToxavCallControlException.Code.FRIEND_NOT_IN_CALL) {
                Log.w(TAG, "Could not end call ${publicKey.fingerprint()}: ${e.code()}")
            }
        } catch (e: Exception) {
            SkyToxCrashLogger.error("call.end.local failed pk=${publicKey.fingerprint()}", e)
            Log.w(TAG, "Could not end call ${publicKey.fingerprint()}: ${e.message}")
        }
    }

    fun remoteCallEnded(publicKey: PublicKey) {
        SkyToxCrashLogger.event("call.end.remote pk=${publicKey.fingerprint()} state=${inCall.value}")
        stopVideoNegotiation(publicKey)
        finishCall(publicKey)
        removePendingCall(publicKey)
    }

    fun updateCallState(publicKey: PublicKey, callState: Set<ToxavFriendCallState>) {
        val state = inCall.value
        if (state !is CallState.InCall || state.publicKey != publicKey) {
            return
        }
        if (
            callState.contains(ToxavFriendCallState.ACCEPTING_A) ||
            callState.contains(ToxavFriendCallState.ACCEPTING_V) ||
            callState.contains(ToxavFriendCallState.SENDING_A) ||
            callState.contains(ToxavFriendCallState.SENDING_V)
        ) {
            _remoteCallAccepted.value = true
        }
    }

    private fun finishCall(publicKey: PublicKey) {
        SkyToxCrashLogger.event("call.finish pk=${publicKey.fingerprint()} state=${inCall.value}")
        videoStopping = true
        val state = inCall.value
        if (state is CallState.InCall && state.publicKey == publicKey) {
            audioManager?.mode = AudioManager.MODE_NORMAL
            _localVideoEnabled.value = false
            _remoteCallAccepted.value = false
            _incomingVideoFrame.value = null
            resetVideoQuality()
            _inCall.value = CallState.NotInCall
            SkyToxCrashLogger.event("call.finished pk=${publicKey.fingerprint()}")
        }
    }

    fun startSendingAudio(): Boolean {
        val to = (inCall.value as? CallState.InCall)?.publicKey ?: return false
        val recorder =
            AudioCapture.create(AUDIO_SAMPLING_RATE_HZ, AUDIO_CHANNELS, AUDIO_SEND_INTERVAL_MS) ?: return false
        startAudioSender(recorder, to)
        return true
    }

    fun stopSendingAudio() {
        _sendingAudio.value = false
    }

    fun receiveVideoFrame(
        publicKey: PublicKey,
        width: Int,
        height: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        yStride: Int,
        uStride: Int,
        vStride: Int,
    ) {
        if (videoStopping) {
            return
        }
        val state = inCall.value
        if (state !is CallState.InCall || state.publicKey != publicKey || width <= 0 || height <= 0) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastVideoFrameAtMs < incomingVideoFrameBudgetMs) {
            incomingVideoDropsForLog++
            logVideoDiagnostics(now)
            return
        }
        lastVideoFrameAtMs = now

        val convertStart = SystemClock.elapsedRealtime()
        runCatching {
            val buffer = nextIncomingYuvBuffer(width, height)
            copyPlane(y, yStride, width, height, buffer.y)
            copyPlane(u, uStride, buffer.chromaWidth, buffer.chromaHeight, buffer.u)
            copyPlane(v, vStride, buffer.chromaWidth, buffer.chromaHeight, buffer.v)
            buffer
        }.onSuccess { buffer ->
            incomingVideoFramesForLog++
            incomingVideoConvertMsForLog += SystemClock.elapsedRealtime() - convertStart
            logVideoDiagnostics(SystemClock.elapsedRealtime())
            if (inCall.value is CallState.InCall) {
                _incomingVideoFrame.value = IncomingVideoFrame(
                    publicKey = publicKey,
                    width = width,
                    height = height,
                    y = buffer.y,
                    u = buffer.u,
                    v = buffer.v,
                )
            }
        }.onFailure {
            Log.w(TAG, "Could not decode video frame: ${it.message}")
        }
    }

    fun sendVideoFrame(width: Int, height: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        val to = (inCall.value as? CallState.InCall)?.publicKey ?: return
        if (videoStopping || !localVideoEnabled.value || width <= 0 || height <= 0) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastOutgoingVideoFrameAtMs < OUTGOING_VIDEO_FRAME_INTERVAL_MS) {
            outgoingVideoDropsForLog++
            logVideoDiagnostics(now)
            return
        }
        lastOutgoingVideoFrameAtMs = now

        try {
            tox.sendVideo(to, width, height, y, u, v)
            outgoingVideoFramesForLog++
            logVideoDiagnostics(SystemClock.elapsedRealtime())
            recordVideoSendSuccess(to)
        } catch (e: Exception) {
            if (e.message?.contains("FRIEND_NOT_IN_CALL") == true) {
                Log.w(TAG, "Skipping early video frame before call is ready")
                return
            }
            SkyToxCrashLogger.error("video.send failed pk=${to.fingerprint()} size=${width}x$height", e)
            Log.w(TAG, "Could not send video frame: ${e.message}")
            recordVideoSendFailure(to)
        }
    }

    fun setLocalVideoEnabled(enabled: Boolean): Boolean {
        val to = (inCall.value as? CallState.InCall)?.publicKey ?: return false
        val bitrate = if (enabled) videoBitRateForHeight(_outgoingVideoHeight.value) else 0
        return try {
            SkyToxCrashLogger.event("video.local.set pk=${to.fingerprint()} enabled=$enabled bitrate=$bitrate")
            tox.setVideoBitRate(to, bitrate)
            if (enabled) {
                runCatching { tox.showVideo(to) }
                    .onFailure { Log.w(TAG, "Could not request video after enabling camera: ${it.message}") }
                resetVideoQuality()
            }
            _localVideoEnabled.value = enabled
            true
        } catch (e: Exception) {
            SkyToxCrashLogger.error("video.local.set failed pk=${to.fingerprint()} enabled=$enabled", e)
            Log.w(TAG, "Could not ${if (enabled) "enable" else "disable"} video bitrate: ${e.message}")
            if (!enabled) {
                _localVideoEnabled.value = false
            }
            false
        }
    }

    var speakerphoneOn: Boolean
        get() = audioManager?.isSpeakerphoneOn ?: false
        set(value) {
            audioManager?.isSpeakerphoneOn = value
        }

    private fun startAudioSender(recorder: AudioCapture, to: PublicKey) {
        scope.launch {
            recorder.start()
            _sendingAudio.value = true
            while (inCall.value is CallState.InCall && sendingAudio.value) {
                val start = System.currentTimeMillis()
                recorder.read()?.let { audioFrame ->
                    try {
                        tox.sendAudio(to, audioFrame, AUDIO_CHANNELS, AUDIO_SAMPLING_RATE_HZ)
                    } catch (e: Exception) {
                        Log.e(TAG, e.toString())
                    }
                }
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < AUDIO_SEND_INTERVAL_MS) {
                    delay(AUDIO_SEND_INTERVAL_MS - elapsed)
                }
            }
            recorder.stop()
            recorder.release()
            _sendingAudio.value = false
        }
    }

    private fun resetVideoQuality() {
        videoHeightIndex = DEFAULT_VIDEO_HEIGHT_INDEX
        videoSuccessFrames = 0
        videoFailureFrames = 0
        lastVideoQualityChangeAtMs = SystemClock.elapsedRealtime()
        _outgoingVideoHeight.value = VIDEO_HEIGHT_LADDER[videoHeightIndex]
    }

    private fun stopVideoNegotiation(to: PublicKey) {
        SkyToxCrashLogger.event("video.stopNegotiation pk=${to.fingerprint()}")
        videoStopping = true
        _localVideoEnabled.value = false
        _incomingVideoFrame.value = null
        runCatching { tox.setVideoBitRate(to, 0) }
            .onFailure {
                SkyToxCrashLogger.error("video.stopNegotiation bitrate0 failed pk=${to.fingerprint()}", it)
                Log.w(TAG, "Could not set video bitrate to zero: ${it.message}")
            }
        runCatching { tox.hideVideo(to) }
            .onFailure {
                SkyToxCrashLogger.error("video.stopNegotiation hide failed pk=${to.fingerprint()}", it)
                Log.w(TAG, "Could not hide video before ending call: ${it.message}")
            }
    }

    private fun recordVideoSendSuccess(to: PublicKey) {
        if (videoStopping) {
            return
        }
        videoSuccessFrames++
        videoFailureFrames = 0
        val now = SystemClock.elapsedRealtime()
        if (
            videoSuccessFrames >= VIDEO_UPGRADE_SUCCESS_FRAMES &&
            now - lastVideoQualityChangeAtMs >= VIDEO_QUALITY_CHANGE_INTERVAL_MS &&
            videoHeightIndex < VIDEO_HEIGHT_LADDER.lastIndex
        ) {
            videoHeightIndex++
            applyVideoQuality(to, now)
        }
    }

    private fun recordVideoSendFailure(to: PublicKey) {
        if (videoStopping) {
            return
        }
        videoFailureFrames++
        videoSuccessFrames = 0
        val now = SystemClock.elapsedRealtime()
        if (
            videoFailureFrames >= VIDEO_DOWNGRADE_FAILURES &&
            now - lastVideoQualityChangeAtMs >= VIDEO_QUALITY_CHANGE_INTERVAL_MS &&
            videoHeightIndex > 0
        ) {
            videoHeightIndex--
            applyVideoQuality(to, now)
        }
    }

    private fun applyVideoQuality(to: PublicKey, now: Long) {
        videoSuccessFrames = 0
        videoFailureFrames = 0
        lastVideoQualityChangeAtMs = now
        _outgoingVideoHeight.value = VIDEO_HEIGHT_LADDER[videoHeightIndex]
        runCatching { tox.setVideoBitRate(to, videoBitRateForHeight(VIDEO_HEIGHT_LADDER[videoHeightIndex])) }
            .onFailure {
                SkyToxCrashLogger.error(
                    "video.quality.apply failed pk=${to.fingerprint()} height=${VIDEO_HEIGHT_LADDER[videoHeightIndex]}",
                    it,
                )
                Log.w(TAG, "Could not apply adaptive video bitrate: ${it.message}")
            }
    }

    private fun videoBitRateForHeight(height: Int) =
        when {
            height <= 144 -> 180
            height <= 240 -> 450
            height <= 360 -> 900
            else -> 1600
        }

    private fun logVideoDiagnostics(now: Long) {
        if (lastVideoDiagnosticAtMs == 0L) {
            lastVideoDiagnosticAtMs = now
            return
        }
        val elapsed = now - lastVideoDiagnosticAtMs
        if (elapsed < 5_000L) {
            return
        }
        val avgConvertMs = if (incomingVideoFramesForLog > 0) {
            incomingVideoConvertMsForLog / incomingVideoFramesForLog
        } else {
            0L
        }
        incomingVideoFrameBudgetMs = when {
            avgConvertMs > 45L -> 100L
            avgConvertMs > 32L -> 83L
            avgConvertMs > 20L -> 66L
            else -> INCOMING_VIDEO_FRAME_INTERVAL_MS
        }
        SkyToxCrashLogger.av(
            "video elapsed=${elapsed}ms in=$incomingVideoFramesForLog inDrop=$incomingVideoDropsForLog " +
                "out=$outgoingVideoFramesForLog outDrop=$outgoingVideoDropsForLog avgPrepareMs=$avgConvertMs " +
                "height=${_outgoingVideoHeight.value} renderBudgetMs=$incomingVideoFrameBudgetMs",
        )
        incomingVideoFramesForLog = 0
        incomingVideoDropsForLog = 0
        outgoingVideoFramesForLog = 0
        outgoingVideoDropsForLog = 0
        incomingVideoConvertMsForLog = 0L
        lastVideoDiagnosticAtMs = now
    }

    private fun nextIncomingYuvBuffer(width: Int, height: Int): IncomingVideoBuffer {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val buffers = incomingVideoYuvBuffers
        if (
            buffers == null ||
            incomingVideoYuvWidth != width ||
            incomingVideoYuvHeight != height ||
            buffers.any {
                it.y.size != width * height ||
                    it.u.size != chromaWidth * chromaHeight ||
                    it.v.size != chromaWidth * chromaHeight
            }
        ) {
            incomingVideoYuvWidth = width
            incomingVideoYuvHeight = height
            incomingVideoYuvBufferIndex = 0
            return Array(INCOMING_VIDEO_YUV_BUFFER_COUNT) {
                IncomingVideoBuffer(
                    width = width,
                    height = height,
                    chromaWidth = chromaWidth,
                    chromaHeight = chromaHeight,
                    y = ByteArray(width * height),
                    u = ByteArray(chromaWidth * chromaHeight),
                    v = ByteArray(chromaWidth * chromaHeight),
                )
            }.also { incomingVideoYuvBuffers = it }[0]
        }

        incomingVideoYuvBufferIndex = (incomingVideoYuvBufferIndex + 1) % buffers.size
        return buffers[incomingVideoYuvBufferIndex]
    }

    private fun copyPlane(source: ByteArray, stride: Int, width: Int, height: Int, target: ByteArray) {
        for (row in 0 until height) {
            val sourceOffset = row * stride
            val targetOffset = row * width
            if (sourceOffset >= source.size || targetOffset >= target.size) {
                break
            }
            val count = minOf(width, source.size - sourceOffset, target.size - targetOffset)
            if (count > 0) {
                source.copyInto(target, targetOffset, sourceOffset, sourceOffset + count)
            }
        }
    }

}
