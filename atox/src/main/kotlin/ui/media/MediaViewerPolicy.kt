// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.media

import java.util.Locale

enum class MediaOpenTarget { INTERNAL_IMAGE, INTERNAL_VIDEO, EXTERNAL }

enum class MediaViewerMode { IMAGE, VIDEO }

object MediaViewerPolicy {
    val internalImageExtensions = setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp")
    val internalVideoExtensions = setOf("mp4", "m4v", "3gp", "3gpp", "webm", "mkv")
    val forceExternalExtensions = setOf(
        "tga", "targa", "tif", "tiff", "gif", "bmp", "heic", "heif", "avif", "dng", "raw",
        "psd", "xcf", "svg", "ico", "avi", "divx", "xvid", "mov", "wmv", "asf", "flv",
        "mpeg", "mpg", "m2ts", "mts", "ts", "vob", "ogv", "rm", "rmvb",
    )

    fun classify(fileName: String, mimeType: String?): MediaOpenTarget {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension in forceExternalExtensions) return MediaOpenTarget.EXTERNAL
        if (extension in internalImageExtensions) return MediaOpenTarget.INTERNAL_IMAGE
        if (extension in internalVideoExtensions) return MediaOpenTarget.INTERNAL_VIDEO
        if (extension.isNotEmpty()) return MediaOpenTarget.EXTERNAL
        return when (mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US)) {
            "image/jpeg", "image/png", "image/webp" -> MediaOpenTarget.INTERNAL_IMAGE
            "video/mp4", "video/3gpp", "video/webm", "video/x-matroska", "video/matroska" ->
                MediaOpenTarget.INTERNAL_VIDEO
            else -> MediaOpenTarget.EXTERNAL
        }
    }
}
