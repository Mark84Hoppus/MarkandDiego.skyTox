// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.location

import android.Manifest
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.Locale
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import ltd.evilcorp.atox.App
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.ui.AvatarImageView
import ltd.evilcorp.core.vo.Contact
import ltd.evilcorp.domain.feature.SkyToxCrashLogger
import org.json.JSONObject

const val EXTRA_LOCATION_CONTACT_PUBLIC_KEY = "locationContactPublicKey"
const val EXTRA_LOCATION_CONTACT_NAME = "locationContactName"
const val EXTRA_LOCATION_CONTACT_AVATAR_URI = "locationContactAvatarUri"
const val EXTRA_LOCATION_PEER_LAT = "locationPeerLat"
const val EXTRA_LOCATION_PEER_LON = "locationPeerLon"

class SkyToxLocationMapActivity : AppCompatActivity() {
    @Inject
    lateinit var sharingManager: SkyToxLocationSharingManager

    private lateinit var mapView: SkyToxMapView
    private lateinit var shareButton: TextView
    private lateinit var distanceText: TextView
    private lateinit var locateButton: TextView
    private lateinit var peerChip: LinearLayout
    private lateinit var altitudeText: TextView
    private var contactPublicKey = ""
    private var contactName = ""
    private var contactAvatarUri = ""
    private var compassMode = false

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
            setMapData(SkyToxMapData.load(this@SkyToxLocationMapActivity))
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

        locateButton = TextView(this).apply {
            text = "▲"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.chat_scroll_to_bottom_background)
            setOnClickListener { onLocateClick() }
        }
        val locateParams = FrameLayout.LayoutParams(dp(54), dp(54), Gravity.BOTTOM or Gravity.END)
        locateParams.setMargins(0, 0, dp(18), dp(24))
        root.addView(locateButton, locateParams)

        peerChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(10), 0)
            visibility = View.GONE
            setOnClickListener {
                mapView.peerLocation?.let { loc -> mapView.centerOn(loc.latitude, loc.longitude, maxZoom = true) }
            }
        }
        val peerAvatar = AvatarImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setFrom(Contact(publicKey = contactPublicKey, name = contactName, avatarUri = contactAvatarUri))
        }
        val peerName = TextView(this).apply {
            text = contactName
            textSize = 14f
            maxLines = 1
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(dp(8), 0, 0, 0)
        }
        peerChip.addView(peerAvatar, LinearLayout.LayoutParams(dp(42), dp(42)))
        peerChip.addView(peerName, LinearLayout.LayoutParams(dp(140), -1))
        val peerParams = FrameLayout.LayoutParams(dp(200), dp(54), Gravity.BOTTOM or Gravity.START)
        peerParams.setMargins(dp(18), 0, 0, dp(24))
        root.addView(peerChip, peerParams)

        altitudeText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            text = ""
        }
        val altitudeParams = FrameLayout.LayoutParams(dp(110), dp(120), Gravity.START or Gravity.CENTER_VERTICAL)
        altitudeParams.setMargins(dp(8), 0, 0, 0)
        root.addView(altitudeText, altitudeParams)
        mapView.onCameraChanged = { updateCameraHeight() }
        updateCameraHeight()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = insets.top)
            topBar.layoutParams.height = dp(56) + insets.top
            locateParams.setMargins(0, 0, dp(18) + insets.right, dp(24) + insets.bottom)
            locateButton.layoutParams = locateParams
            peerParams.setMargins(dp(18) + insets.left, 0, 0, dp(24) + insets.bottom)
            peerChip.layoutParams = peerParams
            altitudeParams.setMargins(dp(8) + insets.left, 0, 0, 0)
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
            mapView.invalidate()
            updateDistance()
        }
        sharingManager.peerLocations().observe(this) {
            mapView.peerLocation = it[contactPublicKey]
            peerChip.visibility = if (mapView.peerLocation != null) View.VISIBLE else View.GONE
            mapView.invalidate()
            updateDistance()
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
        compassMode = if (mapView.followOwnLocation) !compassMode else false
        locateButton.text = if (compassMode) "\u25CE" else "\u25B2"
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

    private fun updateCameraHeight() {
        altitudeText.text = "-----\n-----\n-----\n-----\n-----\n${mapView.cameraHeightLabel()}"
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

private data class MapLine(
    val kind: String,
    val points: List<Pair<Double, Double>>,
    val rank: Int = 99,
    val minLon: Double = points.minOf { it.first },
    val maxLon: Double = points.maxOf { it.first },
    val minLat: Double = points.minOf { it.second },
    val maxLat: Double = points.maxOf { it.second },
)
private data class MapPlace(val name: String, val lon: Double, val lat: Double, val population: Int, val rank: Int)

private class SkyToxMapData(val lines: List<MapLine>, val places: List<MapPlace>) {
    companion object {
        fun load(context: Context): SkyToxMapData {
            val raw = context.resources.openRawResource(R.raw.skytox_ne_50m).bufferedReader().use { it.readText() }
            val root = JSONObject(raw)
            val linesJson = root.getJSONArray("lines")
            val lines = ArrayList<MapLine>(linesJson.length())
            for (i in 0 until linesJson.length()) {
                val item = linesJson.getJSONObject(i)
                val pointsJson = item.getJSONArray("p")
                val points = ArrayList<Pair<Double, Double>>(pointsJson.length())
                for (j in 0 until pointsJson.length()) {
                    val p = pointsJson.getJSONArray(j)
                    points += p.getDouble(0) to p.getDouble(1)
                }
                lines += MapLine(item.getString("k"), points, item.optInt("r", 99))
            }
            val placesJson = root.getJSONArray("places")
            val places = ArrayList<MapPlace>(placesJson.length())
            for (i in 0 until placesJson.length()) {
                val item = placesJson.getJSONObject(i)
                places += MapPlace(
                    item.getString("n"),
                    item.getDouble("x"),
                    item.getDouble("y"),
                    item.optInt("p", 0),
                    item.optInt("r", 99),
                )
            }
            return SkyToxMapData(lines, places)
        }
    }
}

private class SkyToxMapView(context: Context) : View(context) {
    var contactPublicKey: String = ""
    var ownLocation: SkyToxSharedLocation? = null
        set(value) {
            field = value
            if (followOwnLocation && value != null) centerOn(value.latitude, value.longitude, maxZoom = false)
        }
    var peerLocation: SkyToxSharedLocation? = null
    var followOwnLocation = false
    var onCameraChanged: (() -> Unit)? = null
    private var data = SkyToxMapData(emptyList(), emptyList())
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
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom + log2(detector.scaleFactor.toDouble())).coerceIn(1.0, 16.5)
            invalidate()
            onCameraChanged?.invoke()
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
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 80, 91, 97); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val admin1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 70, 83, 80); style = Paint.Style.STROKE; strokeWidth = 0.85f }
    private val admin2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 70, 83, 80); style = Paint.Style.STROKE; strokeWidth = 0.55f }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(62, 68, 66); style = Paint.Style.STROKE; strokeWidth = 2.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 126, 42); style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(65, 75, 72); textSize = 22f }
    private val mePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 118, 235); style = Paint.Style.FILL }
    private val peerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 143, 0); style = Paint.Style.FILL }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    fun setMapData(data: SkyToxMapData) {
        this.data = data
        invalidate()
        onCameraChanged?.invoke()
    }

    fun release() {
        invalidateStaticCache()
        data = SkyToxMapData(emptyList(), emptyList())
        ownLocation = null
        peerLocation = null
    }

    fun centerOn(lat: Double, lon: Double, maxZoom: Boolean) {
        centerLat = lat.coerceIn(-84.0, 84.0)
        centerLon = lon.coerceIn(-180.0, 180.0)
        if (maxZoom) zoom = 16.5
        dragX = 0f
        dragY = 0f
        invalidateStaticCache()
        invalidate()
        onCameraChanged?.invoke()
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
        val cache = ensureStaticCache()
        val visualScale = 2.0.pow(zoom - cacheZoom).toFloat().coerceIn(0.25f, 4f)
        canvas.save()
        canvas.scale(visualScale, visualScale, width / 2f, height / 2f)
        canvas.drawBitmap(cache, dragX - width / 2f, dragY - height / 2f, null)
        canvas.restore()
        peerLocation?.let { drawMarker(canvas, it.latitude, it.longitude, peerPaint) }
        ownLocation?.let { drawMarker(canvas, it.latitude, it.longitude, mePaint) }
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
            line.points.forEachIndexed { index, p ->
                val xy = projectToCanvas(p.first, p.second, canvas.width, canvas.height)
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
        if (heightMeters <= 100_000.0) {
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
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                followOwnLocation = false
            }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress) {
                dragX += event.x - lastX
                dragY += event.y - lastY
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
                }
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun drawMarker(canvas: Canvas, lat: Double, lon: Double, paint: Paint) {
        val xy = projectToScreen(lon, lat)
        val x = xy.first + dragX
        val y = xy.second + dragY
        canvas.drawCircle(x, y, 17f, whitePaint)
        canvas.drawCircle(x, y, 12f, paint)
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

        val bitmap = Bitmap.createBitmap((width * 2).coerceAtLeast(1), (height * 2).coerceAtLeast(1), Bitmap.Config.RGB_565)
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
        "admin2" -> heightMeters <= 350.0
        "road" -> when {
            heightMeters <= 5_000.0 -> true
            else -> false
        }
        "rail" -> when {
            heightMeters <= 5_000.0 -> true
            else -> false
        }
        else -> false
    }

    private fun lineIntersectsCanvas(line: MapLine, canvasWidth: Int, canvasHeight: Int): Boolean {
        if (line.kind == "land") return true
        val topLeft = projectToCanvas(line.minLon, line.maxLat, canvasWidth, canvasHeight)
        val bottomRight = projectToCanvas(line.maxLon, line.minLat, canvasWidth, canvasHeight)
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

    private fun projectToCanvas(lon: Double, lat: Double, canvasWidth: Int, canvasHeight: Int): Pair<Float, Float> {
        val scale = scale()
        val p = world(lon, lat)
        val c = world(centerLon, centerLat)
        return ((p.first - c.first) * scale + canvasWidth / 2f).toFloat() to
            ((p.second - c.second) * scale + canvasHeight / 2f).toFloat()
    }

    private fun scale() = 256.0 * 2.0.pow(zoom) / 360.0
    private fun cameraHeightMeters() = 50.0 * 2.0.pow(16.5 - zoom)

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
    val earth = 6_371_000.0
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return earth * 2 * atan2(sqrt(h), sqrt(1 - h))
}
