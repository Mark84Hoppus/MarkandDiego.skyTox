// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.myskytox.files.audio

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import ltd.evilcorp.atox.R

class FileManagerAudioPreviewBar(context: Context) : LinearLayout(context) {
    val playPauseButton = ImageButton(context)
    val seekBar = SeekBar(context)
    val closeButton = ImageButton(context)

    var onToggle: (() -> Unit)? = null
    var onSeekStart: (() -> Unit)? = null
    var onSeekStop: ((Int) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        visibility = View.GONE
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.ocean_card))
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), ContextCompat.getColor(context, R.color.filemanager_window_border))
        }
        playPauseButton.apply {
            setImageResource(R.drawable.ic_fm_play)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = context.getString(R.string.filemanager_audio_play_pause)
            setOnClickListener { onToggle?.invoke() }
        }
        seekBar.apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(seekBar: SeekBar?) { onSeekStart?.invoke() }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { onSeekStop?.invoke(progress) }
            })
        }
        closeButton.apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = context.getString(R.string.filemanager_audio_close)
            setOnClickListener { onClose?.invoke() }
        }
        addView(playPauseButton, LayoutParams(dp(44), LayoutParams.MATCH_PARENT))
        addView(seekBar, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(closeButton, LayoutParams(dp(44), LayoutParams.MATCH_PARENT))
    }

    fun setPlaying(playing: Boolean) {
        playPauseButton.setImageResource(if (playing) R.drawable.ic_fm_pause else R.drawable.ic_fm_play)
    }

    fun setProgressFraction(fraction: Float) {
        seekBar.progress = (fraction.coerceIn(0f, 1f) * seekBar.max).roundToInt()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
