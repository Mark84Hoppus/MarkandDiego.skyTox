// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

/** Pure state used by the service so repeated lifecycle callbacks cannot extend deadlines. */
class RadioLifecyclePolicy(
    private val backgroundKeepMs: Long = 10 * 60 * 1000L,
) {
    var backgroundDeadlineAt: Long = 0L
        private set

    fun hidden(now: Long): Long {
        if (backgroundDeadlineAt == 0L) backgroundDeadlineAt = now + backgroundKeepMs
        return backgroundDeadlineAt
    }

    fun visible() {
        backgroundDeadlineAt = 0L
    }

    fun explicitLeave() = visible()

    fun shouldStop(now: Long): Boolean = backgroundDeadlineAt != 0L && now >= backgroundDeadlineAt

    companion object {
        fun canAutoRejoin(channel: Int, until: Long, now: Long): Boolean =
            channel in 1000..9999 && until > 0L && now <= until
    }
}
