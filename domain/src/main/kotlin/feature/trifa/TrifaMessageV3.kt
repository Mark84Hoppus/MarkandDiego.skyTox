// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature.trifa

import java.security.SecureRandom
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import ltd.evilcorp.core.vo.Contact

object TrifaMessageV3 {
    private const val GUARD_SIZE = 2
    private const val MESSAGE_ID_SIZE = 32
    private const val TIMESTAMP_SIZE = 4
    private const val TRAILER_SIZE = GUARD_SIZE + MESSAGE_ID_SIZE + TIMESTAMP_SIZE
    private const val DUPLICATE_WINDOW_MS = 24 * 60 * 60 * 1000L
    private val random = SecureRandom()
    private val observedPeers = ConcurrentHashMap.newKeySet<String>()
    private val incomingMessageIds = ConcurrentHashMap<String, Long>()

    fun shouldSendTo(contact: Contact): Boolean {
        if (observedPeers.contains(contact.publicKey)) return true

        val haystack = "${contact.name}\n${contact.statusMessage}".lowercase(Locale.US)
        return "trifa" in haystack
    }

    fun payloadFor(message: String, sentAtMs: Long): ByteArray {
        val text = message.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(text.size + TRAILER_SIZE)
        text.copyInto(payload)

        val guardOffset = text.size
        payload[guardOffset] = 0
        payload[guardOffset + 1] = 0

        val messageId = ByteArray(MESSAGE_ID_SIZE)
        random.nextBytes(messageId)
        messageId.copyInto(payload, guardOffset + GUARD_SIZE)

        val seconds = ((sentAtMs.takeIf { it > 0L } ?: Date().time) / 1000L).toInt()
        val timestampOffset = guardOffset + GUARD_SIZE + MESSAGE_ID_SIZE
        payload[timestampOffset] = (seconds ushr 24).toByte()
        payload[timestampOffset + 1] = (seconds ushr 16).toByte()
        payload[timestampOffset + 2] = (seconds ushr 8).toByte()
        payload[timestampOffset + 3] = seconds.toByte()

        return payload
    }

    fun parseIncoming(publicKey: String, payload: ByteArray): Parsed {
        if (payload.size <= TRAILER_SIZE) return Parsed(String(payload, Charsets.UTF_8), null, false)

        val guardOffset = findTrailerGuard(payload) ?: run {
            return Parsed(String(payload, Charsets.UTF_8), null, false)
        }

        observedPeers.add(publicKey)
        val text = payload.copyOfRange(0, guardOffset).toString(Charsets.UTF_8)
        val messageId = payload.copyOfRange(guardOffset + GUARD_SIZE, guardOffset + GUARD_SIZE + MESSAGE_ID_SIZE)
            .joinToString("") { "%02x".format(it) }
        val timestampOffset = guardOffset + GUARD_SIZE + MESSAGE_ID_SIZE
        val seconds =
            ((payload[timestampOffset].toInt() and 0xff) shl 24) or
                ((payload[timestampOffset + 1].toInt() and 0xff) shl 16) or
                ((payload[timestampOffset + 2].toInt() and 0xff) shl 8) or
                (payload[timestampOffset + 3].toInt() and 0xff)
        return Parsed(text, seconds.toLong() * 1000L, rememberIncoming(publicKey, messageId))
    }

    private fun rememberIncoming(publicKey: String, messageId: String): Boolean {
        val now = Date().time
        incomingMessageIds.entries.removeIf { now - it.value > DUPLICATE_WINDOW_MS }
        return incomingMessageIds.putIfAbsent("$publicKey:$messageId", now) != null
    }

    private fun findTrailerGuard(payload: ByteArray): Int? {
        val exactOffset = payload.size - TRAILER_SIZE
        if (payload[exactOffset] == 0.toByte() && payload[exactOffset + 1] == 0.toByte()) return exactOffset

        val searchStart = (payload.size - 96).coerceAtLeast(0)
        for (offset in (payload.size - TRAILER_SIZE) downTo searchStart) {
            if (payload[offset] == 0.toByte() && payload[offset + 1] == 0.toByte() && offset > 0) {
                return offset
            }
        }

        return null
    }

    data class Parsed(val message: String, val sentAtMs: Long?, val duplicate: Boolean)
}
