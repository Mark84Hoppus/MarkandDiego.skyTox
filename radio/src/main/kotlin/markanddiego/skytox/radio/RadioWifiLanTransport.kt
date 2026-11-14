// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import android.content.Context

internal class RadioWifiLanTransport(
    context: Context,
    localNodeId: Int,
    listener: RadioTransport.Listener,
    diagnostics: RadioDiagnostics,
) : RadioTransport {
    private val delegate = RadioWifiDatagramTransport(
        context, localNodeId, listener, diagnostics,
    )
    override val supported get() = delegate.supported
    override val state get() = delegate.state
    override val networkInfo get() = delegate.networkInfo
    override fun startDiscovery() = delegate.startDiscovery()
    override fun prepareCreatedChannel(channel: Int) = delegate.prepareCreatedChannel(channel)
    override fun joinChannel(channel: Int) = delegate.joinChannel(channel)
    override fun addManualEndpoint(value: String) = delegate.addManualEndpoint(value)
    override fun refreshNetwork() = delegate.refreshNetwork()
    override fun leaveChannel() = delegate.leaveChannel()
    override fun broadcast(bytes: ByteArray, excludeLinkId: String?) = delegate.broadcast(bytes, excludeLinkId)
    override fun maintenance() = delegate.maintenance()
    override fun stop() = delegate.stop()
    override fun diagnosticState() = delegate.diagnosticState()
    override fun qualityForNode(nodeId: Int) = delegate.qualityForNode(nodeId)
    override fun bestQuality() = delegate.bestQuality()
    override fun linkCount() = delegate.linkCount()
}
