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
import ltd.evilcorp.core.repository.ContactRepository
import ltd.evilcorp.core.vo.ConnectionStatus
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.SkyToxCrashLogger
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
private const val FINGERPRINT_LEN = 8

@Singleton
class SkyToxPushManager @Inject constructor(
    context: Context,
    @Suppress("UNUSED_PARAMETER") settings: ltd.evilcorp.atox.settings.Settings,
    private val tox: Tox,
    private val contacts: ContactRepository,
    private val scope: CoroutineScope,
) : SkyToxPushGateway {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun refreshTokenAndShare() {
        SkyToxCrashLogger.diagnostic("push.refreshToken requested")
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                SkyToxCrashLogger.diagnostic("push.refreshToken success tokenLength=${token.length}")
                saveOwnToken(token)
                shareOwnTokenWithOnlineContacts()
            }
            .addOnFailureListener { error ->
                SkyToxCrashLogger.diagnostic("push.refreshToken failed ${error.javaClass.simpleName}:${error.message}")
                Log.w(TAG, "Unable to get FCM token: ${error.message}")
            }
    }

    fun saveOwnToken(token: String) {
        if (token.isBlank()) return
        prefs.edit { putString(OWN_TOKEN, token) }
        SkyToxCrashLogger.diagnostic("push.ownToken saved length=${token.length}")
    }

    override fun shareOwnToken(publicKey: PublicKey) {
        if (!tox.started) {
            SkyToxCrashLogger.diagnostic("push.shareOwnToken skipped tox_stopped pk=${publicKey.fingerprint()}")
            return
        }
        val token = prefs.getString(OWN_TOKEN, null)
        if (token.isNullOrBlank()) {
            SkyToxCrashLogger.diagnostic("push.shareOwnToken skipped no_own_token pk=${publicKey.fingerprint()}")
            return
        }
        val packet = packetFor(token)
        if (packet == null) {
            SkyToxCrashLogger.diagnostic("push.shareOwnToken skipped packet_invalid tokenLength=${token.length}")
            return
        }
        val result = tox.sendLosslessPacket(publicKey, packet)
        SkyToxCrashLogger.diagnostic(
            "push.shareOwnToken sent pk=${publicKey.fingerprint()} tokenLength=${token.length} result=$result",
        )
    }

    override fun rememberFriendToken(publicKey: String, packet: ByteArray): Boolean {
        val token = parsePacket(packet)
        if (token == null) {
            SkyToxCrashLogger.diagnostic("push.friendToken ignored pk=${publicKey.fingerprint()} bytes=${packet.size}")
            return false
        }
        prefs.edit { putString(FRIEND_TOKEN_PREFIX + publicKey, token) }
        SkyToxCrashLogger.diagnostic("push.friendToken saved pk=${publicKey.fingerprint()} tokenLength=${token.length}")
        return true
    }

    override fun wake(publicKey: PublicKey, reason: String) {
        sendWake(publicKey, reason)
    }

    fun hasFriendToken(publicKey: PublicKey): Boolean =
        !prefs.getString(FRIEND_TOKEN_PREFIX + publicKey.string(), null).isNullOrBlank()

    fun sendManualWake(publicKey: PublicKey): Boolean {
        val hasToken = hasFriendToken(publicKey)
        SkyToxCrashLogger.diagnostic("push.manualWake requested pk=${publicKey.fingerprint()} hasToken=$hasToken")
        return sendWake(publicKey, "manual_wake")
    }

    fun sendWakeSignal(publicKey: PublicKey, reason: String): Boolean {
        val hasToken = hasFriendToken(publicKey)
        SkyToxCrashLogger.diagnostic("push.autoWake requested pk=${publicKey.fingerprint()} reason=$reason hasToken=$hasToken")
        return sendWake(publicKey, reason)
    }

    private fun sendWake(publicKey: PublicKey, reason: String): Boolean {
        val token = prefs.getString(FRIEND_TOKEN_PREFIX + publicKey.string(), null)
        if (token.isNullOrBlank()) {
            SkyToxCrashLogger.diagnostic("push.wake skipped no_friend_token pk=${publicKey.fingerprint()} reason=$reason")
            return false
        }
        val serverUrl = BuildConfig.SKYTOX_PUSH_SERVER_URL
        val apiKey = BuildConfig.SKYTOX_PUSH_API_KEY
        if (serverUrl.isBlank() || apiKey.isBlank()) {
            SkyToxCrashLogger.diagnostic("push.wake skipped config_missing urlBlank=${serverUrl.isBlank()} keyBlank=${apiKey.isBlank()}")
            return false
        }

        scope.launch(Dispatchers.IO) {
            runCatching {
                postWakeup(serverUrl, apiKey, token, reason)
                SkyToxCrashLogger.diagnostic("push.wake posted pk=${publicKey.fingerprint()} reason=$reason")
            }.onFailure {
                SkyToxCrashLogger.diagnostic("push.wake failed pk=${publicKey.fingerprint()} reason=$reason ${it.message}")
                Log.w(TAG, "Push wake-up failed: ${it.message}")
            }
        }
        return true
    }

    private fun shareOwnTokenWithOnlineContacts() {
        if (!tox.started) {
            SkyToxCrashLogger.diagnostic("push.shareOnline skipped tox_stopped")
            return
        }

        scope.launch {
            val online = contacts.getAll().first()
                .filter { it.connectionStatus != ConnectionStatus.None }
            SkyToxCrashLogger.diagnostic("push.shareOnline count=${online.size}")
            online.forEach { shareOwnToken(PublicKey(it.publicKey)) }
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

    private fun PublicKey.fingerprint(): String = string().fingerprint()
    private fun String.fingerprint(): String = take(FINGERPRINT_LEN)
}
