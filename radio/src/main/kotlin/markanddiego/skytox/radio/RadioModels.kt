// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

data class RadioChannel(
    val number: Int,
    val signalPercent: Int,
)

data class RadioPeer(
    val nodeId: Int,
    val nickname: String,
    val signalPercent: Int,
    val lastSeenMs: Long,
)

enum class RadioLocalNetworkState { CONNECTING, READY, UNAVAILABLE, RESTRICTED }

data class RadioLocalNetworkInfo(
    val state: RadioLocalNetworkState = RadioLocalNetworkState.CONNECTING,
    val localAddress: String? = null,
    val localPort: Int? = null,
    val interfaceName: String? = null,
    val hotspotHost: Boolean = false,
) {
    val endpoint: String? get() = localAddress?.let { address ->
        localPort?.let { "$address:$it" } ?: address
    }
}

data class RadioSnapshot(
    val supported: Boolean = true,
    val transportState: RadioTransportState = RadioTransportState.IDLE,
    val network: RadioLocalNetworkInfo = RadioLocalNetworkInfo(),
    val scanning: Boolean = false,
    val channel: Int? = null,
    val channels: List<RadioChannel> = emptyList(),
    val peers: List<RadioPeer> = emptyList(),
    val localPending: Boolean = false,
    val localSpeaking: Boolean = false,
    val activeSpeaker: Int? = null,
) {
    val canTalk: Boolean get() = channel != null && peers.isNotEmpty() && activeSpeaker == null
}

interface RadioListener {
    fun onSnapshot(snapshot: RadioSnapshot)
    fun onError(message: String)
}
