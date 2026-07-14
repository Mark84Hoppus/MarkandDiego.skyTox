// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import ltd.evilcorp.atox.settings.Settings
import ltd.evilcorp.domain.feature.SkyToxCrashLogger

private const val TOX_KEEP_ALIVE_WORK = "tox_keep_alive"

object ToxKeepAliveScheduler {
    fun schedule(context: Context) {
        if (!Settings(context.applicationContext).keepAwakeEnabled) {
            SkyToxCrashLogger.diagnostic("keepalive.schedule skipped mode=wake")
            cancel(context)
            return
        }

        val request = PeriodicWorkRequestBuilder<ToxKeepAliveWorker>(30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(TOX_KEEP_ALIVE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        SkyToxCrashLogger.diagnostic("keepalive.schedule active interval=30m")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(TOX_KEEP_ALIVE_WORK)
        SkyToxCrashLogger.diagnostic("keepalive.cancel requested")
    }
}
