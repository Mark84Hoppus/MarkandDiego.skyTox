// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import javax.inject.Inject
import ltd.evilcorp.atox.settings.Settings
import ltd.evilcorp.atox.tox.ToxStarter
import ltd.evilcorp.domain.feature.SkyToxCrashLogger
import ltd.evilcorp.domain.tox.ToxSaveStatus

private const val TAG = "ToxKeepAliveWorker"

class ToxKeepAliveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @Inject
    lateinit var toxStarter: ToxStarter

    @Inject
    lateinit var settings: Settings

    override suspend fun doWork(): Result {
        (applicationContext as App).component.inject(this)
        if (!settings.keepAwakeEnabled) {
            SkyToxCrashLogger.diagnostic("keepalive.worker skipped mode=wake")
            return Result.success()
        }

        return when (toxStarter.ensureToxServiceRunning()) {
            ToxSaveStatus.Ok -> {
                SkyToxCrashLogger.diagnostic("keepalive.worker tox_running")
                Log.i(TAG, "Tox service is running")
                Result.success()
            }
            ToxSaveStatus.Encrypted -> {
                SkyToxCrashLogger.diagnostic("keepalive.worker encrypted_profile")
                Log.i(TAG, "Profile is encrypted, waiting for manual unlock")
                Result.success()
            }
            ToxSaveStatus.SaveNotFound -> {
                SkyToxCrashLogger.diagnostic("keepalive.worker save_not_found")
                Log.i(TAG, "No Tox save found")
                Result.success()
            }
            else -> {
                SkyToxCrashLogger.diagnostic("keepalive.worker start_failed")
                Log.w(TAG, "Unable to start Tox service")
                Result.retry()
            }
        }
    }
}
