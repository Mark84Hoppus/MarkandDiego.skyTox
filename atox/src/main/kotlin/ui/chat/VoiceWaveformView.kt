// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bars = 36
        val gap = width.toFloat() / bars
        val center = height / 2f
        val maxHeight = height * 0.72f
        for (i in 0 until bars) {
            val seed = abs(((i + 7) * 1103515245 + 12345) % 100) / 100f
            val barHeight = max(8f, maxHeight * (0.25f + seed * 0.75f))
            val x = i * gap + gap / 2f
            val paint = if (i.toFloat() / bars <= progress) playedPaint else idlePaint
            canvas.drawLine(x, center - barHeight / 2f, x, center + barHeight / 2f, paint)
        }
    }
}
