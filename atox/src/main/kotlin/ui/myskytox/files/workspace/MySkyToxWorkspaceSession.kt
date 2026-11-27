// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.myskytox.files.workspace

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

@Singleton
class MySkyToxWorkspaceSession @Inject constructor() {
    val state = MutableStateFlow(WorkspaceState())

    fun update(transform: (WorkspaceState) -> WorkspaceState) {
        state.value = transform(state.value)
    }

    fun nextZ(): Long {
        val z = state.value.nextZ
        update { it.copy(nextZ = z + 1L) }
        return z
    }

    fun rewritePrefix(oldPath: String, newPath: String) {
        fun rewrite(path: String): String = when {
            path == oldPath -> newPath
            path.startsWith("$oldPath/") -> newPath + path.removePrefix(oldPath)
            else -> path
        }
        update { current ->
            current.copy(
                folderWindows = current.folderWindows.map { it.copy(currentRelativePath = rewrite(it.currentRelativePath)) },
                textEditor = current.textEditor?.let { it.copy(relativePath = rewrite(it.relativePath)) },
                clipboard = current.clipboard?.let { it.copy(sources = it.sources.map(::rewrite)) },
            )
        }
    }
}
