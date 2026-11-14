// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.radio

import kotlinx.coroutines.flow.MutableStateFlow
import markanddiego.skytox.radio.RadioSnapshot

object RadioRuntime {
    val state = MutableStateFlow(RadioSnapshot())
    val hotspot = MutableStateFlow(RadioHotspotState())
    @Volatile var nickname: String = "skyTox"
}

data class RadioHotspotState(
    val starting: Boolean = false,
    val active: Boolean = false,
    val ssid: String? = null,
    val passphrase: String? = null,
    val errorCode: Int? = null,
)
