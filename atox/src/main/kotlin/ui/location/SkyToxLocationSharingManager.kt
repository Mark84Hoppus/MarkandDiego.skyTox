// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.ChatManager
import ltd.evilcorp.domain.feature.SkyToxCrashLogger

private const val LOCATION_MIN_TIME_MS = 1_000L
private const val LOCATION_MIN_DISTANCE_M = 2f
private const val LOCATION_SEND_INTERVAL_MS = 1_000L
private const val LOCATION_FIX_MAX_AGE_MS = 60_000L
private const val LOCATION_FIX_MAX_ACCURACY_M = 200f
private const val ONE_SHOT_LISTENER_TIMEOUT_MS = 30_000L
private const val GPS_PREFERRED_WINDOW_MS = 5_000L
private const val SPEED_HOLD_WINDOW_MS = 4_000L
private const val MAX_PLAUSIBLE_SPEED_MPS = 100f

data class SkyToxSharedLocation(
    val publicKey: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val altitude: Double = Double.NaN,
    val speedMps: Float = 0f,
)

@Singleton
class SkyToxLocationSharingManager @Inject constructor(
    private val context: Context,
    private val chatManager: ChatManager,
    private val scope: CoroutineScope,
) {
    private val locationManager by lazy { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val activeContact = MutableLiveData<String?>(null)
    private val ownLocation = MutableLiveData<SkyToxSharedLocation?>(null)
    private val peerLocations = MutableLiveData<Map<String, SkyToxSharedLocation>>(emptyMap())
    private var listener: LocationListener? = null
    private var senderJob: Job? = null
    private var oneShotStopJob: Job? = null
    private var persistentLocationConsumers = 0
    private var lastSentAt = 0L
    private var lastAcceptedLocation: Location? = null
    private var lastAcceptedProvider: String? = null
    private var lastValidSpeedMps = 0f
    private var lastValidSpeedAt = 0L
    private val speedSamples = ArrayDeque<Float>()

    fun activeContact(): LiveData<String?> = activeContact
    fun ownLocation(): LiveData<SkyToxSharedLocation?> = ownLocation
    fun peerLocations(): LiveData<Map<String, SkyToxSharedLocation>> = peerLocations
    fun activeContactValue(): String? = activeContact.value
    fun hasActiveSharing(): Boolean = activeContact.value != null
    fun hasFreshOwnLocation(): Boolean = newestLocation() != null

    fun canUseLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun startSharing(contactPublicKey: String): Boolean {
        if (!canUseLocation()) return false
        val current = activeContact.value
        if (current != null && current != contactPublicKey) return false
        SkyToxCrashLogger.diagnostic("location.share.start pk=${contactPublicKey.take(8)}")
        activeContact.postValue(contactPublicKey)
        startLocationUpdates()
        startSender(contactPublicKey)
        return true
    }

    fun stopSharing() {
        val pk = activeContact.value ?: return
        SkyToxCrashLogger.diagnostic("location.share.stop pk=${pk.take(8)}")
        senderJob?.cancel()
        senderJob = null
        activeContact.postValue(null)
        val loc = ownLocation.value
        if (loc != null) {
            chatManager.sendEphemeralMessage(
                PublicKey(pk),
                SkyToxLocationProtocol.encode(
                    SkyToxLocationPayload(
                        SkyToxLocationPayload.Kind.Stop,
                        loc.latitude,
                        loc.longitude,
                        loc.accuracy,
                        System.currentTimeMillis(),
                        loc.altitude,
                    ),
                ),
                MessageType.Normal,
            )
        }
        stopLocationUpdatesIfIdle(ignoreSharing = true)
    }

    fun requestOwnLocationOnly() {
        if (!canUseLocation()) return
        startLocationUpdates()
        oneShotStopJob?.cancel()
        oneShotStopJob = scope.launch {
            delay(ONE_SHOT_LISTENER_TIMEOUT_MS)
            oneShotStopJob = null
            stopLocationUpdatesIfIdle()
        }
    }

    @Synchronized
    fun acquireOwnLocation() {
        persistentLocationConsumers++
        if (canUseLocation()) startLocationUpdates()
    }

    @Synchronized
    fun releaseOwnLocation() {
        persistentLocationConsumers = (persistentLocationConsumers - 1).coerceAtLeast(0)
        stopLocationUpdatesIfIdle()
    }

    fun sendOneShot(contactPublicKey: String): Boolean {
        if (!canUseLocation()) return false
        startLocationUpdates()
        val loc = newestLocation() ?: return false
        SkyToxCrashLogger.diagnostic("location.oneshot.send pk=${contactPublicKey.take(8)}")
        chatManager.sendMessage(
            PublicKey(contactPublicKey),
            SkyToxLocationProtocol.encode(
                SkyToxLocationPayload(
                    SkyToxLocationPayload.Kind.OneShot,
                    loc.latitude,
                    loc.longitude,
                    loc.accuracy,
                    System.currentTimeMillis(),
                    loc.altitude,
                ),
            ),
            MessageType.Normal,
        )
        oneShotStopJob?.cancel()
        oneShotStopJob = null
        stopLocationUpdatesIfIdle()
        return true
    }

    fun handleIncoming(publicKey: String, payload: SkyToxLocationPayload): Boolean {
        if (payload.kind == SkyToxLocationPayload.Kind.OneShot) return false
        SkyToxCrashLogger.diagnostic("location.incoming.${payload.kind.name.lowercase()} pk=${publicKey.take(8)}")
        val map = peerLocations.value.orEmpty().toMutableMap()
        if (payload.kind == SkyToxLocationPayload.Kind.Stop) {
            map.remove(publicKey)
        } else {
            map[publicKey] = SkyToxSharedLocation(
                publicKey,
                payload.latitude,
                payload.longitude,
                payload.accuracy,
                payload.timestamp,
                payload.altitude,
            )
        }
        peerLocations.postValue(map)
        return true
    }

    private fun startSender(contactPublicKey: String) {
        if (senderJob?.isActive == true) return
        senderJob = scope.launch {
            while (true) {
                sendLiveIfDue(contactPublicKey, force = true)
                delay(LOCATION_SEND_INTERVAL_MS)
            }
        }
    }

    private fun sendLiveIfDue(contactPublicKey: String, force: Boolean = false) {
        val loc = ownLocation.value ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSentAt < LOCATION_SEND_INTERVAL_MS) return
        lastSentAt = now
        chatManager.sendEphemeralMessage(
            PublicKey(contactPublicKey),
            SkyToxLocationProtocol.encode(
                SkyToxLocationPayload(
                    SkyToxLocationPayload.Kind.Live,
                    loc.latitude,
                    loc.longitude,
                    loc.accuracy,
                    now,
                    loc.altitude,
                ),
            ),
            MessageType.Normal,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (listener != null || !canUseLocation()) return
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!acceptLocation(location)) return
                val now = System.currentTimeMillis()
                val speed = stableSpeed(location, now)
                val shared = SkyToxSharedLocation(
                    "me",
                    location.latitude,
                    location.longitude,
                    location.accuracy,
                    location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    if (location.hasAltitude()) location.altitude else Double.NaN,
                    speed,
                )
                lastAcceptedLocation = Location(location)
                lastAcceptedProvider = location.provider
                ownLocation.postValue(shared)
                activeContact.value?.let { sendLiveIfDue(it) }
            }

            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.forEach { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        LOCATION_MIN_TIME_MS,
                        LOCATION_MIN_DISTANCE_M,
                        listener!!,
                    )
                    locationManager.getLastKnownLocation(provider)?.let { listener?.onLocationChanged(it) }
                }
            }.onFailure {
                SkyToxCrashLogger.diagnostic("location.provider.failed provider=$provider error=${it.javaClass.simpleName}")
            }
        }
    }

    private fun acceptLocation(location: Location): Boolean {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return false
        val now = System.currentTimeMillis()
        val fixTime = location.time.takeIf { it > 0L } ?: now
        if (fixTime > now + 10_000L || now - fixTime > LOCATION_FIX_MAX_AGE_MS) return false
        val previous = lastAcceptedLocation ?: return true
        if (fixTime + 1_000L < previous.time) return false

        val recentGps = lastAcceptedProvider == LocationManager.GPS_PROVIDER &&
            now - previous.time.coerceAtLeast(0L) < GPS_PREFERRED_WINDOW_MS
        val weakerNetwork = location.provider == LocationManager.NETWORK_PROVIDER &&
            recentGps && !location.hasSpeed() &&
            location.accuracy >= previous.accuracy * 0.75f
        return !weakerNetwork
    }

    private fun stableSpeed(location: Location, now: Long): Float {
        var candidate: Float? = location.speed.takeIf {
            location.hasSpeed() && it.isFinite() && it in 0f..MAX_PLAUSIBLE_SPEED_MPS
        }
        val previous = lastAcceptedLocation
        if (candidate == null && previous != null) {
            val dtSeconds = (location.time - previous.time) / 1_000f
            if (dtSeconds in 0.5f..5f && location.accuracy <= 80f && previous.accuracy <= 80f) {
                candidate = (previous.distanceTo(location) / dtSeconds)
                    .takeIf { it.isFinite() && it in 0f..MAX_PLAUSIBLE_SPEED_MPS }
            }
        }
        if (candidate != null) {
            speedSamples.addLast(candidate)
            while (speedSamples.size > 5) speedSamples.removeFirst()
            val sorted = speedSamples.sorted()
            lastValidSpeedMps = sorted[sorted.size / 2]
            lastValidSpeedAt = now
        } else if (now - lastValidSpeedAt > SPEED_HOLD_WINDOW_MS) {
            lastValidSpeedMps *= 0.65f
            if (lastValidSpeedMps < 0.5f) lastValidSpeedMps = 0f
        }
        return lastValidSpeedMps
    }

    private fun newestLocation(): SkyToxSharedLocation? {
        val loc = ownLocation.value ?: return null
        val age = System.currentTimeMillis() - loc.timestamp
        return loc.takeIf {
            age in 0..LOCATION_FIX_MAX_AGE_MS &&
                (loc.accuracy <= 0f || loc.accuracy <= LOCATION_FIX_MAX_ACCURACY_M)
        }
    }

    @Synchronized
    private fun stopLocationUpdatesIfIdle(ignoreSharing: Boolean = false) {
        if ((!ignoreSharing && hasActiveSharing()) || persistentLocationConsumers > 0 || oneShotStopJob?.isActive == true) return
        val activeListener = listener ?: return
        runCatching { locationManager.removeUpdates(activeListener) }
        listener = null
        SkyToxCrashLogger.diagnostic("location.listener.stopped idle=true")
    }
}
