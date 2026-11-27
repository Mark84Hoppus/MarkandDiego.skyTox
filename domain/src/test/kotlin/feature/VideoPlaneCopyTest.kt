// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature

import kotlin.test.Test
import kotlin.test.assertContentEquals

class VideoPlaneCopyTest {
    @Test
    fun copiesPaddedPositiveStride() {
        val target = ByteArray(6)
        copyVideoPlane(byteArrayOf(1, 2, 3, 9, 4, 5, 6, 9), 4, 3, 2, target)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), target)
    }

    @Test
    fun copiesNegativeStrideBottomUp() {
        val target = ByteArray(6)
        copyVideoPlane(byteArrayOf(4, 5, 6, 9, 1, 2, 3, 9), -4, 3, 2, target)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), target)
    }
}
