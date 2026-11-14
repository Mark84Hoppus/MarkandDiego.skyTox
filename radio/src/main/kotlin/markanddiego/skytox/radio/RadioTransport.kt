// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

enum class RadioTransportState { IDLE, DISCOVERING, CONNECTING, READY, LOST, ERROR }

internal interface RadioTransport {
    val supported: Boolean
    val state: RadioTransportState
    val networkInfo: RadioLocalNetworkInfo

    fun startDiscovery()
    fun prepareCreatedChannel(channel: Int) = joinChannel(channel)
    fun joinChannel(channel: Int)
    fun addManualEndpoint(value: String): Boolean = false
    fun refreshNetwork()
    fun leaveChannel()
    fun broadcast(bytes: ByteArray, excludeLinkId: String? = null)
    fun maintenance()
    fun stop()
    fun diagnosticState(): String
    fun qualityForNode(nodeId: Int): Int?
    fun bestQuality(): Int?
    fun linkCount(): Int

    interface Listener {
        fun onChannelSeen(channel: Int, nodeId: Int, quality: Int)
        fun onPacket(bytes: ByteArray, sourceLinkId: String, sourceNodeId: Int?, quality: Int)
        fun onLinksChanged(count: Int)
        fun onTransportStateChanged(state: RadioTransportState)
        fun onTransportError(message: String)
    }
}
