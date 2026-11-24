// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.media

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    private val drawMatrix = Matrix()
    private var normalizedScale = 1f
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (normalizedScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val factor = target / normalizedScale
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            normalizedScale = target
            correctBounds()
            imageMatrix = drawMatrix
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (normalizedScale <= MIN_SCALE + 0.02f || scaleDetector.isInProgress) return false
            drawMatrix.postTranslate(-distanceX, -distanceY)
            correctBounds()
            imageMatrix = drawMatrix
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateScale(if (normalizedScale <= 1.05f) DOUBLE_TAP_SCALE else MIN_SCALE, e.x, e.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    fun resetToFit() {
        val drawable = drawable ?: return
        if (width == 0 || height == 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
        val scale = min(width.toFloat() / drawable.intrinsicWidth, height.toFloat() / drawable.intrinsicHeight)
        val dx = (width - drawable.intrinsicWidth * scale) / 2f
        val dy = (height - drawable.intrinsicHeight * scale) / 2f
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        normalizedScale = MIN_SCALE
        imageMatrix = drawMatrix
    }

    fun isAtMinimumScale(tolerance: Float = 1.02f): Boolean = normalizedScale <= tolerance

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        if (normalizedScale > 1.02f || scaleDetector.isInProgress) parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) correctBounds()
        return handled || scaleDetector.isInProgress || super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { resetToFit() }
    }

    private fun animateScale(targetScale: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        var previous = normalizedScale
        zoomAnimator = ValueAnimator.ofFloat(normalizedScale, targetScale).apply {
            duration = 220L
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                val factor = value / previous
                drawMatrix.postScale(factor, factor, focusX, focusY)
                normalizedScale = value
                previous = value
                correctBounds()
                imageMatrix = drawMatrix
            }
            start()
        }
    }

    private fun correctBounds() {
        val drawable = drawable ?: return
        val bounds = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        drawMatrix.mapRect(bounds)
        val dx = when {
            bounds.width() <= width -> width / 2f - bounds.centerX()
            bounds.left > 0f -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> height / 2f - bounds.centerY()
            bounds.top > 0f -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
    }

    override fun onDetachedFromWindow() {
        zoomAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val MAX_SCALE = 5f
    }
}
