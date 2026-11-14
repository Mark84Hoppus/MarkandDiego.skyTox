// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.push.SkyToxPushManager
import ltd.evilcorp.core.repository.ContactRepository
import ltd.evilcorp.core.repository.UserRepository
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.ChatManager
import ltd.evilcorp.domain.feature.SkyToxCrashLogger
import ltd.evilcorp.domain.tox.Tox

@Singleton
class SosCoordinator @Inject constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val contacts: ContactRepository,
    private val users: UserRepository,
    private val chatManager: ChatManager,
    private val pushManager: SkyToxPushManager,
    private val tox: Tox,
) : LocationListener {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val _active = MutableStateFlow(prefs.getBoolean(KEY_ACTIVE, false))
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private var latestLocation: Location? = null
    private var senderJob: Job? = null

    init {
        if (_active.value) resume()
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canActivate(callback: (Boolean) -> Unit) {
        scope.launch { callback(contacts.getAll().first().isNotEmpty()) }
    }

    fun activate() {
        if (_active.value || !hasLocationPermission()) return
        _active.value = true
        prefs.edit().putBoolean(KEY_ACTIVE, true).putLong(KEY_LAST_SEND, 0L).apply()
        startLocation()
        startLoop(immediate = true)
        SkyToxCrashLogger.diagnostic("sos.activated")
    }

    fun deactivate() {
        if (!_active.value) return
        _active.value = false
        prefs.edit().putBoolean(KEY_ACTIVE, false).remove(KEY_LAST_SEND).apply()
        senderJob?.cancel()
        senderJob = null
        stopLocation()
        scope.launch { broadcast(stopped = true) }
        SkyToxCrashLogger.diagnostic("sos.deactivated")
    }

    fun resume() {
        if (!_active.value) return
        startLocation()
        startLoop(immediate = false)
    }

    private fun startLoop(immediate: Boolean) {
        senderJob?.cancel()
        senderJob = scope.launch {
            if (immediate) delay(FIRST_FIX_WAIT_MS)
            while (_active.value) {
                val elapsed = System.currentTimeMillis() - prefs.getLong(KEY_LAST_SEND, 0L)
                if (elapsed < SEND_INTERVAL_MS) delay(SEND_INTERVAL_MS - elapsed)
                if (!_active.value) break
                if (broadcast(stopped = false)) {
                    prefs.edit().putLong(KEY_LAST_SEND, System.currentTimeMillis()).apply()
                } else {
                    delay(NO_FIX_RETRY_MS)
                }
            }
        }
    }

    private suspend fun broadcast(stopped: Boolean): Boolean {
        val recipients = contacts.getAll().first()
        if (recipients.isEmpty()) return false
        val ownName = runCatching { users.get(tox.publicKey.string()).first().name }
            .getOrDefault("skyTox user")
        val location = latestLocation ?: bestLastLocation()
        if (!stopped && location == null) {
            SkyToxCrashLogger.diagnostic("sos.broadcast delayed no_location_fix=true")
            return false
        }
        val coordinates = location?.let {
            String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", it.latitude, it.longitude)
        } ?: "location pending"
        val text = if (stopped) {
            context.getString(R.string.sos_stopped_message, ownName)
        } else {
            context.getString(R.string.sos_started_message, ownName, coordinates)
        }
        recipients.forEach { contact ->
            val key = PublicKey(contact.publicKey)
            pushManager.sendWakeSignal(key, if (stopped) "sos_stopped" else "sos_emergency")
            chatManager.sendMessage(key, text)
        }
        SkyToxCrashLogger.diagnostic("sos.broadcast stopped=$stopped recipients=${recipients.size} hasFix=${location != null}")
        return true
    }

    @Suppress("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPermission()) return
        latestLocation = bestLastLocation()
        runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30_000L, 5f, this) }
        runCatching { locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 5f, this) }
    }

    @Suppress("MissingPermission")
    private fun bestLastLocation(): Location? {
        if (!hasLocationPermission()) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time <= MAX_LAST_FIX_AGE_MS }
            .maxByOrNull { it.time }
    }

    private fun stopLocation() = runCatching { locationManager.removeUpdates(this) }.let { Unit }

    override fun onLocationChanged(location: Location) {
        if (latestLocation == null || location.time >= latestLocation!!.time) latestLocation = location
    }
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    companion object {
        private const val KEY_ACTIVE = "sos_active"
        private const val KEY_LAST_SEND = "sos_last_send"
        private const val SEND_INTERVAL_MS = 5 * 60 * 1000L
        private const val FIRST_FIX_WAIT_MS = 3_000L
        private const val NO_FIX_RETRY_MS = 15_000L
        private const val MAX_LAST_FIX_AGE_MS = 10 * 60 * 1000L
    }
}
