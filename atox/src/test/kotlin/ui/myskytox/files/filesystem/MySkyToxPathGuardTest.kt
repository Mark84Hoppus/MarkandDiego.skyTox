// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.myskytox.files.filesystem

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MySkyToxPathGuardTest {
    private val root = createTempDirectory("skytox-path-guard").toFile()
    private val guard = MySkyToxPathGuard(root)

    @Test fun resolvesChildInsideRoot() {
        assertEquals(File(root, "Downloads/file.txt").canonicalFile, guard.resolve("Downloads/file.txt"))
    }

    @Test fun rejectsTraversal() {
        assertFailsWith<IllegalArgumentException> { guard.resolve("../outside") }
    }

    @Test fun rejectsAbsolutePath() {
        assertFailsWith<IllegalArgumentException> { guard.resolve(File(root.parentFile, "outside").absolutePath) }
    }

    @Test fun validatesNames() {
        assertEquals("Документ.txt", guard.validateName(" Документ.txt "))
        assertFailsWith<IllegalArgumentException> { guard.validateName("../bad") }
        assertFailsWith<IllegalArgumentException> { guard.validateName("bad/name") }
    }
}
