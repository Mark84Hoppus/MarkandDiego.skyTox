// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal sealed interface RadioWifiFrame {
    data class Advert(val nodeId: Int, val channel: Int, val port: Int) : RadioWifiFrame
    data class Packet(val nodeId: Int, val payload: ByteArray) : RadioWifiFrame
}

internal object RadioWifiWire {
    private const val MAGIC = 0x53545257 // STRW
    private const val VERSION: Byte = 1
    private const val ADVERT: Byte = 1
    private const val PACKET: Byte = 2
    const val MAX_DATAGRAM = 1200

    fun advert(nodeId: Int, channel: Int, port: Int): ByteArray =
        ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).put(VERSION).put(ADVERT).putInt(nodeId)
            .putShort(channel.coerceIn(0, 9999).toShort()).putShort(port.toShort()).array()

    fun packet(nodeId: Int, payload: ByteArray): ByteArray {
        require(payload.isNotEmpty() && payload.size <= MAX_DATAGRAM - 12)
        return ByteBuffer.allocate(12 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).put(VERSION).put(PACKET).putInt(nodeId)
            .putShort(payload.size.toShort()).put(payload).array()
    }

    fun decode(bytes: ByteArray): RadioWifiFrame? = runCatching {
        if (bytes.size !in 12..MAX_DATAGRAM) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (b.int != MAGIC || b.get() != VERSION) return null
        val type = b.get()
        val node = b.int
        when (type) {
            ADVERT -> {
                if (bytes.size != 14) return null
                val channel = b.short.toInt() and 0xffff
                val port = b.short.toInt() and 0xffff
                if (channel !in 0..9999 || port !in 1024..65535) return null
                RadioWifiFrame.Advert(node, channel, port)
            }
            PACKET -> {
                val length = b.short.toInt() and 0xffff
                if (length <= 0 || length != b.remaining()) return null
                RadioWifiFrame.Packet(node, ByteArray(length).also(b::get))
            }
            else -> null
        }
    }.getOrNull()
}
