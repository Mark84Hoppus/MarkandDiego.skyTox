// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.radio

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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ltd.evilcorp.atox.MainActivity
import ltd.evilcorp.atox.R
import markanddiego.skytox.radio.RadioEngine
import markanddiego.skytox.radio.RadioListener
import markanddiego.skytox.radio.RadioLifecyclePolicy
import markanddiego.skytox.radio.RadioSnapshot

class RadioService : Service(), RadioListener {
    private enum class SessionState { IDLE, DISCOVERING, JOINING, ACTIVE, BACKGROUND_GRACE, STOPPING }

    private lateinit var engine: RadioEngine
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val hotspot by lazy {
        RadioLocalHotspotController(this, ::onHotspotState) { message ->
            if (started) engine.diagnosticEvent(message)
        }
    }
    private var started = false
    private var sessionState = SessionState.IDLE
    private val lifecyclePolicy = RadioLifecyclePolicy(BACKGROUND_KEEP_MS)
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            handler.post {
                if (started && sessionState !in setOf(SessionState.STOPPING, SessionState.IDLE)) {
                    engine.diagnosticEvent("audio_focus_lost change=$change")
                    engine.pttUp()
                }
            }
        }
    }
    private val backgroundStop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (lifecyclePolicy.shouldStop(now)) stopForBackgroundTimeout()
            else lifecyclePolicy.backgroundDeadlineAt.takeIf { it > now }?.let {
                handler.postDelayed(this, it - now)
            }
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) scheduleBackgroundStop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_LEAVE) {
            explicitLeave()
            return START_NOT_STICKY
        }
        if (!started && action !in setOf(ACTION_START, ACTION_VISIBLE)) return START_NOT_STICKY
        ensureStarted(intent?.getStringExtra(EXTRA_NICKNAME))
        engine.diagnosticEvent("service_action action=${action.substringAfterLast('.')}")
        when (action) {
            ACTION_START, ACTION_VISIBLE -> {
                handler.removeCallbacks(backgroundStop)
                lifecyclePolicy.visible()
                sessionState = if (RadioRuntime.state.value.channel == null) SessionState.DISCOVERING else SessionState.ACTIVE
                restoreRecentChannel()
            }
            ACTION_HIDDEN -> scheduleBackgroundStop()
            ACTION_MANUAL_ENDPOINT -> intent?.getStringExtra(EXTRA_ENDPOINT)?.let(engine::addManualEndpoint)
            ACTION_REFRESH_NETWORK -> engine.refreshNetwork()
            ACTION_START_HOTSPOT -> hotspot.start()
            ACTION_STOP_HOTSPOT -> {
                hotspot.stop()
                handler.postDelayed(engine::refreshNetwork, 500)
            }
            ACTION_CREATE -> remember(engine.createChannel())
            ACTION_JOIN -> intent?.getIntExtra(EXTRA_CHANNEL, 0)?.takeIf { it in 1000..9999 }?.let {
                sessionState = SessionState.JOINING
                engine.joinChannel(it)
                remember(it)
            }
            ACTION_PTT_DOWN -> {
                requestAudioFocus()
                engine.pttDown()
            }
            ACTION_PTT_UP -> {
                engine.pttUp()
                abandonAudioFocus()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureStarted(name: String?) {
        if (started) return
        started = true
        sessionState = SessionState.DISCOVERING
        val nickname = name?.takeIf { it.isNotBlank() } ?: RadioRuntime.nickname
        engine = RadioEngine(this, nickname, this)
        startForeground(NOTIFICATION_ID, notification())
        engine.start()
        engine.diagnosticEvent("foreground_service_started")
    }

    private fun restoreRecentChannel() {
        if (RadioRuntime.state.value.channel != null) return
        val remembered = prefs.getInt(KEY_CHANNEL, 0)
        val until = prefs.getLong(KEY_UNTIL, 0)
        if (RadioLifecyclePolicy.canAutoRejoin(remembered, until, System.currentTimeMillis())) {
            engine.diagnosticEvent("auto_rejoin_attempt remaining_ms=${until - System.currentTimeMillis()}")
            engine.joinChannel(remembered)
        } else if (until != 0L) {
            engine.diagnosticEvent("auto_rejoin_expired")
            prefs.edit().remove(KEY_CHANNEL).remove(KEY_UNTIL).commit()
        }
    }

    private fun remember(channel: Int) {
        prefs.edit().putInt(KEY_CHANNEL, channel)
            .putLong(KEY_UNTIL, System.currentTimeMillis() + REJOIN_MS).commit()
    }

    private fun scheduleBackgroundStop() {
        if (!started || lifecyclePolicy.backgroundDeadlineAt != 0L) return
        val deadline = lifecyclePolicy.hidden(System.currentTimeMillis())
        sessionState = SessionState.BACKGROUND_GRACE
        engine.diagnosticEvent("background_stop_scheduled deadline_ms=$deadline")
        handler.postDelayed(backgroundStop, BACKGROUND_KEEP_MS)
    }

    private fun stopForBackgroundTimeout() {
        if (started) engine.diagnosticEvent("background_timeout_stop")
        stopRadioService()
    }

    private fun explicitLeave() {
        handler.removeCallbacks(backgroundStop)
        lifecyclePolicy.explicitLeave()
        sessionState = SessionState.STOPPING
        val cleared = prefs.edit().remove(KEY_CHANNEL).remove(KEY_UNTIL).commit()
        if (started) engine.diagnosticEvent("service_explicit_leave prefs_cleared=$cleared")
        stopRadioService()
    }

    private fun stopRadioService() {
        sessionState = SessionState.STOPPING
        if (started) engine.diagnosticEvent("foreground_service_stopping")
        if (started) engine.stop()
        hotspot.stop()
        started = false
        lifecyclePolicy.visible()
        RadioRuntime.state.value = RadioSnapshot()
        RadioRuntime.hotspot.value = RadioHotspotState()
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        sessionState = SessionState.IDLE
        stopSelf()
    }

    override fun onSnapshot(snapshot: RadioSnapshot) {
        if (!started || sessionState == SessionState.STOPPING || sessionState == SessionState.IDLE) return
        RadioRuntime.state.value = snapshot
        sessionState = when {
            sessionState == SessionState.BACKGROUND_GRACE -> SessionState.BACKGROUND_GRACE
            snapshot.channel == null -> SessionState.DISCOVERING
            snapshot.peers.isEmpty() -> SessionState.JOINING
            else -> SessionState.ACTIVE
        }
        if (snapshot.localSpeaking) requestAudioFocus()
        if (!snapshot.localSpeaking && snapshot.activeSpeaker == null) abandonAudioFocus()
    }

    override fun onError(message: String) {
        if (!started || sessionState == SessionState.STOPPING || sessionState == SessionState.IDLE) return
        RadioRuntime.state.value = RadioRuntime.state.value.copy(supported = false)
    }

    private fun onHotspotState(value: RadioHotspotState) {
        RadioRuntime.hotspot.value = value
        if (started && value.active) handler.postDelayed(engine::refreshNetwork, 1_000)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build().also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusChangeListener)
    }

    private fun notification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_radio)
        .setContentTitle(getString(R.string.radio_title))
        .setContentText(getString(R.string.radio_notification))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL, getString(R.string.radio_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleBackgroundStop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(backgroundStop)
        if (started) engine.stop()
        hotspot.stop()
        started = false
        runCatching { unregisterReceiver(screenReceiver) }
        abandonAudioFocus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ltd.evilcorp.atox.radio.START"
        const val ACTION_VISIBLE = "ltd.evilcorp.atox.radio.VISIBLE"
        const val ACTION_HIDDEN = "ltd.evilcorp.atox.radio.HIDDEN"
        const val ACTION_MANUAL_ENDPOINT = "ltd.evilcorp.atox.radio.MANUAL_ENDPOINT"
        const val ACTION_REFRESH_NETWORK = "ltd.evilcorp.atox.radio.REFRESH_NETWORK"
        const val ACTION_START_HOTSPOT = "ltd.evilcorp.atox.radio.START_HOTSPOT"
        const val ACTION_STOP_HOTSPOT = "ltd.evilcorp.atox.radio.STOP_HOTSPOT"
        const val ACTION_CREATE = "ltd.evilcorp.atox.radio.CREATE"
        const val ACTION_JOIN = "ltd.evilcorp.atox.radio.JOIN"
        const val ACTION_PTT_DOWN = "ltd.evilcorp.atox.radio.PTT_DOWN"
        const val ACTION_PTT_UP = "ltd.evilcorp.atox.radio.PTT_UP"
        const val ACTION_LEAVE = "ltd.evilcorp.atox.radio.LEAVE"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_NICKNAME = "nickname"
        const val EXTRA_ENDPOINT = "endpoint"
        private const val PREFS = "skytox_radio"
        private const val KEY_CHANNEL = "channel"
        private const val KEY_UNTIL = "rejoin_until"
        private const val REJOIN_MS = 60 * 60 * 1000L
        private const val BACKGROUND_KEEP_MS = 10 * 60 * 1000L
        private const val NOTIFICATION_ID = 7350
        private const val NOTIFICATION_CHANNEL = "skytox_radio"
    }
}
