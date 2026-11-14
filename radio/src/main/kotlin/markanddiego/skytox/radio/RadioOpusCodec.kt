// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder

internal class RadioOpusEncoder {
    private var encoder = newEncoder()

    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == FRAME_SAMPLES)
        val output = ByteArray(MAX_PACKET_BYTES)
        val length = encoder.encode(pcm, 0, FRAME_SAMPLES, output, 0, output.size)
        return output.copyOf(length)
    }

    fun reset() { encoder = newEncoder() }

    private fun newEncoder() = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        bitrate = BITRATE
        useDTX = false
        useInbandFEC = false
        complexity = 5
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        // 20 ms is Opus' normal real-time voice frame. The previous 40 ms SILK path
        // intermittently asserted inside Concentus on the Moto G5S Plus after PTT restarts.
        const val FRAME_SAMPLES = 320
        const val FRAME_DURATION_MS = 20
        private const val BITRATE = 16_000
        private const val MAX_PACKET_BYTES = 512
    }
}

internal class RadioOpusDecoder {
    private var decoder = OpusDecoder(RadioOpusEncoder.SAMPLE_RATE, 1)

    fun decode(packet: ByteArray): ShortArray {
        require(packet.isNotEmpty())
        val output = ShortArray(RadioOpusEncoder.FRAME_SAMPLES)
        val samples = decoder.decode(
            packet, 0, packet.size, output, 0, RadioOpusEncoder.FRAME_SAMPLES, false,
        )
        return if (samples == output.size) output else output.copyOf(samples)
    }

    fun reset() { decoder = OpusDecoder(RadioOpusEncoder.SAMPLE_RATE, 1) }
}
