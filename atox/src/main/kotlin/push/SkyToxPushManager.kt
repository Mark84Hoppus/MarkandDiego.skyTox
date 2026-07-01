// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.push

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltd.evilcorp.atox.BuildConfig
import ltd.evilcorp.atox.settings.Settings
import ltd.evilcorp.core.repository.ContactRepository
import ltd.evilcorp.core.vo.ConnectionStatus
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.push.SkyToxPushGateway
import ltd.evilcorp.domain.tox.Tox
import org.json.JSONObject

private const val TAG = "SkyToxPushManager"
private const val PACKET_KIND = 0xB5.toByte()
private const val PACKET_VERSION = 1.toByte()
private const val MAX_TOKEN_BYTES = 1200
private const val PREFS_NAME = "skytox_push"
private const val OWN_TOKEN = "own_token"
private const val FRIEND_TOKEN_PREFIX = "friend_token_"

@Singleton
class SkyToxPushManager @Inject constructor(
    context: Context,
    private val settings: Settings,
    private val tox: Tox,
    private val contacts: ContactRepository,
    private val scope: CoroutineScope,
) : SkyToxPushGateway {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun refreshTokenAndShare() {
        if (!settings.pushEnabled) return

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                saveOwnToken(token)
                shareOwnTokenWithOnlineContacts()
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Unable to get FCM token: ${error.message}")
            }
    }

    fun saveOwnToken(token: String) {
        if (token.isBlank()) return
        prefs.edit { putString(OWN_TOKEN, token) }
    }

    override fun shareOwnToken(publicKey: PublicKey) {
        if (!settings.pushEnabled || !tox.started) return
        val token = prefs.getString(OWN_TOKEN, null) ?: return
        val packet = packetFor(token) ?: return
        tox.sendLosslessPacket(publicKey, packet)
    }

    override fun rememberFriendToken(publicKey: String, packet: ByteArray): Boolean {
        val token = parsePacket(packet) ?: return false
        prefs.edit { putString(FRIEND_TOKEN_PREFIX + publicKey, token) }
        return true
    }

    override fun wake(publicKey: PublicKey, reason: String) {
        if (!settings.pushEnabled) return
        val token = prefs.getString(FRIEND_TOKEN_PREFIX + publicKey.string(), null) ?: return
        val serverUrl = BuildConfig.SKYTOX_PUSH_SERVER_URL
        val apiKey = BuildConfig.SKYTOX_PUSH_API_KEY
        if (serverUrl.isBlank() || apiKey.isBlank()) return

        scope.launch(Dispatchers.IO) {
            runCatching {
                postWakeup(serverUrl, apiKey, token, reason)
            }.onFailure {
                Log.w(TAG, "Push wake-up failed: ${it.message}")
            }
        }
    }

    private fun shareOwnTokenWithOnlineContacts() {
        if (!settings.pushEnabled || !tox.started) return

        scope.launch {
            contacts.getAll().first()
                .filter { it.connectionStatus != ConnectionStatus.None }
                .forEach { shareOwnToken(PublicKey(it.publicKey)) }
        }
    }

    private suspend fun postWakeup(serverUrl: String, apiKey: String, token: String, reason: String) =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("token", token)
                .put("reason", reason)
                .toString()
                .toByteArray(Charsets.UTF_8)

            val connection = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-SkyTox-Key", apiKey)
                outputStream.use { it.write(body) }
            }

            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }

    private fun packetFor(token: String): ByteArray? {
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        if (tokenBytes.isEmpty() || tokenBytes.size > MAX_TOKEN_BYTES) return null
        return ByteArray(2 + tokenBytes.size).apply {
            this[0] = PACKET_KIND
            this[1] = PACKET_VERSION
            tokenBytes.copyInto(this, destinationOffset = 2)
        }
    }

    private fun parsePacket(packet: ByteArray): String? {
        if (packet.size <= 2 || packet[0] != PACKET_KIND || packet[1] != PACKET_VERSION) return null
        val token = packet.copyOfRange(2, packet.size).toString(Charsets.UTF_8)
        return token.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_TOKEN_BYTES }
    }
}
