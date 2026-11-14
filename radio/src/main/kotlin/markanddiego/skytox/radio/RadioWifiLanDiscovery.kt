// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress

internal class RadioWifiLanDiscovery(
    context: Context,
    private val diagnostics: RadioDiagnostics,
    private val onResolved: (InetAddress, Int, Int, Int) -> Unit,
) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private var registered: NsdServiceInfo? = null
    private var localServiceName: String? = null
    private var discovering = false
    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { registered = info; diagnostics.event("wifi_nsd_registered") }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { diagnostics.event("wifi_nsd_register_failed code=$code") }
        override fun onServiceUnregistered(info: NsdServiceInfo) { registered = null }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
    }
    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { discovering = true; diagnostics.event("wifi_nsd_discovery_started") }
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceType != SERVICE_TYPE || info.serviceName == localServiceName) return
            @Suppress("DEPRECATION")
            runCatching { nsd.resolveService(info, resolver) }
        }
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(type: String) { discovering = false }
        override fun onStartDiscoveryFailed(type: String, code: Int) { discovering = false; diagnostics.event("wifi_nsd_discovery_failed code=$code") }
        override fun onStopDiscoveryFailed(type: String, code: Int) { discovering = false }
    }
    private val resolver = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, code: Int) { diagnostics.count("wifi_nsd_resolve_failed") }
        override fun onServiceResolved(info: NsdServiceInfo) {
            val channel = info.attributes["channel"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0
            val node = info.attributes["node"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0
            info.host?.let { onResolved(it, info.port, channel, node) }
            diagnostics.count("wifi_nsd_resolved")
        }
    }

    fun start() {
        if (!discovering) runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    fun register(port: Int, channel: Int, nodeId: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "$INSTANCE_PREFIX${nodeId.toUInt().toString(16)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("proto", "radio-v1,opus-v2")
            setAttribute("channel", channel.toString())
            setAttribute("node", nodeId.toString())
        }
        localServiceName = info.serviceName
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
    }

    fun stop() {
        if (discovering) runCatching { nsd.stopServiceDiscovery(discovery) }
        unregister()
    }

    fun unregisterChannel() = unregister()

    private fun unregister() {
        registered?.let { runCatching { nsd.unregisterService(registration) } }
        registered = null
        localServiceName = null
    }

    companion object {
        private const val SERVICE_TYPE = "_skytox-radio._udp."
        private const val INSTANCE_PREFIX = "skytox-"
    }
}
