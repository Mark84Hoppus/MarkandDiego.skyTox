// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.location

import android.Manifest
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

private const val LOCATION_MIN_TIME_MS = 2_000L
private const val LOCATION_MIN_DISTANCE_M = 2f
private const val LOCATION_SEND_INTERVAL_MS = 5_000L

data class SkyToxSharedLocation(
    val publicKey: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val altitude: Double = Double.NaN,
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
    private var lastSentAt = 0L

    fun activeContact(): LiveData<String?> = activeContact
    fun ownLocation(): LiveData<SkyToxSharedLocation?> = ownLocation
    fun peerLocations(): LiveData<Map<String, SkyToxSharedLocation>> = peerLocations
    fun activeContactValue(): String? = activeContact.value
    fun hasActiveSharing(): Boolean = activeContact.value != null

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
    }

    fun requestOwnLocationOnly() {
        if (canUseLocation()) startLocationUpdates()
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

    private fun startLocationUpdates() {
        if (listener != null || !canUseLocation()) return
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val shared = SkyToxSharedLocation(
                    "me",
                    location.latitude,
                    location.longitude,
                    location.accuracy,
                    location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    if (location.hasAltitude()) location.altitude else Double.NaN,
                )
                ownLocation.postValue(shared)
                activeContact.value?.let { sendLiveIfDue(it) }
            }

            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
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

    private fun newestLocation(): SkyToxSharedLocation? = ownLocation.value
}
