// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import javax.inject.Inject
import ltd.evilcorp.atox.App
import ltd.evilcorp.atox.settings.Settings
import ltd.evilcorp.atox.tox.ToxStarter
import ltd.evilcorp.domain.feature.SkyToxCrashLogger
import ltd.evilcorp.domain.tox.ToxSaveStatus

private const val TAG = "SkyToxFcmService"

class SkyToxFirebaseMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var settings: Settings

    @Inject
    lateinit var pushManager: SkyToxPushManager

    @Inject
    lateinit var toxStarter: ToxStarter

    override fun onCreate() {
        (application as App).component.inject(this)
        super.onCreate()
    }

    override fun onNewToken(token: String) {
        SkyToxCrashLogger.diagnostic("fcm.onNewToken length=${token.length}")
        pushManager.saveOwnToken(token)
        pushManager.refreshTokenAndShare()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        SkyToxCrashLogger.diagnostic(
            "fcm.onMessageReceived type=${message.data["type"]} keepAwake=${settings.keepAwakeEnabled}",
        )
        if (message.data["type"] != "skytox_wakeup") {
            return
        }

        when (toxStarter.ensureToxServiceRunning()) {
            ToxSaveStatus.Ok -> {
                SkyToxCrashLogger.diagnostic("fcm.wakeup tox_started")
                Log.i(TAG, "Tox service started by push")
            }
            ToxSaveStatus.Encrypted -> {
                SkyToxCrashLogger.diagnostic("fcm.wakeup encrypted_profile")
                Log.i(TAG, "Encrypted profile cannot be started by push")
            }
            ToxSaveStatus.SaveNotFound -> {
                SkyToxCrashLogger.diagnostic("fcm.wakeup save_not_found")
                Log.i(TAG, "No profile for push wake-up")
            }
            else -> {
                SkyToxCrashLogger.diagnostic("fcm.wakeup failed")
                Log.w(TAG, "Unable to start Tox service from push")
            }
        }
    }
}
