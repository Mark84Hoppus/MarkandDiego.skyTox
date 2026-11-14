// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RadioWifiWireTest {
    @Test fun advertRoundTrip() {
        val frame = assertIs<RadioWifiFrame.Advert>(RadioWifiWire.decode(RadioWifiWire.advert(7, 1234, 38675)))
        assertEquals(7, frame.nodeId)
        assertEquals(1234, frame.channel)
        assertEquals(38675, frame.port)
    }

    @Test fun packetRoundTrip() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val frame = assertIs<RadioWifiFrame.Packet>(RadioWifiWire.decode(RadioWifiWire.packet(9, payload)))
        assertEquals(9, frame.nodeId)
        assertContentEquals(payload, frame.payload)
    }

    @Test fun malformedAndOversizedFramesAreRejected() {
        assertNull(RadioWifiWire.decode(byteArrayOf(1, 2, 3)))
        assertNull(RadioWifiWire.decode(ByteArray(RadioWifiWire.MAX_DATAGRAM + 1)))
    }
}
