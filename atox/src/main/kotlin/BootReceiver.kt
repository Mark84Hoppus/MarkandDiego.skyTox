// SPDX-FileCopyrightText: 2020-2023 Robin Linden <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import javax.inject.Inject
import ltd.evilcorp.atox.settings.Settings
import ltd.evilcorp.atox.tox.ToxStarter
import ltd.evilcorp.domain.feature.SkyToxCrashLogger
import ltd.evilcorp.domain.tox.ToxSaveStatus

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var toxStarter: ToxStarter

    @Inject
    lateinit var settings: Settings

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        (context.applicationContext as App).component.inject(this)
        if (!settings.keepAwakeEnabled) {
            ToxKeepAliveScheduler.cancel(context)
            SkyToxCrashLogger.diagnostic("boot.skip mode=wake")
            Log.i(TAG, "Keep-awake disabled, skipping boot start")
            return
        }

        ToxKeepAliveScheduler.schedule(context)
        SkyToxCrashLogger.diagnostic("boot.start mode=keep_awake")

        when (toxStarter.ensureToxServiceRunning()) {
            ToxSaveStatus.Ok -> Log.i(TAG, "Tox service started after boot")
            ToxSaveStatus.Encrypted -> Log.i(TAG, "Profile is encrypted, waiting for manual unlock")
            ToxSaveStatus.SaveNotFound -> Log.i(TAG, "No Tox save found after boot")
            else -> Log.w(TAG, "Unable to start Tox service after boot")
        }
    }
}
