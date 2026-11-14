// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioAudioWireV2Test {
    @Test fun roundTripPreservesSessionAndOpusPayload() {
        val encoded = byteArrayOf(1, 2, 3, 4, 5)
        val frame = RadioAudioWireV2.decode(RadioAudioWireV2.encode(73, 9, encoded))!!
        assertEquals(73, frame.talkId)
        assertEquals(9, frame.frameSequence)
        assertContentEquals(encoded, frame.encoded)
    }

    @Test fun rejectsTruncatedOrLengthMismatchedPayload() {
        val valid = RadioAudioWireV2.encode(1, 2, byteArrayOf(3, 4))
        assertNull(RadioAudioWireV2.decode(valid.copyOf(valid.size - 1)))
        assertNull(RadioAudioWireV2.decode(byteArrayOf(1, 2, 3)))
    }
}
