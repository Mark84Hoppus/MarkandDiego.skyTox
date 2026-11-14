// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import java.net.Inet4Address
import java.net.InetAddress

internal data class RadioManualEndpoint(val address: Inet4Address, val port: Int, val channel: Int?)

internal object RadioEndpointParser {
    const val DEFAULT_PORT = 38675

    fun parse(raw: String): RadioManualEndpoint? = runCatching {
        val value = raw.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null
        val uri = if (value.startsWith("skytox-radio://", ignoreCase = true)) java.net.URI(value)
        else java.net.URI("skytox-radio://$value")
        if (!uri.scheme.equals("skytox-radio", ignoreCase = true) || uri.userInfo != null || uri.fragment != null) return null
        val host = uri.host ?: return null
        if (!host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) return null
        val address = InetAddress.getByName(host) as? Inet4Address ?: return null
        if (!isAllowedLocal(address)) return null
        val port = if (uri.port == -1) DEFAULT_PORT else uri.port
        if (port !in 1024..65535) return null
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate {
            val parts = it.split('=', limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
        if (query.keys.any { it !in setOf("channel", "v") }) return null
        if (query["v"]?.toIntOrNull()?.let { it != 1 } == true) return null
        val channel = query["channel"]?.toIntOrNull()
        if (query.containsKey("channel") && (channel == null || channel !in 1000..9999)) return null
        RadioManualEndpoint(address, port, channel)
    }.getOrNull()

    private fun isAllowedLocal(address: Inet4Address): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) return false
        if (address.isSiteLocalAddress) return true
        val bytes = address.address
        return (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
    }
}
