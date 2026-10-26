// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.location

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.LruCache
import android.util.Xml
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import ltd.evilcorp.atox.App
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.settings.Settings
import ltd.evilcorp.atox.tox.ToxStarter
import ltd.evilcorp.core.repository.ContactRepository
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.FileTransferManager
import ltd.evilcorp.domain.feature.SkyToxCrashLogger
import ltd.evilcorp.domain.feature.SkyToxPublicFolders
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

const val EXTRA_LOCATION_CONTACT_PUBLIC_KEY = "locationContactPublicKey"
const val EXTRA_LOCATION_CONTACT_NAME = "locationContactName"
const val EXTRA_LOCATION_CONTACT_AVATAR_URI = "locationContactAvatarUri"
const val EXTRA_LOCATION_PEER_LAT = "locationPeerLat"
const val EXTRA_LOCATION_PEER_LON = "locationPeerLon"
private const val MAP_MIN_ZOOM = 1.0
private const val MAP_MAX_ZOOM = 19.5
private const val MAP_DEFAULT_ZOOM = 18.49
private const val FOLLOW_CAMERA_MIN_INTERVAL_MS = 500L
private const val FOLLOW_CAMERA_MIN_DISTANCE_METERS = 3.0
private const val MOVEMENT_HEADING_MIN_SPEED_MPS = 3_000.0 / 3_600.0
private const val MOVEMENT_HEADING_MIN_TIME_MS = 5_000L
private const val TRACK_MIN_DISTANCE_METERS = 6.0
private const val TRACK_MAX_ACCURACY_METERS = 80f
private const val TRACK_TELEPORT_SPEED_MPS = 22.0
private const val TRACK_JUMP_DISTANCE_METERS = 20.0
private const val TRACK_STATIONARY_RETURN_METERS = 5.0
private const val TRACK_STATIONARY_SPEED_MPS = 1.4
private const val TRACK_SIDE_SPIKE_MIN_METERS = 12.0
private const val TRACK_SIDE_SPIKE_MAX_METERS = 180.0
private const val TRACK_SIDE_SPIKE_ANGLE_DEGREES = 35.0
private const val TRACK_HAIRPIN_ANGLE_DEGREES = 120.0
private const val OSM_TILE_CACHE_MAX_BYTES = 150L * 1024L * 1024L
private const val OSM_TILE_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
private const val OSM_TILE_PREFETCH_MARGIN = 1

class SkyToxLocationMapActivity : AppCompatActivity() {
    @Inject
    lateinit var sharingManager: SkyToxLocationSharingManager

    @Inject
    lateinit var contactRepository: ContactRepository

    @Inject
    lateinit var fileTransferManager: FileTransferManager

    @Inject
    lateinit var settings: Settings

    @Inject
    lateinit var toxStarter: ToxStarter

    @Inject
    lateinit var scope: CoroutineScope

    private lateinit var mapView: SkyToxMapView
    private lateinit var shareButton: TextView
    private lateinit var distanceText: TextView
    private lateinit var locateButton: TextView
    private lateinit var osmButton: MapToolButton
    private lateinit var trackButton: MapToolButton
    private lateinit var trackActionButton: TextView
    private lateinit var trackPanel: LinearLayout
    private lateinit var layerMenuButton: MapToolButton
    private lateinit var layerPanel: LinearLayout
    private lateinit var mapLoadingPanel: LinearLayout
    private lateinit var speedText: TextView
    private lateinit var peerButton: PeerLocationButton
    private lateinit var peerDirectionButton: PeerDirectionButton
    private lateinit var altitudeText: TextView
    private val offlineLayerSwitches = mutableListOf<Switch>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val locationUiTicker = object : Runnable {
        override fun run() {
            if (::mapView.isInitialized) {
                updateSpeed(mapView.ownLocation)
                updateTrackUi()
            }
            uiHandler.postDelayed(this, 1_000L)
        }
    }
    private var offlineLayerState = OfflineLayerState()
    private var contactPublicKey = ""
    private var contactName = ""
    private var contactAvatarUri = ""
    private var savedTracks = listOf<SkyToxRecordedTrack>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            sharingManager.requestOwnLocationOnly()
            updateShareButton()
        }
    }

    private val importTrackLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importTrack(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as App).component.inject(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        contactPublicKey = intent.getStringExtra(EXTRA_LOCATION_CONTACT_PUBLIC_KEY).orEmpty()
        contactName = intent.getStringExtra(EXTRA_LOCATION_CONTACT_NAME).orEmpty().ifEmpty { contactPublicKey.take(8) }
        contactAvatarUri = intent.getStringExtra(EXTRA_LOCATION_CONTACT_AVATAR_URI).orEmpty()
        SkyToxCrashLogger.diagnostic("location.map.open pk=${contactPublicKey.take(8)}")

        mapView = SkyToxMapView(this).apply {
            contactPublicKey = this@SkyToxLocationMapActivity.contactPublicKey
            osmEnabled = true
            setStoredMarkers(loadStoredMarkers())
            onMarkerLongPress = { lat, lon -> handleMarkerLongPress(lat, lon) }
            onTrackLongPress = { track -> showTrackActions(track) }
            onOsmAvailabilityChanged = { updateOsmButton() }
            onOfflineLoadingChanged = { loading ->
                mapLoadingPanel.visibility = if (loading) View.VISIBLE else View.GONE
            }
            onMapTouched = {
                closeLayerPanel()
                closeTrackPanel()
            }
            setOfflineLayers(offlineLayerState)
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(225, 232, 226))
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))

        mapLoadingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.argb(160, 20, 45, 70))
            addView(TextView(this@SkyToxLocationMapActivity).apply {
                text = getString(R.string.location_map_loading)
                textSize = 14f
                setTextColor(Color.rgb(35, 118, 235))
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
                gravity = Gravity.CENTER
            })
            addView(ProgressBar(this@SkyToxLocationMapActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(35, 118, 235))
            }, LinearLayout.LayoutParams(dp(120), dp(8)))
        }
        val loadingParams = FrameLayout.LayoutParams(dp(160), dp(54), Gravity.CENTER)
        root.addView(mapLoadingPanel, loadingParams)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(
                if (isNightMode()) Color.rgb(25, 107, 201)
                else ContextCompat.getColor(this@SkyToxLocationMapActivity, R.color.ocean_toolbar),
            )
            setPadding(dp(4), 0, dp(4), 0)
        }
        val back = topButton("<").apply { setOnClickListener { finish() } }
        distanceText = topTitle("")
        shareButton = topButton(getString(R.string.location_share_start)).apply {
            setOnClickListener { toggleSharing() }
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(52), -1))
        topBar.addView(distanceText, LinearLayout.LayoutParams(0, -1, 1f))
        topBar.addView(shareButton, LinearLayout.LayoutParams(dp(116), -1))
        root.addView(topBar, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))

        layerMenuButton = MapToolButton(this, MapToolButton.Kind.Menu).apply {
            setOnClickListener {
                layerPanel.visibility = if (layerPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                active = layerPanel.visibility == View.VISIBLE
            }
        }
        val layerMenuParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START or Gravity.TOP)
        layerMenuParams.setMargins(dp(8), dp(64), 0, 0)
        root.addView(layerMenuButton, layerMenuParams)

        layerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.argb(220, 44, 58, 64))
            addView(offlineLayerSwitch(R.string.location_layer_cities) {
                offlineLayerState = offlineLayerState.copy(cities = it)
                mapView.setOfflineLayers(offlineLayerState)
            })
            addView(offlineLayerSwitch(R.string.location_layer_borders) {
                offlineLayerState = offlineLayerState.copy(borders = it)
                mapView.setOfflineLayers(offlineLayerState)
            })
            addView(offlineLayerSwitch(R.string.location_layer_roads) {
                offlineLayerState = offlineLayerState.copy(roads = it)
                mapView.setOfflineLayers(offlineLayerState)
            })
            addView(offlineLayerSwitch(R.string.location_layer_rails) {
                offlineLayerState = offlineLayerState.copy(rails = it)
                mapView.setOfflineLayers(offlineLayerState)
            })
            addView(layerSwitch(R.string.location_layer_tracks, checked = true) {
                mapView.tracksVisible = it
            })
        }
        val layerPanelParams = FrameLayout.LayoutParams(dp(210), -2, Gravity.START or Gravity.TOP)
        layerPanelParams.setMargins(dp(56), dp(64), 0, 0)
        root.addView(layerPanel, layerPanelParams)

        locateButton = TextView(this).apply {
            text = "▲"
            textSize = 28f
            text = "\u25B2"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.chat_scroll_to_bottom_background)
            setOnClickListener { onLocateClick() }
        }
        val locateParams = FrameLayout.LayoutParams(dp(54), dp(54), Gravity.BOTTOM or Gravity.END)
        locateParams.setMargins(0, 0, dp(18), dp(24))
        root.addView(locateButton, locateParams)

        trackButton = MapToolButton(this, MapToolButton.Kind.Track).apply {
            alpha = 0.55f
            setOnClickListener {
                trackPanel.visibility = if (trackPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                updateTrackUi()
            }
        }
        val trackParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.CENTER_VERTICAL)
        trackParams.setMargins(0, 0, dp(24), dp(28))
        root.addView(trackButton, trackParams)

        trackPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.argb(220, 44, 58, 64))
            trackActionButton = trackPanelButton(R.string.location_track_start) { toggleTrackRecording() }
            addView(trackActionButton)
            addView(trackPanelButton(R.string.location_track_import) { importTrackLauncher.launch(arrayOf("application/gpx+xml", "text/xml", "*/*")) })
        }
        val trackPanelParams = FrameLayout.LayoutParams(dp(210), -2, Gravity.END or Gravity.CENTER_VERTICAL)
        trackPanelParams.setMargins(0, 0, dp(72), dp(28))
        root.addView(trackPanel, trackPanelParams)

        osmButton = MapToolButton(this, MapToolButton.Kind.Layers).apply {
            setOnClickListener { toggleOsmLayer() }
        }
        val osmParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.CENTER_VERTICAL)
        osmParams.setMargins(0, 0, dp(24), dp(82))
        root.addView(osmButton, osmParams)

        peerDirectionButton = PeerDirectionButton(this).apply {
            visibility = View.GONE
        }
        val directionParams = FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.TOP)
        directionParams.setMargins(0, dp(64), dp(8), 0)
        root.addView(peerDirectionButton, directionParams)

        peerButton = PeerLocationButton(this).apply {
            peerName = contactName
            visibility = View.GONE
            setOnClickListener {
                mapView.peerLocation?.let { loc -> mapView.centerOn(loc.latitude, loc.longitude, defaultZoom = true) }
            }
        }
        val peerParams = FrameLayout.LayoutParams(dp(92), dp(72), Gravity.BOTTOM or Gravity.START)
        peerParams.setMargins(dp(18), 0, 0, dp(24))
        root.addView(peerButton, peerParams)

        speedText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(35, 118, 235))
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            text = "0 ${getString(R.string.location_speed_kmh)}"
        }
        val speedParams = FrameLayout.LayoutParams(dp(82), dp(32), Gravity.START or Gravity.CENTER_VERTICAL)
        speedParams.setMargins(0, -dp(76), 0, 0)
        root.addView(speedText, speedParams)

        altitudeText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            text = ""
        }
        val altitudeParams = FrameLayout.LayoutParams(dp(82), dp(120), Gravity.START or Gravity.CENTER_VERTICAL)
        altitudeParams.setMargins(0, 0, 0, 0)
        root.addView(altitudeText, altitudeParams)
        mapView.onCameraChanged = { updateCameraHeight() }
        updateCameraHeight()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = insets.top)
            topBar.layoutParams.height = dp(56) + insets.top
            layerMenuParams.setMargins(dp(8) + insets.left, dp(64) + insets.top, 0, 0)
            layerMenuButton.layoutParams = layerMenuParams
            layerPanelParams.setMargins(dp(56) + insets.left, dp(64) + insets.top, 0, 0)
            layerPanel.layoutParams = layerPanelParams
            locateParams.setMargins(0, 0, dp(18) + insets.right, dp(24) + insets.bottom)
            locateButton.layoutParams = locateParams
            peerParams.setMargins(dp(18) + insets.left, 0, 0, dp(24) + insets.bottom)
            peerButton.layoutParams = peerParams
            directionParams.setMargins(0, dp(64) + insets.top, dp(8) + insets.right, 0)
            peerDirectionButton.layoutParams = directionParams
            trackParams.setMargins(0, 0, dp(24) + insets.right, dp(28))
            trackButton.layoutParams = trackParams
            trackPanelParams.setMargins(0, 0, dp(72) + insets.right, dp(28))
            trackPanel.layoutParams = trackPanelParams
            osmParams.setMargins(0, 0, dp(24) + insets.right, dp(82))
            osmButton.layoutParams = osmParams
            speedParams.setMargins(insets.left, -dp(76), 0, 0)
            speedText.layoutParams = speedParams
            altitudeParams.setMargins(insets.left, 0, 0, 0)
            altitudeText.layoutParams = altitudeParams
            updateCameraHeight()
            compat
        }

        setContentView(root)
        updateOsmButton()
        updateLayerSwitches()
        reloadSavedTracks()

        val peerLat = intent.getDoubleExtra(EXTRA_LOCATION_PEER_LAT, Double.NaN)
        val peerLon = intent.getDoubleExtra(EXTRA_LOCATION_PEER_LON, Double.NaN)
        if (!peerLat.isNaN() && !peerLon.isNaN()) {
            mapView.peerLocation = SkyToxSharedLocation(contactPublicKey, peerLat, peerLon, 0f, System.currentTimeMillis())
            mapView.centerOn(peerLat, peerLon, defaultZoom = true)
        }

        sharingManager.ownLocation().observe(this) {
            mapView.ownLocation = it
            mapView.addTrackPoint(it)
            mapView.invalidate()
            updateDistance()
            updatePeerDirection()
            updateSpeed(it)
            updateTrackUi()
        }
        sharingManager.peerLocations().observe(this) {
            mapView.peerLocation = it[contactPublicKey]
            peerButton.visibility = if (mapView.peerLocation != null) View.VISIBLE else View.GONE
            mapView.invalidate()
            updateDistance()
            updatePeerDirection()
        }
        sharingManager.activeContact().observe(this) { updateShareButton() }

        ensureLocationPermission()
        uiHandler.post(locationUiTicker)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && ::mapView.isInitialized) {
            saveActiveTrackIfNeeded()
            mapView.release()
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        if (::mapView.isInitialized) {
            saveActiveTrackIfNeeded()
            mapView.release()
        }
        super.onDestroy()
    }

    private fun ensureLocationPermission() {
        if (sharingManager.canUseLocation()) {
            sharingManager.requestOwnLocationOnly()
            return
        }
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun toggleSharing() {
        if (!sharingManager.canUseLocation()) {
            ensureLocationPermission()
            return
        }
        if (sharingManager.activeContactValue() == contactPublicKey) {
            sharingManager.stopSharing()
        } else {
            toxStarter.ensureToxServiceRunning()
            if (!sharingManager.startSharing(contactPublicKey)) {
                android.widget.Toast.makeText(this, R.string.location_share_one_contact_only, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateShareButton() {
        val active = sharingManager.activeContactValue()
        shareButton.isEnabled = active == null || active == contactPublicKey
        shareButton.alpha = if (shareButton.isEnabled) 1f else 0.45f
        shareButton.text = if (active == contactPublicKey) {
            getString(R.string.location_share_stop)
        } else {
            getString(R.string.location_share_start)
        }
    }

    private fun onLocateClick() {
        val own = mapView.ownLocation ?: return
        locateButton.text = "\u25B2"
        mapView.followOwnLocation = true
        mapView.centerOn(own.latitude, own.longitude, defaultZoom = true)
    }

    private fun updateDistance() {
        val own = mapView.ownLocation
        val peer = mapView.peerLocation
        distanceText.text = if (own != null && peer != null) {
            getString(R.string.location_distance_m, distanceMeters(own, peer).roundToInt())
        } else {
            ""
        }
    }

    private fun updatePeerDirection() {
        val own = mapView.ownLocation
        val peer = mapView.peerLocation
        if (own == null || peer == null) {
            peerDirectionButton.visibility = View.GONE
            return
        }
        peerDirectionButton.bearing = bearingDegrees(own.latitude, own.longitude, peer.latitude, peer.longitude).toFloat()
        peerDirectionButton.visibility = View.VISIBLE
    }

    private fun updateSpeed(location: SkyToxSharedLocation?) {
        val speed = if (sharingManager.hasFreshOwnLocation()) location?.speedMps ?: 0f else 0f
        val kmh = (speed * 3.6f).roundToInt().coerceAtLeast(0)
        speedText.text = "$kmh ${getString(R.string.location_speed_kmh)}"
    }

    private fun updateCameraHeight() {
        altitudeText.text = "-----\n-----\n-----\n-----\n-----\n${mapView.cameraHeightLabel()}"
        updateOsmButton()
    }

    private fun toggleOsmLayer() {
        mapView.osmEnabled = !mapView.osmEnabled
        updateOsmButton()
        updateLayerSwitches()
    }

    private fun updateOsmButton() {
        if (!::osmButton.isInitialized) return
        osmButton.isEnabled = true
        osmButton.alpha = if (mapView.osmEnabled) 1f else 0.65f
        osmButton.active = mapView.osmEnabled
    }

    private fun updateLayerSwitches() {
        if (offlineLayerSwitches.isEmpty()) return
        offlineLayerSwitches.forEach { toggle ->
            toggle.isEnabled = !mapView.osmEnabled
            toggle.alpha = if (toggle.isEnabled) 1f else 0.35f
        }
    }

    private fun closeLayerPanel() {
        if (!::layerPanel.isInitialized || layerPanel.visibility != View.VISIBLE) return
        layerPanel.visibility = View.GONE
        layerMenuButton.active = false
    }

    private fun closeTrackPanel() {
        if (!::trackPanel.isInitialized || trackPanel.visibility != View.VISIBLE) return
        trackPanel.visibility = View.GONE
        trackButton.active = mapView.trackRecording
    }

    private fun toggleTrackRecording() {
        if (mapView.trackRecording) {
            saveActiveTrackIfNeeded()
        } else {
            if (!sharingManager.hasFreshOwnLocation()) return
            mapView.startTrackRecording()
            mapView.ownLocation?.let { mapView.addTrackPoint(it) }
        }
        updateTrackUi()
    }

    private fun saveActiveTrackIfNeeded() {
        val saved = mapView.stopTrackRecordingAndBuild()
        updateTrackUi()
        if (saved == null) return
        scope.launch(Dispatchers.IO) {
            runCatching { SkyToxTrackStore.save(saved) }
            withContext(Dispatchers.Main) { reloadSavedTracks() }
        }
    }

    private fun updateTrackUi() {
        if (!::trackButton.isInitialized) return
        val canStart = sharingManager.hasFreshOwnLocation()
        trackButton.alpha = if (mapView.trackRecording) 1f else 0.55f
        trackButton.active = mapView.trackRecording || (::trackPanel.isInitialized && trackPanel.visibility == View.VISIBLE)
        if (::trackActionButton.isInitialized) {
            trackActionButton.text = getString(
                if (mapView.trackRecording) R.string.location_track_stop else R.string.location_track_start,
            )
            trackActionButton.isEnabled = mapView.trackRecording || canStart
            trackActionButton.alpha = if (trackActionButton.isEnabled) 1f else 0.35f
        }
    }

    private fun reloadSavedTracks() {
        savedTracks = SkyToxTrackStore.loadAll()
        if (::mapView.isInitialized) mapView.setSavedTracks(savedTracks)
    }

    private fun importTrack(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val status = contentResolver.openInputStream(uri)?.use { SkyToxTrackStore.importAndSaveGpx(it) }
                ?: SkyToxTrackImportStatus.Failed
            withContext(Dispatchers.Main) {
                when (status) {
                    SkyToxTrackImportStatus.Imported -> {
                        reloadSavedTracks()
                        Toast.makeText(this@SkyToxLocationMapActivity, R.string.location_track_loaded, Toast.LENGTH_SHORT).show()
                    }
                    SkyToxTrackImportStatus.AlreadyExists -> {
                        Toast.makeText(this@SkyToxLocationMapActivity, R.string.location_track_already_exists, Toast.LENGTH_SHORT).show()
                    }
                    SkyToxTrackImportStatus.Failed -> {
                        Toast.makeText(this@SkyToxLocationMapActivity, R.string.location_track_import_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showTrackActions(track: SkyToxRecordedTrack) {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.share), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> shareTrack(track)
                    1 -> deleteTrack(track)
                }
            }
            .show()
    }

    private fun shareTrack(track: SkyToxRecordedTrack) {
        scope.launch {
            contactRepository.getAll().take(1).collect { contacts ->
                withContext(Dispatchers.Main) {
                    val candidates = contacts.sortedBy { it.name.lowercase(Locale.getDefault()) }
                    if (candidates.isEmpty()) {
                        android.widget.Toast.makeText(this@SkyToxLocationMapActivity, R.string.no_contacts_to_forward, android.widget.Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    val names = candidates.map { it.name.ifBlank { getString(R.string.contact_default_name) } }.toTypedArray()
                    AlertDialog.Builder(this@SkyToxLocationMapActivity)
                        .setTitle(R.string.forward_to)
                        .setItems(names) { _, which ->
                            scope.launch(Dispatchers.IO) {
                                fileTransferManager.create(
                                    PublicKey(candidates[which].publicKey),
                                    track.file.toUri(),
                                    settings.pendingFileRetention.millis,
                                    settings.pendingQueueLimit.bytes,
                                )
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun deleteTrack(track: SkyToxRecordedTrack) {
        scope.launch(Dispatchers.IO) {
            runCatching { track.file.delete() }
            withContext(Dispatchers.Main) { reloadSavedTracks() }
        }
    }

    private fun handleMarkerLongPress(lat: Double, lon: Double) {
        val existing = mapView.markerNear(lat, lon)
        if (existing != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setPositiveButton(android.R.string.yes) { _, _ ->
                    mapView.removeMarker(existing.id)
                    saveStoredMarkers(mapView.userMarkers())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.location_marker_name_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.location_marker_add_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty().ifEmpty {
                    getString(R.string.location_marker_default_name)
                }
                mapView.addMarker(UserMapMarker(System.currentTimeMillis().toString(), name, lat, lon))
                saveStoredMarkers(mapView.userMarkers())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun topButton(label: String) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(
            if (isNightMode()) Color.WHITE
            else ContextCompat.getColor(this@SkyToxLocationMapActivity, R.color.ocean_accent),
        )
        textSize = 16f
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun topTitle(label: String) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(
            if (isNightMode()) Color.WHITE
            else ContextCompat.getColor(this@SkyToxLocationMapActivity, R.color.ocean_text_primary),
        )
        textSize = 16f
        maxLines = 1
    }

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun layerSwitch(labelRes: Int, checked: Boolean = false, onChanged: (Boolean) -> Unit) = Switch(this).apply {
        text = getString(labelRes)
        textSize = 14f
        setTextColor(Color.WHITE)
        isChecked = checked
        setOnCheckedChangeListener { _, checked -> onChanged(checked) }
    }

    private fun offlineLayerSwitch(labelRes: Int, checked: Boolean = false, onChanged: (Boolean) -> Unit): Switch {
        return layerSwitch(labelRes, checked, onChanged).also { offlineLayerSwitches += it }
    }

    private fun trackPanelButton(labelRes: Int, onClick: () -> Unit) = TextView(this).apply {
        text = getString(labelRes)
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener {
            onClick()
            closeTrackPanel()
        }
    }

    private fun mapCircleButton(label: String) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundResource(R.drawable.chat_scroll_to_bottom_background)
    }

    private fun loadStoredMarkers(): List<UserMapMarker> {
        val raw = getSharedPreferences("skytox_location_map", MODE_PRIVATE).getString("markers", "[]").orEmpty()
        return runCatching {
            val items = org.json.JSONArray(raw)
            List(items.length()) { index ->
                val item = items.getJSONObject(index)
                UserMapMarker(
                    item.getString("id"),
                    item.getString("name"),
                    item.getDouble("lat"),
                    item.getDouble("lon"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveStoredMarkers(markers: List<UserMapMarker>) {
        val items = org.json.JSONArray()
        markers.forEach { marker ->
            items.put(JSONObject().apply {
                put("id", marker.id)
                put("name", marker.name)
                put("lat", marker.lat)
                put("lon", marker.lon)
            })
        }
        getSharedPreferences("skytox_location_map", MODE_PRIVATE)
            .edit()
            .putString("markers", items.toString())
            .apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        fun open(
            context: Context,
            publicKey: String,
            name: String,
            avatarUri: String = "",
            payload: SkyToxLocationPayload? = null,
        ) {
            context.startActivity(Intent(context, SkyToxLocationMapActivity::class.java).apply {
                putExtra(EXTRA_LOCATION_CONTACT_PUBLIC_KEY, publicKey)
                putExtra(EXTRA_LOCATION_CONTACT_NAME, name)
                putExtra(EXTRA_LOCATION_CONTACT_AVATAR_URI, avatarUri)
                if (payload != null) {
                    putExtra(EXTRA_LOCATION_PEER_LAT, payload.latitude)
                    putExtra(EXTRA_LOCATION_PEER_LON, payload.longitude)
                }
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

private class OsmTileLoader(private val view: View) {
    private val executor = Executors.newFixedThreadPool(4)
    private val pending = ConcurrentHashMap.newKeySet<OsmTileKey>()
    private val cacheDir = File(view.context.cacheDir, "skytox-osm-tiles")
    private val memoryCache = object : LruCache<OsmTileKey, Bitmap>(128) {
        override fun sizeOf(key: OsmTileKey, value: Bitmap): Int = 1
    }

    fun getCached(key: OsmTileKey): Bitmap? {
        synchronized(memoryCache) {
            return memoryCache.get(key)
        }
    }

    private fun loadFromDisk(key: OsmTileKey): Bitmap? {
        val file = tileFile(key)
        if (!file.isFile || isExpired(file)) {
            if (file.exists()) file.delete()
            return null
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
        )?.also {
            file.setLastModified(System.currentTimeMillis())
            synchronized(memoryCache) {
                memoryCache.put(key, it)
            }
        }
    }

    fun request(key: OsmTileKey) {
        synchronized(memoryCache) {
            if (memoryCache.get(key) != null) return
        }
        if (!pending.add(key)) return
        executor.execute {
            val bitmap = runCatching { loadOrDownload(key) }.getOrNull()
            view.post {
                pending.remove(key)
                if (bitmap != null) {
                    synchronized(memoryCache) {
                        memoryCache.put(key, bitmap)
                    }
                    view.invalidate()
                }
            }
        }
    }

    fun requestAll(keys: List<OsmTileKey>) {
        keys.forEach { request(it) }
    }

    fun clear() {
        pending.clear()
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
    }

    fun shutdown() {
        clear()
        executor.shutdownNow()
    }

    private fun loadOrDownload(key: OsmTileKey): Bitmap? {
        getCached(key)?.let { return it }
        loadFromDisk(key)?.let { return it }
        return download(key)
    }

    private fun download(key: OsmTileKey): Bitmap? {
        val url = URL("https://tile.openstreetmap.org/${key.z}/${key.x}/${key.y}.png")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "skyTox/0.8.29 Android online visible map layer")
            setRequestProperty("Referer", "https://github.com/Mark84Hoppus/MarkandDiego.skyTox")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
            )
            if (bitmap != null) {
                writeTile(key, bytes)
                trimCache()
            }
            bitmap
        } finally {
            connection.disconnect()
        }
    }

    private fun writeTile(key: OsmTileKey, bytes: ByteArray) {
        cacheDir.mkdirs()
        val file = tileFile(key)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
        file.setLastModified(System.currentTimeMillis())
    }

    private fun tileFile(key: OsmTileKey) = File(cacheDir, "${key.z}/${key.x}/${key.y}.png")

    private fun isExpired(file: File): Boolean = System.currentTimeMillis() - file.lastModified() > OSM_TILE_CACHE_MAX_AGE_MS

    private fun trimCache() {
        val files = cacheDir
            .walkTopDown()
            .filter { it.isFile }
            .toList()
        var total = 0L
        files.forEach { file ->
            if (isExpired(file)) {
                file.delete()
            } else {
                total += file.length()
            }
        }
        if (total <= OSM_TILE_CACHE_MAX_BYTES) return
        files
            .filter { it.exists() && !isExpired(it) }
            .sortedBy { it.lastModified() }
            .forEach { file ->
                if (total <= OSM_TILE_CACHE_MAX_BYTES) return
                total -= file.length()
                file.delete()
            }
    }
}

private class MapToolButton(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { Layers, Track, Menu }

    var active = false
        set(value) {
            field = value
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgPaint.color = when (kind) {
            Kind.Menu -> if (active) Color.rgb(28, 150, 82) else Color.rgb(38, 175, 95)
            else -> if (active) Color.argb(225, 48, 65, 75) else Color.argb(145, 48, 65, 75)
        }
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, bgPaint)
        when (kind) {
            Kind.Layers -> drawLayers(canvas)
            Kind.Track -> drawTrackIcon(canvas)
            Kind.Menu -> drawMenu(canvas)
        }
    }

    private fun drawMenu(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawLine(cx - 11f, cy - 8f, cx + 11f, cy - 8f, iconPaint)
        canvas.drawLine(cx - 11f, cy, cx + 11f, cy, iconPaint)
        canvas.drawLine(cx - 11f, cy + 8f, cx + 11f, cy + 8f, iconPaint)
    }

    private fun drawLayers(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        drawLayer(canvas, cx, cy - 7f)
        drawLayer(canvas, cx, cy)
        drawLayer(canvas, cx, cy + 7f)
    }

    private fun drawLayer(canvas: Canvas, cx: Float, cy: Float) {
        val path = Path().apply {
            moveTo(cx, cy - 10f)
            lineTo(cx + 16f, cy - 2f)
            lineTo(cx, cy + 7f)
            lineTo(cx - 16f, cy - 2f)
            close()
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun drawTrackIcon(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val path = Path().apply {
            moveTo(cx + 13f, cy - 18f)
            cubicTo(cx - 8f, cy - 16f, cx + 8f, cy - 2f, cx - 12f, cy + 2f)
            cubicTo(cx - 24f, cy + 5f, cx - 4f, cy + 18f, cx - 18f, cy + 20f)
        }
        canvas.save()
        canvas.rotate(-60f, cx, cy)
        canvas.drawPath(path, iconPaint)
        canvas.restore()
    }
}

private class PeerLocationButton(context: Context) : View(context) {
    var peerName: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 175, 95); style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 18f
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val label = peerName.ifBlank { "" }.let { if (it.length > 10) it.take(9) + "..." else it }
        val cx = width / 2f
        val circleRadius = minOf(width, height) * 0.28f
        val cy = height - circleRadius - 2f
        if (label.isNotEmpty()) {
            canvas.drawText(label, cx, 18f, textPaint)
        }
        canvas.drawCircle(cx, cy, circleRadius, circlePaint)
        canvas.drawCircle(cx, cy - 7f, 7f, iconPaint)
        val body = Path().apply {
            moveTo(cx - 15f, cy + 17f)
            cubicTo(cx - 12f, cy + 5f, cx + 12f, cy + 5f, cx + 15f, cy + 17f)
        }
        canvas.drawPath(body, iconPaint)
    }
}

private class PeerDirectionButton(context: Context) : View(context) {
    var bearing: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 128, 26); style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f
        canvas.drawCircle(cx, cy, radius, bgPaint)
        val arrow = Path().apply {
            moveTo(cx, cy - radius * 0.62f)
            lineTo(cx + radius * 0.34f, cy + radius * 0.42f)
            lineTo(cx, cy + radius * 0.20f)
            lineTo(cx - radius * 0.34f, cy + radius * 0.42f)
            close()
        }
        canvas.save()
        canvas.rotate(bearing, cx, cy)
        canvas.drawPath(arrow, arrowPaint)
        canvas.restore()
    }
}

private data class MapLine(
    val kind: String,
    val points: FloatArray,
    val rank: Int = 99,
    val minLon: Float,
    val maxLon: Float,
    val minLat: Float,
    val maxLat: Float,
)
private data class MapPlace(val name: String, val lon: Double, val lat: Double, val population: Int, val rank: Int)
private data class OfflineLayerState(
    val cities: Boolean = false,
    val borders: Boolean = false,
    val roads: Boolean = false,
    val rails: Boolean = false,
)
data class UserMapMarker(val id: String, val name: String, val lat: Double, val lon: Double)
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val timestamp: Long,
    val accuracy: Float,
    val altitude: Double = Double.NaN,
)
data class SkyToxRecordedTrack(val id: String, val name: String, val file: File, val points: List<TrackPoint>)
private data class MovementSample(val lat: Double, val lon: Double, val timestamp: Long)
private data class OsmTileKey(val z: Int, val x: Int, val y: Int)
private data class OsmTileLayout(
    val zoom: Int,
    val startTileX: Int,
    val startTileY: Int,
    val offsetX: Double,
    val offsetY: Double,
    val tileSize: Double,
    val keys: List<OsmTileKey>,
)

enum class SkyToxTrackImportStatus { Imported, AlreadyExists, Failed }

object SkyToxTrackStore {
    private val displayStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val gpxTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun loadAll(): List<SkyToxRecordedTrack> {
        SkyToxPublicFolders.ensureDirectories()
        return SkyToxPublicFolders.tracksDir
            .listFiles { file -> file.isFile && file.extension.equals("gpx", true) }
            ?.mapNotNull { file -> runCatching { readGpx(file) }.getOrNull() }
            ?.sortedByDescending { it.file.lastModified() }
            .orEmpty()
    }

    fun save(track: SkyToxRecordedTrack): SkyToxRecordedTrack {
        SkyToxPublicFolders.ensureDirectories()
        findDuplicate(track)?.let { return it }
        val file = uniqueFile(SkyToxPublicFolders.tracksDir, "${track.name.sanitizeFileName()}.gpx")
        file.writeText(toGpx(track.copy(file = file)), Charsets.UTF_8)
        return readGpx(file)
    }

    fun importAndSaveGpx(input: java.io.InputStream): SkyToxTrackImportStatus {
        return runCatching {
            val imported = importGpx(input)
            if (findDuplicate(imported) != null) {
                SkyToxTrackImportStatus.AlreadyExists
            } else {
                save(imported)
                SkyToxTrackImportStatus.Imported
            }
        }.getOrDefault(SkyToxTrackImportStatus.Failed)
    }

    fun importGpx(input: java.io.InputStream): SkyToxRecordedTrack {
        val temp = File.createTempFile("skytox-track-import", ".gpx")
        temp.outputStream().use { out -> input.copyTo(out) }
        val parsed = readGpx(temp)
        temp.delete()
        val name = parsed.name.ifBlank { newTrackName(System.currentTimeMillis()) }
        return parsed.copy(
            id = System.currentTimeMillis().toString(),
            name = name,
            file = File(SkyToxPublicFolders.tracksDir, "${name.sanitizeFileName()}.gpx"),
        )
    }

    fun fromActive(points: List<TrackPoint>): SkyToxRecordedTrack? {
        if (points.size < 2) return null
        val startedAt = points.first().timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
        val name = newTrackName(startedAt)
        return SkyToxRecordedTrack(
            id = startedAt.toString(),
            name = name,
            file = File(SkyToxPublicFolders.tracksDir, "${name.sanitizeFileName()}.gpx"),
            points = points,
        )
    }

    private fun newTrackName(timestamp: Long): String = displayStamp.format(Date(timestamp))

    private fun readGpx(file: File): SkyToxRecordedTrack {
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, Charsets.UTF_8.name())
            val points = mutableListOf<TrackPoint>()
            var name = file.nameWithoutExtension
            var currentLat = Double.NaN
            var currentLon = Double.NaN
            var currentEle = Double.NaN
            var currentTime = 0L
            var insideTrackName = false
            var insideEle = false
            var insideTime = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "name" -> insideTrackName = true
                        "trkpt" -> {
                            currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                            currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                            currentEle = Double.NaN
                            currentTime = 0L
                        }
                        "ele" -> insideEle = true
                        "time" -> insideTime = true
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.orEmpty().trim()
                        if (insideTrackName && text.isNotBlank()) name = text
                        if (insideEle) currentEle = text.toDoubleOrNull() ?: Double.NaN
                        if (insideTime) currentTime = runCatching { gpxTime.parse(text)?.time ?: 0L }.getOrDefault(0L)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "name" -> insideTrackName = false
                        "ele" -> insideEle = false
                        "time" -> insideTime = false
                        "trkpt" -> if (!currentLat.isNaN() && !currentLon.isNaN()) {
                            points += TrackPoint(currentLat, currentLon, currentTime, 0f, currentEle)
                        }
                    }
                }
                parser.next()
            }
            return SkyToxRecordedTrack(file.absolutePath, name, file, points)
        }
    }

    private fun toGpx(track: SkyToxRecordedTrack): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<gpx version="1.1" creator="skyTox" xmlns="http://www.topografix.com/GPX/1/1">""")
        appendLine("  <trk>")
        appendLine("    <name>${track.name.escapeXml()}</name>")
        appendLine("    <trkseg>")
        track.points.forEach { point ->
            append("""      <trkpt lat="${point.lat}" lon="${point.lon}">""")
            if (!point.altitude.isNaN()) append("<ele>${point.altitude}</ele>")
            append("<time>${gpxTime.format(Date(point.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()))}</time>")
            appendLine("</trkpt>")
        }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
        appendLine("</gpx>")
    }

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var file = File(dir, name)
        var counter = 1
        while (file.exists()) {
            file = File(dir, if (ext.isBlank()) "$base-$counter" else "$base-$counter.$ext")
            counter++
        }
        return file
    }

    private fun findDuplicate(track: SkyToxRecordedTrack): SkyToxRecordedTrack? {
        val signature = track.signature()
        return loadAll().firstOrNull { it.signature() == signature }
    }

    private fun SkyToxRecordedTrack.signature(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        points.forEach { point ->
            val row = "${"%.6f".format(Locale.US, point.lat)},${"%.6f".format(Locale.US, point.lon)},${point.timestamp / 1000}\n"
            digest.update(row.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun String.sanitizeFileName() = replace(Regex("""[\\/:*?"<>|]"""), "-")
    private fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

private class SkyToxMapData(val lines: List<MapLine>, val places: List<MapPlace>) {
    companion object {
        fun load(context: Context, layers: OfflineLayerState): SkyToxMapData {
            val lines = ArrayList<MapLine>()
            val places = ArrayList<MapPlace>()
            context.resources.openRawResource(R.raw.skytox_ne_50m).use { input ->
                JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "lines" -> readLines(reader, layers, lines)
                            "places" -> readPlaces(reader, layers, places)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
            return SkyToxMapData(lines, places)
        }

        private fun readLines(reader: JsonReader, layers: OfflineLayerState, lines: MutableList<MapLine>) {
            reader.beginArray()
            while (reader.hasNext()) {
                var kind = ""
                var rank = 99
                var points: FloatArray? = null
                var minLon = Float.POSITIVE_INFINITY
                var maxLon = Float.NEGATIVE_INFINITY
                var minLat = Float.POSITIVE_INFINITY
                var maxLat = Float.NEGATIVE_INFINITY
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "k" -> kind = reader.nextString()
                        "r" -> rank = reader.nextInt()
                        "p" -> {
                            val values = ArrayList<Float>()
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginArray()
                                val lon = reader.nextDouble().toFloat()
                                val lat = reader.nextDouble().toFloat()
                                while (reader.hasNext()) reader.skipValue()
                                reader.endArray()
                                values += lon
                                values += lat
                                minLon = minOf(minLon, lon)
                                maxLon = maxOf(maxLon, lon)
                                minLat = minOf(minLat, lat)
                                maxLat = maxOf(maxLat, lat)
                            }
                            reader.endArray()
                            points = values.toFloatArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                val finalPoints = points
                if (finalPoints != null && shouldKeepLine(kind, layers)) {
                    lines += MapLine(kind, finalPoints, rank, minLon, maxLon, minLat, maxLat)
                }
            }
            reader.endArray()
        }

        private fun readPlaces(reader: JsonReader, layers: OfflineLayerState, places: MutableList<MapPlace>) {
            reader.beginArray()
            while (reader.hasNext()) {
                var name = ""
                var lon = 0.0
                var lat = 0.0
                var population = 0
                var rank = 99
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "n" -> name = reader.nextString()
                        "x" -> lon = reader.nextDouble()
                        "y" -> lat = reader.nextDouble()
                        "p" -> population = reader.nextInt()
                        "r" -> rank = reader.nextInt()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (layers.cities) places += MapPlace(name, lon, lat, population, rank)
            }
            reader.endArray()
        }

        private fun shouldKeepLine(kind: String, layers: OfflineLayerState): Boolean {
            return when (kind) {
                "land", "lake", "river" -> true
                "border", "admin1", "admin2" -> layers.borders
                "road" -> layers.roads
                "rail" -> layers.rails
                else -> false
            }
        }
    }
}

private class SkyToxMapView(context: Context) : View(context) {
    private var lastFollowCameraUpdateMs = 0L
    var contactPublicKey: String = ""
    var ownLocation: SkyToxSharedLocation? = null
        set(value) {
            updateOwnMovementHeading(value)
            val shouldCenterFirstFix = field == null && value != null
            field = value
            if (shouldCenterFirstFix) {
                followOwnLocation = true
                centerOn(value!!.latitude, value.longitude, defaultZoom = true)
            } else if (followOwnLocation && value != null) {
                updateFollowCamera(value)
            }
        }
    var peerLocation: SkyToxSharedLocation? = null
    var followOwnLocation = false
    var onCameraChanged: (() -> Unit)? = null
    var onMarkerLongPress: ((Double, Double) -> Unit)? = null
    var onTrackLongPress: ((SkyToxRecordedTrack) -> Unit)? = null
    var onOsmAvailabilityChanged: (() -> Unit)? = null
    var onOfflineLoadingChanged: ((Boolean) -> Unit)? = null
    var onMapTouched: (() -> Unit)? = null
    var osmEnabled = false
        set(value) {
            field = value
            if (field) {
                data = SkyToxMapData(emptyList(), emptyList())
                invalidateStaticCache()
                setOfflineMapLoading(false)
            } else {
                osmTileLoader.clear()
                loadOfflineMap()
            }
            invalidate()
        }
    var trackRecording = false
        private set
    var tracksVisible = true
        set(value) {
            field = value
            invalidate()
        }
    private var data = SkyToxMapData(emptyList(), emptyList())
    private var offlineLayers = OfflineLayerState()
    private val osmTileLoader = OsmTileLoader(this)
    private val offlineMapExecutor = Executors.newSingleThreadExecutor()
    private val markers = mutableListOf<UserMapMarker>()
    private val track = mutableListOf<TrackPoint>()
    private val savedTracks = mutableListOf<SkyToxRecordedTrack>()
    private val movementSamples = mutableListOf<MovementSample>()
    private var suspiciousTrackPoint: TrackPoint? = null
    private var offlineMapLoadGeneration = 0
    private var offlineMapLoading = false
    private var ownMovementBearing = 0.0
    private var centerLon = 37.6
    private var centerLat = 55.75
    private var zoom = 2.0
    private var lastX = 0f
    private var lastY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var flingLastX = 0
    private var flingLastY = 0
    private var staticCache: Bitmap? = null
    private var cacheWidth = 0
    private var cacheHeight = 0
    private var cacheCenterLon = Double.NaN
    private var cacheCenterLat = Double.NaN
    private var cacheZoom = Double.NaN
    private var cachePaddingX = 0
    private var cachePaddingY = 0
    private var scaling = false
    private var skipPanAfterScale = false
    private var movedDuringGesture = false
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(event: MotionEvent) {
            if (movedDuringGesture || scaler.isInProgress) return
            val mapPoint = screenPointToMapPoint(event.x, event.y)
            trackNear(mapPoint.first - dragX, mapPoint.second - dragY)?.let {
                onTrackLongPress?.invoke(it)
                return
            }
            val point = screenToLatLon(mapPoint.first - dragX, mapPoint.second - dragY)
            onMarkerLongPress?.invoke(point.first, point.second)
        }
    })
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            skipPanAfterScale = true
            dragX = 0f
            dragY = 0f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom + log2(detector.scaleFactor.toDouble())).coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)
            dragX = 0f
            dragY = 0f
            invalidate()
            onCameraChanged?.invoke()
            onOsmAvailabilityChanged?.invoke()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            invalidateStaticCache()
            invalidate()
        }
    })

    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(194, 213, 188); style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1f }
    private val lakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(142, 190, 213); style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1f }
    private val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(92, 161, 205); style = Paint.Style.STROKE; strokeWidth = 1.2f }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(155, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.6f }
    private val admin1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2.0f }
    private val admin2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.4f }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(62, 68, 66); style = Paint.Style.STROKE; strokeWidth = 2.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 126, 42); style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(65, 75, 72); textSize = 44f }
    private val mePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 118, 235); style = Paint.Style.FILL }
    private val peerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 143, 0); style = Paint.Style.FILL }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(223, 54, 92); style = Paint.Style.FILL }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; setShadowLayer(4f, 0f, 1f, Color.BLACK) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(223, 54, 92); style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    fun setOfflineLayers(layers: OfflineLayerState) {
        offlineLayers = layers
        if (!osmEnabled) loadOfflineMap()
    }

    private fun loadOfflineMap() {
        val layers = offlineLayers
        val generation = ++offlineMapLoadGeneration
        setOfflineMapLoading(true)
        offlineMapExecutor.execute {
            val loaded = SkyToxMapData.load(context, layers)
            post {
                if (generation != offlineMapLoadGeneration || osmEnabled) return@post
                data = loaded
                invalidateStaticCache()
                invalidate()
                onCameraChanged?.invoke()
                setOfflineMapLoading(false)
            }
        }
    }

    private fun setOfflineMapLoading(value: Boolean) {
        if (offlineMapLoading == value) return
        offlineMapLoading = value
        onOfflineLoadingChanged?.invoke(value)
    }

    fun setStoredMarkers(items: List<UserMapMarker>) {
        markers.clear()
        markers += items
        invalidate()
    }

    fun setSavedTracks(items: List<SkyToxRecordedTrack>) {
        savedTracks.clear()
        savedTracks += items
        invalidate()
    }

    fun userMarkers(): List<UserMapMarker> = markers.toList()

    fun addMarker(marker: UserMapMarker) {
        markers += marker
        invalidate()
    }

    fun removeMarker(id: String) {
        markers.removeAll { it.id == id }
        invalidate()
    }

    fun markerNear(lat: Double, lon: Double): UserMapMarker? {
        val touch = projectToScreen(lon, lat)
        return markers.minByOrNull { marker ->
            val xy = projectToScreen(marker.lon, marker.lat)
            (xy.first - touch.first).pow(2) + (xy.second - touch.second).pow(2)
        }?.takeIf { marker ->
            val xy = projectToScreen(marker.lon, marker.lat)
            sqrt((xy.first - touch.first).pow(2) + (xy.second - touch.second).pow(2)) <= 52f
        }
    }

    fun startTrackRecording() {
        track.clear()
        suspiciousTrackPoint = null
        trackRecording = true
        invalidate()
    }

    fun stopTrackRecordingAndBuild(): SkyToxRecordedTrack? {
        trackRecording = false
        val result = SkyToxTrackStore.fromActive(track.toList())
        track.clear()
        suspiciousTrackPoint = null
        invalidate()
        return result
    }

    fun addTrackPoint(location: SkyToxSharedLocation?) {
        if (!trackRecording || location == null) return
        if (location.accuracy > 0f && location.accuracy > TRACK_MAX_ACCURACY_METERS) return
        val point = TrackPoint(
            location.latitude,
            location.longitude,
            location.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis(),
            location.accuracy,
            location.altitude,
        )
        var last = track.lastOrNull()
        if (last == null) {
            track += point
            invalidate()
            return
        }

        val quarantined = suspiciousTrackPoint
        if (quarantined != null) {
            if (confirmsQuarantinedPoint(last, quarantined, point)) {
                track += quarantined
                last = quarantined
            } else if (returnedFromStationaryJump(last, quarantined, point)) {
                suspiciousTrackPoint = null
                invalidate()
                return
            }
            suspiciousTrackPoint = null
        }

        val previous = track.getOrNull(track.size - 2)
        if (previous != null && isOutAndBackSpike(previous, last, point)) {
            track.removeAt(track.lastIndex)
            last = previous
        }

        val distance = distanceMeters(point.lat, point.lon, last.lat, last.lon)
        if (distance < TRACK_MIN_DISTANCE_METERS) return
        val seconds = ((point.timestamp - last.timestamp) / 1000.0).coerceAtLeast(1.0)
        val speed = distance / seconds
        if (isSuspiciousTrackPoint(last, point, speed)) {
            suspiciousTrackPoint = point
            return
        }
        track += point
        invalidate()
    }

    private fun isSuspiciousTrackPoint(last: TrackPoint, point: TrackPoint, speed: Double): Boolean {
        val distance = distanceMeters(point.lat, point.lon, last.lat, last.lon)
        val accuracyGate = trackAccuracyGate(last, point)
        if (distance > accuracyGate && speed > TRACK_TELEPORT_SPEED_MPS) return true
        if (distance >= TRACK_JUMP_DISTANCE_METERS && speed > TRACK_STATIONARY_SPEED_MPS) return true

        val previous = track.getOrNull(track.size - 2) ?: return false
        val previousLeg = distanceMeters(previous.lat, previous.lon, last.lat, last.lon)
        if (previousLeg < TRACK_SIDE_SPIKE_MIN_METERS || distance < TRACK_SIDE_SPIKE_MIN_METERS) return false
        if (distance > TRACK_SIDE_SPIKE_MAX_METERS) return true

        val turnAngle = trackTurnAngleDegrees(previous, last, point)
        val sideDistance = distanceToTrackSegmentMeters(point, previous, last)
        val sideGate = maxOf(10.0, point.accuracy.toDouble() * 2.0, last.accuracy.toDouble() * 2.0)
        return (turnAngle > TRACK_SIDE_SPIKE_ANGLE_DEGREES && sideDistance > sideGate) ||
            (turnAngle > TRACK_HAIRPIN_ANGLE_DEGREES && distance > TRACK_SIDE_SPIKE_MIN_METERS)
    }

    private fun confirmsQuarantinedPoint(last: TrackPoint, quarantined: TrackPoint, point: TrackPoint): Boolean {
        val fromLast = distanceMeters(point.lat, point.lon, last.lat, last.lon)
        val fromQuarantined = distanceMeters(point.lat, point.lon, quarantined.lat, quarantined.lon)
        val gate = trackAccuracyGate(quarantined, point)
        return fromQuarantined < gate && fromQuarantined < fromLast * 0.75
    }

    private fun isOutAndBackSpike(previous: TrackPoint, last: TrackPoint, point: TrackPoint): Boolean {
        val previousToPoint = distanceMeters(previous.lat, previous.lon, point.lat, point.lon)
        val previousToLast = distanceMeters(previous.lat, previous.lon, last.lat, last.lon)
        val lastToPoint = distanceMeters(last.lat, last.lon, point.lat, point.lon)
        if (previousToLast < TRACK_SIDE_SPIKE_MIN_METERS || lastToPoint < TRACK_SIDE_SPIKE_MIN_METERS) return false

        val returnGate = maxOf(35.0, previous.accuracy.toDouble() * 4.0, point.accuracy.toDouble() * 4.0)
        val sideGate = maxOf(10.0, last.accuracy.toDouble() * 2.0)
        val sideDistance = distanceToTrackSegmentMeters(last, previous, point)
        val turnAngle = trackTurnAngleDegrees(previous, last, point)
        return previousToPoint < returnGate && sideDistance > sideGate && turnAngle > TRACK_HAIRPIN_ANGLE_DEGREES
    }

    private fun returnedFromStationaryJump(last: TrackPoint, quarantined: TrackPoint, point: TrackPoint): Boolean {
        val fromLast = distanceMeters(point.lat, point.lon, last.lat, last.lon)
        val jump = distanceMeters(quarantined.lat, quarantined.lon, last.lat, last.lon)
        val back = distanceMeters(point.lat, point.lon, quarantined.lat, quarantined.lon)
        val seconds = ((point.timestamp - last.timestamp) / 1000.0).coerceAtLeast(1.0)
        val averageSpeed = fromLast / seconds
        return jump >= TRACK_JUMP_DISTANCE_METERS &&
            fromLast <= TRACK_STATIONARY_RETURN_METERS &&
            back >= TRACK_JUMP_DISTANCE_METERS &&
            averageSpeed <= TRACK_STATIONARY_SPEED_MPS
    }

    private fun trackAccuracyGate(a: TrackPoint, b: TrackPoint): Double {
        return maxOf(55.0, a.accuracy.toDouble() * 5.0, b.accuracy.toDouble() * 5.0)
    }

    private fun distanceToTrackSegmentMeters(point: TrackPoint, start: TrackPoint, end: TrackPoint): Double {
        val originLat = Math.toRadians((start.lat + end.lat + point.lat) / 3.0)
        val px = metersX(point.lon, originLat)
        val py = metersY(point.lat)
        val ax = metersX(start.lon, originLat)
        val ay = metersY(start.lat)
        val bx = metersX(end.lon, originLat)
        val by = metersY(end.lat)
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) return sqrt((px - ax).pow(2) + (py - ay).pow(2))
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val sx = ax + t * dx
        val sy = ay + t * dy
        return sqrt((px - sx).pow(2) + (py - sy).pow(2))
    }

    private fun trackTurnAngleDegrees(previous: TrackPoint, middle: TrackPoint, point: TrackPoint): Double {
        val first = bearingDegrees(previous.lat, previous.lon, middle.lat, middle.lon)
        val second = bearingDegrees(middle.lat, middle.lon, point.lat, point.lon)
        val delta = abs(((second - first + 540.0) % 360.0) - 180.0)
        return delta
    }

    private fun metersX(lon: Double, originLatRadians: Double) = Math.toRadians(lon) * 6_371_000.0 * cos(originLatRadians)

    private fun metersY(lat: Double) = Math.toRadians(lat) * 6_371_000.0

    fun clearTrack() {
        track.clear()
        suspiciousTrackPoint = null
        invalidate()
    }

    private fun trackNear(x: Float, y: Float): SkyToxRecordedTrack? {
        if (!tracksVisible) return null
        return savedTracks.firstOrNull { recorded ->
            recorded.points.zipWithNext().any { (a, b) ->
                val ax = projectToScreen(a.lon, a.lat)
                val bx = projectToScreen(b.lon, b.lat)
                distanceToSegment(x, y, ax.first, ax.second, bx.first, bx.second) <= 36f
            }
        }
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0f && dy == 0f) return sqrt((px - ax).pow(2) + (py - ay).pow(2))
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val sx = ax + t * dx
        val sy = ay + t * dy
        return sqrt((px - sx).pow(2) + (py - sy).pow(2))
    }

    fun canUseOsmLayer(): Boolean = true

    fun release() {
        invalidateStaticCache()
        osmTileLoader.clear()
        data = SkyToxMapData(emptyList(), emptyList())
        ownLocation = null
        peerLocation = null
        setOfflineMapLoading(false)
    }

    fun centerOn(lat: Double, lon: Double, defaultZoom: Boolean) {
        centerLat = lat.coerceIn(-84.0, 84.0)
        centerLon = lon.coerceIn(-180.0, 180.0)
        val zoomChanged = defaultZoom && kotlin.math.abs(zoom - MAP_DEFAULT_ZOOM) > 0.001
        if (defaultZoom) zoom = MAP_DEFAULT_ZOOM
        dragX = 0f
        dragY = 0f
        if (zoomChanged) invalidateStaticCache()
        invalidate()
        onCameraChanged?.invoke()
        onOsmAvailabilityChanged?.invoke()
    }

    private fun updateFollowCamera(location: SkyToxSharedLocation) {
        val now = System.currentTimeMillis()
        if (now - lastFollowCameraUpdateMs < FOLLOW_CAMERA_MIN_INTERVAL_MS) return
        val minDistance = maxOf(
            FOLLOW_CAMERA_MIN_DISTANCE_METERS,
            location.accuracy.coerceIn(0f, 48f) * 0.25,
        )
        if (distanceMeters(centerLat, centerLon, location.latitude, location.longitude) < minDistance) return
        lastFollowCameraUpdateMs = now
        centerOn(location.latitude, location.longitude, defaultZoom = false)
    }

    fun cameraHeightLabel(): String {
        val meters = cameraHeightMeters()
        return if (meters >= 1000) {
            "${(meters / 1000.0).roundToInt()} ${context.getString(R.string.location_kilometer_short)}"
        } else {
            "${meters.roundToInt().coerceAtLeast(1)} ${context.getString(R.string.location_meter_short)}"
        }
    }

    override fun onDraw(canvas: Canvas) {
        val rotation = mapRotation()
        if (rotation != 0f) {
            canvas.save()
            canvas.rotate(rotation, width / 2f, height / 2f)
        }
        if (osmEnabled) {
            canvas.drawColor(Color.rgb(225, 232, 226))
            drawOsmLayer(canvas)
        } else {
            val cache = ensureStaticCache()
            val visualScale = 2.0.pow(zoom - cacheZoom).toFloat().coerceIn(0.25f, 4f)
            val offset = cacheScreenOffset()
            val drawX = width / 2f - cache.width / 2f + offset.first + dragX
            val drawY = height / 2f - cache.height / 2f + offset.second + dragY
            canvas.save()
            canvas.scale(visualScale, visualScale, width / 2f, height / 2f)
            canvas.drawBitmap(cache, drawX, drawY, null)
            canvas.restore()
        }
        drawTrack(canvas)
        drawUserMarkers(canvas)
        peerLocation?.let { drawMarker(canvas, it.latitude, it.longitude, peerPaint) }
        ownLocation?.let { drawOwnMarker(canvas, it.latitude, it.longitude) }
        if (rotation != 0f) canvas.restore()
    }

    private fun drawOsmLayer(canvas: Canvas) {
        if (!osmEnabled) return
        val layout = osmTileLayout(OSM_TILE_PREFETCH_MARGIN)
        val n = 1 shl layout.zoom
        osmTileLoader.requestAll(layout.keys.map { key -> key.copy(x = ((key.x % n) + n) % n) })
        drawOsmTiles(canvas, layout)
        drawOsmAttribution(canvas)
    }

    private fun drawOsmTiles(canvas: Canvas, layout: OsmTileLayout) {
        val n = 1 shl layout.zoom
        layout.keys.forEach { key ->
            val x = ((key.x - layout.startTileX) * layout.tileSize - layout.offsetX).toFloat()
            val y = ((key.y - layout.startTileY) * layout.tileSize - layout.offsetY).toFloat()
            val wrappedKey = OsmTileKey(layout.zoom, ((key.x % n) + n) % n, key.y)
            val bitmap = osmTileLoader.getCached(wrappedKey)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, RectF(x, y, x + layout.tileSize.toFloat(), y + layout.tileSize.toFloat()), null)
            }
        }
    }

    private fun osmTileLayout(margin: Int): OsmTileLayout {
        val tileZoom = zoom.roundToInt().coerceIn(1, 19)
        val n = 1 shl tileZoom
        val centerTile = lonLatToTile(centerLon, centerLat, tileZoom)
        val scaleFactor = 2.0.pow(zoom - tileZoom)
        val tileSize = 256.0 * scaleFactor
        val centerPx = centerTile.first * tileSize
        val centerPy = centerTile.second * tileSize
        val leftPx = centerPx - width / 2f - dragX
        val topPx = centerPy - height / 2f - dragY
        val startX = floor(leftPx / tileSize).toInt() - margin
        val endX = floor((leftPx + width) / tileSize).toInt() + margin
        val startY = floor(topPx / tileSize).toInt() - margin
        val endY = floor((topPx + height) / tileSize).toInt() + margin
        val centerX = floor(centerTile.first).toInt()
        val centerY = floor(centerTile.second).toInt()
        val keys = buildList {
            for (tileX in startX..endX) {
                for (tileY in startY..endY) {
                    if (tileY !in 0 until n) continue
                    add(OsmTileKey(tileZoom, tileX, tileY))
                }
            }
        }.sortedWith(
            compareBy<OsmTileKey> {
                (it.x - centerX).let { dx -> dx * dx } + (it.y - centerY).let { dy -> dy * dy }
            },
        )
        return OsmTileLayout(tileZoom, startX, startY, leftPx - startX * tileSize, topPx - startY * tileSize, tileSize, keys)
    }

    private fun drawOsmAttribution(canvas: Canvas) {
        val label = "\u00A9 OpenStreetMap contributors"
        val padding = 8f
        textPaint.textSize = 18f
        val textWidth = textPaint.measureText(label)
        canvas.drawRect(
            padding,
            height - 28f,
            padding + textWidth + 12f,
            height - 4f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) },
        )
        canvas.drawText(label, padding + 6f, height - 10f, textPaint)
        textPaint.textSize = 44f
    }

    private fun drawTrack(canvas: Canvas) {
        if (tracksVisible) savedTracks.forEach { drawTrackPoints(canvas, it.points) }
        drawTrackPoints(canvas, track)
    }

    private fun drawTrackPoints(canvas: Canvas, points: List<TrackPoint>) {
        if (points.size < 2) return
        val path = Path()
        points.forEachIndexed { index, point ->
            val xy = projectToScreen(point.lon, point.lat)
            val x = xy.first + dragX
            val y = xy.second + dragY
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, trackPaint)
    }

    private fun drawUserMarkers(canvas: Canvas) {
        markers.forEach { marker ->
            val xy = projectToScreen(marker.lon, marker.lat)
            val x = xy.first + dragX
            val y = xy.second + dragY
            drawUserPin(canvas, x, y)
            canvas.drawText(marker.name, x + 24f, y - 24f, markerTextPaint)
        }
    }

    private fun drawUserPin(canvas: Canvas, x: Float, y: Float) {
        val path = Path().apply {
            addCircle(x, y - 14f, 16f, Path.Direction.CW)
            moveTo(x - 10f, y - 2f)
            lineTo(x, y + 18f)
            lineTo(x + 10f, y - 2f)
            close()
        }
        canvas.drawPath(path, markerPaint)
        canvas.drawCircle(x, y - 14f, 5f, whitePaint)
    }

    private fun renderStatic(canvas: Canvas, renderCenterLon: Double, renderCenterLat: Double) {
        canvas.drawColor(Color.rgb(166, 205, 224))
        val heightMeters = cameraHeightMeters()
        fun drawLine(line: MapLine) {
            if (!shouldDrawLine(line, heightMeters)) return
            if (!lineIntersectsCanvas(line, canvas.width, canvas.height, renderCenterLon, renderCenterLat)) return
            val paint = when (line.kind) {
                "land" -> landPaint
                "lake" -> lakePaint
                "river" -> riverPaint
                "admin1" -> admin1Paint
                "admin2" -> admin2Paint
                "road" -> roadPaint
                "rail" -> railPaint
                else -> borderPaint
            }
            val path = Path()
            var visible = line.kind == "land"
            for (index in line.points.indices step 2) {
                val xy = projectToCanvas(
                    line.points[index].toDouble(),
                    line.points[index + 1].toDouble(),
                    canvas.width,
                    canvas.height,
                    renderCenterLon,
                    renderCenterLat,
                )
                if (xy.first in -200f..(canvas.width + 200f) && xy.second in -200f..(canvas.height + 200f)) {
                    visible = true
                }
                if (index == 0) path.moveTo(xy.first, xy.second) else path.lineTo(xy.first, xy.second)
            }
            if (visible) {
                if (line.kind == "land" || line.kind == "lake") path.close()
                canvas.drawPath(path, paint)
            }
        }
        data.lines.forEach { line ->
            if (line.kind != "road" && line.kind != "rail") drawLine(line)
        }
        data.lines.forEach { line ->
            if (line.kind == "road" || line.kind == "rail") drawLine(line)
        }
        if (heightMeters <= 1_500_000.0) {
            data.places.forEach { place ->
                if (!shouldDrawPlace(place, heightMeters)) return@forEach
                val xy = projectToCanvas(place.lon, place.lat, canvas.width, canvas.height, renderCenterLon, renderCenterLat)
                if (xy.first in -50f..(canvas.width + 50f) && xy.second in -50f..(canvas.height + 50f)) {
                    canvas.drawCircle(xy.first, xy.second, 3f, borderPaint)
                    canvas.drawText(place.name, xy.first + 10f, xy.second - 10f, textPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocityTracker?.addMovement(event)
        if (event.pointerCount == 1 && !scaler.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                onMapTouched?.invoke()
                lastX = event.x
                lastY = event.y
                if (followOwnLocation) {
                    followOwnLocation = false
                    invalidate()
                }
                movedDuringGesture = false
                skipPanAfterScale = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                movedDuringGesture = true
                skipPanAfterScale = true
                dragX = 0f
                dragY = 0f
                lastX = event.getX(0)
                lastY = event.getY(0)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                skipPanAfterScale = true
                dragX = 0f
                dragY = 0f
                lastX = event.getX(0)
                lastY = event.getY(0)
            }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && event.pointerCount == 1) {
                dragX += event.x - lastX
                dragY += event.y - lastY
                if (kotlin.math.abs(dragX) > 12f || kotlin.math.abs(dragY) > 12f) movedDuringGesture = true
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scaling = false
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocityX = velocityTracker?.xVelocity ?: 0f
                val velocityY = velocityTracker?.yVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null
                if (skipPanAfterScale) {
                    dragX = 0f
                    dragY = 0f
                    skipPanAfterScale = false
                    invalidateStaticCache()
                    invalidate()
                    onCameraChanged?.invoke()
                    onOsmAvailabilityChanged?.invoke()
                } else if (dragX != 0f || dragY != 0f) {
                    panBy(-dragX, -dragY)
                    dragX = 0f
                    dragY = 0f
                    invalidate()
                    onCameraChanged?.invoke()
                    onOsmAvailabilityChanged?.invoke()
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        kotlin.math.hypot(velocityX.toDouble(), velocityY.toDouble()) > 650.0
                    ) {
                        startFling(velocityX, velocityY)
                    }
                }
            }
        }
        return true
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val dx = scroller.currX - flingLastX
        val dy = scroller.currY - flingLastY
        flingLastX = scroller.currX
        flingLastY = scroller.currY
        if (dx != 0 || dy != 0) {
            panBy(-dx.toFloat(), -dy.toFloat())
            onCameraChanged?.invoke()
            onOsmAvailabilityChanged?.invoke()
        }
        postInvalidateOnAnimation()
    }

    private fun startFling(velocityX: Float, velocityY: Float) {
        flingLastX = 0
        flingLastY = 0
        scroller.fling(
            0,
            0,
            velocityX.roundToInt(),
            velocityY.roundToInt(),
            -100_000,
            100_000,
            -100_000,
            100_000,
        )
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        release()
        osmTileLoader.shutdown()
        offlineMapExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun drawMarker(canvas: Canvas, lat: Double, lon: Double, paint: Paint) {
        val xy = projectToScreen(lon, lat)
        val x = xy.first + dragX
        val y = xy.second + dragY
        canvas.drawCircle(x, y, 17f, whitePaint)
        canvas.drawCircle(x, y, 12f, paint)
    }

    private fun drawOwnMarker(canvas: Canvas, lat: Double, lon: Double) {
        val xy = projectToScreen(lon, lat)
        val x = xy.first + dragX
        val y = xy.second + dragY
        val size = 60f
        val path = Path().apply {
            moveTo(0f, -size)
            lineTo(size * 0.48f, size * 0.52f)
            lineTo(0f, size * 0.24f)
            lineTo(-size * 0.48f, size * 0.52f)
            close()
        }
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(ownMovementBearing.toFloat())
        canvas.drawPath(path, whitePaint)
        canvas.scale(0.78f, 0.78f)
        canvas.drawPath(path, mePaint)
        canvas.restore()
    }

    private fun mapRotation(): Float = if (followOwnLocation && osmEnabled) {
        -ownMovementBearing.toFloat()
    } else {
        0f
    }

    private fun screenPointToMapPoint(x: Float, y: Float): Pair<Float, Float> {
        val rotation = mapRotation()
        if (rotation == 0f) return x to y
        val radians = Math.toRadians((-rotation).toDouble())
        val dx = x - width / 2f
        val dy = y - height / 2f
        val cosValue = cos(radians)
        val sinValue = sin(radians)
        return (width / 2f + dx * cosValue - dy * sinValue).toFloat() to
            (height / 2f + dx * sinValue + dy * cosValue).toFloat()
    }

    private fun updateOwnMovementHeading(location: SkyToxSharedLocation?) {
        if (location == null) return
        val now = location.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
        movementSamples += MovementSample(location.latitude, location.longitude, now)
        movementSamples.removeAll { now - it.timestamp > 12_000L }
        val base = movementSamples.firstOrNull { now - it.timestamp >= MOVEMENT_HEADING_MIN_TIME_MS } ?: return
        val dt = ((now - base.timestamp) / 1000.0).coerceAtLeast(1.0)
        val distance = distanceMeters(base.lat, base.lon, location.latitude, location.longitude)
        if (distance / dt >= MOVEMENT_HEADING_MIN_SPEED_MPS) {
            ownMovementBearing = bearingDegrees(base.lat, base.lon, location.latitude, location.longitude)
        }
    }

    private fun panBy(dx: Float, dy: Float) {
        val scale = scale()
        val center = world(centerLon, centerLat)
        val target = unworld(center.first + dx / scale, center.second + dy / scale)
        centerLon = target.first.coerceIn(-180.0, 180.0)
        centerLat = target.second.coerceIn(-84.0, 84.0)
        invalidate()
    }

    private fun ensureStaticCache(): Bitmap {
        val reusable = staticCache
        val offset = cacheScreenOffset()
        if (reusable != null &&
            cacheWidth >= width &&
            cacheHeight >= height &&
            kotlin.math.abs(offset.first) < cachePaddingX * 0.72f &&
            kotlin.math.abs(offset.second) < cachePaddingY * 0.72f &&
            (kotlin.math.abs(cacheZoom - zoom) < 0.001 || scaling)
        ) {
            return reusable
        }

        cachePaddingX = width.coerceAtLeast(1) / 2
        cachePaddingY = height.coerceAtLeast(1) / 2
        val bitmapWidth = (width + cachePaddingX * 2).coerceAtLeast(1)
        val bitmapHeight = (height + cachePaddingY * 2).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.RGB_565)
        renderStatic(Canvas(bitmap), centerLon, centerLat)
        staticCache?.recycle()
        staticCache = bitmap
        cacheWidth = bitmapWidth
        cacheHeight = bitmapHeight
        cacheCenterLon = centerLon
        cacheCenterLat = centerLat
        cacheZoom = zoom
        return bitmap
    }

    private fun cacheScreenOffset(): Pair<Float, Float> {
        if (cacheCenterLon.isNaN() || cacheCenterLat.isNaN()) return 0f to 0f
        val cacheCenter = world(cacheCenterLon, cacheCenterLat)
        val currentCenter = world(centerLon, centerLat)
        val scale = scale()
        return ((cacheCenter.first - currentCenter.first) * scale).toFloat() to
            ((cacheCenter.second - currentCenter.second) * scale).toFloat()
    }

    private fun invalidateStaticCache() {
        staticCache?.recycle()
        staticCache = null
    }

    private fun shouldDrawPlace(place: MapPlace, heightMeters: Double): Boolean = when {
        heightMeters <= 5_000.0 -> true
        heightMeters <= 50_000.0 -> place.rank <= 4 || place.population >= 500_000 || place.name in majorCityNames
        heightMeters <= 100_000.0 -> place.population >= 900_000 || place.rank <= 2 || place.name in majorCityNames
        heightMeters <= 1_500_000.0 -> place.rank <= 2 || place.population >= 1_000_000 || place.name in majorCityNames
        else -> false
    }

    private val majorCityNames = setOf(
            "Moscow", "Saint Petersburg", "Kazan", "Yekaterinburg", "Novosibirsk", "Ufa",
            "Nizhniy Novgorod", "Nizhny Novgorod", "Velikiy Novgorod", "Samara", "Saratov", "Perm",
            "London", "Paris", "Berlin", "Rome", "Madrid", "Istanbul", "Beijing", "Tokyo",
            "New York", "Los Angeles", "Cairo", "Delhi", "Mumbai", "Tehran",
        )

    private fun shouldDrawLine(line: MapLine, heightMeters: Double): Boolean = when (line.kind) {
        "land", "lake", "river", "border" -> true
        "admin1" -> heightMeters <= 100_000.0 && line.rank <= 3
        "admin2" -> heightMeters <= 3_000.0
        "road" -> when {
            heightMeters <= 80_000.0 -> true
            else -> false
        }
        "rail" -> when {
            heightMeters <= 80_000.0 -> true
            else -> false
        }
        else -> false
    }

    private fun lineIntersectsCanvas(
        line: MapLine,
        canvasWidth: Int,
        canvasHeight: Int,
        renderCenterLon: Double,
        renderCenterLat: Double,
    ): Boolean {
        if (line.kind == "land") return true
        val topLeft = projectToCanvas(line.minLon.toDouble(), line.maxLat.toDouble(), canvasWidth, canvasHeight, renderCenterLon, renderCenterLat)
        val bottomRight = projectToCanvas(line.maxLon.toDouble(), line.minLat.toDouble(), canvasWidth, canvasHeight, renderCenterLon, renderCenterLat)
        val minX = minOf(topLeft.first, bottomRight.first)
        val maxX = maxOf(topLeft.first, bottomRight.first)
        val minY = minOf(topLeft.second, bottomRight.second)
        val maxY = maxOf(topLeft.second, bottomRight.second)
        val margin = 240f
        return maxX >= -margin &&
            minX <= canvasWidth + margin &&
            maxY >= -margin &&
            minY <= canvasHeight + margin
    }

    private fun projectToScreen(lon: Double, lat: Double): Pair<Float, Float> {
        return projectToCanvas(lon, lat, width, height)
    }

    private fun screenToLatLon(x: Float, y: Float): Pair<Double, Double> {
        val scale = scale()
        val center = world(centerLon, centerLat)
        val worldX = center.first + (x - width / 2f) / scale
        val worldY = center.second + (y - height / 2f) / scale
        val lonLat = unworld(worldX, worldY)
        return lonLat.second to lonLat.first
    }

    private fun lonLatToTile(lon: Double, lat: Double, z: Int): Pair<Double, Double> {
        val n = 2.0.pow(z)
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val x = (lon + 180.0) / 360.0 * n
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return x to y
    }

    private fun projectToCanvas(lon: Double, lat: Double, canvasWidth: Int, canvasHeight: Int): Pair<Float, Float> {
        return projectToCanvas(lon, lat, canvasWidth, canvasHeight, centerLon, centerLat)
    }

    private fun projectToCanvas(
        lon: Double,
        lat: Double,
        canvasWidth: Int,
        canvasHeight: Int,
        renderCenterLon: Double,
        renderCenterLat: Double,
    ): Pair<Float, Float> {
        val scale = scale()
        val p = world(lon, lat)
        val c = world(renderCenterLon, renderCenterLat)
        return ((p.first - c.first) * scale + canvasWidth / 2f).toFloat() to
            ((p.second - c.second) * scale + canvasHeight / 2f).toFloat()
    }

    private fun scale() = 256.0 * 2.0.pow(zoom) / 360.0
    private fun cameraHeightMeters() = 50.0 * 2.0.pow(MAP_MAX_ZOOM - zoom)

    private fun world(lon: Double, lat: Double): Pair<Double, Double> {
        val x = lon
        val sinLat = sin(Math.toRadians(lat.coerceIn(-85.0, 85.0)))
        val y = Math.toDegrees(0.5 * ln((1 + sinLat) / (1 - sinLat))).unaryMinus()
        return x to y
    }

    private fun unworld(x: Double, y: Double): Pair<Double, Double> {
        val lon = x
        val lat = Math.toDegrees(atan2(sinh(Math.toRadians(-y)), 1.0))
        return lon to lat
    }

    private fun sinh(value: Double) = (kotlin.math.exp(value) - kotlin.math.exp(-value)) / 2.0
}

private fun distanceMeters(a: SkyToxSharedLocation, b: SkyToxSharedLocation): Double {
    return distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
}

private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val earth = 6_371_000.0
    val lat1 = Math.toRadians(aLat)
    val lat2 = Math.toRadians(bLat)
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return earth * 2 * atan2(sqrt(h), sqrt(1 - h))
}

private fun bearingDegrees(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val lat1 = Math.toRadians(aLat)
    val lat2 = Math.toRadians(bLat)
    val dLon = Math.toRadians(bLon - aLon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}
