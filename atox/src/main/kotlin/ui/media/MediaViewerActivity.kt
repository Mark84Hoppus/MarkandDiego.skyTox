// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.media

import android.app.ActivityManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import kotlin.math.max
import kotlin.math.min
import ltd.evilcorp.atox.R

class MediaViewerActivity : AppCompatActivity() {
    private lateinit var uri: Uri
    private lateinit var displayName: String
    private lateinit var mimeType: String
    private lateinit var mode: MediaViewerMode
    private lateinit var swipeRoot: SwipeDismissFrameLayout
    private lateinit var content: FrameLayout
    private lateinit var imageView: ZoomableImageView
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private var player: ExoPlayer? = null
    private var playbackPosition = 0L
    private var playWhenReady = true
    private var resumeAfterCancelledDismiss = false
    private var playbackFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsedUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val parsedMode = intent.getStringExtra(EXTRA_MODE)?.let { runCatching { MediaViewerMode.valueOf(it) }.getOrNull() }
        if (parsedUri == null || parsedMode == null) {
            finish()
            return
        }
        uri = parsedUri
        mode = parsedMode
        displayName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        mimeType = intent.getStringExtra(EXTRA_MIME).orEmpty().ifBlank { "*/*" }
        playbackPosition = savedInstanceState?.getLong(STATE_POSITION) ?: 0L
        playWhenReady = savedInstanceState?.getBoolean(STATE_PLAY_WHEN_READY) ?: true

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_media_viewer)
        swipeRoot = findViewById(R.id.swipeRoot)
        content = findViewById(R.id.viewerContent)
        imageView = findViewById(R.id.mediaImage)
        playerView = findViewById(R.id.mediaPlayer)
        loading = findViewById(R.id.mediaLoading)
        errorPanel = findViewById(R.id.mediaErrorPanel)
        errorText = findViewById(R.id.mediaErrorText)
        findViewById<ImageButton>(R.id.mediaClose).setOnClickListener { finishWithoutTransition() }
        findViewById<Button>(R.id.mediaOpenExternal).setOnClickListener {
            MediaViewerLauncher.openExternal(this, uri, displayName, mimeType)
        }
        hideSystemBars()
        configureSwipeDismiss()
        if (mode == MediaViewerMode.IMAGE) loadImage() else configureVideoGestures()
    }

    override fun onStart() {
        super.onStart()
        if (mode == MediaViewerMode.VIDEO && !playbackFailed) initializePlayer()
    }

    override fun onStop() {
        if (mode == MediaViewerMode.VIDEO) releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        Picasso.get().cancelRequest(imageView)
        imageView.setImageDrawable(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        player?.let {
            playbackPosition = it.currentPosition
            playWhenReady = it.playWhenReady
        }
        outState.putLong(STATE_POSITION, playbackPosition)
        outState.putBoolean(STATE_PLAY_WHEN_READY, playWhenReady)
        super.onSaveInstanceState(outState)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, swipeRoot).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun configureSwipeDismiss() {
        swipeRoot.setDismissTarget(content)
        swipeRoot.canDismiss = { mode == MediaViewerMode.VIDEO || imageView.isAtMinimumScale() }
        swipeRoot.onDismiss = { finishWithoutTransition() }
        swipeRoot.onDragStateChanged = { dragging, dismissed ->
            if (mode == MediaViewerMode.VIDEO) {
                if (dragging) {
                    resumeAfterCancelledDismiss = player?.isPlaying == true
                    player?.pause()
                } else if (!dismissed && resumeAfterCancelledDismiss) {
                    player?.play()
                }
            }
        }
    }

    private fun loadImage() {
        imageView.visibility = View.INVISIBLE
        playerView.visibility = View.GONE
        val memoryClass = getSystemService(ActivityManager::class.java).memoryClass
        val maxSide = when {
            memoryClass <= 128 -> 2048
            memoryClass <= 256 -> 2560
            else -> 3072
        }
        Picasso.get().load(uri).resize(maxSide, maxSide).centerInside().onlyScaleDown().noFade()
            .into(imageView, object : Callback {
                override fun onSuccess() {
                    loading.visibility = View.GONE
                    imageView.visibility = View.VISIBLE
                    imageView.post { imageView.resetToFit() }
                }

                override fun onError(error: Exception?) = showError(R.string.media_viewer_cannot_display)
            })
    }

    private fun configureVideoGestures() {
        imageView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        playerView.useController = true
        playerView.controllerAutoShow = true
        playerView.controllerHideOnTouch = true
        playerView.controllerShowTimeoutMs = 3000
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.setKeepContentOnPlayerReset(true)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = player ?: return true
                val target = if (e.x < playerView.width / 2f) {
                    max(0L, current.currentPosition - SEEK_STEP_MS)
                } else {
                    val forward = current.currentPosition + SEEK_STEP_MS
                    if (current.duration == C.TIME_UNSET || current.duration <= 0L) forward else min(current.duration, forward)
                }
                current.seekTo(target)
                return true
            }
        })
        playerView.setOnTouchListener { _, event -> detector.onTouchEvent(event); false }
    }

    private fun initializePlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView.player = exoPlayer
            exoPlayer.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            exoPlayer.setHandleAudioBecomingNoisy(true)
            exoPlayer.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    loading.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackFailed = true
                    releasePlayer()
                    showError(R.string.media_viewer_cannot_play)
                }
            })
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.seekTo(playbackPosition)
            exoPlayer.playWhenReady = playWhenReady
        }
    }

    private fun releasePlayer() {
        player?.let {
            playbackPosition = it.currentPosition
            playWhenReady = it.playWhenReady
            it.release()
        }
        player = null
        playerView.player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showError(message: Int) {
        loading.visibility = View.GONE
        imageView.visibility = View.GONE
        playerView.visibility = View.GONE
        errorText.setText(message)
        errorPanel.visibility = View.VISIBLE
    }

    private fun finishWithoutTransition() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_URI = "mediaViewerUri"
        const val EXTRA_NAME = "mediaViewerName"
        const val EXTRA_MIME = "mediaViewerMime"
        const val EXTRA_MODE = "mediaViewerMode"
        private const val STATE_POSITION = "mediaViewerPosition"
        private const val STATE_PLAY_WHEN_READY = "mediaViewerPlayWhenReady"
        private const val SEEK_STEP_MS = 10_000L
    }
}
