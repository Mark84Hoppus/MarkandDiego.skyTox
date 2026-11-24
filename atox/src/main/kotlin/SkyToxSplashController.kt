// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import java.util.concurrent.atomic.AtomicBoolean

class SkyToxSplashController(private val activity: MainActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: FrameLayout? = null
    private var player: MediaPlayer? = null
    private var completed: (() -> Unit)? = null
    private var oldStatusBarColor = Color.TRANSPARENT
    private var oldNavigationBarColor = Color.BLACK
    val isShowing: Boolean get() = overlay != null

    fun showIfEligible(
        enabled: Boolean,
        onComplete: () -> Unit,
    ): Boolean {
        // This process-local gate is the lifecycle rule: show once after Android
        // creates a new process, but never when the existing process is resumed.
        if (!enabled) return false
        if (!shownInThisProcess.compareAndSet(false, true)) return false
        completed = onComplete

        val parent = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            shownInThisProcess.set(false)
            return false
        }
        val container = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            elevation = 1000f
        }
        val video = TextureView(activity)
        container.addView(video, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        parent.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = container
        oldStatusBarColor = activity.window.statusBarColor
        oldNavigationBarColor = activity.window.navigationBarColor
        activity.window.statusBarColor = Color.BLACK
        activity.window.navigationBarColor = Color.BLACK
        container.doOnLayout {
            val side = minOf(container.width, container.height).coerceAtLeast(1)
            video.layoutParams = FrameLayout.LayoutParams(side, side, Gravity.CENTER)
        }
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                preparePlayer(surfaceTexture)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                dismiss(immediate = true, runCompletion = false)
                return true
            }
        }
        if (video.isAvailable) {
            video.surfaceTexture?.let(::preparePlayer)
        }
        handler.postDelayed({ dismiss(immediate = false) }, MAX_VISIBLE_MS)
        return true
    }

    private fun preparePlayer(surfaceTexture: SurfaceTexture) {
        if (overlay == null || player != null) return
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        val surface = Surface(surfaceTexture)
        mediaPlayer.setSurface(surface)
        surface.release()
        mediaPlayer.setOnPreparedListener {
            if (overlay == null) return@setOnPreparedListener
            it.setVolume(1f, 1f)
            it.start()
        }
        mediaPlayer.setOnCompletionListener { dismiss(immediate = false) }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            dismiss(immediate = true)
            true
        }
        runCatching {
            mediaPlayer.setDataSource(activity, Uri.parse("android.resource://${activity.packageName}/${R.raw.skytox_splash}"))
            mediaPlayer.prepareAsync()
        }.onFailure { dismiss(immediate = true) }
    }

    fun dismiss(immediate: Boolean = true, runCompletion: Boolean = true) {
        handler.removeCallbacksAndMessages(null)
        val current = overlay ?: return
        val finish = {
            overlay = null
            (current.parent as? ViewGroup)?.removeView(current)
            releasePlayer()
            activity.window.statusBarColor = oldStatusBarColor
            activity.window.navigationBarColor = oldNavigationBarColor
            if (runCompletion) completed?.invoke()
            completed = null
        }
        if (immediate) finish() else current.animate().alpha(0f).setDuration(160L).withEndAction(finish).start()
    }

    fun release() {
        dismiss(immediate = true, runCompletion = false)
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }

    companion object {
        private const val MAX_VISIBLE_MS = 3_700L
        private val shownInThisProcess = AtomicBoolean(false)
    }
}
