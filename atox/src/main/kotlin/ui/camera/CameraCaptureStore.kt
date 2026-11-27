// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.camera

import android.content.Context
import java.io.File
import java.util.UUID

object CameraCaptureStore {
    private const val DIRECTORY = "skytox-camera"
    private const val STALE_AGE_MS = 24L * 60L * 60L * 1000L
    private const val MIN_FREE_BYTES = 32L * 1024L * 1024L

    private fun root(context: Context): File = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    fun hasRoom(context: Context): Boolean = root(context).usableSpace >= MIN_FREE_BYTES

    fun createPhoto(context: Context): File = create(context, "jpg")

    fun createVideo(context: Context): File = create(context, "mp4")

    private fun create(context: Context, extension: String): File =
        File(root(context), "capture-${System.currentTimeMillis()}-${UUID.randomUUID()}.$extension")

    fun token(file: File): String = file.name

    fun resolve(context: Context, token: String?): File? {
        if (token.isNullOrBlank() || token.contains('/') || token.contains('\\')) return null
        val root = root(context).canonicalFile
        val file = File(root, token).canonicalFile
        if (file.parentFile != root || !file.isFile || file.length() <= 0L) return null
        val extension = file.extension.lowercase()
        if (extension != "jpg" && extension != "mp4") return null
        return file
    }

    fun delete(file: File?) {
        runCatching { file?.takeIf { it.exists() }?.delete() }
    }

    fun cleanupStale(context: Context) {
        val cutoff = System.currentTimeMillis() - STALE_AGE_MS
        root(context).listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }
}
