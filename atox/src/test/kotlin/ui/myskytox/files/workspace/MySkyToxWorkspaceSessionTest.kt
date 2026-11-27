// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.myskytox.files.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MySkyToxWorkspaceSessionTest {
    @Test fun newProcessSessionStartsEmpty() {
        val state = MySkyToxWorkspaceSession().state.value
        assertEquals(emptyList(), state.folderWindows)
        assertNull(state.textEditor)
        assertNull(state.clipboard)
    }

    @Test fun renameRewritesEveryLiveReference() {
        val session = MySkyToxWorkspaceSession()
        session.state.value = WorkspaceState(
            folderWindows = listOf(FolderWindowState("1", "Docs/Sub", 0, 0, 1)),
            textEditor = TextEditorWindowState("Docs/a.txt", 0, 0, 2, "x", "x"),
            clipboard = FileManagerClipboard(listOf("Docs/copy.txt")),
        )
        session.rewritePrefix("Docs", "Documents")
        val state = session.state.value
        assertEquals("Documents/Sub", state.folderWindows.single().currentRelativePath)
        assertEquals("Documents/a.txt", state.textEditor?.relativePath)
        assertEquals("Documents/copy.txt", state.clipboard?.sources?.single())
    }
}
