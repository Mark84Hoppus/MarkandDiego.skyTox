// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import ltd.evilcorp.atox.ui.contactlist.ARG_SHARE_FILES

private const val TAG = "MainActivity"
private const val SCHEME = "tox:"
private const val TOX_ID_LENGTH = 76
private const val REQUEST_LEGACY_STORAGE = 8413

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
        requestLegacyStorageAccessIfNeeded()
        requestAllFilesAccessIfNeeded()
        requestOverlayAccessForLegacyIncomingCallsIfNeeded()

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
        if (isIncomingCallDeepLink()) {
            allowCallScreenOverAppLock()
            return
        }

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

    fun allowCallScreenOverAppLock() {
        lockPromptActive = false
        lockAccepted = true
        findViewById<View>(R.id.app_lock_scrim)?.visibility = View.GONE
    }

    private fun isIncomingCallDeepLink(): Boolean {
        val deepLinkIds = intent.getIntArrayExtra("android-support-nav:controller:deepLinkIds") ?: return false
        return deepLinkIds.contains(R.id.callFragment)
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

    private fun requestOverlayAccessForLegacyIncomingCallsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) return
        if (AndroidSettings.canDrawOverlays(this)) return

        Toast.makeText(this, R.string.overlay_permission_needed_for_calls, Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }.onFailure {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun requestLegacyStorageAccessIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_LEGACY_STORAGE)
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
        val navController =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController() ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { arrayListOf(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            else -> null
        }.orEmpty()

        if (uris.isNotEmpty()) {
            uris.forEach {
                grantUriPermission(packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            navController.navigate(R.id.contactListFragment, bundleOf(ARG_SHARE_FILES to uris))
            return
        }

        val data = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!data.isNullOrEmpty()) {
            Log.i(TAG, "Got text share: $data")
            navController.navigate(R.id.contactListFragment, bundleOf(ARG_SHARE to data))
            return
        }

        Log.e(TAG, "Got unsupported share type ${intent.type}")
    }
}
