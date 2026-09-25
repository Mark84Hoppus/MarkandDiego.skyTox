// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.location

import android.util.Base64
import java.util.Locale

private const val PREFIX = "skytox-location-v1:"

data class SkyToxLocationPayload(
    val kind: Kind,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val altitude: Double = Double.NaN,
) {
    enum class Kind { OneShot, Live, Stop }
}

object SkyToxLocationProtocol {
    fun isLocation(message: String): Boolean = message.startsWith(PREFIX)

    fun encode(payload: SkyToxLocationPayload): String {
        val kind = when (payload.kind) {
            SkyToxLocationPayload.Kind.OneShot -> "one"
            SkyToxLocationPayload.Kind.Live -> "live"
            SkyToxLocationPayload.Kind.Stop -> "stop"
        }
        val raw = String.format(
            Locale.US,
            "%s|%.6f|%.6f|%.1f|%d|%.1f",
            kind,
            payload.latitude,
            payload.longitude,
            payload.accuracy,
            payload.timestamp,
            payload.altitude,
        )
        return PREFIX + Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun decode(message: String): SkyToxLocationPayload? = runCatching {
        if (!isLocation(message)) return null
        val raw = Base64.decode(
            message.removePrefix(PREFIX),
            Base64.NO_WRAP or Base64.URL_SAFE,
        ).toString(Charsets.UTF_8)
        val parts = raw.split("|")
        if (parts.size !in 5..6) return null
        SkyToxLocationPayload(
            kind = when (parts[0]) {
                "one" -> SkyToxLocationPayload.Kind.OneShot
                "live" -> SkyToxLocationPayload.Kind.Live
                "stop" -> SkyToxLocationPayload.Kind.Stop
                else -> return null
            },
            latitude = parts[1].toDouble(),
            longitude = parts[2].toDouble(),
            accuracy = parts[3].toFloat(),
            timestamp = parts[4].toLong(),
            altitude = parts.getOrNull(5)?.toDoubleOrNull() ?: Double.NaN,
        )
    }.getOrNull()
}
