// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import ltd.evilcorp.atox.R

enum class TransferVisualState { ACTIVE, WAITING, PAUSED, COMPLETE, FAILED }

class TransferLottieProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val lottie = LottieAnimationView(context).apply {
        setAnimation(R.raw.skytox_transfer_progress)
        repeatCount = LottieDrawable.INFINITE
        repeatMode = LottieDrawable.RESTART
        scaleType = android.widget.ImageView.ScaleType.FIT_XY
        val darkTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val lineColor = if (darkTheme) android.graphics.Color.WHITE else {
            ContextCompat.getColor(context, R.color.ocean_text_primary)
        }
        addValueCallback(
            KeyPath("**"),
            LottieProperty.COLOR_FILTER,
            LottieValueCallback(SimpleColorFilter(lineColor)),
        )
        // The approved JSON uses a 640x140 canvas, while its actual capsule is 560x54.
        // Compensate only those transparent margins so the animation fills our oval.
        scaleX = 640f / 560f
        scaleY = 140f / 54f
    }
    private val progressCover = View(context)
    private val percent = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 11f
        setTextColor(ContextCompat.getColor(context, R.color.ocean_on_bubble))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.ocean_text_primary)
    }
    private var state = TransferVisualState.WAITING
    private var uiActive = true
    private var attached = false
    private var progressFraction = 0f

    init {
        setWillNotDraw(false)
        clipChildren = true
        clipToPadding = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        clipToOutline = true
        addView(lottie, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(progressCover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(percent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        lottie.pivotX = width / 2f
        lottie.pivotY = height / 2f
    }

    fun bind(state: TransferVisualState, progress: Float, outgoing: Boolean) {
        resetAnimationOnly()
        this.state = state
        progressCover.setBackgroundColor(
            ContextCompat.getColor(
                context,
                if (outgoing) R.color.ocean_bubble_sent else R.color.ocean_file_transfer_surface,
            ),
        )
        setProgress(progress)
        updateAnimationState()
    }

    fun setProgress(progress: Float) {
        progressFraction = progress.coerceIn(0f, 1f)
        percent.text = "${(progressFraction * 100f).toInt()}%"
        percent.contentDescription = percent.text
        applyCoverTransform()
    }

    fun setUiActive(active: Boolean) {
        uiActive = active
        updateAnimationState()
    }

    fun reset() {
        state = TransferVisualState.WAITING
        progressFraction = 0f
        percent.text = "0%"
        resetAnimationOnly()
        applyCoverTransform()
    }

    private fun resetAnimationOnly() {
        lottie.cancelAnimation()
        lottie.visibility = INVISIBLE
    }

    private fun updateAnimationState() {
        when (state) {
            TransferVisualState.ACTIVE -> {
                lottie.visibility = VISIBLE
                if (attached && uiActive) lottie.playAnimation() else lottie.pauseAnimation()
            }
            TransferVisualState.PAUSED -> {
                lottie.visibility = VISIBLE
                lottie.pauseAnimation()
            }
            TransferVisualState.WAITING,
            TransferVisualState.COMPLETE,
            TransferVisualState.FAILED,
            -> resetAnimationOnly()
        }
    }

    private fun applyCoverTransform() {
        progressCover.post {
            progressCover.pivotX = progressCover.width.toFloat()
            progressCover.scaleX = 1f - progressFraction
            progressCover.translationX = 0f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updateAnimationState()
    }

    override fun onDetachedFromWindow() {
        attached = false
        lottie.pauseAnimation()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
        lottie.pivotX = w / 2f
        lottie.pivotY = h / 2f
        applyCoverTransform()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val inset = borderPaint.strokeWidth / 2f
        val radius = (height - borderPaint.strokeWidth) / 2f
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, borderPaint)
    }
}
