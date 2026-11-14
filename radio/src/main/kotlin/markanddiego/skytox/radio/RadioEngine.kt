// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import android.content.Context
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Hostless local Wi-Fi radio runtime. */
class RadioEngine(
    context: Context,
    private val nickname: String,
    private val listener: RadioListener,
) : RadioTransport.Listener {
    companion object {
        private const val CONTENTION_MS = 500L
        private const val PEER_TIMEOUT_MS = 15_000L
        private const val CHANNEL_TIMEOUT_MS = 8_000L
        private const val PACKET_CACHE_MS = 30_000L
        private const val INCOMING_STALE_MS = 1_800L
        private const val AUDIO_DRAIN_BEFORE_END_MS = 240L
        private const val DIAGNOSTIC_PERIOD_MS = 4_000L
        private const val RECOVERY_COOLDOWN_MS = 1_500L
        private const val AUDIO_V2_CAPABILITY = "opus-v2"
    }
    private data class SeenChannel(var rssi: Int, var seenAt: Long)
    private data class PeerState(
        var name: String,
        var rssi: Int,
        var seenAt: Long,
        var session: Int,
        var audioV2: Boolean,
    )
    private data class TalkBid(val node: Int, val talkId: Int, val priority: Long)

    private val nodeId = Random.nextInt()
    private val appContext = context.applicationContext
    private val session = Random.nextInt()
    private val sequence = AtomicInteger(Random.nextInt())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val diagnostics = RadioDiagnostics(context)
    private val transport: RadioTransport = RadioWifiLanTransport(appContext, nodeId, this, diagnostics)
    private val audio = RadioAudio(::sendAudio, diagnostics, ::onCaptureFailure, ::onCaptureState)
    private val healthMonitor = RadioHealthMonitorCore()
    private val stateLock = Any()
    private val sessionEpoch = Random.nextLong()
    private val channels = ConcurrentHashMap<Int, SeenChannel>()
    private val peers = ConcurrentHashMap<Int, PeerState>()
    private val seenPackets = ConcurrentHashMap<String, Long>()
    private val bids = ConcurrentHashMap<Int, TalkBid>()
    private var channel: Int? = null
    private var pttHeld = false
    private var localPending = false
    private var localSpeaking = false
    private var activeSpeaker: Int? = null
    private var lastIncomingAudioAt = 0L
    private var localTalkId = 0
    private var incomingTalkId: Int? = null
    private var audioFrameSequence = 0
    private var talkEpoch = 0L
    private var activeCaptureEpoch = 0L
    private var pttHealthState = RadioPttHealthState.IDLE
    private var pttStateSinceElapsed = SystemClock.elapsedRealtime()
    private var recoveryTalkEpoch = Long.MIN_VALUE
    private var heartbeat: Job? = null
    private var contentionJob: Job? = null
    private var talkEndJob: Job? = null
    private val closed = AtomicBoolean(false)

    val supported: Boolean get() = transport.supported

    fun start() {
        if (closed.get()) return
        diagnostics.event("engine_start supported=$supported transport=wifi_lan")
        transport.startDiscovery()
        heartbeat?.cancel()
        heartbeat = scope.launch {
            var lastPresenceAt = 0L
            while (isActive) {
                expireState()
                evaluateWatchdogs()
                if (now() - lastPresenceAt >= 2_000L) {
                    channel?.let { sendPresence() }
                    lastPresenceAt = now()
                }
                if (now() % DIAGNOSTIC_PERIOD_MS < 450L) {
                    diagnostics.gauge("peers", peers.size.toLong())
                    diagnostics.gauge("channels", channels.size.toLong())
                    diagnostics.gauge("seen_packets", seenPackets.size.toLong())
                    diagnostics.stats("${transport.diagnosticState()} ${audio.diagnosticState()} active=${activeSpeaker != null} speaking=$localSpeaking")
                }
                publish()
                delay(400)
            }
        }
        publish()
    }

    fun createChannel(): Int {
        val number = generateSequence { Random.nextInt(1000, 10_000) }
            .first { !channels.containsKey(it) }
        channel = number
        diagnostics.event("channel_create transport=wifi_lan")
        channels.remove(number)
        peers.clear()
        transport.prepareCreatedChannel(number)
        sendPresence()
        publish()
        return number
    }

    fun joinChannel(number: Int) {
        if (number !in 1000..9999 || channel != null) return
        channel = number
        diagnostics.event("channel_join")
        channels.remove(number)
        peers.clear()
        transport.joinChannel(number)
        sendPresence()
        publish()
    }

    fun addManualEndpoint(value: String): Boolean = transport.addManualEndpoint(value)

    fun refreshNetwork() = transport.refreshNetwork()

    fun leaveChannel() {
        diagnostics.event("channel_leave peers=${peers.size} seen_packets=${seenPackets.size}")
        cancelPttImmediately()
        channel = null
        peers.clear()
        bids.clear()
        activeSpeaker = null
        transport.leaveChannel()
        publish()
    }

    fun pttDown() {
        val accepted = synchronized(stateLock) {
            if (closed.get() || pttHeld || channel == null || peers.isEmpty() || activeSpeaker != null ||
                pttHealthState == RadioPttHealthState.RECOVERING
            ) return@synchronized false
            pttHeld = true
            talkEpoch++
            localPending = true
            localTalkId = Random.nextInt()
            val priority = Random.nextLong()
            bids[nodeId] = TalkBid(nodeId, localTalkId, priority)
            setPttHealthState(RadioPttHealthState.CONTENDING)
            true
        }
        if (accepted) {
            diagnostics.event("ptt_down peers=${peers.size} session=$sessionEpoch talk=$talkEpoch")
            val currentTalkEpoch = talkEpoch
            val talkId = localTalkId
            val bid = bids[nodeId] ?: return
            send(RadioPacket.TYPE_TALK_REQUEST, bidPayload(talkId, bid.priority))
            publish()
            contentionJob?.cancel()
            contentionJob = scope.launch {
                delay(CONTENTION_MS)
                val won = synchronized(stateLock) {
                    if (closed.get() || currentTalkEpoch != talkEpoch || pttHealthState != RadioPttHealthState.CONTENDING) {
                        false
                    } else {
                        val winner = bids.values.maxWithOrNull(compareBy<TalkBid> { it.priority }.thenBy { it.node })
                        bids.clear()
                        if (pttHeld && winner?.node == nodeId && activeSpeaker == null) {
                            localPending = false
                            localSpeaking = true
                            activeSpeaker = nodeId
                            setPttHealthState(RadioPttHealthState.TX_STARTING)
                            true
                        } else {
                            localPending = false
                            setPttHealthState(RadioPttHealthState.IDLE)
                            false
                        }
                    }
                }
                if (won) {
                    diagnostics.event("ptt_won capture_start")
                    repeatTalkStart(currentTalkEpoch, localTalkId)
                    activeCaptureEpoch = 0L
                    audioFrameSequence = 0
                    val captureEpoch = audio.startCapture()
                    if (captureEpoch == null) requestRecovery(
                        RadioRecoveryDecision(RadioRecoveryComponent.AUDIO, "capture_start_rejected", sessionEpoch, currentTalkEpoch),
                    ) else activeCaptureEpoch = captureEpoch
                    publish()
                } else {
                    diagnostics.event("ptt_not_selected")
                    publish()
                }
            }
        }
    }

    fun pttUp() {
        val endingTalk = synchronized(stateLock) {
            pttHeld = false
            localPending = false
            contentionJob?.cancel()
            contentionJob = null
            if (localSpeaking) {
                setPttHealthState(RadioPttHealthState.TX_STOPPING)
                talkEpoch
            } else {
                setPttHealthState(RadioPttHealthState.IDLE)
                null
            }
        }
        if (endingTalk != null) {
            diagnostics.event("ptt_up capture_stop")
            audio.stopCapture()
            synchronized(stateLock) {
                if (talkEpoch == endingTalk) {
                    localSpeaking = false
                    activeSpeaker = null
        incomingTalkId = null
                }
            }
            scheduleTalkEnd(endingTalk, localTalkId)
        }
        publish()
    }

    private fun cancelPttImmediately() {
        synchronized(stateLock) {
            pttHeld = false
            localPending = false
            localSpeaking = false
            contentionJob?.cancel()
            contentionJob = null
            talkEndJob?.cancel()
            talkEndJob = null
            activeSpeaker = null
            bids.clear()
            setPttHealthState(RadioPttHealthState.IDLE)
        }
        audio.stopCapture()
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        diagnostics.event("engine_stop peers=${peers.size} seen_packets=${seenPackets.size}")
        cancelPttImmediately()
        heartbeat?.cancel()
        heartbeat = null
        audio.stop()
        transport.stop()
        channel = null
        peers.clear()
        channels.clear()
        bids.clear()
        seenPackets.clear()
        localTalkId = 0
        lastIncomingAudioAt = 0L
        scope.cancel()
        healthMonitor.reset()
        listener.onSnapshot(RadioSnapshot())
        val finalState = "${transport.diagnosticState()} ${audio.diagnosticState()} peers=${peers.size} channels=${channels.size}"
        diagnostics.close(finalState)
    }

    fun diagnosticEvent(message: String) = diagnostics.event(message)

    override fun onChannelSeen(channel: Int, nodeId: Int, quality: Int) {
        if (this.channel == null && nodeId != this.nodeId) {
            channels[channel] = SeenChannel(quality, now())
            publish()
        }
    }

    override fun onPacket(bytes: ByteArray, sourceLinkId: String, sourceNodeId: Int?, quality: Int) {
        val packet = RadioPacket.decode(bytes) ?: return
        if (packet.origin == nodeId || packet.channel != channel) return
        if (seenPackets.putIfAbsent(packet.key, now()) != null) return
        var publishState = true
        when (packet.type) {
            RadioPacket.TYPE_PRESENCE -> {
                val presence = packet.payload.toString(Charsets.UTF_8).split('\u0000', limit = 3)
                val name = presence.firstOrNull().orEmpty().take(48).ifBlank { "skyTox" }
                val reciprocalRssi = presence.getOrNull(1)?.toIntOrNull()
                val previous = peers[packet.origin]?.rssi
                val effectiveRssi = transport.qualityForNode(packet.origin)
                    ?: quality.takeIf { it in 0..100 }
                    ?: reciprocalRssi?.takeIf { it in 0..100 }
                    ?: previous?.takeIf { it in 0..100 }
                    ?: 50
                peers[packet.origin] = PeerState(
                    name, effectiveRssi, now(), packet.session,
                    presence.getOrNull(2)?.split(',')?.contains(AUDIO_V2_CAPABILITY) == true,
                )
            }
            RadioPacket.TYPE_TALK_REQUEST -> decodeBid(packet)?.let { bid ->
                if (activeSpeaker == null) bids[packet.origin] = bid
            }
            RadioPacket.TYPE_TALK_START -> {
                val talkId = decodeInt(packet.payload)
                if (talkId != null && (activeSpeaker == null || activeSpeaker == packet.origin)) {
                    val duplicate = activeSpeaker == packet.origin && incomingTalkId == talkId
                    diagnostics.event("talk_start_received duplicate=$duplicate talk=$talkId")
                    activeSpeaker = packet.origin
                    incomingTalkId = talkId
                    lastIncomingAudioAt = now()
                    localPending = false
                    if (localSpeaking) {
                        audio.stopCapture()
                        localSpeaking = false
                    }
                    if (!duplicate) audio.beginIncomingTransmission()
                }
            }
            RadioPacket.TYPE_AUDIO, RadioPacket.TYPE_AUDIO_OPUS -> {
                diagnostics.count("audio_packets_rx")
                val expectedType = if (RadioFeatureFlags.CODEC_OPUS_ENABLED) {
                    RadioPacket.TYPE_AUDIO_OPUS
                } else RadioPacket.TYPE_AUDIO
                if (packet.type != expectedType) {
                    diagnostics.count("audio_packets_wrong_codec")
                } else if (activeSpeaker == packet.origin) {
                    lastIncomingAudioAt = now()
                    audio.playEncodedFrame(packet.payload)
                    publishState = false
                } else diagnostics.count("audio_packets_rejected_speaker")
            }
            RadioPacket.TYPE_AUDIO_OPUS_V2 -> {
                diagnostics.count("audio_packets_rx")
                val frame = RadioAudioWireV2.decode(packet.payload)
                val peer = peers[packet.origin]
                if (frame == null || peer?.audioV2 != true || peer.session != packet.session) {
                    diagnostics.count("audio_v2_invalid")
                } else if (activeSpeaker == packet.origin && incomingTalkId == frame.talkId) {
                    lastIncomingAudioAt = now()
                    audio.playEncodedFrame(frame.encoded)
                    publishState = false
                } else if (activeSpeaker == null) {
                    diagnostics.event("talk_start_recovered_from_audio talk=${frame.talkId}")
                    activeSpeaker = packet.origin
                    incomingTalkId = frame.talkId
                    lastIncomingAudioAt = now()
                    audio.beginIncomingTransmission()
                    audio.playEncodedFrame(frame.encoded)
                    publishState = false
                } else diagnostics.count("audio_packets_rejected_speaker")
            }
            RadioPacket.TYPE_TALK_END -> if (
                activeSpeaker == packet.origin && decodeInt(packet.payload) == incomingTalkId
            ) {
                diagnostics.event("talk_end_received")
                audio.endIncomingTransmission()
                activeSpeaker = null
                incomingTalkId = null
            }
        }
        peers[packet.origin]?.apply {
            if (quality in 0..100) this.rssi = quality
            seenAt = now()
        }
        if (publishState) publish()
    }

    override fun onLinksChanged(count: Int) {
        diagnostics.event("wifi_links_changed count=$count transport=wifi_lan")
        publish()
    }

    override fun onTransportStateChanged(state: RadioTransportState) = publish()

    override fun onTransportError(message: String) = listener.onError(message)

    private fun sendPresence() = send(
        RadioPacket.TYPE_PRESENCE,
        "${nickname.take(48)}\u0000${transport.bestQuality() ?: 0}\u0000$AUDIO_V2_CAPABILITY".toByteArray(),
    )

    private fun sendAudio(frame: ByteArray) {
        if (localSpeaking && pttHeld) {
            diagnostics.count("audio_packets_generated")
            if (RadioFeatureFlags.CODEC_OPUS_ENABLED && peers.isNotEmpty() && peers.values.all { it.audioV2 }) {
                send(RadioPacket.TYPE_AUDIO_OPUS_V2, RadioAudioWireV2.encode(
                    localTalkId, audioFrameSequence++, frame,
                ))
            } else send(
                if (RadioFeatureFlags.CODEC_OPUS_ENABLED) RadioPacket.TYPE_AUDIO_OPUS else RadioPacket.TYPE_AUDIO, frame,
            )
        }
    }

    private fun onCaptureState(captureEpoch: Long, state: RadioCaptureHealthState) {
        scope.launch {
            synchronized(stateLock) {
                if (activeCaptureEpoch == 0L && state in setOf(
                        RadioCaptureHealthState.STARTING, RadioCaptureHealthState.RUNNING,
                    )
                ) activeCaptureEpoch = captureEpoch
                if (closed.get() || captureEpoch != activeCaptureEpoch) return@synchronized
                if (state == RadioCaptureHealthState.RUNNING && localSpeaking && pttHeld) {
                    setPttHealthState(RadioPttHealthState.TX_ACTIVE)
                }
            }
        }
    }

    private fun onCaptureFailure(captureEpoch: Long, stage: String, error: Throwable) {
        scope.launch {
            if (captureEpoch != activeCaptureEpoch || closed.get()) return@launch
            diagnostics.event("audio_failure_reported stage=$stage capture=$captureEpoch type=${error.javaClass.simpleName}")
            requestRecovery(RadioRecoveryDecision(
                RadioRecoveryComponent.AUDIO, stage, sessionEpoch, talkEpoch,
            ))
        }
    }

    private fun evaluateWatchdogs() {
        if (!RadioFeatureFlags.WATCHDOG_ACTIVE_RECOVERY_ENABLED || closed.get()) return
        val audioHealth = audio.healthSnapshot()
        val snapshot = synchronized(stateLock) {
            RadioHealthSnapshot(sessionEpoch, talkEpoch, pttHealthState, audioHealth.captureState,
                pttHeld, localSpeaking, pttStateSinceElapsed, audioHealth.capturedFrames,
                audioHealth.encodedFrames, audioHealth.encodeErrorStreak,
                audioHealth.lastCaptureElapsed, audioHealth.lastEncodeElapsed, SystemClock.elapsedRealtime())
        }
        val decision = healthMonitor.evaluate(snapshot) ?: return
        when (decision.component) {
            RadioRecoveryComponent.PTT -> if (RadioFeatureFlags.PTT_WATCHDOG_ENABLED) requestRecovery(decision)
            RadioRecoveryComponent.AUDIO -> if (RadioFeatureFlags.AUDIO_WATCHDOG_ENABLED) requestRecovery(decision)
            RadioRecoveryComponent.LINK -> Unit
        }
    }

    private fun requestRecovery(decision: RadioRecoveryDecision) {
        val talkId: Int
        synchronized(stateLock) {
            if (closed.get() || decision.sessionEpoch != sessionEpoch || decision.talkEpoch != talkEpoch ||
                recoveryTalkEpoch == talkEpoch
            ) return
            recoveryTalkEpoch = talkEpoch
            talkId = localTalkId
            pttHeld = false
            localPending = false
            localSpeaking = false
            if (activeSpeaker == nodeId) activeSpeaker = null
            contentionJob?.cancel()
            setPttHealthState(RadioPttHealthState.RECOVERING)
        }
        diagnostics.count("wd_${decision.component.name.lowercase()}_triggers")
        diagnostics.event("watchdog_trigger component=${decision.component.name.lowercase()} reason=${decision.reason} session=$sessionEpoch talk=${decision.talkEpoch}")
        val stopped = audio.stopCapture()
        diagnostics.event("watchdog_recovery_end component=${decision.component.name.lowercase()} result=${if (stopped) "success" else "failed"}")
        scheduleTalkEnd(decision.talkEpoch, talkId)
        scope.launch {
            delay(RECOVERY_COOLDOWN_MS)
            synchronized(stateLock) {
                if (!closed.get() && talkEpoch == decision.talkEpoch) setPttHealthState(RadioPttHealthState.IDLE)
            }
            publish()
        }
    }

    private fun scheduleTalkEnd(expectedTalkEpoch: Long, talkId: Int) {
        talkEndJob?.cancel()
        talkEndJob = scope.launch {
            delay(AUDIO_DRAIN_BEFORE_END_MS)
            repeat(3) {
                if (closed.get() || expectedTalkEpoch != talkEpoch) return@launch
                send(RadioPacket.TYPE_TALK_END, intPayload(talkId))
                delay(100)
            }
            synchronized(stateLock) {
                if (expectedTalkEpoch == talkEpoch && pttHealthState == RadioPttHealthState.TX_STOPPING) {
                    setPttHealthState(RadioPttHealthState.IDLE)
                }
            }
        }
    }

    private fun setPttHealthState(state: RadioPttHealthState) {
        if (pttHealthState == state) return
        pttHealthState = state
        pttStateSinceElapsed = SystemClock.elapsedRealtime()
    }

    private fun send(type: Byte, payload: ByteArray) {
        val number = channel ?: return
        val packet = RadioPacket(type, number, nodeId, session, sequence.incrementAndGet(), RadioPacket.MAX_TTL, payload)
        seenPackets[packet.key] = now()
        transport.broadcast(packet.encode())
    }

    private fun expireState() {
        transport.maintenance()
        val cutoff = now() - PEER_TIMEOUT_MS
        val expiredPeers = peers.entries.count { it.value.seenAt < cutoff }
        peers.entries.removeAll { it.value.seenAt < cutoff }
        if (expiredPeers > 0) {
            val links = transport.linkCount()
            diagnostics.event("peer_timeout count=$expiredPeers timeout_ms=$PEER_TIMEOUT_MS links=$links")
        }
        channels.entries.removeAll { it.value.seenAt < now() - CHANNEL_TIMEOUT_MS }
        seenPackets.entries.removeAll { it.value < now() - PACKET_CACHE_MS }
        if (activeSpeaker != null && activeSpeaker != nodeId && !peers.containsKey(activeSpeaker)) {
            activeSpeaker = null
            incomingTalkId = null
            audio.endIncomingTransmission()
        }
        if (activeSpeaker != null && activeSpeaker != nodeId && now() - lastIncomingAudioAt > INCOMING_STALE_MS) {
            diagnostics.event("rx_stale_released talk=${incomingTalkId ?: 0}")
            activeSpeaker = null
            incomingTalkId = null
            audio.endIncomingTransmission()
        }
    }

    private fun publish() {
        val snapshot = RadioSnapshot(
            supported = supported,
            transportState = transport.state,
            network = transport.networkInfo,
            scanning = channel == null,
            channel = channel,
            channels = channels.map { RadioChannel(it.key, signalPercent(it.value.rssi)) }.sortedByDescending { it.signalPercent },
            peers = peers.map { RadioPeer(it.key, it.value.name, signalPercent(it.value.rssi), it.value.seenAt) }.sortedBy { it.nickname.lowercase() },
            localPending = localPending,
            localSpeaking = localSpeaking,
            activeSpeaker = activeSpeaker,
        )
        listener.onSnapshot(snapshot)
    }

    private fun signalPercent(quality: Int): Int = ((quality.coerceIn(0, 100) + 2) / 5 * 5).coerceIn(0, 100)

    private fun bidPayload(talkId: Int, priority: Long) = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        .putInt(talkId).putLong(priority).array()

    private fun decodeBid(packet: RadioPacket): TalkBid? = runCatching {
        val b = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN)
        TalkBid(packet.origin, b.int, b.long)
    }.getOrNull()

    private fun intPayload(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun decodeInt(payload: ByteArray): Int? = if (payload.size == 4) {
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int
    } else null

    private fun repeatTalkStart(expectedTalkEpoch: Long, talkId: Int) {
        scope.launch {
            repeat(3) {
                if (closed.get() || talkEpoch != expectedTalkEpoch || !localSpeaking) return@launch
                send(RadioPacket.TYPE_TALK_START, intPayload(talkId))
                delay(80)
            }
        }
    }
    private fun now() = System.currentTimeMillis()

}
