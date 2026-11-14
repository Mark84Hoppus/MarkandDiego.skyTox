// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkAddress
import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.util.Collections

internal data class LocalWifiNetwork(
    val network: Network?,
    val address: Inet4Address,
    val prefixLength: Int,
    val interfaceName: String,
    val hotspotHost: Boolean,
) {
    val fingerprint: String = "$interfaceName/${address.hostAddress}/$prefixLength/${network?.hashCode() ?: 0}"

    fun contains(peer: InetAddress): Boolean {
        if (peer !is Inet4Address || prefixLength !in 0..32) return false
        val local = address.address
        val remote = peer.address
        var bits = prefixLength
        for (index in local.indices) {
            val mask = when {
                bits >= 8 -> 0xff
                bits <= 0 -> 0
                else -> (0xff shl (8 - bits)) and 0xff
            }
            if ((local[index].toInt() and mask) != (remote[index].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }
}

/** Socket-local Wi-Fi selection. It never changes process-wide routing. */
internal class RadioNetworkResolver(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun inspect(): LocalWifiNetwork? {
        val active = connectivity.activeNetwork
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
            val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val link = properties.linkAddresses.firstUsableIpv4() ?: return@mapNotNull null
            val name = properties.interfaceName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (name.contains("p2p", ignoreCase = true)) return@mapNotNull null
            val score = (if (network == active) 100 else 0) +
                (if (link.address.isSiteLocalAddress) 20 else 0) +
                (if (properties.routes.any { it.gateway == null }) 5 else 0)
            score to LocalWifiNetwork(network, link.address as Inet4Address, link.prefixLength, name, false)
        }
        candidates.maxByOrNull { it.first }?.second?.let { return it }
        return hotspotInterfaceFallback()
    }

    fun bindToNetwork(socket: DatagramSocket, selected: LocalWifiNetwork): Boolean {
        val network = selected.network ?: return true
        return runCatching { network.bindSocket(socket); true }.getOrDefault(false)
    }

    fun networkInterface(selected: LocalWifiNetwork): NetworkInterface? =
        runCatching { NetworkInterface.getByName(selected.interfaceName) }.getOrNull()

    private fun hotspotInterfaceFallback(): LocalWifiNetwork? {
        val candidates = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filterNot { it.name.matches(Regex("(?i).*(rmnet|ccmni|cell|wwan|tun|tap|vpn|p2p).*")) }
            .mapNotNull { iface ->
                val address = iface.interfaceAddresses.firstOrNull {
                    val value = it.address
                    value is Inet4Address && !value.isLoopbackAddress && !value.isLinkLocalAddress &&
                        (value.isSiteLocalAddress || isCarrierGradeNat(value))
                } ?: return@mapNotNull null
                val name = iface.name.orEmpty()
                val score = when {
                    name.matches(Regex("(?i).*(softap|swlan|ap[0-9]*).*")) -> 100
                    name.matches(Regex("(?i).*(wlan|wifi).*")) -> 60
                    else -> 10
                }
                score to LocalWifiNetwork(
                    null, address.address as Inet4Address, address.networkPrefixLength.toInt(), name, true,
                )
            }
        return candidates.maxByOrNull { it.first }?.second
    }

    private fun List<LinkAddress>.firstUsableIpv4(): LinkAddress? = firstOrNull {
        val value = it.address
        value is Inet4Address && !value.isLoopbackAddress && !value.isLinkLocalAddress && !value.isAnyLocalAddress
    }

    private fun isCarrierGradeNat(address: Inet4Address): Boolean {
        val bytes = address.address
        return (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
    }
}
