// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SkyToxEncryptedMessage {
    private const val PREFIX = "skytox-secure-v1:"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()
    private val key by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("skyTox encrypted message module v1".toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    fun isEncrypted(message: String): Boolean = message.startsWith(PREFIX)

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return PREFIX + encode(iv + encrypted)
    }

    fun decrypt(message: String): String {
        require(isEncrypted(message))
        val payload = decode(message.removePrefix(PREFIX))
        require(payload.size > IV_SIZE)
        val iv = payload.copyOfRange(0, IV_SIZE)
        val encrypted = payload.copyOfRange(IV_SIZE, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decode(text: String): ByteArray =
        Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE)
}
