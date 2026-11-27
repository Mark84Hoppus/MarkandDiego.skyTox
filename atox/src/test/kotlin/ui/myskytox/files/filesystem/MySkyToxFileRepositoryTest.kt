// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.myskytox.files.filesystem

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MySkyToxFileRepositoryTest {
    @Test fun moveFolderIntoItselfIsRejected() = withRepository { root, repository ->
        root.resolve("A").mkdirs()
        assertFailsWith<IllegalArgumentException> { runBlocking { repository.move("A", "A", false) } }
        assertTrue(root.resolve("A").isDirectory)
        assertFalse(root.resolve("A/A").exists())
    }

    @Test fun moveFolderIntoDescendantIsRejected() = withRepository { root, repository ->
        root.resolve("A/B").mkdirs()
        assertFailsWith<IllegalArgumentException> { runBlocking { repository.move("A", "A/B", false) } }
        assertTrue(root.resolve("A/B").isDirectory)
    }

    @Test fun moveFolderAndFileToSiblingWorks() = withRepository { root, repository ->
        root.resolve("A").mkdirs()
        root.resolve("B").mkdirs()
        runBlocking { repository.move("A", "B", false) }
        assertTrue(root.resolve("B/A").isDirectory)
        root.resolve("C").mkdirs()
        root.resolve("C/file.txt").writeText("ok")
        runBlocking { repository.move("C/file.txt", "B", false) }
        assertTrue(root.resolve("B/file.txt").isFile)
    }

    @Test fun copyFolderToSameParentOrDescendantIsRejected() = withRepository { root, repository ->
        root.resolve("A/B").mkdirs()
        assertFailsWith<IllegalArgumentException> { runBlocking { repository.copy(listOf("A"), "", false) } }
        assertFailsWith<IllegalArgumentException> { runBlocking { repository.copy(listOf("A"), "A/B", false) } }
    }

    private fun withRepository(block: (java.io.File, MySkyToxFileRepository) -> Unit) {
        val root = createTempDirectory("skytox-repository").toFile()
        try { block(root, MySkyToxFileRepository(root)) } finally { root.deleteRecursively() }
    }
}
