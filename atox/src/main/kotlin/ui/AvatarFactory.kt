// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import kotlin.math.abs
import ltd.evilcorp.atox.R

internal object AvatarFactory {

    // Stable qTox-like generated avatar based on the public key.
    fun create(
        resources: Resources,
        name: String,
        publicKey: String,
        size: Px = Px(resources.getDimension(R.dimen.default_avatar_size).toInt()),
    ): Bitmap {
        val bitmap = createBitmap(size.px, size.px)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val colors = resources.getIntArray(R.array.contactBackgrounds)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors[abs(publicKey.hashCode()).rem(colors.size)]
        }
        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 210
        }

        canvas.drawRoundRect(rect, rect.bottom, rect.right, backgroundPaint)

        val key = (publicKey.ifEmpty { name }).ifEmpty { "skyTox" }
        val tile = bitmap.width / GRID_SIZE.toFloat()
        val inset = tile * 0.12f
        for (y in 0 until GRID_SIZE) {
            for (x in 0 until GRID_SIZE / 2 + 1) {
                val index = (y * 3 + x).mod(key.length)
                val draw = key[index].code + x * 31 + y * 17 and 1 == 0
                if (!draw) continue

                drawTile(canvas, tilePaint, x, y, tile, inset)
                val mirrorX = GRID_SIZE - 1 - x
                if (mirrorX != x) {
                    drawTile(canvas, tilePaint, mirrorX, y, tile, inset)
                }
            }
        }

        return bitmap
    }

    private fun drawTile(canvas: Canvas, paint: Paint, x: Int, y: Int, tile: Float, inset: Float) {
        canvas.drawRoundRect(
            RectF(x * tile + inset, y * tile + inset, (x + 1) * tile - inset, (y + 1) * tile - inset),
            inset,
            inset,
            paint,
        )
    }

    private const val GRID_SIZE = 5
}
