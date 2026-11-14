// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioHealthMonitorTest {
    private fun snapshot(
        now: Long = 2_000,
        state: RadioPttHealthState = RadioPttHealthState.IDLE,
        capture: RadioCaptureHealthState = RadioCaptureHealthState.STOPPED,
        held: Boolean = false,
        speaking: Boolean = false,
        since: Long = 1_000,
        captured: Long = 0,
        encoded: Long = 0,
        errors: Int = 0,
        lastEncode: Long = 0,
    ) = RadioHealthSnapshot(1, 2, state, capture, held, speaking, since, captured, encoded,
        errors, 0, lastEncode, now)

    @Test fun idleAndHealthyLongTransmissionDoNotTrigger() {
        val monitor = RadioHealthMonitorCore()
        assertNull(monitor.evaluate(snapshot()))
        assertNull(monitor.evaluate(snapshot(now = 61_000, state = RadioPttHealthState.TX_ACTIVE,
            capture = RadioCaptureHealthState.RUNNING, held = true, speaking = true, since = 1,
            captured = 1_500, encoded = 1_500, lastEncode = 60_980)))
    }

    @Test fun encoderStreakTriggersOnceWithinCooldown() {
        val monitor = RadioHealthMonitorCore()
        val unhealthy = snapshot(state = RadioPttHealthState.TX_ACTIVE,
            capture = RadioCaptureHealthState.RUNNING, held = true, speaking = true, errors = 3)
        assertEquals("encode_error_streak", monitor.evaluate(unhealthy)?.reason)
        assertNull(monitor.evaluate(unhealthy.copy(nowElapsed = 2_500)))
    }

    @Test fun stalledContentionAndLostReleaseAreIndependent() {
        val monitor = RadioHealthMonitorCore()
        assertEquals(RadioRecoveryComponent.PTT,
            monitor.evaluate(snapshot(now = 2_300, state = RadioPttHealthState.CONTENDING, held = true))?.component)
        monitor.reset()
        assertEquals("release_invariant", monitor.evaluate(snapshot(now = 2_000,
            state = RadioPttHealthState.TX_ACTIVE, speaking = true, held = false))?.reason)
    }

    @Test fun missingTimestampsDoNotCreateFalseStall() {
        assertNull(RadioHealthMonitorCore().evaluate(snapshot(now = 99_000,
            state = RadioPttHealthState.TX_ACTIVE, capture = RadioCaptureHealthState.RUNNING,
            held = true, speaking = true, captured = 4, encoded = 0, lastEncode = 0)))
    }
}
