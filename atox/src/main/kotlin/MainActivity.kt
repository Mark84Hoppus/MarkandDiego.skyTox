// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.findNavController
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import javax.inject.Inject
import ltd.evilcorp.atox.di.ViewModelFactory
import ltd.evilcorp.atox.settings.AppLockMode
import ltd.evilcorp.atox.settings.Settings
import ltd.evilcorp.atox.ui.contactlist.ARG_ADD_CONTACT
import ltd.evilcorp.atox.ui.contactlist.ARG_SHARE
import ltd.evilcorp.atox.ui.contactlist.ARG_SHARE_FILES
import ltd.evilcorp.atox.ui.call.EXTRA_OPEN_CALL_PUBLIC_KEY
import ltd.evilcorp.atox.ui.chat.CONTACT_PUBLIC_KEY
import ltd.evilcorp.atox.ui.motion.topLevelWindowMotion
import ltd.evilcorp.atox.tox.ToxStarter
import ltd.evilcorp.domain.feature.SkyToxPublicFolders

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

    @Inject
    lateinit var toxStarter: ToxStarter

    private var lockPromptActive = false
    private var lockAccepted = false
    private var musicPanelExpanded = false
    private val musicHandler = Handler(Looper.getMainLooper())
    private var musicAvailabilityListener: ((Boolean) -> Unit)? = null
    private val collapseMusicPanel = Runnable { collapseMusicPanel() }
    private val musicStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateMusicControls()
    }
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
        setupMusicOverlay()
        ContextCompat.registerReceiver(
            this,
            musicStateReceiver,
            IntentFilter(SkyToxMusicService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setupBottomNavigation()
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
        if (settings.appLockMode == AppLockMode.None || lockAccepted) {
            findViewById<View>(R.id.app_lock_scrim)?.visibility = View.GONE
        }
        if (!lockPromptActive) {
            toxStarter.ensureToxServiceRunning()
        }
        maybePromptAppLock()
    }

    private fun setupBottomNavigation() {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) ?: return
        val navController = navHost.findNavController()
        val bottomNav = findViewById<View>(R.id.mainBottomNav) ?: return
        val baseBottomMargin = (bottomNav.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = baseBottomMargin + insets.bottom
                view.layoutParams = it
            }
            compat
        }

        findViewById<View>(R.id.bottomNavChats).setOnClickListener {
            topLevelWindowMotion(it, settings.windowAnimationsEnabled)
                .open(navController, R.id.contactListFragment, it)
        }
        findViewById<View>(R.id.bottomNavAdd).setOnClickListener {
            topLevelWindowMotion(it, settings.windowAnimationsEnabled)
                .open(navController, R.id.addContactFragment, it)
        }
        findViewById<View>(R.id.bottomNavSettings).setOnClickListener {
            Log.d(TAG, "settingsClickUptimeMs=${SystemClock.uptimeMillis()}")
            topLevelWindowMotion(it, settings.windowAnimationsEnabled)
                .open(navController, R.id.settingsFragment, it)
        }
        findViewById<View>(R.id.bottomNavExport).setOnClickListener {
            topLevelWindowMotion(it, settings.windowAnimationsEnabled)
                .open(navController, R.id.importExportMenuFragment, it)
        }
        findViewById<View>(R.id.bottomNavProfile).setOnClickListener {
            topLevelWindowMotion(it, settings.windowAnimationsEnabled)
                .open(navController, R.id.userProfileFragment, it)
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val visible = destination.id in bottomNavigationDestinations
            bottomNav.visibility = if (visible) View.VISIBLE else View.GONE
            highlightBottomNavigation(destination.id)
        }
    }

    private fun highlightBottomNavigation(destinationId: Int) {
        val items = mapOf(
            R.id.bottomNavChats to R.id.contactListFragment,
            R.id.bottomNavAdd to R.id.addContactFragment,
            R.id.bottomNavSettings to R.id.settingsFragment,
            R.id.bottomNavExport to R.id.importExportMenuFragment,
            R.id.bottomNavProfile to R.id.userProfileFragment,
        )
        items.forEach { (viewId, targetId) ->
            findViewById<View>(viewId)?.setBackgroundResource(
                if (destinationId == targetId) {
                    R.drawable.skytox_start_menu_selected_background
                } else {
                    0
                },
            )
        }
    }

    fun setMusicAvailabilityListener(listener: ((Boolean) -> Unit)?) {
        musicAvailabilityListener = listener
        listener?.invoke(findViewById<View>(R.id.musicOverlay)?.visibility != View.VISIBLE)
    }

    fun showMusicWidget() {
        SkyToxPublicFolders.ensureDirectories()
        findViewById<View>(R.id.musicOverlay).visibility = View.VISIBLE
        findViewById<View>(R.id.musicHandle).visibility = View.VISIBLE
        findViewById<View>(R.id.musicPanel).visibility = View.GONE
        musicAvailabilityListener?.invoke(false)
    }

    private fun setupMusicOverlay() {
        findViewById<View>(R.id.musicHandle).setOnClickListener { expandMusicPanel() }
        findViewById<View>(R.id.musicPlayPause).setOnClickListener { toggleMusicPlayback() }
        findViewById<View>(R.id.musicNext).setOnClickListener { playRelative(1) }
        findViewById<View>(R.id.musicPrevious).setOnClickListener { playRelative(-1) }
        findViewById<TextView>(R.id.musicRandom).setOnClickListener { view ->
            if (SkyToxMusicService.active) {
                sendMusicAction(SkyToxMusicService.ACTION_RANDOM)
            } else {
                SkyToxMusicService.random = !SkyToxMusicService.random
            }
            (view as TextView).setTextColor(if (SkyToxMusicService.random) Color.GREEN else Color.WHITE)
            scheduleMusicPanelCollapse()
        }
        findViewById<View>(R.id.musicClose).setOnClickListener { closeMusicWidget() }
    }

    private fun expandMusicPanel() {
        val panel = findViewById<View>(R.id.musicPanel)
        findViewById<View>(R.id.musicHandle).visibility = View.GONE
        panel.visibility = View.VISIBLE
        panel.translationX = resources.displayMetrics.density * 58f
        panel.animate().translationX(0f).setDuration(650L).start()
        musicPanelExpanded = true
        scheduleMusicPanelCollapse()
    }

    private fun collapseMusicPanel() {
        if (!musicPanelExpanded) return
        musicHandler.removeCallbacks(collapseMusicPanel)
        val panel = findViewById<View>(R.id.musicPanel)
        panel.animate().translationX(resources.displayMetrics.density * 58f).setDuration(650L).withEndAction {
            panel.visibility = View.GONE
            panel.translationX = 0f
            if (findViewById<View>(R.id.musicOverlay).visibility == View.VISIBLE) {
                findViewById<View>(R.id.musicHandle).visibility = View.VISIBLE
            }
        }.start()
        musicPanelExpanded = false
    }

    private fun scheduleMusicPanelCollapse() {
        musicHandler.removeCallbacks(collapseMusicPanel)
        musicHandler.postDelayed(collapseMusicPanel, 10_000L)
    }

    private fun toggleMusicPlayback() {
        sendMusicAction(SkyToxMusicService.ACTION_TOGGLE)
        scheduleMusicPanelCollapse()
    }

    private fun playRelative(delta: Int) {
        sendMusicAction(if (delta < 0) SkyToxMusicService.ACTION_PREVIOUS else SkyToxMusicService.ACTION_NEXT)
        scheduleMusicPanelCollapse()
    }

    private fun sendMusicAction(action: String) {
        ContextCompat.startForegroundService(this, Intent(this, SkyToxMusicService::class.java).setAction(action))
    }

    private fun updateMusicPlayIcon(playing: Boolean) {
        findViewById<ImageButton>(R.id.musicPlayPause).apply {
            setImageResource(if (playing) R.drawable.ic_music_pause else R.drawable.ic_play)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
    }

    private fun closeMusicWidget() {
        musicHandler.removeCallbacks(collapseMusicPanel)
        if (SkyToxMusicService.active) sendMusicAction(SkyToxMusicService.ACTION_CLOSE)
        musicPanelExpanded = false
        findViewById<View>(R.id.musicOverlay).visibility = View.GONE
        updateMusicPlayIcon(false)
        musicAvailabilityListener?.invoke(true)
    }

    private fun updateMusicControls() {
        updateMusicPlayIcon(SkyToxMusicService.playing)
        findViewById<TextView>(R.id.musicRandom)?.setTextColor(
            if (SkyToxMusicService.random) Color.GREEN else Color.WHITE,
        )
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && musicPanelExpanded) {
            val panel = findViewById<View>(R.id.musicPanel)
            val bounds = Rect()
            panel.getGlobalVisibleRect(bounds)
            if (!bounds.contains(event.rawX.toInt(), event.rawY.toInt())) collapseMusicPanel()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        musicHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(musicStateReceiver) }
        super.onDestroy()
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
        intent.getStringExtra(EXTRA_OPEN_CALL_PUBLIC_KEY)?.let { publicKey ->
            openCallScreen(publicKey)
            intent.removeExtra(EXTRA_OPEN_CALL_PUBLIC_KEY)
            return
        }

        when (intent.action) {
            Intent.ACTION_VIEW -> handleToxLinkIntent(intent)
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> handleShareIntent(intent)
        }
    }

    private fun openCallScreen(publicKey: String) {
        val navController =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController() ?: return
        navController.navigate(
            R.id.callFragment,
            bundleOf(CONTACT_PUBLIC_KEY to publicKey),
            NavOptions.Builder()
                .setLaunchSingleTop(true)
                .build(),
        )
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
        val uris = sharedUris(intent)

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

    private fun sharedUris(intent: Intent): ArrayList<Uri> {
        val uris = linkedSetOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        }
        intent.clipData?.let { clip ->
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(uris::add) }
        }
        if (intent.action == Intent.ACTION_SEND) intent.data?.let(uris::add)
        return ArrayList(uris)
    }

    companion object {
        private val bottomNavigationDestinations = setOf(
            R.id.contactListFragment,
            R.id.addContactFragment,
            R.id.settingsFragment,
            R.id.importExportMenuFragment,
            R.id.userProfileFragment,
        )
    }
}
