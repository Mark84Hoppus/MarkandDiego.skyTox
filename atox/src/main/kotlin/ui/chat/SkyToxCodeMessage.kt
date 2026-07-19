// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.util.Base64

object SkyToxCodeMessage {
    private const val PREFIX = "skytox-code-v1:"

    fun encode(code: String): String =
        PREFIX + Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

    fun isCode(message: String): Boolean = message.startsWith(PREFIX)

    fun decode(message: String): String? = runCatching {
        val data = message.removePrefix(PREFIX)
        Base64.decode(data, Base64.NO_WRAP or Base64.URL_SAFE).toString(Charsets.UTF_8)
    }.getOrNull()
}
