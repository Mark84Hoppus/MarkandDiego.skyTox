// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import im.tox.tox4j.core.enums.ToxFileControl
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.collections.forEach as kForEach
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import ltd.evilcorp.core.repository.ContactRepository
import ltd.evilcorp.core.repository.FileTransferRepository
import ltd.evilcorp.core.repository.MessageRepository
import ltd.evilcorp.core.vo.FT_NOT_STARTED
import ltd.evilcorp.core.vo.FT_REJECTED
import ltd.evilcorp.core.vo.FT_STARTED
import ltd.evilcorp.core.vo.FileKind
import ltd.evilcorp.core.vo.FileTransfer
import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.core.vo.Sender
import ltd.evilcorp.core.vo.interruptedProgress
import ltd.evilcorp.core.vo.isComplete
import ltd.evilcorp.core.vo.isInterrupted
import ltd.evilcorp.core.vo.isRejected
import ltd.evilcorp.core.vo.isStarted
import ltd.evilcorp.core.vo.transferredBytes
import ltd.evilcorp.domain.tox.MAX_AVATAR_SIZE
import ltd.evilcorp.domain.tox.Tox
import ltd.evilcorp.domain.tox.bytesToHex
import ltd.evilcorp.domain.tox.hexToBytes

private const val TAG = "FileTransferManager"
private const val TOX_FILE_ID_BYTES = 32
private const val THUMB_SIZE = 480
private const val THUMB_QUALITY = 60
private const val CHUNK_RETRY_DELAY_MS = 500L

// TODO(robinlinden): This will go away when PublicKey is used everywhere it should be.
private const val FINGERPRINT_LEN = 8
private fun String.fingerprint() = take(FINGERPRINT_LEN)

@Suppress("ArrayInDataClass")
private data class Chunk(val pos: Long, val data: ByteArray)

private data class OutgoingFile(val uri: Uri, val unsentChunks: MutableList<Chunk>, var retrying: Boolean = false)

@Singleton
class FileTransferManager @Inject constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val resolver: ContentResolver,
    private val contactRepository: ContactRepository,
    private val messageRepository: MessageRepository,
    private val fileTransferRepository: FileTransferRepository,
    private val tox: Tox,
) {
    private val fileTransfers: MutableList<FileTransfer> = mutableListOf()
    private val outgoingFiles = mutableMapOf<Pair<String, Int>, OutgoingFile>()
    private val outgoingCacheRoot by lazy { context.cacheDir.canonicalFile }
    private val storage by lazy { AtoxStorage() }

    init {
        File(context.filesDir, "ft").mkdir()
        File(context.filesDir, "avatar").mkdir()
        storage.ensureDirectories()
    }

    fun reset() {
        fileTransfers.clear()
        scope.launch {
            fileTransferRepository.resetTransientData()
        }
    }

    fun interruptForContact(pk: String) {
        Log.i(TAG, "Interrupting fts for contact ${pk.fingerprint()}")
        fileTransfers.filter { it.publicKey == pk }.kForEach { ft ->
            if (ft.fileKind == FileKind.Data.ordinal && !ft.isComplete() && !ft.isRejected()) {
                setProgress(ft, ft.interruptedProgress())
            }

            fileTransfers.remove(ft)
            if (ft.outgoing) {
                outgoingFiles.remove(Pair(pk, ft.fileNumber))
            } else if (ft.fileKind != FileKind.Data.ordinal) {
                File(ft.destination).delete()
            }
        }
    }

    fun resumeOutgoingForContact(pk: String) = scope.launch {
        fileTransferRepository.getInterruptedOutgoing(pk).kForEach { ft ->
            if (fileTransfers.any { it.id == ft.id }) {
                return@kForEach
            }

            val uri = ft.destination.toUri()
            if (!canRead(uri)) {
                Log.e(TAG, "Unable to resume outgoing ft ${ft.id}: source is no longer readable")
                fileTransferRepository.updateProgress(ft.id, FT_REJECTED)
                releaseFilePermission(uri)
                return@kForEach
            }

            val fileId = ft.fileId.ifEmpty { Random.nextBytes(TOX_FILE_ID_BYTES).bytesToHex() }
            val resumed = ft.copy(
                fileNumber = tox.sendFile(PublicKey(pk), FileKind.Data, ft.fileSize, ft.fileName, fileId.hexToBytes()),
                progress = FT_NOT_STARTED,
                fileId = fileId,
            ).apply { id = ft.id }

            fileTransferRepository.add(resumed)
            fileTransfers.add(resumed)
            outgoingFiles[Pair(pk, resumed.fileNumber)] = OutgoingFile(uri, mutableListOf())
        }
    }

    fun add(ft: FileTransfer): Int {
        Log.i(TAG, "Add ${ft.fileNumber} for ${ft.publicKey.fingerprint()}")
        return when (ft.fileKind) {
            FileKind.Data.ordinal -> {
                val fileId = tox.getFileFileId(PublicKey(ft.publicKey), ft.fileNumber).bytesToHex()
                val interrupted = fileTransferRepository.getInterruptedIncoming(
                    ft.publicKey,
                    fileId,
                    ft.fileName,
                    ft.fileSize,
                )
                if (interrupted != null) {
                    val resumed = interrupted.copy(fileNumber = ft.fileNumber, fileId = fileId).apply {
                        id = interrupted.id
                    }
                    fileTransferRepository.add(resumed)
                    fileTransfers.removeAll { it.id == resumed.id }
                    fileTransfers.add(resumed)
                    accept(resumed)
                    resumed.id
                } else {
                    val withFileId = ft.copy(fileId = fileId)
                    val id = fileTransferRepository.add(withFileId).toInt()
                    messageRepository.add(
                        Message(ft.publicKey, ft.fileName, Sender.Received, MessageType.FileTransfer, id, Date().time),
                    )
                    fileTransfers.add(withFileId.copy().apply { this.id = id })
                    id
                }
            }
            FileKind.Avatar.ordinal -> {
                if (ft.fileSize == 0L) {
                    contactRepository.setAvatarUri(ft.publicKey, "")
                    reject(ft)
                    return -1
                } else if (ft.fileSize > MAX_AVATAR_SIZE) {
                    Log.e(TAG, "Got trash avatar with size ${ft.fileSize} from ${ft.publicKey}")
                    contactRepository.setAvatarUri(ft.publicKey, "")
                    tox.stopFileTransfer(PublicKey(ft.publicKey), ft.fileNumber)
                    return -1
                }

                fileTransfers.add(ft)
                accept(ft)
                -1
            }
            else -> {
                Log.e(TAG, "Got unknown file kind ${ft.fileKind} in file transfer")
                -1
            }
        }
    }

    fun accept(id: Int) {
        fileTransfers.find { it.id == id }?.let {
            accept(it)
        } ?: Log.e(TAG, "Unable to find & accept ft $id")
    }

    fun accept(ft: FileTransfer) {
        Log.i(TAG, "Accept ${ft.fileNumber} for ${ft.publicKey.fingerprint()}")
        if (ft.isStarted() && !ft.isInterrupted()) {
            Log.i(TAG, "Ignoring duplicate accept for ${ft.fileNumber} from ${ft.publicKey.fingerprint()}")
            return
        }

        val startPosition = if (ft.isInterrupted()) ft.transferredBytes() else FT_STARTED
        val file = when (ft.fileKind) {
            FileKind.Data.ordinal -> {
                val dest = if (ft.isInterrupted() && ft.destination.isNotEmpty()) {
                    ft.destination.toUri()
                } else {
                    storage.destinationFor(ft)
                }
                val file = File(dest.path!!)
                file.parentFile!!.mkdirs()
                file
            }
            FileKind.Avatar.ordinal -> wipAvatar(ft.fileName)
            else -> {
                Log.e(TAG, "Got unknown file kind when accepting ft: $ft")
                return
            }
        }

        RandomAccessFile(file, "rwd").use { it.setLength(ft.fileSize) }
        setDestination(ft, Uri.fromFile(file))
        setProgress(ft, startPosition)
        if (startPosition > FT_STARTED) {
            tox.seekFileTransfer(PublicKey(ft.publicKey), ft.fileNumber, startPosition)
        }
        tox.startFileTransfer(PublicKey(ft.publicKey), ft.fileNumber)
    }

    fun reject(id: Int) {
        fileTransfers.find { it.id == id }?.let {
            reject(it)
        } ?: Log.e(TAG, "Unable to find & reject ft $id")
    }

    fun reject(ft: FileTransfer) {
        Log.i(TAG, "Reject ${ft.fileNumber} for ${ft.publicKey.fingerprint()}")
        fileTransfers.remove(ft)
        setProgress(ft, FT_REJECTED)
        tox.stopFileTransfer(PublicKey(ft.publicKey), ft.fileNumber)
        val uri = ft.destination.toUri()
        if (ft.outgoing) {
            outgoingFiles.remove(Pair(ft.publicKey, ft.fileNumber))
            deleteOutgoingCacheFile(uri)
            releaseFilePermission(uri)
        } else {
            File(uri.path!!).delete()
        }
    }

    private fun setDestination(ft: FileTransfer, destination: Uri) {
        fileTransfers[fileTransfers.indexOf(ft)].destination = destination.toString()
        if (ft.fileKind == FileKind.Data.ordinal) {
            fileTransferRepository.setDestination(ft.id, destination.toString())
        }
    }

    private fun setThumbnail(ft: FileTransfer, thumbnail: Uri?) {
        val thumbnailString = thumbnail?.toString() ?: ""
        fileTransfers.elementAtOrNull(fileTransfers.indexOf(ft))?.thumbnail = thumbnailString
        if (ft.fileKind == FileKind.Data.ordinal) {
            fileTransferRepository.setThumbnail(ft.id, thumbnailString)
        }
    }

    private fun setProgress(ft: FileTransfer, progress: Long) {
        val id = fileTransfers.indexOf(ft)
        fileTransfers.elementAtOrNull(id)?.progress = progress
        if (ft.fileKind == FileKind.Data.ordinal) {
            fileTransferRepository.updateProgress(ft.id, progress)
        }
    }

    fun addDataToTransfer(publicKey: String, fileNumber: Int, position: Long, data: ByteArray) {
        val ft = fileTransfers.find { it.publicKey == publicKey && it.fileNumber == fileNumber }
        if (ft == null) {
            if (data.isNotEmpty()) {
                Log.e(TAG, "Got data for ft $fileNumber for ${publicKey.fingerprint()} we don't know about")
            }
            return
        }

        if (ft.fileKind != FileKind.Data.ordinal && ft.fileKind != FileKind.Avatar.ordinal) {
            Log.e(TAG, "Got unknown file kind when adding data to ft: $ft")
            return
        }

        RandomAccessFile(File(ft.destination.toUri().path!!), "rwd").use {
            it.seek(position)
            it.write(data)
        }

        setProgress(ft, max(ft.transferredBytes(), position + data.size))

        if (ft.isComplete()) {
            Log.i(TAG, "Finished ${ft.fileNumber} for ${ft.publicKey.fingerprint()}")
            if (ft.fileKind == FileKind.Avatar.ordinal) {
                wipAvatar(ft.fileName).copyTo(avatar(ft.fileName), overwrite = true)
                wipAvatar(ft.fileName).delete()
                contactRepository.setAvatarUri(ft.publicKey, Uri.fromFile(avatar(ft.fileName)).toString())
            } else if (ft.fileKind == FileKind.Data.ordinal) {
                setThumbnail(ft, createThumbnail(ft, ft.destination.toUri()))
            }
            fileTransfers.remove(ft)
        }
    }

    fun transfersFor(publicKey: PublicKey) = fileTransferRepository.get(publicKey.string())

    fun voiceMessageFile(): File = storage.voiceMessageFile()

    fun create(pk: PublicKey, file: Uri) {
        val (name, size) = queryNameAndSize(file) ?: return

        val fileId = Random.nextBytes(TOX_FILE_ID_BYTES).bytesToHex()
        val ft = FileTransfer(
            pk.string(),
            tox.sendFile(pk, FileKind.Data, size, name, fileId.hexToBytes()),
            FileKind.Data.ordinal,
            size,
            name,
            true,
            FT_NOT_STARTED,
            file.toString(),
            fileId,
        )
        val id = fileTransferRepository.add(ft).toInt()
        ft.id = id
        messageRepository.add(
            Message(ft.publicKey, ft.fileName, Sender.Sent, MessageType.FileTransfer, id, Date().time),
        )
        val thumbnail = createThumbnail(ft, file)
        if (thumbnail != null) {
            ft.thumbnail = thumbnail.toString()
            fileTransferRepository.setThumbnail(id, ft.thumbnail)
        }
        fileTransfers.add(ft.copy().apply { this.id = id })

        if (!canRead(file)) {
            reject(ft)
            return
        }
        outgoingFiles[Pair(ft.publicKey, ft.fileNumber)] = OutgoingFile(file, mutableListOf())
    }

    // TODO(robinlinden): An error when sending the last chunk in a transfer will stall it.
    fun sendChunk(pk: String, fileNo: Int, pos: Long, length: Int) {
        val ft = fileTransfers.find { it.publicKey == pk && it.fileNumber == fileNo }
        if (ft == null) {
            Log.e(TAG, "Received request for chunk of unknown ft ${pk.fingerprint()} $fileNo")
            tox.stopFileTransfer(PublicKey(pk), fileNo)
            return
        }

        if (length == 0) {
            Log.i(TAG, "Finished outgoing ft ${pk.fingerprint()} $fileNo ${ft.isComplete()}")
            setProgress(ft, ft.fileSize)
            fileTransfers.remove(ft)
            outgoingFiles.remove(Pair(pk, fileNo))
            deleteOutgoingCacheFile(ft.destination.toUri())
            releaseFilePermission(ft.destination.toUri())
            return
        }

        val file = outgoingFiles[Pair(pk, fileNo)] ?: return

        while (file.unsentChunks.isNotEmpty()) {
            val chunk = file.unsentChunks.first()
            Log.i(TAG, "Resending chunk @ ${chunk.pos} to ${pk.fingerprint()} ($fileNo)}")
            if (tox.sendFileChunk(PublicKey(pk), fileNo, chunk.pos, chunk.data).isFailure) {
                scheduleChunkRetry(pk, fileNo)
                return
            }
            setProgress(ft, max(ft.transferredBytes(), chunk.pos + chunk.data.size))
            file.unsentChunks.removeAt(0)
        }

        val bytes = readChunk(file.uri, pos, length) ?: run {
            reject(ft)
            return
        }

        if (tox.sendFileChunk(PublicKey(pk), fileNo, pos, bytes).isFailure) {
            file.queueUnsent(Chunk(pos, bytes))
            scheduleChunkRetry(pk, fileNo)
            return
        }

        setProgress(ft, max(ft.transferredBytes(), pos + bytes.size))
    }

    private fun OutgoingFile.queueUnsent(chunk: Chunk) {
        if (unsentChunks.none { it.pos == chunk.pos }) {
            unsentChunks.add(chunk)
        }
    }

    private fun scheduleChunkRetry(pk: String, fileNo: Int) {
        val key = Pair(pk, fileNo)
        val file = outgoingFiles[key] ?: return
        if (file.retrying) return

        file.retrying = true
        scope.launch {
            while (true) {
                delay(CHUNK_RETRY_DELAY_MS)
                val current = outgoingFiles[key] ?: break
                val ft = fileTransfers.find { it.publicKey == pk && it.fileNumber == fileNo } ?: break
                val chunk = current.unsentChunks.firstOrNull() ?: break

                if (tox.sendFileChunk(PublicKey(pk), fileNo, chunk.pos, chunk.data).isFailure) {
                    continue
                }

                setProgress(ft, max(ft.transferredBytes(), chunk.pos + chunk.data.size))
                current.unsentChunks.removeAt(0)
            }
            outgoingFiles[key]?.retrying = false
        }
    }

    fun setStatus(pk: String, fileNo: Int, fileStatus: ToxFileControl) {
        Log.e(TAG, "Setting ${pk.fingerprint()} $fileNo to status $fileStatus")
        val ft = fileTransfers.find { it.publicKey == pk && it.fileNumber == fileNo }
        if (ft == null) {
            Log.e(TAG, "Attempted to set status for unknown ft ${pk.fingerprint()} $fileNo")
            return
        }

        if (fileStatus == ToxFileControl.RESUME && ft.progress == FT_NOT_STARTED) {
            ft.progress = FT_STARTED
        } else if (fileStatus == ToxFileControl.CANCEL) {
            Log.i(TAG, "Friend canceled ft ${pk.fingerprint()} $fileNo")
            reject(ft)
        }
    }

    suspend fun deleteAll(publicKey: PublicKey) {
        fileTransferRepository.get(publicKey.string()).take(1).collect { fts ->
            fts.kForEach { delete(it.id) }
        }
    }

    suspend fun delete(id: Int) {
        fileTransfers.find { it.id == id }?.let {
            if (it.isStarted() && !it.isComplete()) {
                reject(it)
            }
            fileTransfers.remove(it)
        }
        fileTransferRepository.get(id).take(1).collect {
            if (!it.outgoing && it.destination.startsWith("file://")) {
                File(it.destination.toUri().path!!).delete()
            }
            fileTransferRepository.delete(id)
        }
    }

    fun get(id: Int) = fileTransferRepository.get(id)

    private fun releaseFilePermission(uri: Uri) {
        if (fileTransfers.firstOrNull { it.destination == uri.toString() } != null) {
            return
        }

        Log.i(TAG, "Releasing read permission for $uri")
        runCatching {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long>? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(uri.path ?: return null)
            return Pair(file.name, file.length())
        }

        return resolver.query(uri, null, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            val fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            Pair(name, fileSize)
        }
    }

    private fun deleteOutgoingCacheFile(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return
        val file = File(uri.path ?: return)
        runCatching {
            val canonicalFile = file.canonicalFile
            if (canonicalFile.path.startsWith(outgoingCacheRoot.path)) {
                canonicalFile.delete()
            }
        }.onFailure {
            Log.e(TAG, "Error deleting outgoing cache file $uri\n$it")
        }
    }

    private fun createThumbnail(ft: FileTransfer, source: Uri): Uri? {
        val destination = storage.thumbnailFor(ft)
        return try {
            val bitmap = when (storage.fileClass(ft.fileName)) {
                FileClass.Image -> imageThumbnail(source)
                FileClass.Video -> videoThumbnail(source)
                FileClass.Recorder -> null
                FileClass.Document -> documentThumbnail(ft.fileName)
            } ?: return null

            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it)
            }
            bitmap.recycle()
            Uri.fromFile(destination)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create thumbnail for ${ft.fileName}\n$e")
            null
        }
    }

    private fun imageThumbnail(uri: Uri): Bitmap? = resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input)?.scaledThumbnail()
    }

    private fun videoThumbnail(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.frameAtTime?.scaledThumbnail()
        } finally {
            retriever.release()
        }
    }

    private fun documentThumbnail(fileName: String): Bitmap {
        val bitmap = Bitmap.createBitmap(THUMB_SIZE, THUMB_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(38, 50, 56))

        paint.color = Color.rgb(96, 125, 139)
        canvas.drawRect(32f, 24f, 128f, 136f, paint)
        paint.color = Color.rgb(144, 164, 174)
        canvas.drawRect(96f, 24f, 128f, 56f, paint)

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 24f
        paint.isFakeBoldText = true
        val ext = fileName.substringAfterLast('.', "?").take(4).uppercase(Locale.US)
        canvas.drawText(ext, THUMB_SIZE / 2f, 100f, paint)
        return bitmap
    }

    private fun Bitmap.scaledThumbnail(): Bitmap {
        val scale = minOf(THUMB_SIZE.toFloat() / width, THUMB_SIZE.toFloat() / height)
        val scaledWidth = max(1, (width * scale).toInt())
        val scaledHeight = max(1, (height * scale).toInt())
        return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true).also {
            if (it != this) recycle()
        }
    }

    private fun canRead(uri: Uri) = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        Log.e(TAG, "Unable to read outgoing file $uri\n$e")
        false
    }

    private fun readChunk(uri: Uri, pos: Long, length: Int): ByteArray? = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            FileInputStream(afd.fileDescriptor).use { stream ->
                stream.channel.position(afd.startOffset + pos)
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = stream.read(bytes, offset, length - offset)
                    if (read < 0) break
                    offset += read
                }
                if (offset == length) bytes else bytes.copyOf(offset)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error reading chunk $pos:$length from $uri\n$e")
        null
    }

    private enum class FileClass {
        Image,
        Video,
        Recorder,
        Document,
    }

    private inner class AtoxStorage {
        private val imageDir get() = SkyToxPublicFolders.imageDir
        private val videoDir get() = SkyToxPublicFolders.videoDir
        private val recorderDir get() = SkyToxPublicFolders.recorderDir
        private val documentDir get() = SkyToxPublicFolders.documentDir
        private val thumbDir get() = SkyToxPublicFolders.thumbDir

        fun ensureDirectories() {
            SkyToxPublicFolders.ensureDirectories()
        }

        fun destinationFor(ft: FileTransfer): Uri {
            ensureDirectories()
            val dir = when (fileClass(ft.fileName)) {
                FileClass.Image -> imageDir
                FileClass.Video -> videoDir
                FileClass.Recorder -> recorderDir
                FileClass.Document -> documentDir
            }
            return Uri.fromFile(uniqueFile(dir, ft.fileName))
        }

        fun thumbnailFor(ft: FileTransfer): File {
            ensureDirectories()
            return randomThumbFile()
        }

        fun voiceMessageFile(): File {
            ensureDirectories()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            return uniqueFile(recorderDir, "voice-message-$stamp.m4a")
        }

        fun fileClass(fileName: String): FileClass {
            val mime = guessMime(fileName)
            return when {
                fileName.startsWith("voice-message-", ignoreCase = true) -> FileClass.Recorder
                mime.startsWith("image/") -> FileClass.Image
                mime.startsWith("video/") -> FileClass.Video
                mime.startsWith("audio/") -> FileClass.Recorder
                else -> FileClass.Document
            }
        }

        private fun uniqueFile(dir: File, fileName: String): File {
            val sanitized = fileName.replace(Regex("""[\\/:*?"<>|]"""), "_").ifEmpty { "file" }
            val base = sanitized.substringBeforeLast('.', sanitized)
            val ext = sanitized.substringAfterLast('.', "")
            var candidate = File(dir, sanitized)
            var i = 1
            while (candidate.exists()) {
                val suffix = if (ext.isEmpty()) " ($i)" else " ($i).$ext"
                candidate = File(dir, "$base$suffix")
                i += 1
            }
            return candidate
        }

        private fun randomThumbFile(): File {
            var candidate: File
            do {
                candidate = File(thumbDir, "${randomName(16)}.jpg")
            } while (candidate.exists())
            return candidate
        }

        private fun randomName(length: Int): String {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            return buildString(length) {
                repeat(length) {
                    append(chars[Random.nextInt(chars.length)])
                }
            }
        }
    }

    private fun guessMime(fileName: String) =
        URLConnection.guessContentTypeFromName(fileName) ?: when (fileName.substringAfterLast('.', "").lowercase()) {
            "m4a", "aac", "mp3", "ogg", "opus", "wav" -> "audio/*"
            "mp4", "mkv", "webm", "avi", "mov" -> "video/*"
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "image/*"
            else -> "application/octet-stream"
        }

    private fun wipAvatar(name: String): File = File(File(context.filesDir, "avatar"), "$name.wip")
    private fun avatar(name: String): File = File(File(context.filesDir, "avatar"), name)
}
