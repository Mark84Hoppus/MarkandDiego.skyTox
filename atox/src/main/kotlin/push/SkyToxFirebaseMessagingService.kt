// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import javax.inject.Inject
import ltd.evilcorp.atox.App
import ltd.evilcorp.atox.settings.Settings
import ltd.evilcorp.atox.tox.ToxStarter
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
        pushManager.saveOwnToken(token)
        pushManager.refreshTokenAndShare()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!settings.pushEnabled) return
        if (message.data["type"] != "skytox_wakeup") return

        when (toxStarter.ensureToxServiceRunning()) {
            ToxSaveStatus.Ok -> Log.i(TAG, "Tox service started by push")
            ToxSaveStatus.Encrypted -> Log.i(TAG, "Encrypted profile cannot be started by push")
            ToxSaveStatus.SaveNotFound -> Log.i(TAG, "No profile for push wake-up")
            else -> Log.w(TAG, "Unable to start Tox service from push")
        }
    }
}
