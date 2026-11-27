// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.myskytox.files.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class FileManagerAudioPreviewController(
    private val context: Context,
    private val bar: FileManagerAudioPreviewBar,
    private val onError: () -> Unit,
) {
    var currentRelativePath: String? = null
        private set

    private var player: ExoPlayer? = null
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            if (player != null) handler.postDelayed(this, 250L)
        }
    }

    init {
        bar.onToggle = ::toggle
        bar.onSeekStart = { userSeeking = true }
        bar.onSeekStop = { progress -> seekToFraction(progress / 1000f); userSeeking = false }
        bar.onClose = ::close
    }

    fun open(relativePath: String, file: File) {
        releasePlayer()
        currentRelativePath = relativePath
        val created = ExoPlayer.Builder(context).build()
        player = created
        created.apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { bar.setPlaying(isPlaying) }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) { bar.setProgressFraction(1f); bar.setPlaying(false) }
                }
                override fun onPlayerError(error: PlaybackException) { onError(); close() }
            })
            setMediaItem(MediaItem.fromUri(file.toUri()))
            prepare()
            playWhenReady = true
        }
        bar.setProgressFraction(0f)
        bar.setPlaying(true)
        bar.visibility = View.VISIBLE
        handler.removeCallbacks(progressUpdater)
        handler.post(progressUpdater)
    }

    fun toggle() {
        val current = player ?: return
        if (current.isPlaying) current.pause() else {
            if (current.playbackState == Player.STATE_ENDED) current.seekTo(0L)
            current.play()
        }
    }

    fun seekToFraction(fraction: Float) {
        val current = player ?: return
        val duration = current.duration
        if (duration > 0 && duration != C.TIME_UNSET) current.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun close() {
        releasePlayer()
        currentRelativePath = null
        bar.setProgressFraction(0f)
        bar.setPlaying(false)
        bar.visibility = View.GONE
    }

    fun release() = close()

    private fun updateProgress() {
        val current = player ?: return
        val duration = current.duration
        if (!userSeeking && duration > 0 && duration != C.TIME_UNSET) {
            bar.setProgressFraction(current.currentPosition.toFloat() / duration.toFloat())
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressUpdater)
        player?.release()
        player = null
        userSeeking = false
    }
}
