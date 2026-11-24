// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ltd.evilcorp.domain.feature.SkyToxPublicFolders

class SkyToxMusicService : Service() {
    private var player: MediaPlayer? = null
    private var fadingPlayer: MediaPlayer? = null
    private val fadeHandler = Handler(Looper.getMainLooper())
    private var fadeGeneration = 0
    private var files = emptyList<java.io.File>()
    private var index = 0
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeAfterFocusGain = false
    private var noisyReceiverRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeAfterFocusGain) {
                resumeAfterFocusGain = false
                player?.start()
                publishState()
            }
            AudioManager.AUDIOFOCUS_LOSS -> { resumeAfterFocusGain = false; pausePlayback() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> { resumeAfterFocusGain = player?.isPlaying == true; pausePlayback() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        audioManager = getSystemService(AudioManager::class.java)
        mediaSession = MediaSessionCompat(this, "SkyToxMusic").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = toggle(forcePlay = true)
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = playRelative(1)
                override fun onSkipToPrevious() = playRelative(-1)
                override fun onStop() = closePlayer()
                override fun onSeekTo(pos: Long) {
                    player?.seekTo(pos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    publishState()
                }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        publishState()
        when (intent?.action ?: ACTION_TOGGLE) {
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> playRelative(1)
            ACTION_PREVIOUS -> playRelative(-1)
            ACTION_RANDOM -> { random = !random; publishState() }
            ACTION_CLOSE -> { closePlayer(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadFiles(): List<java.io.File> = SkyToxPublicFolders.musicDir
        .listFiles { file -> file.isFile && file.extension.equals("mp3", ignoreCase = true) }
        ?.sortedWith(compareBy<java.io.File>({ if (it.name.firstOrNull()?.isDigit() == true) 1 else 0 }, { it.name.lowercase() }))
        .orEmpty()

    private fun toggle(forcePlay: Boolean = false) {
        val current = player
        when {
            current?.isPlaying == true && !forcePlay -> pausePlayback()
            current != null -> if (requestAudioFocus()) current.start()
            else -> {
                files = loadFiles()
                if (files.isEmpty()) {
                    Toast.makeText(this, R.string.music_no_files, Toast.LENGTH_LONG).show()
                    closePlayer()
                    return
                }
                index = index.coerceIn(files.indices)
                playAt(index)
                return
            }
        }
        publishState()
    }

    private fun pausePlayback() {
        cancelCrossfade()
        if (player?.isPlaying == true) player?.pause()
        publishState()
    }

    private fun playRelative(delta: Int) {
        files = loadFiles()
        if (files.isEmpty()) { closePlayer(); return }
        index = if (random) files.indices.random() else (index + delta).mod(files.size)
        playAt(index)
    }

    private fun playAt(position: Int) {
        if (!requestAudioFocus()) return
        cancelCrossfade()
        val previous = player
        val shouldCrossfade = previous?.isPlaying == true
        index = position
        val next = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(files[index].absolutePath)
            setOnCompletionListener { playRelative(1) }
            setOnErrorListener { _, _, _ -> publishState(); true }
            prepare()
            setVolume(if (shouldCrossfade) 0f else 1f, if (shouldCrossfade) 0f else 1f)
            start()
        }
        player = next
        if (shouldCrossfade && previous != null) startCrossfade(previous, next) else previous?.release()
        active = true
        publishMetadata()
        publishState()
    }

    private fun startCrossfade(previous: MediaPlayer, next: MediaPlayer) {
        previous.setOnCompletionListener(null)
        previous.setOnErrorListener(null)
        fadingPlayer = previous
        val generation = ++fadeGeneration
        val startedAt = android.os.SystemClock.uptimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (generation != fadeGeneration || player !== next || fadingPlayer !== previous) return
                val progress = ((android.os.SystemClock.uptimeMillis() - startedAt).toFloat() / CROSSFADE_MS).coerceIn(0f, 1f)
                runCatching { next.setVolume(progress, progress) }
                runCatching { previous.setVolume(1f - progress, 1f - progress) }
                if (progress >= 1f) {
                    runCatching { previous.stop() }
                    previous.release()
                    fadingPlayer = null
                } else {
                    fadeHandler.postDelayed(this, CROSSFADE_STEP_MS)
                }
            }
        }
        fadeHandler.post(tick)
    }

    private fun cancelCrossfade() {
        fadeGeneration++
        fadeHandler.removeCallbacksAndMessages(null)
        fadingPlayer?.release()
        fadingPlayer = null
        runCatching { player?.setVolume(1f, 1f) }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener).build().also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun publishMetadata() {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, files.getOrNull(index)?.nameWithoutExtension.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player?.duration?.toLong() ?: 0L)
                .build(),
        )
    }

    private fun publishState() {
        playing = player?.isPlaying == true
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SEEK_TO
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder().setActions(actions).setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                player?.currentPosition?.toLong() ?: 0L,
                if (playing) 1f else 0f,
            ).build(),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(files.getOrNull(index)?.nameWithoutExtension ?: getString(R.string.music))
            .setContentText(getString(R.string.app_name))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PENDING_FLAGS))
            .setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setOngoing(playing)
            .addAction(R.drawable.ic_music_previous, ACTION_LABEL_PREVIOUS, actionIntent(ACTION_PREVIOUS, 1))
            .addAction(if (playing) R.drawable.ic_music_pause else R.drawable.ic_play, if (playing) ACTION_LABEL_PAUSE else ACTION_LABEL_PLAY, actionIntent(ACTION_TOGGLE, 2))
            .addAction(R.drawable.ic_music_next, ACTION_LABEL_NEXT, actionIntent(ACTION_NEXT, 3))
            .addAction(R.drawable.ic_close, ACTION_LABEL_CLOSE, actionIntent(ACTION_CLOSE, 4))
            .build()
        startForeground(NOTIFICATION_ID, notification)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, SkyToxMusicService::class.java).setAction(action), PENDING_FLAGS,
    )

    @Suppress("DEPRECATION")
    private fun closePlayer() {
        cancelCrossfade()
        player?.release()
        player = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        else audioManager.abandonAudioFocus(focusListener)
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f).build())
        playing = false
        active = false
        stopForeground(true)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        stopSelf()
    }

    override fun onDestroy() {
        if (noisyReceiverRegistered) unregisterReceiver(noisyReceiver)
        cancelCrossfade()
        player?.release()
        mediaSession.release()
        playing = false
        active = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.music), NotificationManager.IMPORTANCE_LOW).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        const val ACTION_TOGGLE = "markanddiego.skytox.music.TOGGLE"
        const val ACTION_NEXT = "markanddiego.skytox.music.NEXT"
        const val ACTION_PREVIOUS = "markanddiego.skytox.music.PREVIOUS"
        const val ACTION_RANDOM = "markanddiego.skytox.music.RANDOM"
        const val ACTION_CLOSE = "markanddiego.skytox.music.CLOSE"
        const val ACTION_STATE_CHANGED = "markanddiego.skytox.music.STATE_CHANGED"
        private const val CHANNEL_ID = "skytox_music"
        private const val NOTIFICATION_ID = 7342
        private const val CROSSFADE_MS = 2_000f
        private const val CROSSFADE_STEP_MS = 50L
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private const val ACTION_LABEL_PREVIOUS = "|<"
        private const val ACTION_LABEL_PLAY = ">"
        private const val ACTION_LABEL_PAUSE = "||"
        private const val ACTION_LABEL_NEXT = ">|"
        private const val ACTION_LABEL_CLOSE = "X"
        @Volatile var active = false
        @Volatile var playing = false
        @Volatile var random = false
    }
}
