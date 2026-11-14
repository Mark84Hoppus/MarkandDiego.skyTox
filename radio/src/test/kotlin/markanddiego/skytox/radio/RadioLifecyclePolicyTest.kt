// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioLifecyclePolicyTest {
    @Test fun repeatedHiddenDoesNotExtendDeadline() {
        val policy = RadioLifecyclePolicy(backgroundKeepMs = 600)
        assertEquals(700, policy.hidden(100))
        assertEquals(700, policy.hidden(500))
        assertFalse(policy.shouldStop(699))
        assertTrue(policy.shouldStop(700))
    }

    @Test fun visibleAndExplicitLeaveClearDeadline() {
        val policy = RadioLifecyclePolicy(600)
        policy.hidden(100)
        policy.visible()
        assertEquals(0, policy.backgroundDeadlineAt)
        policy.hidden(200)
        policy.explicitLeave()
        assertEquals(0, policy.backgroundDeadlineAt)
    }

    @Test fun rejoinExpiresWithoutSliding() {
        assertTrue(RadioLifecyclePolicy.canAutoRejoin(4321, 3_600, 3_599))
        assertFalse(RadioLifecyclePolicy.canAutoRejoin(4321, 3_600, 3_601))
        assertFalse(RadioLifecyclePolicy.canAutoRejoin(0, 3_600, 100))
    }
}
