// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import javax.inject.Inject
import ltd.evilcorp.atox.tox.ToxStarter
import ltd.evilcorp.domain.tox.ToxSaveStatus

private const val TAG = "ToxKeepAliveWorker"

class ToxKeepAliveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @Inject
    lateinit var toxStarter: ToxStarter

    override suspend fun doWork(): Result {
        (applicationContext as App).component.inject(this)

        return when (toxStarter.ensureToxServiceRunning()) {
            ToxSaveStatus.Ok -> {
                Log.i(TAG, "Tox service is running")
                Result.success()
            }
            ToxSaveStatus.Encrypted -> {
                Log.i(TAG, "Profile is encrypted, waiting for manual unlock")
                Result.success()
            }
            ToxSaveStatus.SaveNotFound -> {
                Log.i(TAG, "No Tox save found")
                Result.success()
            }
            else -> {
                Log.w(TAG, "Unable to start Tox service")
                Result.retry()
            }
        }
    }
}
