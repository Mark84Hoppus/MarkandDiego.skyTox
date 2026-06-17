// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val TOX_KEEP_ALIVE_WORK = "tox_keep_alive"

object ToxKeepAliveScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ToxKeepAliveWorker>(30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(TOX_KEEP_ALIVE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
