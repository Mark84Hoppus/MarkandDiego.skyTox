// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RadioOpusCodecTest {
    @Test fun roundTripProducesExactFrameAndBoundedPacket() {
        val pcm = ShortArray(RadioOpusEncoder.FRAME_SAMPLES) { index ->
            (sin(2.0 * Math.PI * 440.0 * index / RadioOpusEncoder.SAMPLE_RATE) * 8_000).toInt().toShort()
        }
        val encoded = RadioOpusEncoder().encode(pcm)
        val decoded = RadioOpusDecoder().decode(encoded)
        assertTrue(encoded.size in 1..512)
        assertEquals(RadioOpusEncoder.FRAME_SAMPLES, decoded.size)
        assertTrue(decoded.any { it.toInt() != 0 })
    }

    @Test fun silenceRoundTripAndResetRemainValid() {
        val encoder = RadioOpusEncoder()
        val decoder = RadioOpusDecoder()
        val encoded = encoder.encode(ShortArray(RadioOpusEncoder.FRAME_SAMPLES))
        assertEquals(RadioOpusEncoder.FRAME_SAMPLES, decoder.decode(encoded).size)
        encoder.reset()
        decoder.reset()
        assertEquals(
            RadioOpusEncoder.FRAME_SAMPLES,
            decoder.decode(encoder.encode(ShortArray(RadioOpusEncoder.FRAME_SAMPLES))).size,
        )
    }
}
