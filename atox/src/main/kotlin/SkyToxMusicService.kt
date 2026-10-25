// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import ltd.evilcorp.domain.feature.SkyToxPublicFolders

class SkyToxMusicService : Service() {
    private var player: MediaPlayer? = null
    private var files = emptyList<java.io.File>()
    private var index = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_TOGGLE) {
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> playRelative(1)
            ACTION_PREVIOUS -> playRelative(-1)
            ACTION_RANDOM -> {
                random = !random
                if (active) publishState() else stopSelf()
            }
            ACTION_CLOSE -> {
                closePlayer()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadFiles(): List<java.io.File> = SkyToxPublicFolders.musicDir
        .listFiles { file -> file.isFile && file.extension.equals("mp3", ignoreCase = true) }
        ?.sortedWith(
            compareBy<java.io.File>(
                { if (it.name.firstOrNull()?.isDigit() == true) 1 else 0 },
                { it.name.lowercase() },
            ),
        )
        .orEmpty()

    private fun toggle() {
        val current = player
        when {
            current?.isPlaying == true -> current.pause()
            current != null -> current.start()
            else -> {
                files = loadFiles()
                if (files.isEmpty()) {
                    Toast.makeText(this, R.string.music_no_files, Toast.LENGTH_LONG).show()
                    stopSelf()
                    return
                }
                index = index.coerceIn(files.indices)
                playAt(index)
                return
            }
        }
        publishState()
    }

    private fun playRelative(delta: Int) {
        files = loadFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.music_no_files, Toast.LENGTH_LONG).show()
            closePlayer()
            return
        }
        index = if (random) files.indices.random() else (index + delta).mod(files.size)
        playAt(index)
    }

    private fun playAt(position: Int) {
        player?.release()
        index = position
        player = MediaPlayer().apply {
            setDataSource(files[index].absolutePath)
            setOnCompletionListener { playRelative(1) }
            setOnErrorListener { _, _, _ ->
                publishState()
                true
            }
            prepare()
            start()
        }
        active = true
        publishState()
    }

    private fun publishState() {
        playing = player?.isPlaying == true
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(getString(R.string.music))
            .setContentText(files.getOrNull(index)?.name.orEmpty())
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PENDING_FLAGS),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(
                R.drawable.ic_music_previous,
                ACTION_LABEL_PREVIOUS,
                actionIntent(ACTION_PREVIOUS, 1),
            )
            .addAction(
                if (playing) R.drawable.ic_music_pause else R.drawable.ic_play,
                if (playing) ACTION_LABEL_PAUSE else ACTION_LABEL_PLAY,
                actionIntent(ACTION_TOGGLE, 2),
            )
            .addAction(R.drawable.ic_music_next, ACTION_LABEL_NEXT, actionIntent(ACTION_NEXT, 3))
            .addAction(R.drawable.ic_close, ACTION_LABEL_CLOSE, actionIntent(ACTION_CLOSE, 4))
            .build()
        startForeground(NOTIFICATION_ID, notification)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, SkyToxMusicService::class.java).setAction(action),
        PENDING_FLAGS,
    )

    private fun closePlayer() {
        player?.release()
        player = null
        playing = false
        active = false
        stopForeground(true)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        playing = false
        active = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.music), NotificationManager.IMPORTANCE_LOW),
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
