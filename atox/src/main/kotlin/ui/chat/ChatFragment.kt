// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
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
import ltd.evilcorp.atox.ui.call.REQUEST_VIDEO_CALL
import ltd.evilcorp.atox.ui.texteditor.SkyToxTextEditorActivity
import ltd.evilcorp.atox.vmFactory
import ltd.evilcorp.core.vo.ConnectionStatus
import ltd.evilcorp.core.vo.Contact
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
private const val WAKE_CONTACT_COOLDOWN_MS = 30_000L
private const val AUTO_WAKE_INTERVAL_MS = 3 * 60 * 1000L
private const val AUTO_WAKE_OPEN_ATTEMPTS = 3

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
    private var contacts: List<Contact> = listOf()
    private val selectedMessageIds = mutableSetOf<Long>()
    private var voiceRecorder: MediaRecorder? = null
    private var voiceRecordingFile: File? = null
    private var voiceRecordingStartedAt = 0L
    private var audioPlayer: MediaPlayer? = null
    private var playingAudioId: Int = Int.MIN_VALUE
    private val voiceTimer = Handler(Looper.getMainLooper())
    private val audioProgressTimer = Handler(Looper.getMainLooper())
    private val wakeTimer = Handler(Looper.getMainLooper())
    private var startAfterMicPermission = false
    private var pendingEncryptedMessage: String? = null
    private val expandedTextMessageIds = mutableSetOf<Long>()
    private var lastWakeSignalAtMs = 0L
    private var autoWakeOpenAttempts = 0
    private var pendingMessageWakeActive = false

    companion object {
        private val autoWakeOpenAttemptsByContact = mutableMapOf<String, Int>()
        private val lastWakeSignalAtMsByContact = mutableMapOf<String, Long>()
    }

    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(requireContext(), getString(R.string.call_mic_permission_needed), Toast.LENGTH_LONG).show()
        } else if (startAfterMicPermission) {
            startAfterMicPermission = false
            startVoiceRecording()
        }
    }

    private val decryptMessageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val encrypted = pendingEncryptedMessage
            pendingEncryptedMessage = null
            if (result.resultCode != Activity.RESULT_OK || encrypted == null) {
                Toast.makeText(requireContext(), R.string.encrypted_message_read_failed, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            showDecryptedMessage(encrypted)
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

        toolbar.inflateMenu(R.menu.chat_options_menu)
        toolbar.menu.findItem(R.id.wake_contact)?.isVisible = false
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
                        navigateToCallScreen(requestVideo = false)
                        return@setOnMenuItemClickListener true
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.call_confirm)
                        .setPositiveButton(R.string.call) { _, _ ->
                            navigateToCallScreen(requestVideo = false)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                R.id.video_call -> {
                    if (!viewModel.callingNeedsConfirmation()) {
                        navigateToCallScreen(requestVideo = true)
                        return@setOnMenuItemClickListener true
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.call_confirm)
                        .setPositiveButton(R.string.video_call) { _, _ ->
                            navigateToCallScreen(requestVideo = true)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                R.id.wake_contact -> {
                    if (wakeContactReady()) {
                        lastWakeSignalAtMs = System.currentTimeMillis()
                        if (viewModel.wakeContact()) {
                            Toast.makeText(requireContext(), R.string.wake_signal_sent, Toast.LENGTH_SHORT).show()
                        }
                        updateWakeContactMenuItem()
                    }
                    true
                }
                R.id.send_encrypted_message -> {
                    if (!viewModel.appProtectionEnabled()) {
                        Toast.makeText(requireContext(), R.string.app_lock_not_set, Toast.LENGTH_SHORT).show()
                    } else {
                        showSendEncryptedDialog()
                    }
                    true
                }
                R.id.send_code_message -> {
                    showSendCodeDialog()
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
            updateWakeContactMenuItem()
            updateAutoWakeForContact()
        }

        viewModel.callState.observe(viewLifecycleOwner) { state ->
            when (state) {
                CallAvailability.Unavailable -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.call)
                    toolbar.menu.findItem(R.id.call).isEnabled = false
                    toolbar.menu.findItem(R.id.video_call).isEnabled = false
                    tintCallIcons(enabled = false)
                }
                CallAvailability.Available -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.call)
                    toolbar.menu.findItem(R.id.call).isEnabled = true
                    toolbar.menu.findItem(R.id.video_call).isEnabled = true
                    tintCallIcons(enabled = true)
                }
                CallAvailability.Active -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.ongoing_call)
                    toolbar.menu.findItem(R.id.call).isEnabled = true
                    toolbar.menu.findItem(R.id.video_call).isEnabled = false
                    tintCallIcons(enabled = true)
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
        updateSelectionUi(adapter)
        adapter.expandedTextMessageIds = expandedTextMessageIds.toSet()
        adapter.onLongTextToggle = { messageId ->
            if (messageId in expandedTextMessageIds) {
                expandedTextMessageIds.remove(messageId)
            } else {
                expandedTextMessageIds.add(messageId)
            }
            adapter.expandedTextMessageIds = expandedTextMessageIds.toSet()
            adapter.notifyDataSetChanged()
        }
        adapter.onCodePreviewClick = { message ->
            SkyToxCodeMessage.decode(message.message)?.let { showCodeViewerDialog(it) }
        }
        adapter.onFileTransferLongClick = { anchor, position ->
            showMessageContextMenu(anchor, adapter.messages[position], adapter)
        }
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
        viewModel.contacts.observe(viewLifecycleOwner) {
            contacts = it
        }

        messages.setOnItemClickListener { _, view, position, _ ->
            if (selectedMessageIds.isNotEmpty()) {
                toggleMessageSelection(adapter.messages[position], adapter)
                return@setOnItemClickListener
            }
            val message = adapter.messages[position]
            when (view.id) {
                R.id.accept -> viewModel.acceptFt(adapter.messages[position].correlationId)
                R.id.reject, R.id.cancel -> viewModel.rejectFt(adapter.messages[position].correlationId)
                R.id.audioPlay -> toggleAudioPlayback(adapter.messages[position].correlationId)
                else -> if (message.type == MessageType.FileTransfer) {
                    val id = message.correlationId
                    val ft = adapter.fileTransfers.find { it.id == id } ?: return@setOnItemClickListener
                    openFileTransfer(ft)
                }
            }
        }
        messages.setOnItemLongClickListener { _, view, position, _ ->
            view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0))
            showMessageContextMenu(view, adapter.messages[position], adapter)
            true
        }

        registerForContextMenu(send)
        send.setOnClickListener { send(MessageType.Normal) }

        attach.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            attachFilesLauncher.launch(arrayOf("*/*"))
        }

        voiceMessage.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            if (voiceRecorder == null) {
                startVoiceRecording()
            } else {
                stopVoiceRecording(send = true)
            }
        }
        voiceMessage.setOnLongClickListener {
            stopVoiceRecording(send = false)
            true
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
        wakeTimer.removeCallbacksAndMessages(null)
        viewModel.setDraft(binding.outgoingMessage.text.toString())
        viewModel.setActiveChat(PublicKey(""))
        super.onPause()
    }

    override fun onResume() = binding.run {
        viewModel.setActiveChat(PublicKey(contactPubKey))
        viewModel.setTyping(outgoingMessage.text.isNotEmpty())
        updateWakeContactMenuItem()
        super.onResume()
    }

    private fun showMessageContextMenu(anchor: View, message: Message, adapter: ChatAdapter) {
        val popupContext = ContextThemeWrapper(requireContext(), R.style.ChatContextPopup)
        val popup = PopupMenu(popupContext, anchor)
        val inflater = popup.menuInflater
        if (selectedMessageIds.isNotEmpty()) {
            inflater.inflate(R.menu.selected_message_context_menu, popup.menu)
        } else {
            when (message.type) {
                MessageType.Action, MessageType.Normal -> {
                    inflater.inflate(
                        if (SkyToxEncryptedMessage.isEncrypted(message.message)) {
                            R.menu.encrypted_message_context_menu
                        } else {
                            R.menu.chat_message_context_menu
                        },
                        popup.menu,
                    )
                }
                MessageType.FileTransfer -> {
                    inflater.inflate(R.menu.ft_message_context_menu, popup.menu)
                    val ft = fts.find { it.id == message.correlationId } ?: return
                    if (!ft.isComplete() || ft.outgoing || !ft.destination.startsWith("file://")) {
                        popup.menu.findItem(R.id.export).isVisible = false
                    }
                }
            }
        }

        popup.setOnMenuItemClickListener { handleMessageContextItem(it, message, adapter) }
        popup.show()
    }

    private fun handleMessageContextItem(item: MenuItem, message: Message, adapter: ChatAdapter): Boolean = when (item.itemId) {
        R.id.copy -> {
            val clipboard = requireActivity().getSystemService<ClipboardManager>()!!
            clipboard.setPrimaryClip(ClipData.newPlainText(getText(R.string.message), message.message))
            Toast.makeText(requireContext(), getText(R.string.copied), Toast.LENGTH_SHORT).show()
            true
        }
        R.id.copy_part -> {
            showCopyPartDialog(message.message)
            true
        }
        R.id.select_message -> {
            toggleMessageSelection(message, adapter)
            true
        }
        R.id.forward_message -> {
            if (SkyToxEncryptedMessage.isEncrypted(message.message)) {
                false
            } else {
                showForwardDialog(message.message)
                true
            }
        }
        R.id.decrypt_message -> {
            decryptEncryptedMessage(message.message)
            true
        }
        R.id.delete_selected -> {
            confirmDeleteSelected(adapter)
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

    private fun toggleMessageSelection(message: Message, adapter: ChatAdapter) {
        if (message.id in selectedMessageIds) {
            selectedMessageIds.remove(message.id)
        } else {
            selectedMessageIds.add(message.id)
        }
        adapter.selectedMessageIds = selectedMessageIds.toSet()
        adapter.notifyDataSetChanged()
        updateSelectionUi(adapter)
    }

    private fun clearSelection(adapter: ChatAdapter) {
        selectedMessageIds.clear()
        adapter.selectedMessageIds = emptySet()
        adapter.notifyDataSetChanged()
        updateSelectionUi(adapter)
    }

    private fun updateSelectionUi(adapter: ChatAdapter) = binding.run {
        val selecting = selectedMessageIds.isNotEmpty()
        toolbar.setNavigationIcon(if (selecting) R.drawable.ic_close else R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, requireView()).hide(WindowInsetsCompat.Type.ime())
            if (selecting) {
                clearSelection(adapter)
            } else {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun confirmDeleteSelected(adapter: ChatAdapter) {
        val selected = adapter.messages.filter { it.id in selectedMessageIds }
        if (selected.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_message)
            .setMessage(R.string.delete_selected_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.delete(selected)
                clearSelection(adapter)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCopyPartDialog(message: String) {
        val textView = TextView(requireContext()).apply {
            text = message
            setTextIsSelectable(true)
            setPadding(32, 16, 32, 0)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.copy_part)
            .setMessage(R.string.select_text_to_copy)
            .setView(textView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showForwardDialog(message: String) {
        val candidates = contacts.filter { it.publicKey != contactPubKey }
        if (candidates.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_contacts_to_forward, Toast.LENGTH_SHORT).show()
            return
        }
        val names = candidates.map { it.name.ifEmpty { getString(R.string.contact_default_name) } }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.forward_to)
            .setItems(names) { _, which ->
                viewModel.forwardText(candidates[which].publicKey, message)
            }
            .show()
    }

    private fun showSendEncryptedDialog() {
        val input = EditText(requireContext()).apply {
            minLines = 3
            setPadding(32, 16, 32, 0)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.send_encrypted_message)
            .setView(input)
            .setPositiveButton(R.string.send) { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    viewModel.send(SkyToxEncryptedMessage.encrypt(text), MessageType.Normal)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSendCodeDialog() {
        val input = EditText(requireContext()).apply {
            minLines = 6
            setSingleLine(false)
            setPadding(32, 16, 32, 0)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.send_code_message)
            .setView(input)
            .setPositiveButton(R.string.send) { _, _ ->
                val code = input.text.toString()
                if (code.isNotBlank()) {
                    viewModel.send(SkyToxCodeMessage.encode(code), MessageType.Normal)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCodeViewerDialog(code: String) {
        val textView = TextView(requireContext()).apply {
            text = code
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 16, 32, 0)
        }
        val scrollView = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            addView(
                textView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.code_message)
            .setView(scrollView)
            .setPositiveButton(R.string.copy_all) { _, _ ->
                val clipboard = requireActivity().getSystemService<ClipboardManager>()!!
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.code_message), code))
                Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun decryptEncryptedMessage(encrypted: String) {
        if (!viewModel.appProtectionEnabled()) {
            Toast.makeText(requireContext(), R.string.app_lock_not_set, Toast.LENGTH_SHORT).show()
            return
        }
        val keyguard = requireContext().getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardSecure != true) {
            Toast.makeText(requireContext(), R.string.app_lock_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.app_lock_unlock_title),
            "",
        )
        if (intent == null) {
            Toast.makeText(requireContext(), R.string.encrypted_message_read_failed, Toast.LENGTH_SHORT).show()
            return
        }
        pendingEncryptedMessage = encrypted
        decryptMessageLauncher.launch(intent)
    }

    private fun showDecryptedMessage(encrypted: String) {
        val plainText = runCatching { SkyToxEncryptedMessage.decrypt(encrypted) }.getOrNull()
        if (plainText == null) {
            Toast.makeText(requireContext(), R.string.encrypted_message_read_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val textView = TextView(requireContext()).apply {
            text = plainText
            setTextIsSelectable(true)
            setPadding(32, 16, 32, 0)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.decrypted_message)
            .setView(textView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
                        if (SkyToxEncryptedMessage.isEncrypted(message.message)) {
                            R.menu.encrypted_message_context_menu
                        } else {
                            R.menu.chat_message_context_menu
                        },
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
            R.id.decrypt_message -> {
                val info = item.menuInfo as AdapterView.AdapterContextMenuInfo
                val message = messages.adapter.getItem(info.position) as Message
                decryptEncryptedMessage(message.message)
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
        if (!viewModel.contactOnline) {
            startPendingMessageWakeLoop()
        }
        outgoingMessage.text.clear()
    }

    private fun openFileTransfer(ft: FileTransfer) {
        if (!ft.isComplete()) return

        val destination = ft.destination.takeIf { it.isNotBlank() }?.toUri() ?: run {
            Toast.makeText(requireContext(), R.string.file_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        val uri = when (destination.scheme) {
            "file" -> {
                val file = File(destination.path ?: "")
                if (!file.exists()) {
                    Toast.makeText(requireContext(), R.string.file_not_found, Toast.LENGTH_SHORT).show()
                    return
                }
                FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            }
            "content" -> destination
            else -> {
                Toast.makeText(requireContext(), R.string.file_not_found, Toast.LENGTH_SHORT).show()
                return
            }
        }

        val contentType = URLConnection.guessContentTypeFromName(ft.fileName) ?: "*/*"
        if (isEditableTextFile(ft.fileName, contentType)) {
            val intent = Intent(requireContext(), SkyToxTextEditorActivity::class.java).apply {
                putExtra("uri", uri.toString())
                putExtra("name", ft.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to open text editor for ft ${ft.id}\n$e")
                Toast.makeText(requireContext(), R.string.file_not_found, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            putExtra(Intent.EXTRA_TITLE, ft.fileName)
            setDataAndType(uri, contentType)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            view?.let { WindowInsetsControllerCompat(requireActivity().window, it).hide(WindowInsetsCompat.Type.ime()) }
            startActivity(Intent.createChooser(openIntent, getString(R.string.open_with)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                getString(R.string.mimetype_handler_not_found, contentType),
                Toast.LENGTH_LONG,
            ).show()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Unable to open file transfer ${ft.id}\n$e")
            Toast.makeText(requireContext(), R.string.file_not_found, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to open file transfer ${ft.id}: permission lost\n$e")
            Toast.makeText(requireContext(), R.string.file_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isEditableTextFile(fileName: String, contentType: String): Boolean {
        if (contentType.startsWith("text/")) return true
        return fileName.substringAfterLast('.', "").lowercase(Locale.US) in setOf(
            "txt",
            "json",
            "md",
            "markdown",
            "html",
            "htm",
            "xml",
            "csv",
            "log",
            "ini",
            "conf",
            "cfg",
            "yml",
            "yaml",
            "kt",
            "java",
            "js",
            "ts",
            "css",
            "sh",
            "bat",
            "ps1",
            "py",
            "c",
            "cpp",
            "h",
            "hpp",
        )
    }

    private fun startVoiceRecording() {
        if (voiceRecorder != null) return
        if (!viewModel.contactOnline) return
        if (!requireContext().hasPermission(PERMISSION_RECORD_AUDIO)) {
            startAfterMicPermission = true
            requestRecordAudioLauncher.launch(PERMISSION_RECORD_AUDIO)
            return
        }

        val file = viewModel.voiceMessageFile()

        try {
            voiceRecorder = createVoiceRecorder(file).apply { start() }
            voiceRecordingFile = file
            voiceRecordingStartedAt = System.currentTimeMillis()
            startVoiceTimer()
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

    @Suppress("DEPRECATION")
    private fun createVoiceRecorder(file: File): MediaRecorder {
        var lastError: Exception? = null
        val attempts = listOf(
            Triple(MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC, 44_100),
            Triple(MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC, 22_050),
            Triple(MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC, 16_000),
        )

        for ((format, encoder, sampleRate) in attempts) {
            val recorder = MediaRecorder()
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(format)
                recorder.setAudioEncoder(encoder)
                recorder.setAudioEncodingBitRate(VOICE_MESSAGE_BIT_RATE)
                recorder.setAudioSamplingRate(sampleRate)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                return recorder
            } catch (e: Exception) {
                lastError = e
                runCatching { recorder.release() }
            }
        }

        throw lastError ?: IllegalStateException("Unable to prepare voice recorder")
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
        stopVoiceTimer()

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
                startAudioProgressTimer()
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
        audioProgressTimer.removeCallbacksAndMessages(null)
        audioPlayer?.release()
        audioPlayer = null
        playingAudioId = Int.MIN_VALUE
        (binding.messages.adapter as? ChatAdapter)?.apply {
            playingAudioId = Int.MIN_VALUE
            playingAudioProgress = 0f
            notifyDataSetChanged()
        }
    }

    private fun startVoiceTimer() {
        voiceTimer.removeCallbacksAndMessages(null)
        binding.voiceRecordingTimer.visibility = View.VISIBLE
        val tick = object : Runnable {
            override fun run() {
                val elapsed = ((System.currentTimeMillis() - voiceRecordingStartedAt) / 1000).coerceAtLeast(0)
                val text = "%02d:%02d".format(elapsed / 60, elapsed % 60)
                binding.voiceRecordingTimer.text = getString(R.string.voice_recording, text)
                voiceTimer.postDelayed(this, 500)
            }
        }
        voiceTimer.post(tick)
    }

    private fun stopVoiceTimer() {
        voiceTimer.removeCallbacksAndMessages(null)
        binding.voiceRecordingTimer.visibility = View.GONE
    }

    private fun startAudioProgressTimer() {
        audioProgressTimer.removeCallbacksAndMessages(null)
        val tick = object : Runnable {
            override fun run() {
                val player = audioPlayer ?: return
                val duration = player.duration.takeIf { it > 0 } ?: 1
                (binding.messages.adapter as? ChatAdapter)?.apply {
                    playingAudioId = this@ChatFragment.playingAudioId
                    playingAudioProgress = player.currentPosition.toFloat() / duration.toFloat()
                    notifyDataSetChanged()
                }
                audioProgressTimer.postDelayed(this, 250)
            }
        }
        audioProgressTimer.post(tick)
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
                    voiceRecorder != null -> android.R.color.holo_green_light
                    voiceMessage.isEnabled -> android.R.color.white
                    else -> android.R.color.darker_gray
                },
            ),
        )
    }

    private fun wakeContactReady(): Boolean =
        viewModel.hasWakeToken() && wakeCooldownRemainingMs() <= 0L

    private fun wakeCooldownRemainingMs(): Long =
        WAKE_CONTACT_COOLDOWN_MS - (System.currentTimeMillis() - lastWakeSignalAtMsByContact[contactPubKey].orZero())

    private fun updateWakeContactMenuItem() {
        val item = binding.toolbar.menu.findItem(R.id.wake_contact) ?: return
        if (!item.isVisible) return
        val remainingMs = wakeCooldownRemainingMs()
        val waiting = remainingMs > 0L
        item.title = getString(if (waiting) R.string.wake_contact_waiting else R.string.wake_contact)
        item.isEnabled = viewModel.hasWakeToken() && !waiting

        wakeTimer.removeCallbacksAndMessages(null)
        if (waiting) {
            wakeTimer.postDelayed({ updateWakeContactMenuItem() }, remainingMs)
        }
    }

    private fun updateAutoWakeForContact() {
        if (viewModel.contactOnline) {
            autoWakeOpenAttempts = 0
            autoWakeOpenAttemptsByContact.remove(contactPubKey)
            pendingMessageWakeActive = false
            wakeTimer.removeCallbacksAndMessages(null)
            return
        }
        autoWakeOpenAttempts = autoWakeOpenAttemptsByContact[contactPubKey] ?: 0
        if (autoWakeOpenAttempts == 0) {
            scheduleOpenAutoWake()
        }
    }

    private fun scheduleOpenAutoWake() {
        autoWakeOpenAttempts = autoWakeOpenAttemptsByContact[contactPubKey] ?: 0
        if (viewModel.contactOnline || autoWakeOpenAttempts >= AUTO_WAKE_OPEN_ATTEMPTS) return

        val remainingMs = wakeCooldownRemainingMs()
        if (remainingMs > 0L) {
            wakeTimer.postDelayed({ scheduleOpenAutoWake() }, remainingMs)
            return
        }

        Toast.makeText(requireContext(), R.string.wake_signal_sending, Toast.LENGTH_SHORT).show()
        if (sendWakeSignal("auto_chat_open")) {
            autoWakeOpenAttempts++
            autoWakeOpenAttemptsByContact[contactPubKey] = autoWakeOpenAttempts
        }
        if (autoWakeOpenAttempts < AUTO_WAKE_OPEN_ATTEMPTS) {
            wakeTimer.postDelayed({ scheduleOpenAutoWake() }, AUTO_WAKE_INTERVAL_MS)
        }
    }

    private fun startPendingMessageWakeLoop() {
        if (pendingMessageWakeActive) return
        pendingMessageWakeActive = true
        val tick = object : Runnable {
            override fun run() {
                if (!pendingMessageWakeActive || viewModel.contactOnline) {
                    pendingMessageWakeActive = false
                    return
                }
                sendWakeSignal("auto_pending_message")
                wakeTimer.postDelayed(this, AUTO_WAKE_INTERVAL_MS)
            }
        }
        tick.run()
    }

    private fun sendWakeSignal(reason: String): Boolean {
        if (!viewModel.hasWakeToken()) return false
        val now = System.currentTimeMillis()
        lastWakeSignalAtMs = now
        lastWakeSignalAtMsByContact[contactPubKey] = now
        return viewModel.wakeContact(reason)
    }

    private fun navigateToCallScreen(requestVideo: Boolean = false) {
        view?.let { WindowInsetsControllerCompat(requireActivity().window, it).hide(WindowInsetsCompat.Type.ime()) }
        findNavController().navigate(
            R.id.action_chatFragment_to_callFragment,
            bundleOf(CONTACT_PUBLIC_KEY to contactPubKey, REQUEST_VIDEO_CALL to requestVideo),
        )
    }

    private fun tintCallIcons(enabled: Boolean) {
        val color = ContextCompat.getColor(
            requireContext(),
            if (enabled) android.R.color.white else android.R.color.darker_gray,
        )
        binding.toolbar.menu.findItem(R.id.call)?.icon?.mutate()?.setTint(color)
        binding.toolbar.menu.findItem(R.id.video_call)?.icon?.mutate()?.setTint(color)
    }
}

private fun Long?.orZero(): Long = this ?: 0L
