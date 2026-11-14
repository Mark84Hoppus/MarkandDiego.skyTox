// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class RadioWifiDatagramTransport(
    context: Context,
    private val localNodeId: Int,
    private val listener: RadioTransport.Listener,
    private val diagnostics: RadioDiagnostics,
) : RadioTransport {
    private data class Endpoint(
        val nodeId: Int,
        val address: Inet4Address,
        val port: Int,
        @Volatile var channel: Int,
        @Volatile var seenElapsed: Long = SystemClock.elapsedRealtime(),
    )

    private data class Outbound(
        val bytes: ByteArray,
        val targets: List<Endpoint>,
        val audio: Boolean,
        val queuedElapsed: Long = SystemClock.elapsedRealtime(),
    )

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val resolver = RadioNetworkResolver(appContext)
    private val running = AtomicBoolean(false)
    private val runtimeLock = Any()
    private val endpoints = ConcurrentHashMap<Int, Endpoint>()
    private val outbound = LinkedBlockingDeque<Outbound>(MAX_QUEUE)
    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "skytox-radio-wifi-timer").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val nsd = RadioWifiLanDiscovery(appContext, diagnostics, ::onNsdResolved)
    private var socket: DatagramSocket? = null
    private var discoverySocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiveThread: Thread? = null
    private var discoveryThread: Thread? = null
    private var sendThread: Thread? = null
    private var selectedNetwork: LocalWifiNetwork? = null
    private var channel: Int? = null
    private var networkCheckElapsed = 0L
    private var unavailableReported = false

    @Volatile override var state: RadioTransportState = RadioTransportState.IDLE
        private set
    @Volatile override var networkInfo: RadioLocalNetworkInfo = RadioLocalNetworkInfo()
        private set

    override val supported: Boolean get() = networkInfo.state != RadioLocalNetworkState.UNAVAILABLE

    override fun startDiscovery() {
        if (running.compareAndSet(false, true)) {
            updateState(RadioTransportState.CONNECTING)
            scheduler.scheduleAtFixedRate(::announce, 0, ANNOUNCE_MS, TimeUnit.MILLISECONDS)
        }
        ensureRuntime(force = false)
    }

    override fun joinChannel(channel: Int) {
        if (channel !in 1000..9999) return
        this.channel = channel
        startDiscovery()
        socket?.localPort?.let { nsd.register(it, channel, localNodeId) }
        updateState(RadioTransportState.CONNECTING)
        announce()
    }

    override fun leaveChannel() {
        channel = null
        endpoints.clear()
        outbound.clear()
        listener.onLinksChanged(0)
        nsd.unregisterChannel()
        if (running.get() && socket != null) updateState(RadioTransportState.DISCOVERING)
    }

    override fun addManualEndpoint(value: String): Boolean {
        val endpoint = RadioEndpointParser.parse(value) ?: run {
            diagnostics.event("radio_manual_probe invalid")
            return false
        }
        startDiscovery()
        val selected = selectedNetwork ?: return false
        if (!selected.contains(endpoint.address)) {
            diagnostics.event("radio_manual_probe rejected_outside_lan")
            return false
        }
        probe(endpoint.address, endpoint.port)
        diagnostics.event("radio_manual_probe accepted")
        return true
    }

    override fun refreshNetwork() = ensureRuntime(force = true)

    override fun broadcast(bytes: ByteArray, excludeLinkId: String?) {
        if (bytes.isEmpty() || bytes.size > MAX_RADIO_PACKET) {
            diagnostics.count("wifi_packet_oversize_drop")
            return
        }
        val activeChannel = channel ?: return
        val now = SystemClock.elapsedRealtime()
        val targets = endpoints.values.filter {
            it.channel == activeChannel && linkId(it.nodeId) != excludeLinkId && now - it.seenElapsed <= ENDPOINT_TTL_MS
        }
        if (targets.isEmpty()) return
        val audio = bytes.getOrNull(1) in setOf(
            RadioPacket.TYPE_AUDIO, RadioPacket.TYPE_AUDIO_OPUS, RadioPacket.TYPE_AUDIO_OPUS_V2,
        )
        val wire = runCatching { RadioWifiWire.packet(localNodeId, bytes) }.getOrNull() ?: return
        val item = Outbound(wire, targets, audio)
        if (!outbound.offerLast(item)) {
            if (!audio) {
                outbound.firstOrNull { it.audio }?.let(outbound::remove)
                if (!outbound.offerFirst(item)) diagnostics.count("wifi_control_queue_drop")
            } else diagnostics.count("wifi_audio_queue_drop")
        }
    }

    override fun maintenance() {
        val now = SystemClock.elapsedRealtime()
        if (now - networkCheckElapsed >= NETWORK_CHECK_MS) {
            networkCheckElapsed = now
            ensureRuntime(force = false)
        }
        val cutoff = now - ENDPOINT_TTL_MS
        val removed = endpoints.entries.count { it.value.seenElapsed < cutoff }
        endpoints.entries.removeAll { it.value.seenElapsed < cutoff }
        if (removed > 0) {
            diagnostics.event("radio_peer_unreachable count=$removed")
            listener.onLinksChanged(linkCount())
        }
        when {
            socket == null -> updateState(RadioTransportState.ERROR)
            channel != null && endpoints.values.any { it.channel == channel } -> updateState(RadioTransportState.READY)
            channel != null -> updateState(RadioTransportState.CONNECTING)
            else -> updateState(RadioTransportState.DISCOVERING)
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        diagnostics.event("wifi_transport_stop endpoints=${endpoints.size}")
        nsd.stop()
        scheduler.shutdownNow()
        synchronized(runtimeLock) { closeRuntime() }
        endpoints.clear()
        outbound.clear()
        channel = null
        selectedNetwork = null
        networkInfo = RadioLocalNetworkInfo(RadioLocalNetworkState.UNAVAILABLE)
        updateState(RadioTransportState.IDLE)
        listener.onLinksChanged(0)
    }

    override fun diagnosticState(): String =
        "transport=wifi_lan state=${state.name.lowercase()} endpoints=${endpoints.size} wifi_q=${outbound.size} " +
            "socket=${if (socket == null) 0 else 1} iface=${networkInfo.interfaceName ?: "none"} ap=${networkInfo.hotspotHost}"

    override fun qualityForNode(nodeId: Int): Int? = endpoints[nodeId]?.let(::quality)
    override fun bestQuality(): Int? = endpoints.values.maxOfOrNull(::quality)
    override fun linkCount(): Int = endpoints.values.count { it.channel == channel }

    private fun ensureRuntime(force: Boolean) = synchronized(runtimeLock) {
        if (!running.get()) return@synchronized
        val candidate = resolver.inspect()
        if (candidate == null) {
            if (socket != null) {
                diagnostics.event("radio_ip_changed unavailable")
                closeRuntime()
                clearNetworkPeers()
            }
            networkInfo = RadioLocalNetworkInfo(RadioLocalNetworkState.UNAVAILABLE)
            updateState(RadioTransportState.ERROR)
            if (!unavailableReported) {
                unavailableReported = true
                listener.onTransportError("No local Wi-Fi network")
            }
            return@synchronized
        }
        unavailableReported = false
        val changed = selectedNetwork?.fingerprint != candidate.fingerprint
        if (!force && !changed && socket != null) return@synchronized
        if (changed) {
            diagnostics.event("radio_ip_changed iface=${candidate.interfaceName} ap=${candidate.hotspotHost}")
            clearNetworkPeers()
        }
        closeRuntime()
        selectedNetwork = candidate
        openRuntime(candidate)
    }

    private fun openRuntime(selected: LocalWifiNetwork) {
        try {
            val data = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = RECEIVE_TIMEOUT_MS
            }
            if (!resolver.bindToNetwork(data, selected)) throw IllegalStateException("Wi-Fi socket bind failed")
            data.bind(if (selected.network == null) InetSocketAddress(selected.address, 0) else InetSocketAddress(0))

            val discovery = MulticastSocket(null).apply {
                reuseAddress = true
                soTimeout = RECEIVE_TIMEOUT_MS
            }
            if (!resolver.bindToNetwork(discovery, selected)) {
                data.close()
                throw IllegalStateException("Wi-Fi discovery bind failed")
            }
            discovery.bind(InetSocketAddress(DISCOVERY_PORT))
            val networkInterface = resolver.networkInterface(selected)
                ?: throw IllegalStateException("Wi-Fi interface unavailable")
            discovery.networkInterface = networkInterface
            discovery.joinGroup(InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT), networkInterface)

            socket = data
            discoverySocket = discovery
            multicastLock = wifi.createMulticastLock("skytox-radio-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            networkInfo = RadioLocalNetworkInfo(
                RadioLocalNetworkState.READY,
                selected.address.hostAddress,
                data.localPort,
                selected.interfaceName,
                selected.hotspotHost,
            )
            diagnostics.event("radio_network_detected iface=${selected.interfaceName} ap=${selected.hotspotHost}")
            diagnostics.event("radio_socket_bind_success iface=${selected.interfaceName}")
            if (selected.hotspotHost) diagnostics.event("radio_ap_role_detected iface=${selected.interfaceName}")
            receiveThread = receiveLoop(data, false)
            discoveryThread = receiveLoop(discovery, true)
            sendThread = sendLoop(data)
            nsd.start()
            channel?.let { nsd.register(data.localPort, it, localNodeId) }
            updateState(if (channel == null) RadioTransportState.DISCOVERING else RadioTransportState.CONNECTING)
        } catch (error: Exception) {
            diagnostics.exception("radio_socket_bind_fail iface=${selected.interfaceName}", error)
            closeRuntime()
            networkInfo = RadioLocalNetworkInfo(
                RadioLocalNetworkState.RESTRICTED,
                selected.address.hostAddress,
                interfaceName = selected.interfaceName,
                hotspotHost = selected.hotspotHost,
            )
            updateState(RadioTransportState.ERROR)
            listener.onTransportError("Local Wi-Fi socket unavailable")
        }
    }

    private fun receiveLoop(source: DatagramSocket, discovery: Boolean) = thread(
        name = if (discovery) "skytox-radio-wifi-discovery" else "skytox-radio-wifi-rx",
        isDaemon = true,
    ) {
        val buffer = ByteArray(RadioWifiWire.MAX_DATAGRAM)
        while (running.get() && source === (if (discovery) discoverySocket else socket) && !source.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                source.receive(packet)
                val peer = packet.address as? Inet4Address ?: continue
                if (selectedNetwork?.contains(peer) != true) {
                    diagnostics.count("wifi_cross_network_drop")
                    continue
                }
                val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                when (val frame = RadioWifiWire.decode(bytes)) {
                    is RadioWifiFrame.Advert -> acceptAdvert(frame, peer)
                    is RadioWifiFrame.Packet -> acceptPacket(frame, peer, packet.port)
                    null -> diagnostics.count("wifi_malformed_drop")
                }
            } catch (_: SocketTimeoutException) {
                Unit
            } catch (error: Exception) {
                if (running.get() && !source.isClosed) diagnostics.event("wifi_receive_error type=${error.javaClass.simpleName}")
            }
        }
    }

    private fun sendLoop(source: DatagramSocket) = thread(name = "skytox-radio-wifi-tx", isDaemon = true) {
        while (running.get() && source === socket && !source.isClosed) {
            try {
                val item = outbound.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (item.audio && SystemClock.elapsedRealtime() - item.queuedElapsed > MAX_AUDIO_AGE_MS) {
                    diagnostics.count("radio_packet_stale_drop")
                    continue
                }
                item.targets.forEach { target -> sendDatagram(source, item.bytes, target.address, target.port) }
                diagnostics.count(if (item.audio) "wifi_audio_sent" else "wifi_control_sent", item.targets.size.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun acceptAdvert(frame: RadioWifiFrame.Advert, address: Inet4Address) {
        if (frame.nodeId == localNodeId) return
        val now = SystemClock.elapsedRealtime()
        val old = endpoints[frame.nodeId]
        if (old != null && old.address != address) diagnostics.event("wifi_node_address_changed peer=${peerTag(frame.nodeId)}")
        val endpoint = Endpoint(frame.nodeId, address, frame.port, frame.channel, now)
        val changed = old == null || old.address != address || old.port != frame.port
        val fresh = endpoints.put(frame.nodeId, endpoint) == null
        if (frame.channel in 1000..9999) listener.onChannelSeen(frame.channel, frame.nodeId, quality(endpoint))
        if (fresh) {
            diagnostics.event("radio_peer_reachable peer=${peerTag(frame.nodeId)}")
            listener.onLinksChanged(linkCount())
        }
        if (changed) probe(address, frame.port)
    }

    private fun acceptPacket(frame: RadioWifiFrame.Packet, address: Inet4Address, port: Int) {
        if (frame.nodeId == localNodeId) return
        val decoded = RadioPacket.decode(frame.payload) ?: run {
            diagnostics.count("wifi_radio_packet_invalid")
            return
        }
        if (decoded.origin != frame.nodeId || decoded.channel != channel) return
        val endpoint = endpoints.compute(frame.nodeId) { _, old ->
            (old?.takeIf { it.address == address && it.port == port }
                ?: Endpoint(frame.nodeId, address, port, decoded.channel)).apply {
                channel = decoded.channel
                seenElapsed = SystemClock.elapsedRealtime()
            }
        } ?: return
        listener.onPacket(frame.payload, linkId(frame.nodeId), frame.nodeId, quality(endpoint))
    }

    private fun announce() {
        if (!running.get()) return
        val data = socket ?: return
        val discovery = discoverySocket ?: return
        val bytes = RadioWifiWire.advert(localNodeId, channel ?: 0, data.localPort)
        sendDatagram(discovery, bytes, MULTICAST_GROUP, DISCOVERY_PORT)
        diagnostics.count("radio_discovery_method_multicast")
        sendDatagram(data, bytes, BROADCAST, DISCOVERY_PORT)
        diagnostics.count("radio_discovery_method_broadcast")
        endpoints.values.forEach { sendDatagram(data, bytes, it.address, it.port) }
    }

    private fun probe(address: InetAddress, port: Int = DISCOVERY_PORT) {
        val data = socket ?: return
        val bytes = RadioWifiWire.advert(localNodeId, channel ?: 0, data.localPort)
        sendDatagram(data, bytes, address, port)
    }

    private fun onNsdResolved(address: InetAddress, port: Int, channel: Int, nodeId: Int) {
        val peer = address as? Inet4Address ?: return
        if (nodeId == localNodeId || port !in 1024..65535 || selectedNetwork?.contains(peer) != true) return
        val endpoint = Endpoint(nodeId, peer, port, channel)
        endpoints[nodeId] = endpoint
        if (channel in 1000..9999) listener.onChannelSeen(channel, nodeId, quality(endpoint))
        diagnostics.count("radio_discovery_method_nsd")
        probe(peer, port)
    }

    private fun sendDatagram(source: DatagramSocket, bytes: ByteArray, address: InetAddress, port: Int) {
        runCatching { source.send(DatagramPacket(bytes, bytes.size, address, port)) }
            .onFailure { diagnostics.count("wifi_send_error") }
    }

    private fun clearNetworkPeers() {
        endpoints.clear()
        outbound.clear()
        listener.onLinksChanged(0)
    }

    private fun closeRuntime() {
        val selected = selectedNetwork
        val iface = selected?.let(resolver::networkInterface)
        runCatching {
            if (iface != null) discoverySocket?.leaveGroup(InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT), iface)
        }
        discoverySocket?.close()
        socket?.close()
        discoverySocket = null
        socket = null
        receiveThread?.interrupt()
        discoveryThread?.interrupt()
        sendThread?.interrupt()
        receiveThread = null
        discoveryThread = null
        sendThread = null
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        multicastLock = null
        nsd.unregisterChannel()
    }

    private fun updateState(value: RadioTransportState) {
        if (state == value) return
        state = value
        diagnostics.event("wifi_state state=${value.name.lowercase()}")
        listener.onTransportStateChanged(value)
    }

    private fun quality(endpoint: Endpoint): Int {
        val age = SystemClock.elapsedRealtime() - endpoint.seenElapsed
        return when {
            age < 3_000 -> 100
            age < 7_000 -> 75
            age < 12_000 -> 50
            else -> 25
        }
    }

    private fun linkId(node: Int) = "wifi-${node.toUInt().toString(16).takeLast(8)}"
    private fun peerTag(node: Int) = node.toUInt().toString(16).takeLast(6)

    companion object {
        private const val DISCOVERY_PORT = RadioEndpointParser.DEFAULT_PORT
        private val MULTICAST_GROUP: InetAddress = InetAddress.getByName("239.255.83.75")
        private val BROADCAST: InetAddress = InetAddress.getByName("255.255.255.255")
        private const val RECEIVE_TIMEOUT_MS = 1_000
        private const val ANNOUNCE_MS = 2_000L
        private const val NETWORK_CHECK_MS = 2_000L
        private const val ENDPOINT_TTL_MS = 15_000L
        private const val MAX_AUDIO_AGE_MS = 500L
        private const val MAX_RADIO_PACKET = RadioWifiWire.MAX_DATAGRAM - 12
        private const val MAX_QUEUE = 256
    }
}
