// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.media

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class SwipeDismissFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var canDismiss: () -> Boolean = { true }
    var onDismiss: () -> Unit = {}
    var onDragStateChanged: (Boolean, Boolean) -> Unit = { _, _ -> }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 5
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var target: View? = null
    private var velocityTracker: VelocityTracker? = null

    fun setDismissTarget(view: View) {
        target = view
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (canDismiss() && dy > touchSlop && abs(dy) > abs(dx) * 1.2f) {
                    dragging = true
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onDragStateChanged(true, false)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging && event.actionMasked != MotionEvent.ACTION_DOWN) return false
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> applyDrag((event.y - downY).coerceAtLeast(0f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishDrag(event.actionMasked == MotionEvent.ACTION_CANCEL)
        }
        return true
    }

    private fun applyDrag(distance: Float) {
        val progress = (distance / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        target?.apply {
            translationY = distance
            val scale = 1f - progress * 0.06f
            scaleX = scale
            scaleY = scale
        }
        setBackgroundColor(Color.argb((255 * (1f - progress * 0.75f)).toInt(), 0, 0, 0))
    }

    private fun finishDrag(cancelled: Boolean) {
        velocityTracker?.computeCurrentVelocity(1000)
        val velocity = velocityTracker?.yVelocity ?: 0f
        val distance = target?.translationY ?: 0f
        val dismiss = !cancelled && (distance > height * 0.23f || velocity > minimumFling)
        velocityTracker?.recycle()
        velocityTracker = null
        dragging = false
        val view = target ?: return
        if (dismiss) {
            view.animate().translationY(height.toFloat()).scaleX(0.94f).scaleY(0.94f).setDuration(180L)
                .withEndAction { onDragStateChanged(false, true); onDismiss() }.start()
        } else {
            view.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(180L)
                .withEndAction { setBackgroundColor(Color.BLACK); onDragStateChanged(false, false) }.start()
        }
    }
}
