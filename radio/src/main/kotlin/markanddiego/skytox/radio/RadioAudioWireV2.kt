// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class RadioAudioFrameV2(
    val talkId: Int,
    val frameSequence: Int,
    val flags: Int,
    val encoded: ByteArray,
)

/** Versioned audio payload. TYPE_AUDIO_OPUS (6) remains untouched for compatibility. */
internal object RadioAudioWireV2 {
    private const val HEADER_SIZE = 11
    private const val MAX_ENCODED_SIZE = 4096

    fun encode(talkId: Int, frameSequence: Int, encoded: ByteArray, flags: Int = 0): ByteArray {
        require(encoded.isNotEmpty() && encoded.size <= MAX_ENCODED_SIZE)
        return ByteBuffer.allocate(HEADER_SIZE + encoded.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(talkId)
            .putInt(frameSequence)
            .putShort(encoded.size.toShort())
            .put(flags.toByte())
            .put(encoded)
            .array()
    }

    fun decode(payload: ByteArray): RadioAudioFrameV2? = runCatching {
        if (payload.size < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val talkId = buffer.int
        val frameSequence = buffer.int
        val encodedSize = buffer.short.toInt() and 0xffff
        val flags = buffer.get().toInt() and 0xff
        if (encodedSize !in 1..MAX_ENCODED_SIZE || buffer.remaining() != encodedSize) return null
        RadioAudioFrameV2(talkId, frameSequence, flags, ByteArray(encodedSize).also(buffer::get))
    }.getOrNull()
}
