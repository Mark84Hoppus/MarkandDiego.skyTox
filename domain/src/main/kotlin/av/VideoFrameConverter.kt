// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.av

object VideoFrameConverter {
    private val nativeAvailable = runCatching {
        System.loadLibrary("skytox_video")
    }.isSuccess

    fun yuv420ToArgb(
        width: Int,
        height: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        yStride: Int,
        uStride: Int,
        vStride: Int,
        pixels: IntArray,
    ) {
        if (
            nativeAvailable &&
            runCatching {
                nativeYuv420ToArgb(width, height, y, u, v, yStride, uStride, vStride, pixels)
            }.getOrDefault(false)
        ) {
            return
        }

        yuv420ToArgbKotlin(width, height, y, u, v, yStride, uStride, vStride, pixels)
    }

    fun nv21ToI420(
        data: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
    ) {
        if (
            nativeAvailable &&
            runCatching {
                nativeNv21ToI420(data, width, height, rotation, y, u, v)
            }.getOrDefault(false)
        ) {
            return
        }

        nv21ToI420Kotlin(data, width, height, rotation, y, u, v)
    }

    private external fun nativeYuv420ToArgb(
        width: Int,
        height: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        yStride: Int,
        uStride: Int,
        vStride: Int,
        pixels: IntArray,
    ): Boolean

    private external fun nativeNv21ToI420(
        data: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
    ): Boolean

    private fun yuv420ToArgbKotlin(
        width: Int,
        height: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        yStride: Int,
        uStride: Int,
        vStride: Int,
        pixels: IntArray,
    ) {
        for (row in 0 until height) {
            val yRow = row * yStride
            val uRow = (row / 2) * uStride
            val vRow = (row / 2) * vStride
            for (col in 0 until width) {
                val yIndex = yRow + col
                val uvCol = col / 2
                val uIndex = uRow + uvCol
                val vIndex = vRow + uvCol
                val yy = if (yIndex in y.indices) y[yIndex].toInt() and 0xff else 0
                val uu = (if (uIndex in u.indices) u[uIndex].toInt() and 0xff else 128) - 128
                val vv = (if (vIndex in v.indices) v[vIndex].toInt() and 0xff else 128) - 128

                val r = clamp((yy * 1024 + 1436 * vv) shr 10)
                val g = clamp((yy * 1024 - 352 * uu - 731 * vv) shr 10)
                val b = clamp((yy * 1024 + 1815 * uu) shr 10)
                pixels[row * width + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun clamp(value: Int) = value.coerceIn(0, 255)

    private fun nv21ToI420Kotlin(
        data: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
    ) {
        val normalizedRotation = ((rotation % 360) + 360) % 360
        val outWidth = if (normalizedRotation == 90 || normalizedRotation == 270) height else width
        val outHeight = if (normalizedRotation == 90 || normalizedRotation == 270) width else height
        val frameSize = width * height

        for (outY in 0 until outHeight) {
            for (outX in 0 until outWidth) {
                val inputX: Int
                val inputY: Int
                when (normalizedRotation) {
                    90 -> {
                        inputX = outY.coerceIn(0, width - 1)
                        inputY = (height - 1 - outX).coerceIn(0, height - 1)
                    }
                    180 -> {
                        inputX = (width - 1 - outX).coerceIn(0, width - 1)
                        inputY = (height - 1 - outY).coerceIn(0, height - 1)
                    }
                    270 -> {
                        inputX = (width - 1 - outY).coerceIn(0, width - 1)
                        inputY = outX.coerceIn(0, height - 1)
                    }
                    else -> {
                        inputX = outX.coerceIn(0, width - 1)
                        inputY = outY.coerceIn(0, height - 1)
                    }
                }
                y[outY * outWidth + outX] = data.getOrElse(inputY * width + inputX) { 0 }
            }
        }

        for (outY in 0 until outHeight / 2) {
            for (outX in 0 until outWidth / 2) {
                val sampleX = outX * 2
                val sampleY = outY * 2
                val inputX: Int
                val inputY: Int
                when (normalizedRotation) {
                    90 -> {
                        inputX = sampleY.coerceIn(0, width - 1)
                        inputY = (height - 1 - sampleX).coerceIn(0, height - 1)
                    }
                    180 -> {
                        inputX = (width - 1 - sampleX).coerceIn(0, width - 1)
                        inputY = (height - 1 - sampleY).coerceIn(0, height - 1)
                    }
                    270 -> {
                        inputX = (width - 1 - sampleY).coerceIn(0, width - 1)
                        inputY = sampleX.coerceIn(0, height - 1)
                    }
                    else -> {
                        inputX = sampleX.coerceIn(0, width - 1)
                        inputY = sampleY.coerceIn(0, height - 1)
                    }
                }
                val chromaIndex = frameSize + (inputY / 2) * width + (inputX / 2) * 2
                val outIndex = outY * (outWidth / 2) + outX
                v[outIndex] = data.getOrElse(chromaIndex) { 128.toByte() }
                u[outIndex] = data.getOrElse(chromaIndex + 1) { 128.toByte() }
            }
        }
    }
}
