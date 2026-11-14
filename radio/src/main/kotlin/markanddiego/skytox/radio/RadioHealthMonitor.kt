// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

internal enum class RadioPttHealthState { IDLE, CONTENDING, TX_STARTING, TX_ACTIVE, TX_STOPPING, RX_ACTIVE, RECOVERING }
internal enum class RadioCaptureHealthState { STOPPED, STARTING, RUNNING, STOP_REQUESTED, STOPPING, FAILED }
internal enum class RadioRecoveryComponent { PTT, AUDIO, LINK }

internal data class RadioHealthSnapshot(
    val sessionEpoch: Long,
    val talkEpoch: Long,
    val pttState: RadioPttHealthState,
    val captureState: RadioCaptureHealthState,
    val pttHeld: Boolean,
    val localSpeaking: Boolean,
    val stateSinceElapsed: Long,
    val captureFrames: Long,
    val encodedFrames: Long,
    val encodeErrorStreak: Int,
    val lastCaptureElapsed: Long,
    val lastEncodeElapsed: Long,
    val nowElapsed: Long,
)

internal data class RadioWatchdogPolicy(
    val contentionStallMs: Long = 1_250,
    val captureStartStallMs: Long = 1_500,
    val encodeNoProgressMs: Long = 1_000,
    val encodeErrorStreak: Int = 3,
    val releaseInvariantMs: Long = 700,
    val recoveryCooldownMs: Long = 1_500,
)

internal data class RadioRecoveryDecision(
    val component: RadioRecoveryComponent,
    val reason: String,
    val sessionEpoch: Long,
    val talkEpoch: Long,
)

/** Pure policy. It observes monotonic snapshots and never touches Android resources directly. */
internal class RadioHealthMonitorCore(private val policy: RadioWatchdogPolicy = RadioWatchdogPolicy()) {
    private val lastRecovery = mutableMapOf<RadioRecoveryComponent, Long>()

    fun evaluate(snapshot: RadioHealthSnapshot): RadioRecoveryDecision? {
        val stateAge = age(snapshot.nowElapsed, snapshot.stateSinceElapsed)
        val decision = when {
            snapshot.pttState == RadioPttHealthState.CONTENDING && stateAge > policy.contentionStallMs ->
                RadioRecoveryDecision(RadioRecoveryComponent.PTT, "contention_stalled", snapshot.sessionEpoch, snapshot.talkEpoch)
            snapshot.pttState == RadioPttHealthState.TX_STARTING && stateAge > policy.captureStartStallMs ->
                RadioRecoveryDecision(RadioRecoveryComponent.AUDIO, "capture_start_stalled", snapshot.sessionEpoch, snapshot.talkEpoch)
            snapshot.pttState == RadioPttHealthState.TX_ACTIVE &&
                snapshot.captureState == RadioCaptureHealthState.RUNNING &&
                snapshot.encodeErrorStreak >= policy.encodeErrorStreak ->
                RadioRecoveryDecision(RadioRecoveryComponent.AUDIO, "encode_error_streak", snapshot.sessionEpoch, snapshot.talkEpoch)
            snapshot.pttState == RadioPttHealthState.TX_ACTIVE &&
                snapshot.captureFrames > snapshot.encodedFrames + 2 &&
                age(snapshot.nowElapsed, snapshot.lastEncodeElapsed) > policy.encodeNoProgressMs ->
                RadioRecoveryDecision(RadioRecoveryComponent.AUDIO, "encode_no_progress", snapshot.sessionEpoch, snapshot.talkEpoch)
            !snapshot.pttHeld && snapshot.localSpeaking && stateAge > policy.releaseInvariantMs ->
                RadioRecoveryDecision(RadioRecoveryComponent.PTT, "release_invariant", snapshot.sessionEpoch, snapshot.talkEpoch)
            else -> null
        } ?: return null
        val previous = lastRecovery[decision.component] ?: Long.MIN_VALUE
        if (previous != Long.MIN_VALUE && snapshot.nowElapsed - previous < policy.recoveryCooldownMs) return null
        lastRecovery[decision.component] = snapshot.nowElapsed
        return decision
    }

    fun reset() = lastRecovery.clear()

    private fun age(now: Long, then: Long): Long = if (then <= 0L || now < then) 0L else now - then
}
