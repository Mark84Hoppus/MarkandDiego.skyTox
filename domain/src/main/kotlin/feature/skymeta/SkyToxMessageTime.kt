// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature.skymeta

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Date
import kotlin.math.min
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.tox.Tox

object SkyToxMessageTime {
    const val ENABLED = true
    private const val PACKET_KIND = 0xCB.toByte()
    private const val PACKET_VERSION = 1.toByte()
    private const val DIGEST_SIZE = 32
    private const val PACKET_SIZE = 2 + Long.SIZE_BYTES + DIGEST_SIZE
    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private val cache = mutableListOf<PendingTime>()

    fun outgoingTimestamp(now: Long = Date().time): Long =
        if (ENABLED) now else 0L

    fun sendOutgoingMetadata(tox: Tox, publicKey: PublicKey, message: String, sentAt: Long) {
        if (!ENABLED || sentAt <= 0L) return
        tox.sendLosslessPacket(publicKey, packetFor(message, sentAt))
    }

    fun rememberIncomingMetadata(publicKey: String, packet: ByteArray): Boolean {
        val parsed = parsePacket(packet) ?: return false
        synchronized(cache) {
            val now = Date().time
            cache.removeAll { now - it.receivedAt > CACHE_TTL_MS }
            cache.add(PendingTime(publicKey, parsed.digest, parsed.sentAt, now))
        }
        return true
    }

    fun incomingTimestamp(
        publicKey: String,
        message: String,
        receivedAt: Long = Date().time,
        timeDeltaSeconds: Int,
    ): Long {
        if (!ENABLED) return receivedAt

        consumeSentAt(publicKey, message, receivedAt)?.let { return it }
        return incomingTimestampFromTox(receivedAt, timeDeltaSeconds)
    }

    private fun incomingTimestampFromTox(receivedAt: Long, timeDeltaSeconds: Int): Long {
        if (!ENABLED || timeDeltaSeconds <= 0) return receivedAt

        val deltaMs = timeDeltaSeconds.toLong() * 1000L
        return min(receivedAt, receivedAt - deltaMs).coerceAtLeast(0L)
    }

    private fun packetFor(message: String, sentAt: Long): ByteArray =
        ByteBuffer.allocate(PACKET_SIZE)
            .put(PACKET_KIND)
            .put(PACKET_VERSION)
            .putLong(sentAt)
            .put(digest(message))
            .array()

    private fun parsePacket(packet: ByteArray): ParsedTime? {
        if (packet.size != PACKET_SIZE || packet[0] != PACKET_KIND || packet[1] != PACKET_VERSION) return null
        val buffer = ByteBuffer.wrap(packet)
        buffer.get()
        buffer.get()
        val sentAt = buffer.long
        if (sentAt <= 0L) return null
        val digest = ByteArray(DIGEST_SIZE)
        buffer.get(digest)
        return ParsedTime(sentAt, digest)
    }

    private fun consumeSentAt(publicKey: String, message: String, receivedAt: Long): Long? {
        val digest = digest(message)
        synchronized(cache) {
            cache.removeAll { receivedAt - it.receivedAt > CACHE_TTL_MS }
            val match = cache.firstOrNull { it.publicKey == publicKey && it.digest.contentEquals(digest) } ?: return null
            cache.remove(match)
            return match.sentAt.coerceAtMost(receivedAt).coerceAtLeast(0L)
        }
    }

    private fun digest(message: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(message.toByteArray(Charsets.UTF_8))

    private data class ParsedTime(val sentAt: Long, val digest: ByteArray)
    private data class PendingTime(
        val publicKey: String,
        val digest: ByteArray,
        val sentAt: Long,
        val receivedAt: Long,
    )
}
