// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ContextThemeWrapper
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.math.MathUtils.lerp
import java.io.File
import java.net.URLConnection
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ltd.evilcorp.atox.BuildConfig
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.FragmentChatBinding
import ltd.evilcorp.atox.hasPermission
import ltd.evilcorp.atox.requireStringArg
import ltd.evilcorp.atox.truncated
import ltd.evilcorp.atox.ui.BaseFragment
import ltd.evilcorp.atox.vmFactory
import ltd.evilcorp.core.vo.ConnectionStatus
import ltd.evilcorp.core.vo.FileTransfer
import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.core.vo.isComplete
import ltd.evilcorp.domain.feature.CallState

private const val TAG = "ChatFragment"
const val CONTACT_PUBLIC_KEY = "publicKey"
const val FOCUS_ON_MESSAGE_BOX = "focusOnMessageBox"
private const val MAX_CONFIRM_DELETE_STRING_LENGTH = 20
private const val VOICE_MESSAGE_BIT_RATE = 64_000
private const val MIN_VOICE_MESSAGE_DURATION_MS = 500
private const val PERMISSION_RECORD_AUDIO = Manifest.permission.RECORD_AUDIO

class OpenMultiplePersistableDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input)
        .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

class ChatFragment : BaseFragment<FragmentChatBinding>(FragmentChatBinding::inflate) {
    private val viewModel: ChatViewModel by viewModels { vmFactory }

    private lateinit var contactPubKey: String
    private var contactName = ""
    private var selectedFt: Int = Int.MIN_VALUE
    private var fts: List<FileTransfer> = listOf()
    private var voiceRecorder: MediaRecorder? = null
    private var voiceRecordingFile: File? = null
    private var voiceRecordingStartedAt = 0L
    private var audioPlayer: MediaPlayer? = null
    private var playingAudioId: Int = Int.MIN_VALUE

    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(requireContext(), getString(R.string.call_mic_permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { dest ->
            if (dest == null) return@registerForActivityResult
            viewModel.backupHistory(contactPubKey, dest)
        }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
            if (source == null) return@registerForActivityResult
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_history)
                .setMessage(R.string.import_text_chat_confirm)
                .setPositiveButton(R.string.continue_import) { _, _ ->
                    viewModel.importHistory(contactPubKey, source)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    private val exportFtLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { dest ->
        if (dest == null) return@registerForActivityResult
        viewModel.exportFt(selectedFt, dest)
    }

    private val attachFilesLauncher =
        registerForActivityResult(OpenMultiplePersistableDocuments()) { files ->
            viewModel.setActiveChat(PublicKey(contactPubKey))
            for (file in files) {
                activity?.contentResolver?.takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                viewModel.createFt(file)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?): Unit = binding.run {
        contactPubKey = requireStringArg(CONTACT_PUBLIC_KEY)
        viewModel.setActiveChat(PublicKey(contactPubKey))

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            appBarLayout.updatePadding(left = insets.left, top = insets.top, right = insets.right)
            bottomBar.updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
            messages.updatePadding(left = insets.left, right = insets.right)
            compat
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                var startBottom = 0
                var endBottom = 0

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    val pos = IntArray(2)
                    outgoingMessage.getLocationInWindow(pos)
                    startBottom = pos[1]
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat,
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    val pos = IntArray(2)
                    outgoingMessage.getLocationInWindow(pos)
                    endBottom = pos[1]
                    val offset = (startBottom - endBottom).toFloat()
                    messages.translationY = offset
                    bottomBar.translationY = offset

                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    val animation = runningAnimations[0]
                    val offset = lerp((startBottom - endBottom).toFloat(), 0f, animation.interpolatedFraction)
                    messages.translationY = offset
                    bottomBar.translationY = offset
                    return insets
                }
            },
        )

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        toolbar.inflateMenu(R.menu.chat_options_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.backup_history -> {
                    exportBackupLauncher.launch(
                        "skytox-user-chat_${contactPubKey}_${
                            SimpleDateFormat(
                                """yyyy-MM-dd'T'HH-mm-ss""",
                                Locale.getDefault(),
                            ).format(Date())
                        }.json",
                    )
                    true
                }
                R.id.import_history -> {
                    importBackupLauncher.launch(arrayOf("application/json"))
                    true
                }
                R.id.clear_history -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.clear_history)
                        .setMessage(getString(R.string.clear_history_confirm, contactName))
                        .setPositiveButton(R.string.clear_history) { _, _ ->
                            Toast.makeText(requireContext(), R.string.clear_history_cleared, Toast.LENGTH_LONG).show()
                            viewModel.clearHistory()
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                    true
                }
                R.id.call -> {
                    if (!viewModel.callingNeedsConfirmation()) {
                        navigateToCallScreen()
                        return@setOnMenuItemClickListener true
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.call_confirm)
                        .setPositiveButton(R.string.call) { _, _ ->
                            navigateToCallScreen()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                else -> super.onOptionsItemSelected(item)
            }
        }

        contactHeader.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            findNavController().navigate(
                R.id.action_chatFragment_to_contactProfileFragment,
                bundleOf(CONTACT_PUBLIC_KEY to contactPubKey),
            )
        }

        viewModel.contact.observe(viewLifecycleOwner) {
            if (it == null) {
                Log.e(TAG, "Contact $contactPubKey does not exist, leaving chat")
                findNavController().popBackStack()
                return@observe
            }
            it.name = it.name.ifEmpty { getString(R.string.contact_default_name) }

            contactName = it.name
            ongoingCall.info.text = getString(R.string.in_call_with, contactName)
            viewModel.contactOnline = it.connectionStatus != ConnectionStatus.None

            title.text = contactName
            // TODO(robinlinden): Replace last message with last seen.
            subtitle.text = when {
                it.typing -> getString(R.string.contact_typing)
                it.lastMessage == 0L -> getString(R.string.never)
                else -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it.lastMessage)
            }.lowercase(Locale.getDefault())

            avatarImageView.setFrom(it)

            if (it.draftMessage.isNotEmpty() && outgoingMessage.text.isEmpty()) {
                outgoingMessage.setText(it.draftMessage)
                viewModel.clearDraft()
            }

            updateActions()
        }

        viewModel.callState.observe(viewLifecycleOwner) { state ->
            when (state) {
                CallAvailability.Unavailable -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.call)
                    toolbar.menu.findItem(R.id.call).isEnabled = false
                }
                CallAvailability.Available -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.call)
                    toolbar.menu.findItem(R.id.call).isEnabled = true
                }
                CallAvailability.Active -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.ongoing_call)
                    toolbar.menu.findItem(R.id.call).isEnabled = true
                }
                null -> {}
            }
        }

        viewModel.ongoingCall.observe(viewLifecycleOwner) {
            if (it is CallState.InCall && it.publicKey.string() == contactPubKey) {
                ongoingCall.container.visibility = View.VISIBLE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ongoingCall.duration.visibility = View.VISIBLE
                    ongoingCall.duration.base = it.startTime
                    ongoingCall.duration.isCountDown = false
                    ongoingCall.duration.start()
                } else {
                    ongoingCall.duration.visibility = View.GONE
                }
            } else {
                ongoingCall.container.visibility = View.GONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ongoingCall.duration.stop()
                }
            }
        }

        ongoingCall.endCall.setOnClickListener { viewModel.onEndCall() }
        ongoingCall.info.setOnClickListener { navigateToCallScreen() }

        val adapter = ChatAdapter(layoutInflater, resources)
        messages.adapter = adapter
        viewModel.messages.observe(viewLifecycleOwner) {
            adapter.messages = it
            adapter.notifyDataSetChanged()
        }

        viewModel.fileTransfers.observe(viewLifecycleOwner) {
            fts = it
            adapter.fileTransfers = it
            adapter.notifyDataSetChanged()
        }

        messages.setOnItemClickListener { _, view, position, _ ->
            when (view.id) {
                R.id.accept -> viewModel.acceptFt(adapter.messages[position].correlationId)
                R.id.reject, R.id.cancel -> viewModel.rejectFt(adapter.messages[position].correlationId)
                R.id.audioPlay -> toggleAudioPlayback(adapter.messages[position].correlationId)
                R.id.fileTransfer -> {
                    val id = adapter.messages[position].correlationId
                    val ft = adapter.fileTransfers.find { it.id == id } ?: return@setOnItemClickListener
                    if (ft.outgoing) return@setOnItemClickListener
                    if (!ft.isComplete()) return@setOnItemClickListener
                    if (!ft.destination.startsWith("file://")) return@setOnItemClickListener
                    val file = File(ft.destination.toUri().path!!)
                    if (!file.exists()) return@setOnItemClickListener
                    val contentType = URLConnection.guessContentTypeFromName(ft.fileName)
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        file,
                    )
                    val shareIntent = Intent(Intent.ACTION_VIEW).apply {
                        putExtra(Intent.EXTRA_TITLE, ft.fileName)
                        setDataAndType(uri, contentType)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    try {
                        WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
                        startActivity(Intent.createChooser(shareIntent, null))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.mimetype_handler_not_found, contentType),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        messages.setOnItemLongClickListener { _, view, position, _ ->
            view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0))
            showMessageContextMenu(view, adapter.messages[position])
            true
        }

        registerForContextMenu(send)
        send.setOnClickListener { send(MessageType.Normal) }

        attach.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            attachFilesLauncher.launch(arrayOf("*/*"))
        }

        voiceMessage.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
                    startVoiceRecording()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopVoiceRecording(send = true)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopVoiceRecording(send = false)
                    true
                }
                else -> false
            }
        }

        outgoingMessage.doAfterTextChanged {
            viewModel.setTyping(outgoingMessage.text.isNotEmpty())
            updateActions()
        }

        updateActions()

        if (arguments?.getBoolean(FOCUS_ON_MESSAGE_BOX) == true) {
            outgoingMessage.requestFocus()
        }
    }

    override fun onPause() {
        stopVoiceRecording(send = false)
        stopAudioPlayback()
        viewModel.setDraft(binding.outgoingMessage.text.toString())
        viewModel.setActiveChat(PublicKey(""))
        super.onPause()
    }

    override fun onResume() = binding.run {
        viewModel.setActiveChat(PublicKey(contactPubKey))
        viewModel.setTyping(outgoingMessage.text.isNotEmpty())
        super.onResume()
    }

    private fun showMessageContextMenu(anchor: View, message: Message) {
        val popupContext = ContextThemeWrapper(requireContext(), R.style.ChatContextPopup)
        val popup = PopupMenu(popupContext, anchor)
        val inflater = popup.menuInflater
        when (message.type) {
            MessageType.Action, MessageType.Normal -> inflater.inflate(R.menu.chat_message_context_menu, popup.menu)
            MessageType.FileTransfer -> {
                inflater.inflate(R.menu.ft_message_context_menu, popup.menu)
                val ft = fts.find { it.id == message.correlationId } ?: return
                if (!ft.isComplete() || ft.outgoing || !ft.destination.startsWith("file://")) {
                    popup.menu.findItem(R.id.export).isVisible = false
                }
            }
        }

        popup.setOnMenuItemClickListener { handleMessageContextItem(it, message) }
        popup.show()
    }

    private fun handleMessageContextItem(item: MenuItem, message: Message): Boolean = when (item.itemId) {
        R.id.copy -> {
            val clipboard = requireActivity().getSystemService<ClipboardManager>()!!
            clipboard.setPrimaryClip(ClipData.newPlainText(getText(R.string.message), message.message))
            Toast.makeText(requireContext(), getText(R.string.copied), Toast.LENGTH_SHORT).show()
            true
        }
        R.id.delete -> {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_message)
                .setMessage(
                    getString(
                        R.string.delete_message_confirm,
                        message.message.truncated(MAX_CONFIRM_DELETE_STRING_LENGTH),
                    ),
                )
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewModel.delete(message)
                }
                .setNegativeButton(android.R.string.cancel, null).show()
            true
        }
        R.id.export -> {
            selectedFt = message.correlationId
            exportFtLauncher.launch(message.message)
            true
        }
        else -> false
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) = binding.run {
        super.onCreateContextMenu(menu, v, menuInfo)
        v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0))
        val inflater = requireActivity().menuInflater
        when (v.id) {
            R.id.messages -> {
                val info = menuInfo as AdapterView.AdapterContextMenuInfo
                val message = messages.adapter.getItem(info.position) as Message
                when (message.type) {
                    MessageType.Action, MessageType.Normal -> inflater.inflate(
                        R.menu.chat_message_context_menu,
                        menu,
                    )
                    MessageType.FileTransfer -> {
                        inflater.inflate(R.menu.ft_message_context_menu, menu)
                        val ft = fts.find { it.id == message.correlationId } ?: return
                        if (!ft.isComplete() || ft.outgoing || !ft.destination.startsWith("file://")) {
                            menu.findItem(R.id.export).isVisible = false
                        }
                    }
                }
            }
            R.id.send -> requireActivity().menuInflater.inflate(R.menu.chat_send_long_press_menu, menu)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean = binding.run {
        return when (item.itemId) {
            R.id.copy -> {
                val info = item.menuInfo as AdapterView.AdapterContextMenuInfo
                val clipboard = requireActivity().getSystemService<ClipboardManager>()!!
                val message = messages.adapter.getItem(info.position) as Message
                clipboard.setPrimaryClip(ClipData.newPlainText(getText(R.string.message), message.message))

                Toast.makeText(requireContext(), getText(R.string.copied), Toast.LENGTH_SHORT).show()
                true
            }
            R.id.delete -> {
                val info = item.menuInfo as AdapterView.AdapterContextMenuInfo
                val message = messages.adapter.getItem(info.position) as Message

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.delete_message)
                    .setMessage(
                        getString(
                            R.string.delete_message_confirm,
                            message.message.truncated(MAX_CONFIRM_DELETE_STRING_LENGTH),
                        ),
                    )
                    .setPositiveButton(R.string.delete) { _, _ ->
                        viewModel.delete(message)
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
            R.id.send_action -> {
                send(MessageType.Action)
                true
            }
            R.id.export -> {
                val info = item.menuInfo as AdapterView.AdapterContextMenuInfo
                val message = messages.adapter.getItem(info.position) as Message
                selectedFt = message.correlationId
                exportFtLauncher.launch(message.message)
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    private fun send(type: MessageType) = binding.run {
        viewModel.clearDraft()
        viewModel.send(outgoingMessage.text.toString(), type)
        outgoingMessage.text.clear()
    }

    private fun startVoiceRecording() {
        if (voiceRecorder != null) return
        if (!viewModel.contactOnline) return
        if (!requireContext().hasPermission(PERMISSION_RECORD_AUDIO)) {
            requestRecordAudioLauncher.launch(PERMISSION_RECORD_AUDIO)
            return
        }

        val file = viewModel.voiceMessageFile()

        try {
            @Suppress("DEPRECATION")
            voiceRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(VOICE_MESSAGE_BIT_RATE)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            voiceRecordingFile = file
            voiceRecordingStartedAt = System.currentTimeMillis()
            updateActions()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start voice recording\n$e")
            voiceRecorder?.release()
            voiceRecorder = null
            voiceRecordingFile = null
            file.delete()
            Toast.makeText(requireContext(), R.string.voice_record_failed, Toast.LENGTH_LONG).show()
            updateActions()
        }
    }

    private fun stopVoiceRecording(send: Boolean) {
        val recorder = voiceRecorder ?: return
        voiceRecorder = null

        val file = voiceRecordingFile
        voiceRecordingFile = null
        val duration = System.currentTimeMillis() - voiceRecordingStartedAt
        voiceRecordingStartedAt = 0L

        val stopped = runCatching { recorder.stop() }
            .onFailure { Log.e(TAG, "Unable to stop voice recording\n$it") }
            .isSuccess
        recorder.release()

        if (send && stopped && duration >= MIN_VOICE_MESSAGE_DURATION_MS && file != null && file.length() > 0L &&
            viewModel.contactOnline
        ) {
            viewModel.createFt(file.toUri())
        } else {
            file?.delete()
        }

        updateActions()
    }

    private fun toggleAudioPlayback(id: Int) {
        if (playingAudioId == id) {
            stopAudioPlayback()
            return
        }

        stopAudioPlayback()
        val ft = fts.find { it.id == id } ?: return
        if (!ft.isComplete()) return

        audioPlayer = MediaPlayer().apply {
            try {
                setDataSource(requireContext(), ft.destination.toUri())
                setOnCompletionListener { stopAudioPlayback() }
                prepare()
                start()
                playingAudioId = id
                (binding.messages.adapter as? ChatAdapter)?.playingAudioId = playingAudioId
                (binding.messages.adapter as? ChatAdapter)?.notifyDataSetChanged()
            } catch (e: Exception) {
                Log.e(TAG, "Unable to play audio message\n$e")
                release()
                audioPlayer = null
                playingAudioId = Int.MIN_VALUE
                Toast.makeText(
                    requireContext(),
                    getString(R.string.mimetype_handler_not_found, "audio/*"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun stopAudioPlayback() {
        audioPlayer?.release()
        audioPlayer = null
        playingAudioId = Int.MIN_VALUE
        (binding.messages.adapter as? ChatAdapter)?.playingAudioId = playingAudioId
        (binding.messages.adapter as? ChatAdapter)?.notifyDataSetChanged()
    }

    private fun updateActions() = binding.run {
        send.visibility = if (outgoingMessage.text.isEmpty()) View.GONE else View.VISIBLE
        attach.visibility = if (send.isVisible) View.GONE else View.VISIBLE
        voiceMessage.visibility = if (send.isVisible) View.GONE else View.VISIBLE
        attach.isEnabled = viewModel.contactOnline
        voiceMessage.isEnabled = viewModel.contactOnline
        attach.setColorFilter(
            ContextCompat.getColor(
                requireContext(),
                if (attach.isEnabled) android.R.color.white else android.R.color.darker_gray,
            ),
        )
        voiceMessage.setColorFilter(
            ContextCompat.getColor(
                requireContext(),
                when {
                    voiceRecorder != null -> android.R.color.holo_red_light
                    voiceMessage.isEnabled -> android.R.color.white
                    else -> android.R.color.darker_gray
                },
            ),
        )
    }

    private fun navigateToCallScreen() {
        view?.let { WindowInsetsControllerCompat(requireActivity().window, it).hide(WindowInsetsCompat.Type.ime()) }
        findNavController().navigate(
            R.id.action_chatFragment_to_callFragment,
            bundleOf(CONTACT_PUBLIC_KEY to contactPubKey),
        )
    }
}
