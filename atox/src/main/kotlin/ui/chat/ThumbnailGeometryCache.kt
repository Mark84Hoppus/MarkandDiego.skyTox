// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

internal data class ThumbnailGeometry(val width: Int, val height: Int)

internal class ThumbnailGeometryCache {
    private val cache = LruCache<String, ThumbnailGeometry>(256)
    private val pending = HashSet<String>()
    private val executor = Executors.newSingleThreadExecutor()

    @Synchronized
    fun get(uri: String): ThumbnailGeometry? = cache.get(key(uri))

    fun prefetch(uri: String) {
        val cacheKey = key(uri)
        synchronized(this) {
            if (cache.get(cacheKey) != null || !pending.add(cacheKey)) return
        }
        executor.execute {
            val path = Uri.parse(uri).path
            val geometry = path?.let {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    ThumbnailGeometry(options.outWidth, options.outHeight)
                } else null
            }
            synchronized(this) {
                pending.remove(cacheKey)
                if (geometry != null) cache.put(cacheKey, geometry)
            }
        }
    }

    fun close() = executor.shutdownNow()

    private fun key(uri: String): String {
        val file = Uri.parse(uri).path?.let(::File)
        return "$uri:${file?.lastModified() ?: 0L}:${file?.length() ?: 0L}"
    }
}
