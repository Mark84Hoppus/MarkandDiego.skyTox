// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature.chatmarkers

import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.Sender

object SkyToxChatMarkers {
    const val ENABLED = true
    const val PENDING_CORRELATION_ID = Int.MIN_VALUE

    fun isUndelivered(message: Message): Boolean =
        ENABLED && message.sender == Sender.Sent && message.correlationId == PENDING_CORRELATION_ID

    fun pendingConversationKeys(messages: List<Message>): Set<String> =
        if (!ENABLED) {
            emptySet()
        } else {
            messages.asSequence()
                .filter(::isUndelivered)
                .map { it.publicKey }
                .toSet()
        }
}
