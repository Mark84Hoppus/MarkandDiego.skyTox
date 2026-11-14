package markanddiego.skytox.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RadioEndpointParserTest {
    @Test fun parsesPrivateIpv4AndPort() {
        val endpoint = RadioEndpointParser.parse("192.168.43.1:38675")
        assertNotNull(endpoint)
        assertEquals(38675, endpoint?.port)
    }

    @Test fun parsesVersionedQrPayload() {
        val endpoint = RadioEndpointParser.parse("skytox-radio://10.0.0.4:41000?channel=1234&v=1")
        assertEquals(1234, endpoint?.channel)
        assertEquals(41000, endpoint?.port)
    }

    @Test fun rejectsNonLocalOrAmbiguousTargets() {
        listOf(
            "example.com:38675",
            "127.0.0.1:38675",
            "224.0.0.1:38675",
            "192.168.1.4:80",
            "skytox-radio://192.168.1.4:38675?channel=12&v=1",
            "skytox-radio://192.168.1.4:38675?channel=1234&v=2",
        ).forEach { assertNull(it, RadioEndpointParser.parse(it)) }
    }
}
