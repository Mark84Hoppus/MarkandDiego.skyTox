// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature.avatar

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.FileTransferManager
import ltd.evilcorp.domain.tox.MAX_AVATAR_SIZE

@Singleton
class SkyToxAvatarManager @Inject constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val resolver: ContentResolver,
    private val fileTransferManager: FileTransferManager,
) {
    private val avatarDir = File(context.filesDir, "skytox-avatar")
    private val ownAvatarFile = File(avatarDir, "profile.jpg")

    fun ownAvatarUri(): Uri? = if (ownAvatarFile.exists()) Uri.fromFile(ownAvatarFile) else null

    fun setOwnAvatar(source: Uri, onDone: (Uri?) -> Unit = {}) = scope.launch {
        val result = withContext(Dispatchers.IO) {
            avatarDir.mkdirs()
            resolver.openInputStream(source)?.use { input ->
                val original = BitmapFactory.decodeStream(input) ?: return@withContext null
                val avatar = original.centerSquare().scaleToAvatar()
                original.recycle()
                saveAvatar(avatar)
                avatar.recycle()
                ownAvatarUri()
            }
        }
        onDone(result)
    }

    fun deleteOwnAvatar(onDone: () -> Unit = {}) = scope.launch(Dispatchers.IO) {
        ownAvatarFile.delete()
        withContext(Dispatchers.Main) { onDone() }
    }

    fun syncWith(publicKey: String) {
        if (!ENABLED) return
        ownAvatarUri()?.let {
            fileTransferManager.sendAvatar(PublicKey(publicKey), it)
        } ?: fileTransferManager.clearAvatar(PublicKey(publicKey))
    }

    private fun saveAvatar(bitmap: Bitmap) {
        var quality = AVATAR_QUALITY
        do {
            FileOutputStream(ownAvatarFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            quality -= 8
        } while (ownAvatarFile.length() > MAX_AVATAR_SIZE && quality >= MIN_AVATAR_QUALITY)
    }

    private fun Bitmap.centerSquare(): Bitmap {
        val side = min(width, height)
        val src = Rect((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
        val out = createBitmap(side, side)
        Canvas(out).drawBitmap(this, src, Rect(0, 0, side, side), null)
        return out
    }

    private fun Bitmap.scaleToAvatar(): Bitmap {
        if (width == AVATAR_SIZE && height == AVATAR_SIZE) return this
        return Bitmap.createScaledBitmap(this, AVATAR_SIZE, AVATAR_SIZE, true).also {
            if (it != this) recycle()
        }
    }

    companion object {
        const val ENABLED = true
        const val AVATAR_SIZE = 128
        private const val AVATAR_QUALITY = 88
        private const val MIN_AVATAR_QUALITY = 48
    }
}
