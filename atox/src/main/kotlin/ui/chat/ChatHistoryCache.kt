// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import ltd.evilcorp.core.vo.Message

enum class ChatHistoryMode { Latest, AroundTarget }

data class ChatScrollAnchor(val messageId: Long, val topPx: Int)

data class ChatHistorySnapshot(
    val messages: List<Message>,
    val mode: ChatHistoryMode,
    val targetId: Long?,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val anchor: ChatScrollAnchor?,
    val leftAt: Long = SystemClock.elapsedRealtime(),
)

@Singleton
class ChatHistoryCache @Inject constructor() {
    private val entries = LinkedHashMap<String, ChatHistorySnapshot>(8, 0.75f, true)

    @Synchronized
    fun restore(publicKey: String, targetId: Long?): ChatHistorySnapshot? {
        evictExpired()
        val snapshot = entries[publicKey] ?: return null
        if (snapshot.targetId != targetId) return null
        entries.remove(publicKey)
        return snapshot
    }

    @Synchronized
    fun store(publicKey: String, snapshot: ChatHistorySnapshot) {
        if (publicKey.isBlank()) return
        evictExpired()
        entries[publicKey] = snapshot.copy(
            messages = snapshot.messages.takeLast(MAX_MESSAGES_PER_CHAT),
            leftAt = SystemClock.elapsedRealtime(),
        )
        trimToLimits()
    }

    @Synchronized
    fun remove(publicKey: String) {
        entries.remove(publicKey)
    }

    private fun evictExpired() {
        val now = SystemClock.elapsedRealtime()
        entries.entries.removeAll { now - it.value.leftAt >= CACHE_TTL_MS }
    }

    private fun trimToLimits() {
        while (entries.size > MAX_INACTIVE_CHATS || entries.values.sumOf { it.messages.size } > MAX_CACHED_MESSAGES) {
            val eldest = entries.entries.firstOrNull() ?: return
            entries.remove(eldest.key)
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L
        const val MAX_INACTIVE_CHATS = 5
        const val MAX_CACHED_MESSAGES = 1500
        const val MAX_MESSAGES_PER_CHAT = 800
    }
}
