// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.navigation.fragment.findNavController
import javax.inject.Inject
import ltd.evilcorp.atox.di.ViewModelFactory
import ltd.evilcorp.atox.settings.AppLockMode
import ltd.evilcorp.atox.settings.Settings
import ltd.evilcorp.atox.ui.contactlist.ARG_ADD_CONTACT
import ltd.evilcorp.atox.ui.contactlist.ARG_SHARE

private const val TAG = "MainActivity"
private const val SCHEME = "tox:"
private const val TOX_ID_LENGTH = 76

class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var vmFactory: ViewModelFactory

    @Inject
    lateinit var autoAway: AutoAway

    @Inject
    lateinit var settings: Settings

    private var lockPromptActive = false
    private var lockAccepted = false
    private val appLockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lockPromptActive = false
        if (result.resultCode == RESULT_OK) {
            lockAccepted = true
            findViewById<View>(R.id.app_lock_scrim)?.visibility = View.GONE
        } else {
            closeAfterFailedAppLock()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as App).component.inject(this)

        super.onCreate(savedInstanceState)

        if (settings.disableScreenshots) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(settings.appLanguage))
        AppCompatDelegate.setDefaultNightMode(settings.theme)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)
        requestAllFilesAccessIfNeeded()

        // Only handle intent the first time it triggers the app.
        if (savedInstanceState != null) return
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        autoAway.onBackground()
        if (!lockPromptActive) {
            lockAccepted = false
        }
    }

    override fun onResume() {
        super.onResume()
        autoAway.onForeground()
        maybePromptAppLock()
    }

    private fun maybePromptAppLock() {
        if (lockAccepted || lockPromptActive || settings.appLockMode == AppLockMode.None) return

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardSecure != true) {
            Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            settings.appLockMode = AppLockMode.None
            findViewById<View>(R.id.app_lock_scrim)?.visibility = View.GONE
            return
        }

        val intent = keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.app_lock_unlock_title),
            "",
        ) ?: run {
            settings.appLockMode = AppLockMode.None
            Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        findViewById<View>(R.id.app_lock_scrim)?.visibility = View.VISIBLE
        lockPromptActive = true
        appLockLauncher.launch(intent)
    }

    private fun closeAfterFailedAppLock() {
        lockAccepted = false
        findViewById<View>(R.id.app_lock_scrim)?.visibility = View.VISIBLE
        finishAndRemoveTask()
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> handleToxLinkIntent(intent)
            Intent.ACTION_SEND -> handleShareIntent(intent)
        }
    }

    private fun requestAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) return
        runCatching {
            startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }.onFailure {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun handleToxLinkIntent(intent: Intent) {
        val data = intent.dataString ?: ""
        Log.i(TAG, "Got uri with data: $data")
        if (!data.startsWith(SCHEME) || data.length != SCHEME.length + TOX_ID_LENGTH) {
            Log.e(TAG, "Got malformed uri: $data")
            return
        }

        supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController()?.navigate(
            R.id.contactListFragment,
            bundleOf(ARG_ADD_CONTACT to data.drop(SCHEME.length)),
        )
    }

    private fun handleShareIntent(intent: Intent) {
        if (intent.type != "text/plain") {
            Log.e(TAG, "Got unsupported share type ${intent.type}")
            return
        }

        val data = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (data.isNullOrEmpty()) {
            Log.e(TAG, "Got share intent with no data")
            return
        }

        Log.i(TAG, "Got text share: $data")
        val navController =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController() ?: return
        navController.navigate(R.id.contactListFragment, bundleOf(ARG_SHARE to data))
    }
}
