// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.myskytox.files.filesystem

import java.io.File

class MySkyToxPathGuard(root: File) {
    val root: File = root.canonicalFile

    fun resolve(relativePath: String): File {
        require(!File(relativePath).isAbsolute) { "Absolute paths are not allowed" }
        require('\u0000' !in relativePath) { "NUL is not allowed" }
        require(relativePath.split('/', '\\').none { it == ".." }) { "Parent traversal is not allowed" }
        val result = File(root, relativePath).canonicalFile
        require(result == root || result.path.startsWith(root.path + File.separator)) { "Path escapes My skyTox" }
        return result
    }

    fun relative(file: File): String {
        val canonical = file.canonicalFile
        require(canonical == root || canonical.path.startsWith(root.path + File.separator)) { "Path escapes My skyTox" }
        return if (canonical == root) "" else canonical.path.removePrefix(root.path + File.separator).replace(File.separatorChar, '/')
    }

    fun validateName(name: String): String {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean != "." && clean != "..") { "Invalid name" }
        require(clean.length <= 180) { "Name is too long" }
        require(clean.none { it == '/' || it == '\\' || it == '\u0000' || it.code < 32 }) { "Invalid character" }
        return clean
    }
}
