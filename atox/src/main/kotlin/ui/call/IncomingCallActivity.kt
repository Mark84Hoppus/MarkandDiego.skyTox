// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.call

import android.Manifest
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDeepLinkBuilder
import javax.inject.Inject
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import ltd.evilcorp.atox.App
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.ActivityIncomingCallBinding
import ltd.evilcorp.atox.hasPermission
import ltd.evilcorp.atox.ui.chat.CONTACT_PUBLIC_KEY
import ltd.evilcorp.core.repository.ContactRepository
import ltd.evilcorp.core.vo.Contact
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.CallManager
import ltd.evilcorp.atox.ui.NotificationHelper

private const val EXTRA_CONTACT_PUBLIC_KEY = "contactPublicKey"
private const val WAKE_LOCK_TIMEOUT_MS = 5_000L

class IncomingCallActivity : AppCompatActivity() {
    @Inject
    lateinit var callManager: CallManager

    @Inject
    lateinit var contactRepository: ContactRepository

    @Inject
    lateinit var notificationHelper: NotificationHelper

    private lateinit var binding: ActivityIncomingCallBinding
    private var publicKey = PublicKey("")
    private var contact: Contact? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as App).component.inject(this)
        prepareLockScreenWindow()
        wakeScreenBriefly()
        super.onCreate(savedInstanceState)

        val publicKeyText = intent.getStringExtra(EXTRA_CONTACT_PUBLIC_KEY)
        if (publicKeyText.isNullOrBlank()) {
            finish()
            return
        }
        publicKey = PublicKey(publicKeyText)

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rejectCall.setOnClickListener { rejectCall() }
        binding.acceptCall.setOnClickListener { acceptCall() }

        lifecycleScope.launch {
            contact = contactRepository.get(publicKey.string()).firstOrNull()
            val shownContact = contact ?: Contact(publicKey.string(), name = publicKey.string().take(8))
            binding.callerName.text = shownContact.name.ifEmpty { getString(R.string.contact_default_name) }
            binding.callerAvatar.setFrom(shownContact)
        }
    }

    private fun acceptCall() {
        lifecycleScope.launch {
            val shownContact = contact ?: contactRepository.get(publicKey.string()).firstOrNull()
                ?: Contact(publicKey.string(), name = publicKey.string().take(8))
            runCatching {
                callManager.startCall(publicKey)
                notificationHelper.dismissCallNotification(publicKey)
                notificationHelper.showOngoingCallNotification(shownContact)
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    callManager.startSendingAudio()
                } else {
                    Toast.makeText(
                        this@IncomingCallActivity,
                        R.string.call_mic_permission_needed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                openCallScreen()
            }.onFailure {
                Toast.makeText(this@IncomingCallActivity, R.string.error_simultaneous_calls, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun rejectCall() {
        callManager.endCall(publicKey)
        notificationHelper.dismissCallNotification(publicKey)
        finish()
    }

    private fun openCallScreen() {
        NavDeepLinkBuilder(this)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.callFragment)
            .setArguments(bundleOf(CONTACT_PUBLIC_KEY to publicKey.string()))
            .createPendingIntent()
            .send()
        finish()
    }

    private fun prepareLockScreenWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Do not dismiss keyguard. The call UI is allowed to sit above it.
            getSystemService(KeyguardManager::class.java)
        }
    }

    private fun wakeScreenBriefly() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "skyTox:incoming-call",
        )
        @Suppress("DEPRECATION")
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    companion object {
        fun intent(context: Context, publicKey: PublicKey) =
            Intent(context, IncomingCallActivity::class.java)
                .putExtra(EXTRA_CONTACT_PUBLIC_KEY, publicKey.string())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun pendingIntent(context: Context, publicKey: PublicKey): PendingIntent =
            PendingIntent.getActivity(
                context,
                "${publicKey.string()}_incoming_call_screen".hashCode(),
                intent(context, publicKey),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
