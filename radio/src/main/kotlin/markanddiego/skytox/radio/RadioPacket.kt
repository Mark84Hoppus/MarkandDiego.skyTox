// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class RadioPacket(
    val type: Byte,
    val channel: Int,
    val origin: Int,
    val session: Int,
    val sequence: Int,
    val ttl: Int,
    val payload: ByteArray,
) {
    val key: String get() = "$origin:$session:$sequence"

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        .order(ByteOrder.BIG_ENDIAN)
        .put(VERSION)
        .put(type)
        .putShort(channel.toShort())
        .putInt(origin)
        .putInt(session)
        .putInt(sequence)
        .put(ttl.coerceIn(0, MAX_TTL).toByte())
        .put(payload)
        .array()

    fun relayed(): RadioPacket = copy(ttl = (ttl - 1).coerceAtLeast(0))

    companion object {
        const val TYPE_PRESENCE: Byte = 1
        const val TYPE_TALK_REQUEST: Byte = 2
        const val TYPE_TALK_START: Byte = 3
        const val TYPE_TALK_END: Byte = 4
        const val TYPE_AUDIO: Byte = 5
        const val TYPE_AUDIO_OPUS: Byte = 6
        const val TYPE_AUDIO_OPUS_V2: Byte = 7
        const val MAX_TTL = 4
        private const val VERSION: Byte = 1
        private const val HEADER_SIZE = 17

        fun decode(bytes: ByteArray): RadioPacket? = runCatching {
            if (bytes.size < HEADER_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (buffer.get() != VERSION) return null
            val type = buffer.get()
            val channel = buffer.short.toInt() and 0xffff
            val origin = buffer.int
            val session = buffer.int
            val sequence = buffer.int
            val ttl = buffer.get().toInt() and 0xff
            if (channel !in 1000..9999 || ttl > MAX_TTL) return null
            val payload = ByteArray(buffer.remaining()).also(buffer::get)
            RadioPacket(type, channel, origin, session, sequence, ttl, payload)
        }.getOrNull()
    }
}
