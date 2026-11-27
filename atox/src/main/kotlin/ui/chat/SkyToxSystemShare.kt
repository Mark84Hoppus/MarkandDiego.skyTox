// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.net.URLConnection
import ltd.evilcorp.atox.BuildConfig
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.ui.location.SkyToxLocationProtocol
import ltd.evilcorp.core.vo.FileTransfer
import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.isComplete

object SkyToxSystemShare {
    fun launchFiles(context: Context, files: List<File>): Boolean {
        val uris = files.filter(File::isFile).mapNotNull { file ->
            runCatching { FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file) }.getOrNull()
        }.distinct()
        if (uris.isEmpty()) return false
        return launchUris(context, uris, emptyList())
    }

    fun launch(context: Context, messages: List<Message>, transfers: List<FileTransfer>): Boolean {
        val selected = messages.sortedBy { it.id }
        val texts = selected
            .filter { it.type != MessageType.FileTransfer && !SkyToxEncryptedMessage.isEncrypted(it.message) }
            .map { message ->
                SkyToxLocationProtocol.decode(message.message)?.let { location ->
                    "geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}"
                } ?: message.message
            }
        val uris = selected.mapNotNull { message ->
            if (message.type != MessageType.FileTransfer) return@mapNotNull null
            val transfer = transfers.firstOrNull { it.id == message.correlationId } ?: return@mapNotNull null
            shareableUri(context, transfer)
        }.distinct()
        return launchUris(context, uris, texts)
    }

    private fun launchUris(context: Context, uris: List<Uri>, texts: List<String>): Boolean {
        if (texts.isEmpty() && uris.isEmpty()) return false
        val intent = when {
            uris.isEmpty() -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, texts.joinToString("\n\n"))
            }
            uris.size == 1 -> Intent(Intent.ACTION_SEND).apply {
                type = mimeType(context, uris.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
                if (texts.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, texts.joinToString("\n\n"))
            }
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = aggregateMime(context, uris)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                if (texts.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, texts.joinToString("\n\n"))
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.isNotEmpty()) {
                clipData = ClipData.newUri(context.contentResolver, "skyTox", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
            }
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via_system)))
        return true
    }

    private fun shareableUri(context: Context, transfer: FileTransfer): Uri? {
        if (!transfer.isComplete() || transfer.destination.isBlank()) return null
        val source = transfer.destination.toUri()
        if (source.scheme == "content") return source
        val file = when (source.scheme) {
            "file" -> source.path?.let(::File)
            null -> File(transfer.destination)
            else -> null
        } ?: return null
        if (!file.isFile) return null
        return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }

    private fun mimeType(context: Context, uri: Uri): String =
        context.contentResolver.getType(uri)
            ?: URLConnection.guessContentTypeFromName(uri.lastPathSegment.orEmpty())
            ?: "application/octet-stream"

    private fun aggregateMime(context: Context, uris: List<Uri>): String {
        val types = uris.map { mimeType(context, it) }.distinct()
        if (types.size == 1) return types.first()
        val families = types.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1) "${families.first()}/*" else "*/*"
    }
}
