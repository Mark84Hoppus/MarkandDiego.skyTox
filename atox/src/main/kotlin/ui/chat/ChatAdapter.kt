// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.content.res.Resources
import android.graphics.Color
import android.text.format.Formatter
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.net.toUri
import com.squareup.picasso.Picasso
import java.io.File
import java.net.URLConnection
import java.text.DateFormat
import java.util.Locale
import kotlin.math.roundToInt
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.ui.markdown.SkyToxMarkdown
import ltd.evilcorp.core.vo.FileTransfer
import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.Sender
import ltd.evilcorp.core.vo.isComplete
import ltd.evilcorp.core.vo.isInterrupted
import ltd.evilcorp.core.vo.isRejected
import ltd.evilcorp.core.vo.isStarted
import ltd.evilcorp.core.vo.transferredBytes
import ltd.evilcorp.domain.feature.chatmarkers.SkyToxChatMarkers

private const val TAG = "ChatAdapter"
private const val IMAGE_TO_SCREEN_RATIO = 0.5
private const val DOCUMENT_THUMB_SIZE_DP = 96
private const val SELECTED_MESSAGE_COLOR = 0x55D81B60

private fun FileTransfer.isAudio() = try {
    URLConnection.guessContentTypeFromName(fileName).startsWith("audio/")
} catch (e: Exception) {
    Log.e(TAG, e.toString())
    fileName.endsWith(".m4a", ignoreCase = true)
}

private fun FileTransfer.hasThumbnail() = thumbnail.isNotEmpty()
private fun FileTransfer.thumbnailFileExists() =
    thumbnail.startsWith("file://") && File(thumbnail.toUri().path ?: "").exists()
private fun FileTransfer.isImageOrVideo() = try {
    val contentType = URLConnection.guessContentTypeFromName(fileName).orEmpty()
    contentType.startsWith("image/") || contentType.startsWith("video/")
} catch (_: Exception) {
    false
}

private fun inflateView(type: ChatItemType, inflater: LayoutInflater): View = inflater.inflate(
    when (type) {
        ChatItemType.SentMessage -> R.layout.chat_message_sent
        ChatItemType.ReceivedMessage -> R.layout.chat_message_received
        ChatItemType.SentAction -> R.layout.chat_action_sent
        ChatItemType.ReceivedAction -> R.layout.chat_action_received
        ChatItemType.SentFileTransfer, ChatItemType.ReceivedFileTransfer -> R.layout.chat_filetransfer
    },
    null,
    true,
)

private enum class ChatItemType {
    ReceivedMessage,
    SentMessage,
    ReceivedAction,
    SentAction,
    ReceivedFileTransfer,
    SentFileTransfer,
}

private val timeFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

private class MessageViewHolder(row: View) {
    val message: TextView = row.findViewById(R.id.message)
    val timestamp: TextView = row.findViewById(R.id.timestamp)
}

private class FileTransferViewHolder(row: View) {
    val container: RelativeLayout = row.findViewById(R.id.fileTransfer)
    val fileName: TextView = row.findViewById(R.id.fileName)
    val fileSize: TextView = row.findViewById(R.id.fileSize)
    val progress: ProgressBar = row.findViewById(R.id.progress)
    val state: TextView = row.findViewById(R.id.state)
    val timestamp: TextView = row.findViewById(R.id.timestamp)
    val acceptLayout: View = row.findViewById(R.id.acceptLayout)
    val accept: Button = row.findViewById(R.id.accept)
    val reject: Button = row.findViewById(R.id.reject)
    val cancelLayout: View = row.findViewById(R.id.cancelLayout)
    val cancel: Button = row.findViewById(R.id.cancel)
    val completedLayout: View = row.findViewById(R.id.completedLayout)
    val imagePreview: ImageView = row.findViewById(R.id.imagePreview)
    val audioPlay: ImageButton = row.findViewById(R.id.audioPlay)
}

class ChatAdapter(private val inflater: LayoutInflater, private val resources: Resources) : BaseAdapter() {
    var messages: List<Message> = listOf()
    var fileTransfers: List<FileTransfer> = listOf()
    var playingAudioId: Int = Int.MIN_VALUE
    var selectedMessageIds: Set<Long> = emptySet()

    override fun getCount(): Int = messages.size
    override fun getItem(position: Int): Any = messages[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = ChatItemType.entries.size
    override fun getItemViewType(position: Int): Int = with(messages[position]) {
        when (type) {
            MessageType.Normal -> when (sender) {
                Sender.Sent -> ChatItemType.SentMessage.ordinal
                Sender.Received -> ChatItemType.ReceivedMessage.ordinal
            }
            MessageType.Action -> when (sender) {
                Sender.Sent -> ChatItemType.SentAction.ordinal
                Sender.Received -> ChatItemType.ReceivedAction.ordinal
            }
            MessageType.FileTransfer -> when (sender) {
                Sender.Sent -> ChatItemType.SentFileTransfer.ordinal
                Sender.Received -> ChatItemType.ReceivedFileTransfer.ordinal
            }
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val type = ChatItemType.entries[getItemViewType(position)]) {
            ChatItemType.ReceivedMessage, ChatItemType.SentMessage,
            ChatItemType.ReceivedAction, ChatItemType.SentAction,
            -> {
                val message = messages[position]
                val view: View
                val vh: MessageViewHolder

                if (convertView != null) {
                    view = convertView
                    vh = view.tag as MessageViewHolder
                } else {
                    view = inflateView(type, inflater)
                    vh = MessageViewHolder(view)
                    view.tag = vh
                }

                val unsent = message.timestamp == 0L || SkyToxChatMarkers.isUndelivered(message)
                view.setBackgroundColor(if (message.id in selectedMessageIds) SELECTED_MESSAGE_COLOR else Color.TRANSPARENT)
                SkyToxMarkdown.render(vh.message, message.message)
                vh.timestamp.text = if (!unsent) {
                    timeFormatter.format(message.timestamp)
                } else {
                    resources.getText(R.string.sending)
                }

                vh.timestamp.visibility = if (position == messages.lastIndex || unsent) {
                    View.VISIBLE
                } else {
                    val next = messages[position + 1]
                    if (next.timestamp != 0L &&
                        next.sender == message.sender &&
                        next.timestamp - message.timestamp < 60_000
                    ) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }

                view
            }
            ChatItemType.ReceivedFileTransfer, ChatItemType.SentFileTransfer -> {
                val message = messages[position]
                val fileTransfer = fileTransfers.find { it.id == message.correlationId } ?: run {
                    Log.e(TAG, "Unable to find ft ${message.correlationId} for ${message.publicKey} required for view")
                    FileTransfer("", 0, 0, 0, "", message.sender == Sender.Sent)
                }

                val view: View
                val vh: FileTransferViewHolder

                if (convertView != null) {
                    view = convertView
                    vh = view.tag as FileTransferViewHolder
                } else {
                    view = inflateView(type, inflater)
                    vh = FileTransferViewHolder(view)
                    view.tag = vh
                }
                view.setBackgroundColor(if (message.id in selectedMessageIds) SELECTED_MESSAGE_COLOR else Color.TRANSPARENT)

                // TODO(robinlinden)
                // Updating the file transfer progress refreshes this so often that onClick-listeners never trigger
                // for some reason. Will revisit this once I've replaced the ListView with a RecyclerView.
                val touchListener = View.OnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        (parent as ListView).performItemClick(v, position, position.toLong())
                    }
                    false
                }
                vh.accept.setOnTouchListener(touchListener)
                vh.reject.setOnTouchListener(touchListener)
                vh.cancel.setOnTouchListener(touchListener)
                vh.audioPlay.setOnTouchListener(touchListener)

                if (fileTransfer.hasThumbnail() || fileTransfer.isComplete() && !fileTransfer.isAudio()) {
                    vh.completedLayout.visibility = View.VISIBLE
                    val targetWidth = if (fileTransfer.isImageOrVideo()) {
                        (Resources.getSystem().displayMetrics.widthPixels * IMAGE_TO_SCREEN_RATIO).roundToInt()
                    } else {
                        (DOCUMENT_THUMB_SIZE_DP * resources.displayMetrics.density).roundToInt()
                    }
                    vh.imagePreview.layoutParams = vh.imagePreview.layoutParams.apply {
                        width = targetWidth
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    if (fileTransfer.thumbnailFileExists()) {
                        Picasso.get()
                            .load(fileTransfer.thumbnail)
                            .resize(targetWidth, 0)
                            .into(vh.imagePreview)
                    } else {
                        vh.imagePreview.setImageResource(R.drawable.ic_missing_thumb)
                    }
                } else {
                    vh.completedLayout.visibility = View.GONE
                }

                vh.audioPlay.visibility =
                    if (fileTransfer.isAudio() && fileTransfer.isComplete()) View.VISIBLE else View.GONE
                vh.audioPlay.setImageResource(
                    if (fileTransfer.id == playingAudioId) R.drawable.ic_stop else R.drawable.ic_play,
                )

                vh.state.visibility = View.GONE
                if (fileTransfer.isRejected() || fileTransfer.isComplete() || fileTransfer.isInterrupted()) {
                    vh.acceptLayout.visibility = View.GONE
                    vh.cancelLayout.visibility = View.GONE
                    vh.progress.visibility = if (fileTransfer.isInterrupted()) View.VISIBLE else View.GONE
                    vh.state.visibility =
                        if (fileTransfer.hasThumbnail() && fileTransfer.isComplete()) View.GONE else View.VISIBLE
                } else if (!fileTransfer.isStarted()) {
                    if (fileTransfer.outgoing) {
                        vh.acceptLayout.visibility = View.GONE
                        vh.cancelLayout.visibility = View.VISIBLE
                        vh.progress.visibility = View.VISIBLE
                    } else {
                        vh.acceptLayout.visibility = View.VISIBLE
                        vh.cancelLayout.visibility = View.GONE
                        vh.progress.visibility = View.GONE
                    }
                } else {
                    vh.acceptLayout.visibility = View.GONE
                    vh.cancelLayout.visibility = View.VISIBLE
                    vh.progress.visibility = View.VISIBLE
                }

                vh.fileName.text = fileTransfer.fileName
                vh.fileSize.text = Formatter.formatFileSize(inflater.context, fileTransfer.fileSize)
                vh.progress.max = fileTransfer.fileSize.toInt()
                vh.progress.progress = fileTransfer.transferredBytes().toInt()
                val stateId = when {
                    fileTransfer.isRejected() -> R.string.cancelled
                    fileTransfer.isInterrupted() -> R.string.interrupted
                    else -> R.string.completed
                }
                vh.state.text = resources.getString(stateId).lowercase(Locale.getDefault())
                vh.timestamp.text = timeFormatter.format(message.timestamp)

                vh.timestamp.visibility = if (position == messages.lastIndex) {
                    View.VISIBLE
                } else {
                    val next = messages[position + 1]
                    if (next.sender == message.sender && next.timestamp - message.timestamp < 60_000) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }

                vh.container.gravity = if (fileTransfer.outgoing) {
                    Gravity.END
                } else {
                    Gravity.START
                }

                view
            }
        }
}
