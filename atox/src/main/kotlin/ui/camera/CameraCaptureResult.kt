// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.camera

import android.content.Context
import android.content.Intent

object CameraCaptureResult {
    const val EXTRA_TOKEN = "skytox.camera.token"
    const val EXTRA_ERROR = "skytox.camera.error"

    fun intent(context: Context): Intent = Intent(context, SkyToxCameraActivity::class.java)
}
