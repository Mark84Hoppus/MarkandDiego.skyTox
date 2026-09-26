// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.location

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.JsonReader
import android.util.LruCache
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
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
import ltd.evilcorp.domain.feature.SkyToxCrashLogger
import org.json.JSONObject

const val EXTRA_LOCATION_CONTACT_PUBLIC_KEY = "locationContactPublicKey"
const val EXTRA_LOCATION_CONTACT_NAME = "locationContactName"
const val EXTRA_LOCATION_CONTACT_AVATAR_URI = "locationContactAvatarUri"
const val EXTRA_LOCATION_PEER_LAT = "locationPeerLat"
const val EXTRA_LOCATION_PEER_LON = "locationPeerLon"
private const val MAP_MIN_ZOOM = 1.0
private const val MAP_MAX_ZOOM = 19.5
private const val OSM_MAX_HEIGHT_METERS = 20_000.0
private const val MOVEMENT_HEADING_MIN_SPEED_MPS = 3_000.0 / 3_600.0
private const val MOVEMENT_HEADING_MIN_TIME_MS = 5_000L

class SkyToxLocationMapActivity : AppCompatActivity() {
    @Inject
    lateinit var sharingManager: SkyToxLocationSharingManager

    private lateinit var mapView: SkyToxMapView
    private lateinit var shareButton: TextView
    private lateinit var distanceText: TextView
    private lateinit var locateButton: TextView
    private lateinit var osmButton: MapToolButton
    private lateinit var trackButton: MapToolButton
    private lateinit var layerMenuButton: MapToolButton
    private lateinit var layerPanel: LinearLayout
    private lateinit var peerButton: PeerLocationButton
    private lateinit var peerDirectionButton: PeerDirectionButton
    private lateinit var altitudeText: TextView
    private var offlineLayerState = OfflineLayerState()
    private var contactPublicKey = ""
    private var contactName = ""
    private var contactAvatarUri = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            sharingManager.requestOwnLocationOnly()
            updateShareButton()
        }
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
            setStoredMarkers(loadStoredMarkers())
            onMarkerLongPress = { lat, lon -> handleMarkerLongPress(lat, lon) }
            onOsmAvailabilityChanged = { updateOsmButton() }
            onMapTouched = { closeLayerPanel() }
            setOfflineLayers(offlineLayerState)
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(225, 232, 226))
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(25, 107, 201))
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
            addView(layerSwitch(R.string.location_layer_cities) {
                offlineLayerState = offlineLayerState.copy(cities = it)
                mapView.setOfflineLayers(offlineLayerState)
            })
            addView(layerSwitch(R.string.location_layer_borders) {
                offlineLayerState = offlineLayerState.copy(borders = it)
                mapView.setOfflineLayers(offlineLayerState)
            })
            addView(layerSwitch(R.string.location_layer_roads) {
                offlineLayerState = offlineLayerState.copy(roads = it)
                mapView.setOfflineLayers(offlineLayerState)
            })
            addView(layerSwitch(R.string.location_layer_rails) {
                offlineLayerState = offlineLayerState.copy(rails = it)
                mapView.setOfflineLayers(offlineLayerState)
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
            setOnClickListener { toggleTrackRecording() }
            setOnLongClickListener {
                confirmClearTrack()
                true
            }
        }
        val trackParams = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.CENTER_VERTICAL)
        trackParams.setMargins(0, 0, dp(24), dp(28))
        root.addView(trackButton, trackParams)

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
                mapView.peerLocation?.let { loc -> mapView.centerOn(loc.latitude, loc.longitude, maxZoom = true) }
            }
        }
        val peerParams = FrameLayout.LayoutParams(dp(92), dp(72), Gravity.BOTTOM or Gravity.START)
        peerParams.setMargins(dp(18), 0, 0, dp(24))
        root.addView(peerButton, peerParams)

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
            osmParams.setMargins(0, 0, dp(24) + insets.right, dp(82))
            osmButton.layoutParams = osmParams
            altitudeParams.setMargins(insets.left, 0, 0, 0)
            altitudeText.layoutParams = altitudeParams
            updateCameraHeight()
            compat
        }

        setContentView(root)

        val peerLat = intent.getDoubleExtra(EXTRA_LOCATION_PEER_LAT, Double.NaN)
        val peerLon = intent.getDoubleExtra(EXTRA_LOCATION_PEER_LON, Double.NaN)
        if (!peerLat.isNaN() && !peerLon.isNaN()) {
            mapView.peerLocation = SkyToxSharedLocation(contactPublicKey, peerLat, peerLon, 0f, System.currentTimeMillis())
            mapView.centerOn(peerLat, peerLon, maxZoom = true)
        }

        sharingManager.ownLocation().observe(this) {
            mapView.ownLocation = it
            mapView.addTrackPoint(it)
            mapView.invalidate()
            updateDistance()
            updatePeerDirection()
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
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && ::mapView.isInitialized) {
            mapView.release()
        }
    }

    override fun onDestroy() {
        if (::mapView.isInitialized) {
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
        } else if (!sharingManager.startSharing(contactPublicKey)) {
            android.widget.Toast.makeText(this, R.string.location_share_one_contact_only, android.widget.Toast.LENGTH_LONG).show()
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
        mapView.centerOn(own.latitude, own.longitude, maxZoom = true)
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

    private fun updateCameraHeight() {
        altitudeText.text = "-----\n-----\n-----\n-----\n-----\n${mapView.cameraHeightLabel()}"
        updateOsmButton()
    }

    private fun toggleOsmLayer() {
        if (!mapView.canUseOsmLayer()) return
        mapView.osmEnabled = !mapView.osmEnabled
        updateOsmButton()
    }

    private fun updateOsmButton() {
        if (!::osmButton.isInitialized) return
        val available = mapView.canUseOsmLayer()
        osmButton.isEnabled = available
        osmButton.alpha = when {
            !available -> 0.35f
            mapView.osmEnabled -> 1f
            else -> 0.65f
        }
        osmButton.active = mapView.osmEnabled
    }

    private fun closeLayerPanel() {
        if (!::layerPanel.isInitialized || layerPanel.visibility != View.VISIBLE) return
        layerPanel.visibility = View.GONE
        layerMenuButton.active = false
    }

    private fun toggleTrackRecording() {
        mapView.trackRecording = !mapView.trackRecording
        trackButton.alpha = if (mapView.trackRecording) 1f else 0.55f
        trackButton.active = mapView.trackRecording
        mapView.ownLocation?.let { mapView.addTrackPoint(it) }
    }

    private fun confirmClearTrack() {
        if (!mapView.hasTrack()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.location_track_delete_title)
            .setPositiveButton(R.string.delete) { _, _ -> mapView.clearTrack() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        setTextColor(Color.WHITE)
        textSize = 16f
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun topTitle(label: String) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 16f
        maxLines = 1
    }

    private fun layerSwitch(labelRes: Int, onChanged: (Boolean) -> Unit) = Switch(this).apply {
        text = getString(labelRes)
        textSize = 14f
        setTextColor(Color.WHITE)
        isChecked = false
        setOnCheckedChangeListener { _, checked -> onChanged(checked) }
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
    private val executor = Executors.newFixedThreadPool(3)
    private val pending = mutableSetOf<OsmTileKey>()
    private val memoryCache = object : LruCache<OsmTileKey, Bitmap>(128) {
        override fun sizeOf(key: OsmTileKey, value: Bitmap): Int = 1
    }

    fun get(key: OsmTileKey): Bitmap? = memoryCache.get(key)

    fun request(key: OsmTileKey) {
        if (memoryCache.get(key) != null || !pending.add(key)) return
        executor.execute {
            val bitmap = runCatching { download(key) }.getOrNull()
            view.post {
                pending.remove(key)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                    view.invalidate()
                }
            }
        }
    }

    fun clear() {
        pending.clear()
        memoryCache.evictAll()
    }

    fun shutdown() {
        clear()
        executor.shutdownNow()
    }

    private fun download(key: OsmTileKey): Bitmap? {
        val url = URL("https://tile.openstreetmap.org/${key.z}/${key.x}/${key.y}.png")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 4500
            setRequestProperty("User-Agent", "skyTox/0.8.28 Android online visible map layer")
            setRequestProperty("Referer", "https://github.com/Mark84Hoppus/MarkandDiego.skyTox")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
                )
            }
        } finally {
            connection.disconnect()
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
private data class UserMapMarker(val id: String, val name: String, val lat: Double, val lon: Double)
private data class TrackPoint(val lat: Double, val lon: Double)
private data class MovementSample(val lat: Double, val lon: Double, val timestamp: Long)
private data class OsmTileKey(val z: Int, val x: Int, val y: Int)

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
    var contactPublicKey: String = ""
    var ownLocation: SkyToxSharedLocation? = null
        set(value) {
            updateOwnMovementHeading(value)
            val shouldCenterFirstFix = field == null && value != null
            field = value
            if (shouldCenterFirstFix) {
                followOwnLocation = true
                centerOn(value!!.latitude, value.longitude, maxZoom = true)
            } else if (followOwnLocation && value != null) {
                centerOn(value.latitude, value.longitude, maxZoom = false)
            }
        }
    var peerLocation: SkyToxSharedLocation? = null
    var followOwnLocation = false
    var onCameraChanged: (() -> Unit)? = null
    var onMarkerLongPress: ((Double, Double) -> Unit)? = null
    var onOsmAvailabilityChanged: (() -> Unit)? = null
    var onMapTouched: (() -> Unit)? = null
    var osmEnabled = false
        set(value) {
            field = value && canUseOsmLayer()
            if (field) {
                data = SkyToxMapData(emptyList(), emptyList())
                invalidateStaticCache()
            } else {
                osmTileLoader.clear()
                loadOfflineMap()
            }
            invalidate()
        }
    var trackRecording = false
    private var data = SkyToxMapData(emptyList(), emptyList())
    private var offlineLayers = OfflineLayerState()
    private val osmTileLoader = OsmTileLoader(this)
    private val markers = mutableListOf<UserMapMarker>()
    private val track = mutableListOf<TrackPoint>()
    private val movementSamples = mutableListOf<MovementSample>()
    private var ownMovementBearing = 0.0
    private var centerLon = 37.6
    private var centerLat = 55.75
    private var zoom = 2.0
    private var lastX = 0f
    private var lastY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var staticCache: Bitmap? = null
    private var cacheWidth = 0
    private var cacheHeight = 0
    private var cacheCenterLon = Double.NaN
    private var cacheCenterLat = Double.NaN
    private var cacheZoom = Double.NaN
    private var scaling = false
    private var movedDuringGesture = false
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(event: MotionEvent) {
            if (movedDuringGesture || scaler.isInProgress) return
            val point = screenToLatLon(event.x - dragX, event.y - dragY)
            onMarkerLongPress?.invoke(point.first, point.second)
        }
    })
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val minZoom = if (osmEnabled) osmMinimumZoom() else MAP_MIN_ZOOM
            zoom = (zoom + log2(detector.scaleFactor.toDouble())).coerceIn(minZoom, MAP_MAX_ZOOM)
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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(65, 75, 72); textSize = 22f }
    private val mePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 118, 235); style = Paint.Style.FILL }
    private val peerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 143, 0); style = Paint.Style.FILL }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(223, 54, 92); style = Paint.Style.FILL }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; setShadowLayer(4f, 0f, 1f, Color.BLACK) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(223, 54, 92); style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    fun setOfflineLayers(layers: OfflineLayerState) {
        offlineLayers = layers
        if (!osmEnabled) loadOfflineMap()
    }

    private fun loadOfflineMap() {
        data = SkyToxMapData.load(context, offlineLayers)
        invalidateStaticCache()
        invalidate()
        onCameraChanged?.invoke()
    }

    fun setStoredMarkers(items: List<UserMapMarker>) {
        markers.clear()
        markers += items
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

    fun addTrackPoint(location: SkyToxSharedLocation?) {
        if (!trackRecording || location == null) return
        val last = track.lastOrNull()
        if (last == null || distanceMeters(location.latitude, location.longitude, last.lat, last.lon) >= 8.0) {
            track += TrackPoint(location.latitude, location.longitude)
            invalidate()
        }
    }

    fun hasTrack(): Boolean = track.isNotEmpty()

    fun clearTrack() {
        track.clear()
        invalidate()
    }

    fun canUseOsmLayer(): Boolean = cameraHeightMeters() <= OSM_MAX_HEIGHT_METERS

    fun release() {
        invalidateStaticCache()
        osmTileLoader.clear()
        data = SkyToxMapData(emptyList(), emptyList())
        ownLocation = null
        peerLocation = null
    }

    fun centerOn(lat: Double, lon: Double, maxZoom: Boolean) {
        centerLat = lat.coerceIn(-84.0, 84.0)
        centerLon = lon.coerceIn(-180.0, 180.0)
        if (maxZoom) zoom = MAP_MAX_ZOOM
        dragX = 0f
        dragY = 0f
        invalidateStaticCache()
        invalidate()
        onCameraChanged?.invoke()
        onOsmAvailabilityChanged?.invoke()
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
        if (osmEnabled && canUseOsmLayer()) {
            canvas.drawColor(Color.rgb(225, 232, 226))
            drawOsmLayer(canvas)
        } else {
            val cache = ensureStaticCache()
            val visualScale = 2.0.pow(zoom - cacheZoom).toFloat().coerceIn(0.25f, 4f)
            canvas.save()
            canvas.scale(visualScale, visualScale, width / 2f, height / 2f)
            canvas.drawBitmap(cache, dragX, dragY, null)
            canvas.restore()
        }
        drawTrack(canvas)
        drawUserMarkers(canvas)
        peerLocation?.let { drawMarker(canvas, it.latitude, it.longitude, peerPaint) }
        ownLocation?.let { drawOwnMarker(canvas, it.latitude, it.longitude) }
    }

    private fun drawOsmLayer(canvas: Canvas) {
        if (!osmEnabled || !canUseOsmLayer()) {
            if (osmEnabled) osmEnabled = false
            return
        }
        val tileZoom = zoom.roundToInt().coerceIn(1, 19)
        val n = 1 shl tileZoom
        val centerTile = lonLatToTile(centerLon, centerLat, tileZoom)
        val scaleFactor = 2.0.pow(zoom - tileZoom)
        val tileSize = (256.0 * scaleFactor).toFloat()
        val centerPx = centerTile.first * tileSize
        val centerPy = centerTile.second * tileSize
        val leftPx = centerPx - width / 2f - dragX
        val topPx = centerPy - height / 2f - dragY
        val startX = floor(leftPx / tileSize).toInt() - 1
        val endX = floor((leftPx + width) / tileSize).toInt() + 1
        val startY = floor(topPx / tileSize).toInt() - 1
        val endY = floor((topPx + height) / tileSize).toInt() + 1

        for (tileX in startX..endX) {
            val wrappedX = ((tileX % n) + n) % n
            for (tileY in startY..endY) {
                if (tileY !in 0 until n) continue
                val key = OsmTileKey(tileZoom, wrappedX, tileY)
                val x = (tileX * tileSize - leftPx).toFloat()
                val y = (tileY * tileSize - topPx).toFloat()
                val bitmap = osmTileLoader.get(key)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, android.graphics.RectF(x, y, x + tileSize, y + tileSize), null)
                } else {
                    osmTileLoader.request(key)
                }
            }
        }
        drawOsmAttribution(canvas)
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
        textPaint.textSize = 22f
    }

    private fun drawTrack(canvas: Canvas) {
        if (track.size < 2) return
        val path = Path()
        track.forEachIndexed { index, point ->
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
            canvas.drawText(marker.name, x + 18f, y - 20f, markerTextPaint)
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

    private fun renderStatic(canvas: Canvas) {
        canvas.drawColor(Color.rgb(166, 205, 224))
        val heightMeters = cameraHeightMeters()
        fun drawLine(line: MapLine) {
            if (!shouldDrawLine(line, heightMeters)) return
            if (!lineIntersectsCanvas(line, canvas.width, canvas.height)) return
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
                val xy = projectToCanvas(place.lon, place.lat, canvas.width, canvas.height)
                if (xy.first in -50f..(canvas.width + 50f) && xy.second in -50f..(canvas.height + 50f)) {
                    canvas.drawCircle(xy.first, xy.second, 3f, borderPaint)
                    canvas.drawText(place.name, xy.first + 5f, xy.second - 5f, textPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount == 1 && !scaler.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onMapTouched?.invoke()
                lastX = event.x
                lastY = event.y
                followOwnLocation = false
                movedDuringGesture = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                movedDuringGesture = true
                dragX = 0f
                dragY = 0f
                lastX = event.getX(0)
                lastY = event.getY(0)
            }
            MotionEvent.ACTION_POINTER_UP -> {
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
                if (dragX != 0f || dragY != 0f) {
                    panBy(-dragX, -dragY)
                    dragX = 0f
                    dragY = 0f
                    invalidateStaticCache()
                    invalidate()
                    onCameraChanged?.invoke()
                    onOsmAvailabilityChanged?.invoke()
                }
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        release()
        osmTileLoader.shutdown()
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
        if (reusable != null &&
            cacheWidth == width &&
            cacheHeight == height &&
            cacheCenterLon == centerLon &&
            cacheCenterLat == centerLat &&
            (cacheZoom == zoom || scaling)
        ) {
            return reusable
        }

        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.RGB_565)
        renderStatic(Canvas(bitmap))
        staticCache?.recycle()
        staticCache = bitmap
        cacheWidth = width
        cacheHeight = height
        cacheCenterLon = centerLon
        cacheCenterLat = centerLat
        cacheZoom = zoom
        return bitmap
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

    private fun lineIntersectsCanvas(line: MapLine, canvasWidth: Int, canvasHeight: Int): Boolean {
        if (line.kind == "land") return true
        val topLeft = projectToCanvas(line.minLon.toDouble(), line.maxLat.toDouble(), canvasWidth, canvasHeight)
        val bottomRight = projectToCanvas(line.maxLon.toDouble(), line.minLat.toDouble(), canvasWidth, canvasHeight)
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
        val scale = scale()
        val p = world(lon, lat)
        val c = world(centerLon, centerLat)
        return ((p.first - c.first) * scale + canvasWidth / 2f).toFloat() to
            ((p.second - c.second) * scale + canvasHeight / 2f).toFloat()
    }

    private fun scale() = 256.0 * 2.0.pow(zoom) / 360.0
    private fun cameraHeightMeters() = 50.0 * 2.0.pow(MAP_MAX_ZOOM - zoom)
    private fun osmMinimumZoom() = MAP_MAX_ZOOM - log2(OSM_MAX_HEIGHT_METERS / 50.0)

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
