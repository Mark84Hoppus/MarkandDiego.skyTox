// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.contactlist

import kotlin.test.Test
import kotlin.test.assertEquals

class ShareRecipientPolicyTest {
    private val thirty = (1..30).toList()

    @Test fun normalContactKeepsTenFileLimit() {
        assertEquals((1..10).toList(), shareFilesForRecipient(thirty, selfChat = false))
    }

    @Test fun mySkyToxKeepsAllSharedFiles() {
        assertEquals(thirty, shareFilesForRecipient(thirty, selfChat = true))
    }
}
