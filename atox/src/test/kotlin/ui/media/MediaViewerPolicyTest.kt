// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaViewerPolicyTest {
    @Test
    fun commonImagesAreInternal() {
        listOf("photo.jpg", "photo.JPEG", "photo.png", "photo.webp").forEach {
            assertEquals(MediaOpenTarget.INTERNAL_IMAGE, MediaViewerPolicy.classify(it, null), it)
        }
    }

    @Test
    fun commonVideosAreInternal() {
        listOf("video.mp4", "video.m4v", "video.3gp", "video.3gpp", "video.webm", "video.mkv").forEach {
            assertEquals(MediaOpenTarget.INTERNAL_VIDEO, MediaViewerPolicy.classify(it, null), it)
        }
    }

    @Test
    fun uncommonMediaRemainExternal() {
        listOf("photo.tga", "photo.tif", "photo.tiff", "photo.gif", "photo.heic", "photo.avif",
            "video.avi", "video.mov", "video.wmv", "video.flv", "document.pdf", "app.apk").forEach {
            assertEquals(MediaOpenTarget.EXTERNAL, MediaViewerPolicy.classify(it, null), it)
        }
    }
}
