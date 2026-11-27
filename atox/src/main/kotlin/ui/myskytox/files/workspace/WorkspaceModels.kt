// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.myskytox.files.workspace

enum class SortMode { NAME, DATE }
enum class FolderViewMode { LIST, TILES }

data class FolderWindowState(
    val id: String,
    val currentRelativePath: String,
    val x: Int,
    val y: Int,
    val z: Long,
    val sortMode: SortMode = SortMode.NAME,
    val viewMode: FolderViewMode = FolderViewMode.TILES,
)

data class TextEditorWindowState(
    val relativePath: String,
    val x: Int,
    val y: Int,
    val z: Long,
    val currentText: String,
    val originalText: String,
)

data class FileManagerClipboard(val sources: List<String>)

data class WorkspaceState(
    val folderWindows: List<FolderWindowState> = emptyList(),
    val textEditor: TextEditorWindowState? = null,
    val clipboard: FileManagerClipboard? = null,
    val nextZ: Long = 1L,
)

const val MAX_FOLDER_WINDOWS = 5
