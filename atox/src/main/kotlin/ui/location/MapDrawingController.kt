// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.location

import kotlin.math.hypot

internal data class DrawingPoint(
    val lat: Double,
    val lon: Double,
    val screenX: Float,
    val screenY: Float,
)

internal class MapDrawingController(
    private val minimumScreenDistancePx: Float,
    private val maximumPoints: Int = 7_500,
) {
    enum class State { IDLE, ARMED, DRAWING }

    var state: State = State.IDLE
        private set
    private var activePointerId = INVALID_POINTER
    private val points = ArrayList<DrawingPoint>()

    fun arm(): Boolean {
        if (state != State.IDLE) return false
        points.clear()
        activePointerId = INVALID_POINTER
        state = State.ARMED
        return true
    }

    fun disarm() {
        reset()
    }

    fun onDown(pointerId: Int, point: DrawingPoint): Boolean {
        if (state != State.ARMED || !point.valid()) return false
        points.clear()
        points += point
        activePointerId = pointerId
        state = State.DRAWING
        return true
    }

    fun onMove(pointerId: Int, point: DrawingPoint): Boolean {
        if (state != State.DRAWING || pointerId != activePointerId || !point.valid()) return false
        if (points.size >= maximumPoints) return true
        val previous = points.last()
        if (hypot((point.screenX - previous.screenX).toDouble(), (point.screenY - previous.screenY).toDouble()) >= minimumScreenDistancePx) {
            points += point
        }
        return true
    }

    fun onUp(pointerId: Int, point: DrawingPoint?): List<DrawingPoint> {
        if (state != State.DRAWING || pointerId != activePointerId) {
            reset()
            return emptyList()
        }
        if (point != null && point.valid() && points.size < maximumPoints) {
            val previous = points.last()
            if (hypot((point.screenX - previous.screenX).toDouble(), (point.screenY - previous.screenY).toDouble()) >= 1.0) {
                points += point
            }
        }
        val result = if (points.size >= 2 && totalScreenLength() >= minimumScreenDistancePx * 2f) points.toList() else emptyList()
        reset()
        return result
    }

    fun cancel() = reset()
    fun preview(): List<DrawingPoint> = points.toList()

    private fun totalScreenLength(): Float = points.zipWithNext().sumOf { (a, b) ->
        hypot((b.screenX - a.screenX).toDouble(), (b.screenY - a.screenY).toDouble())
    }.toFloat()

    private fun DrawingPoint.valid() = lat.isFinite() && lon.isFinite() && lat in -85.0..85.0 && lon in -180.0..180.0

    private fun reset() {
        points.clear()
        activePointerId = INVALID_POINTER
        state = State.IDLE
    }

    companion object { private const val INVALID_POINTER = -1 }
}
