// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.myskytox.files.filesystem

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ltd.evilcorp.domain.feature.SkyToxPublicFolders
import org.json.JSONArray
import org.json.JSONObject

data class MySkyToxEntry(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val protected: Boolean = false,
    val recycleId: String? = null,
)

data class RecycleRecord(
    val id: String,
    val displayName: String,
    val originalRelativePath: String,
    val deletedAt: Long,
    val isDirectory: Boolean,
)

@Singleton
class MySkyToxFileRepository private constructor(
    private val resolver: ContentResolver?,
    root: File,
) {
    @Inject constructor(context: Context) : this(context.contentResolver, SkyToxPublicFolders.mySkyToxDir)
    internal constructor(root: File) : this(null, root)

    private val guard = MySkyToxPathGuard(root)
    private val mutex = Mutex()
    private val downloads get() = File(guard.root, "Downloads")
    private val recycleDir get() = File(guard.root, "Recycle Bin")
    private val metadataDir get() = File(guard.root, ".skytox")
    private val recycleItems get() = File(metadataDir, "recycle-items")
    private val recycleIndex get() = File(metadataDir, "recycle-index.json")
    private val protectedTop = setOf("downloads", "recycle bin", ".skytox")

    suspend fun ensureRoot() = withContext(Dispatchers.IO) {
        guard.root.mkdirs()
        downloads.mkdirs()
        recycleDir.mkdirs()
        metadataDir.mkdirs()
        recycleItems.mkdirs()
        cleanupTemps(guard.root)
    }

    suspend fun list(relativePath: String): List<MySkyToxEntry> = withContext(Dispatchers.IO) {
        if (relativePath == "Recycle Bin") return@withContext readRecycle().map { record ->
            val stored = File(recycleItems, record.id)
            MySkyToxEntry(
                relativePath = guard.relative(stored),
                name = record.displayName,
                isDirectory = record.isDirectory,
                size = if (stored.isFile) stored.length() else 0L,
                modified = record.deletedAt,
                recycleId = record.id,
            )
        }
        val dir = guard.resolve(relativePath)
        require(dir.isDirectory) { "Folder not found" }
        dir.listFiles().orEmpty().asSequence()
            .filterNot { it.name == ".skytox" || it.name.startsWith(".skytox-tmp-") }
            .map { file ->
                val rel = guard.relative(file)
                MySkyToxEntry(rel, file.name, file.isDirectory, if (file.isFile) file.length() else 0L, file.lastModified(), isProtected(rel))
            }.toList()
    }

    suspend fun createFolder(parent: String, name: String): String = mutate {
        val valid = guard.validateName(name)
        val dir = guard.resolve(parent)
        val target = childWithoutCollision(dir, valid)
        require(target.mkdir()) { "Unable to create folder" }
        guard.relative(target)
    }

    suspend fun createText(parent: String, name: String): String = mutate {
        val requested = guard.validateName(name).let { if (it.endsWith(".txt", true)) it else "$it.txt" }
        val dir = guard.resolve(parent)
        val target = childWithoutCollision(dir, requested)
        require(target.createNewFile()) { "Unable to create document" }
        guard.relative(target)
    }

    suspend fun rename(path: String, newName: String, replace: Boolean = false): String = mutate {
        require(!isProtected(path)) { "Protected folder" }
        val source = guard.resolve(path)
        require(source.exists()) { "Source not found" }
        val validName = guard.validateName(newName)
        if (source.name == validName) return@mutate path
        val target = File(source.parentFile, validName)
        guard.resolve(guard.relative(target))
        val caseMatch = source.parentFile?.listFiles().orEmpty().firstOrNull { it.name.equals(validName, true) }
        if (caseMatch?.canonicalFile == source.canonicalFile) {
            val temp = File(source.parentFile, ".skytox-tmp-rename-${UUID.randomUUID()}")
            require(source.renameTo(temp)) { "Unable to rename" }
            if (!temp.renameTo(target)) {
                temp.renameTo(source)
                error("Unable to rename")
            }
            return@mutate guard.relative(target)
        }
        resolveManualCollision(target, source.isDirectory, replace)
        require(source.renameTo(target)) { "Unable to rename" }
        guard.relative(target)
    }

    suspend fun copy(paths: List<String>, targetParent: String, replace: Boolean): List<String> = mutate {
        val targetDir = guard.resolve(targetParent)
        require(targetDir.isDirectory) { "Target folder not found" }
        paths.map { path ->
            val source = guard.resolve(path)
            require(source.exists()) { "Source no longer exists" }
            val target = File(targetDir, source.name)
            require(source.canonicalFile != target.canonicalFile) { "Source and destination are the same" }
            require(!source.isDirectory || !target.canonicalPath.startsWith(source.canonicalPath + File.separator)) { "Cannot copy folder into itself" }
            resolveManualCollision(target, source.isDirectory, replace)
            atomicCopy(source, target)
            guard.relative(target)
        }
    }

    suspend fun move(path: String, targetParent: String, replace: Boolean): String = mutate {
        require(!isProtected(path)) { "Protected folder" }
        val source = guard.resolve(path)
        val targetDir = guard.resolve(targetParent)
        require(source.exists()) { "Source no longer exists" }
        require(targetDir.isDirectory) { "Target folder not found" }
        val sourceCanonical = source.canonicalFile
        val targetDirCanonical = targetDir.canonicalFile
        require(targetDirCanonical != sourceCanonical) { "Cannot move folder into itself" }
        if (source.isDirectory) {
            require(!targetDirCanonical.path.startsWith(sourceCanonical.path + File.separator)) { "Cannot move folder into itself" }
        }
        val target = File(targetDir, source.name)
        if (source.canonicalFile == target.canonicalFile) return@mutate path
        resolveManualCollision(target, source.isDirectory, replace)
        if (!source.renameTo(target)) {
            atomicCopy(source, target)
            require(source.deleteRecursively()) { "Copied, but could not remove source" }
        }
        guard.relative(target)
    }

    suspend fun recycle(path: String) = mutate {
        require(!isProtected(path)) { "Protected folder" }
        val source = guard.resolve(path)
        require(source.exists()) { "Source no longer exists" }
        val id = UUID.randomUUID().toString()
        recycleItems.mkdirs()
        val stored = File(recycleItems, id)
        if (!source.renameTo(stored)) {
            atomicCopy(source, stored)
            require(source.deleteRecursively()) { "Copied successfully, but source could not be removed" }
        }
        val records = readRecycle().toMutableList()
        records += RecycleRecord(id, source.name, path, System.currentTimeMillis(), stored.isDirectory)
        writeRecycle(records)
    }

    suspend fun restore(id: String, replace: Boolean): String = mutate {
        val records = readRecycle().toMutableList()
        val record = records.firstOrNull { it.id == id } ?: error("Recycle item not found")
        val source = File(recycleItems, id)
        val target = guard.resolve(record.originalRelativePath)
        target.parentFile?.mkdirs()
        resolveManualCollision(target, record.isDirectory, replace)
        if (!source.renameTo(target)) {
            atomicCopy(source, target)
            require(source.deleteRecursively()) { "Restored successfully, but recycle copy could not be removed" }
        }
        records.removeAll { it.id == id }
        writeRecycle(records)
        guard.relative(target)
    }

    suspend fun permanentDelete(id: String) = mutate {
        val stored = File(recycleItems, id)
        if (stored.exists()) require(stored.deleteRecursively()) { "Unable to permanently delete item" }
        writeRecycle(readRecycle().filterNot { it.id == id })
    }

    suspend fun clearRecycleBin() = mutate {
        val records = readRecycle()
        records.forEach { record ->
            val stored = File(recycleItems, record.id)
            if (stored.exists()) require(stored.deleteRecursively()) { "Unable to delete recycle item" }
        }
        writeRecycle(emptyList())
    }

    suspend fun importUris(uris: List<Uri>): List<String> = mutate {
        val dir = downloads.apply { require(mkdirs() || isDirectory) { "Downloads folder is unavailable" } }
        val contentResolver = resolver ?: error("Content resolver is unavailable")
        uris.map { uri ->
            val display = queryDisplayName(uri).ifBlank { "shared-file" }
            val target = uniqueFile(dir, sanitizeImportName(display))
            val temp = File(dir, ".skytox-tmp-${UUID.randomUUID()}")
            try {
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { out -> input.copyTo(out); out.fd.sync() } }
                    ?: error("Cannot open shared file")
                require(temp.renameTo(target)) { "Cannot commit imported file" }
            } finally {
                if (temp.exists()) temp.delete()
            }
            guard.relative(target)
        }
    }

    suspend fun readText(path: String, maxBytes: Long = 4L * 1024L * 1024L): String = withContext(Dispatchers.IO) {
        val file = guard.resolve(path)
        require(file.exists()) { "Document not found" }
        require(file.isFile) { "Not a document" }
        require(file.length() <= maxBytes) { "File is too large for embedded editor" }
        file.readText(Charsets.UTF_8)
    }

    suspend fun saveText(path: String, text: String) = mutate {
        val target = guard.resolve(path)
        require(target.isFile) { "Document no longer exists" }
        val temp = File(target.parentFile, ".skytox-tmp-${UUID.randomUUID()}")
        val backup = File(target.parentFile, ".skytox-tmp-backup-${UUID.randomUUID()}")
        try {
            temp.writeText(text, Charsets.UTF_8)
            require(target.renameTo(backup)) { "Unable to preserve document" }
            if (!temp.renameTo(target)) {
                backup.renameTo(target)
                error("Unable to save document")
            }
            backup.delete()
        } finally {
            if (temp.exists()) temp.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            if (target.exists() && backup.exists()) backup.delete()
        }
    }

    fun fileForShare(path: String): File = guard.resolve(path).also { require(it.isFile) { "File no longer exists" } }

    fun isProtected(path: String): Boolean {
        if (path.isBlank()) return true
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
        return parts.size == 1 && parts.first().lowercase(Locale.US) in protectedTop
    }

    private suspend fun <T> mutate(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun childWithoutCollision(parent: File, name: String): File {
        require(parent.isDirectory) { "Target folder not found" }
        require(parent.listFiles().orEmpty().none { it.name.equals(name, true) }) { "Name already exists" }
        return File(parent, name).also { guard.resolve(guard.relative(it)) }
    }

    private fun resolveManualCollision(target: File, directory: Boolean, replace: Boolean) {
        val existing = target.parentFile?.listFiles().orEmpty().firstOrNull { it.name.equals(target.name, true) } ?: return
        require(replace) { "Name already exists" }
        require(!directory && !existing.isDirectory) { "Folder name already exists" }
        require(existing.deleteRecursively()) { "Unable to replace existing item" }
    }

    private fun atomicCopy(source: File, target: File) {
        val sourceCanonical = source.canonicalFile
        val targetCanonical = target.canonicalFile
        require(sourceCanonical != targetCanonical) { "Source and destination are the same" }
        if (sourceCanonical.isDirectory) {
            require(!targetCanonical.path.startsWith(sourceCanonical.path + File.separator)) { "Cannot copy folder into itself" }
        }
        val temp = File(target.parentFile, ".skytox-tmp-${UUID.randomUUID()}")
        try {
            if (source.isDirectory) {
                require(temp.mkdirs()) { "Unable to create temporary folder" }
                source.listFiles().orEmpty().forEach { atomicCopy(it, File(temp, it.name)) }
            } else {
                FileOutputStream(temp).use { out -> source.inputStream().use { it.copyTo(out) }; out.fd.sync() }
            }
            require(temp.renameTo(target)) { "Unable to commit copy" }
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    private fun readRecycle(): List<RecycleRecord> = runCatching {
        if (!recycleIndex.isFile) return emptyList()
        val items = JSONObject(recycleIndex.readText(Charsets.UTF_8)).optJSONArray("items") ?: JSONArray()
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            RecycleRecord(item.getString("id"), item.getString("displayName"), item.getString("originalRelativePath"), item.getLong("deletedAt"), item.getBoolean("isDirectory"))
        }.filter { File(recycleItems, it.id).exists() }
    }.getOrElse { emptyList() }

    private fun writeRecycle(records: List<RecycleRecord>) {
        require(metadataDir.mkdirs() || metadataDir.isDirectory) { "File manager metadata folder is unavailable" }
        val root = JSONObject().put("items", JSONArray().also { array -> records.forEach { record ->
            array.put(JSONObject().put("id", record.id).put("displayName", record.displayName).put("originalRelativePath", record.originalRelativePath).put("deletedAt", record.deletedAt).put("isDirectory", record.isDirectory))
        } })
        val temp = File(recycleIndex.parentFile, ".skytox-tmp-recycle-${UUID.randomUUID()}")
        val backup = File(recycleIndex.parentFile, ".skytox-tmp-recycle-backup-${UUID.randomUUID()}")
        try {
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (recycleIndex.exists()) require(recycleIndex.renameTo(backup)) { "Unable to preserve recycle index" }
            if (!temp.renameTo(recycleIndex)) {
                if (backup.exists()) backup.renameTo(recycleIndex)
                error("Unable to save recycle index")
            }
            backup.delete()
        } finally {
            temp.delete()
            if (!recycleIndex.exists() && backup.exists()) backup.renameTo(recycleIndex)
            if (recycleIndex.exists()) backup.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
        resolver?.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun sanitizeImportName(name: String): String = name.map { if (it == '/' || it == '\\' || it == '\u0000' || it.code < 32) '_' else it }.joinToString("").trim().take(180).ifBlank { "shared-file" }

    private fun uniqueFile(parent: File, requested: String): File {
        val base = requested.substringBeforeLast('.', requested)
        val ext = requested.substringAfterLast('.', "")
        var candidate = File(parent, requested)
        var index = 1
        while (parent.listFiles().orEmpty().any { it.name.equals(candidate.name, true) }) {
            candidate = File(parent, if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext")
            index++
        }
        return candidate
    }

    private fun cleanupTemps(dir: File) {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        dir.walkTopDown().filter { it.name.startsWith(".skytox-tmp-") && it.lastModified() < cutoff }.forEach { it.deleteRecursively() }
    }
}
