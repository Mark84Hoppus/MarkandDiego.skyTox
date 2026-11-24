// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.location

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SkyToxGeoOpenActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val latitude = intent.getDoubleExtra(EXTRA_GEO_LATITUDE, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_GEO_LONGITUDE, Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -85.0..85.0 || longitude !in -180.0..180.0) {
            finish()
            return
        }
        val publicKey = intent.getStringExtra(EXTRA_GEO_PUBLIC_KEY).orEmpty()
        val contactName = intent.getStringExtra(EXTRA_GEO_CONTACT_NAME).orEmpty()
        val sos = intent.getBooleanExtra(EXTRA_GEO_IS_SOS, false)
        val markerId = "received_${if (sos) "sos" else "location"}_${publicKey.ifBlank { "external" }}"
        val markerName = if (sos) "SOS" else contactName.ifBlank { getString(ltd.evilcorp.atox.R.string.location_marker_default_name) }
        SkyToxMarkerStore.upsert(this, UserMapMarker(markerId, markerName, latitude, longitude))
        SkyToxLocationMapActivity.open(
            context = this,
            publicKey = publicKey,
            name = contactName,
            payload = SkyToxLocationPayload(
                kind = SkyToxLocationPayload.Kind.OneShot,
                latitude = latitude,
                longitude = longitude,
                accuracy = 0f,
                timestamp = System.currentTimeMillis(),
                source = if (sos) SkyToxLocationPayload.Source.Sos else SkyToxLocationPayload.Source.Location,
                label = markerName,
            ),
            focusSharedMarker = true,
        )
        finish()
    }

    companion object {
        const val EXTRA_GEO_LATITUDE = "skytoxGeoLatitude"
        const val EXTRA_GEO_LONGITUDE = "skytoxGeoLongitude"
        const val EXTRA_GEO_PUBLIC_KEY = "skytoxGeoPublicKey"
        const val EXTRA_GEO_CONTACT_NAME = "skytoxGeoContactName"
        const val EXTRA_GEO_IS_SOS = "skytoxGeoIsSos"
    }
}
