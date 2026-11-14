// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.radio

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

internal class RadioLocalHotspotController(
    context: Context,
    private val onState: (RadioHotspotState) -> Unit,
    private val onDiagnostic: (String) -> Unit,
) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun start() {
        if (Build.VERSION.SDK_INT < 26) {
            onState(RadioHotspotState(errorCode = ERROR_UNSUPPORTED))
            onDiagnostic("radio_hotspot_start_fail unsupported_sdk")
            return
        }
        if (reservation != null) return
        onState(RadioHotspotState(starting = true))
        onDiagnostic("radio_hotspot_start_requested")
        try {
            @Suppress("DEPRECATION")
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = value
                    val credentials = credentials(value)
                    onState(RadioHotspotState(active = true, ssid = credentials.first, passphrase = credentials.second))
                    onDiagnostic("radio_hotspot_start_success")
                }

                override fun onStopped() {
                    reservation = null
                    onState(RadioHotspotState())
                    onDiagnostic("radio_hotspot_stopped")
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    onState(RadioHotspotState(errorCode = reason))
                    onDiagnostic("radio_hotspot_start_fail reason=$reason")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (error: SecurityException) {
            onState(RadioHotspotState(errorCode = ERROR_PERMISSION))
            onDiagnostic("radio_hotspot_start_fail permission")
        } catch (error: RuntimeException) {
            onState(RadioHotspotState(errorCode = ERROR_RUNTIME))
            onDiagnostic("radio_hotspot_start_fail type=${error.javaClass.simpleName}")
        }
    }

    fun stop() {
        val active = reservation ?: return
        reservation = null
        runCatching { active.close() }
        onState(RadioHotspotState())
        onDiagnostic("radio_hotspot_reservation_release")
    }

    @Suppress("DEPRECATION")
    private fun credentials(value: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> {
        return if (Build.VERSION.SDK_INT >= 30) {
            value.softApConfiguration.ssid to value.softApConfiguration.passphrase
        } else {
            // Legacy WifiConfiguration commonly wraps both values in quotes. Keeping
            // those quotes produced a valid-looking QR for a non-existent SSID.
            value.wifiConfiguration?.SSID.unquoted() to value.wifiConfiguration?.preSharedKey.unquoted()
        }
    }

    private fun String?.unquoted(): String? = this?.trim()?.removeSurrounding("\"")

    private companion object {
        const val ERROR_UNSUPPORTED = -100
        const val ERROR_PERMISSION = -101
        const val ERROR_RUNTIME = -102
    }
}
