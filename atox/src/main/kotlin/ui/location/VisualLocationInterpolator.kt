// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.location

import android.os.SystemClock
import kotlin.math.abs

/** UI-only interpolation. It never changes the location sent through Tox. */
internal class VisualLocationInterpolator(
    private val durationMs: Long = 1_100L,
) {
    private var from: SkyToxSharedLocation? = null
    private var target: SkyToxSharedLocation? = null
    private var startedAtMs = 0L

    fun setTarget(value: SkyToxSharedLocation?, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (value == null) {
            clear()
            return true
        }
        val oldTarget = target
        if (oldTarget != null && value.timestamp < oldTarget.timestamp) return false
        val current = valueAt(nowMs) ?: value
        from = current
        target = value
        startedAtMs = nowMs
        return true
    }

    fun valueAt(nowMs: Long = SystemClock.elapsedRealtime()): SkyToxSharedLocation? {
        val end = target ?: return null
        val start = from ?: return end
        val linear = ((nowMs - startedAtMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
        val t = linear * linear * (3.0 - 2.0 * linear)
        return end.copy(
            latitude = lerp(start.latitude, end.latitude, t),
            longitude = lerp(start.longitude, end.longitude, t),
            accuracy = lerp(start.accuracy.toDouble(), end.accuracy.toDouble(), t).toFloat(),
            altitude = if (start.altitude.isFinite() && end.altitude.isFinite()) {
                lerp(start.altitude, end.altitude, t)
            } else {
                end.altitude
            },
            speedMps = lerp(start.speedMps.toDouble(), end.speedMps.toDouble(), t).toFloat(),
        )
    }

    fun isAnimating(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        target != null && nowMs - startedAtMs < durationMs

    fun clear() {
        from = null
        target = null
        startedAtMs = 0L
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
}

internal fun smoothBearing(current: Float, target: Float, factor: Float): Float {
    val delta = ((target - current + 540f) % 360f) - 180f
    if (abs(delta) < 0.05f) return target
    return (current + delta * factor).mod(360f)
}
