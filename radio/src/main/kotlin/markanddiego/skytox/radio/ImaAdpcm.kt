// SPDX-License-Identifier: GPL-3.0-only

package markanddiego.skytox.radio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

internal object ImaAdpcm {
    private val indexTable = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
    private val stepTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
        11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
        32767,
    )

    fun encode(samples: ShortArray): ByteArray {
        if (samples.isEmpty()) return byteArrayOf()
        var predictor = samples[0].toInt()
        val averageDelta = samples.asSequence().zipWithNext { a, b -> abs(b.toInt() - a.toInt()) }
            .take(24).average().takeIf { !it.isNaN() }?.toInt() ?: 0
        var index = stepTable.indexOfFirst { it >= averageDelta }.let { if (it < 0) stepTable.lastIndex else it }
        val out = ByteBuffer.allocate(3 + (samples.size - 1 + 1) / 2).order(ByteOrder.BIG_ENDIAN)
        out.putShort(predictor.toShort()).put(index.toByte())
        var half = false
        var packed = 0
        for (i in 1 until samples.size) {
            val step = stepTable[index]
            var delta = samples[i].toInt() - predictor
            var code = if (delta < 0) 8 else 0
            delta = abs(delta)
            var difference = step shr 3
            if (delta >= step) { code = code or 4; delta -= step; difference += step }
            if (delta >= step shr 1) { code = code or 2; delta -= step shr 1; difference += step shr 1 }
            if (delta >= step shr 2) { code = code or 1; difference += step shr 2 }
            predictor += if (code and 8 != 0) -difference else difference
            predictor = predictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            index = (index + indexTable[code and 7]).coerceIn(0, stepTable.lastIndex)
            if (!half) {
                packed = code and 0x0f
                half = true
            } else {
                out.put((packed or ((code and 0x0f) shl 4)).toByte())
                half = false
            }
        }
        if (half) out.put(packed.toByte())
        return out.array()
    }

    fun decode(data: ByteArray, sampleCount: Int): ShortArray {
        if (data.size < 3 || sampleCount <= 0) return ShortArray(0)
        val input = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        var predictor = input.short.toInt()
        var index = (input.get().toInt() and 0xff).coerceIn(0, stepTable.lastIndex)
        val result = ShortArray(sampleCount)
        result[0] = predictor.toShort()
        var output = 1
        while (input.hasRemaining() && output < sampleCount) {
            val packed = input.get().toInt() and 0xff
            for (shift in intArrayOf(0, 4)) {
                if (output >= sampleCount) break
                val code = (packed shr shift) and 0x0f
                val step = stepTable[index]
                var difference = step shr 3
                if (code and 4 != 0) difference += step
                if (code and 2 != 0) difference += step shr 1
                if (code and 1 != 0) difference += step shr 2
                predictor += if (code and 8 != 0) -difference else difference
                predictor = predictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                index = (index + indexTable[code and 7]).coerceIn(0, stepTable.lastIndex)
                result[output++] = predictor.toShort()
            }
        }
        return if (output == sampleCount) result else result.copyOf(output)
    }
}
