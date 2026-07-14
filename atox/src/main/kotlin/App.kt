// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2019 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.app.Application
import androidx.annotation.VisibleForTesting
import ltd.evilcorp.atox.di.AppComponent
import ltd.evilcorp.atox.di.DaggerAppComponent
import ltd.evilcorp.domain.feature.SkyToxCrashLogger
import ltd.evilcorp.domain.feature.SkyToxPublicFolders

class App : Application() {
    val component: AppComponent by lazy {
        componentOverride ?: DaggerAppComponent.factory().create(applicationContext)
    }

    @VisibleForTesting
    var componentOverride: AppComponent? = null

    override fun onCreate() {
        super.onCreate()
        SkyToxPublicFolders.ensureDirectories()
        SkyToxCrashLogger.install(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        SkyToxCrashLogger.event("app.onCreate")
        SkyToxCrashLogger.diagnostic("app.onCreate version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}")
        ToxKeepAliveScheduler.schedule(this)
    }
}
