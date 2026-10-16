// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.contactlist

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.method.PasswordTransformationMethod
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.navigation.NavigationView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.MainActivity
import ltd.evilcorp.atox.databinding.ContactListViewItemBinding
import ltd.evilcorp.atox.databinding.FragmentContactListBinding
import ltd.evilcorp.atox.databinding.FriendRequestItemBinding
import ltd.evilcorp.atox.databinding.NavHeaderContactListBinding
import ltd.evilcorp.atox.hasPermission
import ltd.evilcorp.atox.truncated
import ltd.evilcorp.atox.ui.BaseFragment
import ltd.evilcorp.atox.ui.ReceiveShareDialogFragment
import ltd.evilcorp.atox.ui.chat.CONTACT_PUBLIC_KEY
import ltd.evilcorp.atox.ui.colorFromStatus
import ltd.evilcorp.atox.ui.contactListSorter
import ltd.evilcorp.atox.ui.friendrequest.FRIEND_REQUEST_PUBLIC_KEY
import ltd.evilcorp.atox.ui.search.SkyToxChatSearchDialog
import ltd.evilcorp.atox.vmFactory
import ltd.evilcorp.core.vo.ConnectionStatus
import ltd.evilcorp.core.vo.Contact
import ltd.evilcorp.core.vo.FriendRequest
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.core.vo.User
import ltd.evilcorp.domain.tox.ToxID
import ltd.evilcorp.domain.tox.ToxSaveStatus
import ltd.evilcorp.domain.feature.SKYTOX_SELF_CHAT_PUBLIC_KEY
import ltd.evilcorp.domain.feature.isSkyToxSelfChat

const val ARG_ADD_CONTACT = "add_contact"
const val ARG_SHARE = "share"
const val ARG_SHARE_FILES = "share_files"
private const val MAX_CONFIRM_DELETE_STRING_LENGTH = 32
private const val MAX_FILES_PER_SHARE_BATCH = 10

private fun User.online(): Boolean = connectionStatus != ConnectionStatus.None
private fun localAiSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
        Build.SUPPORTED_64_BIT_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

class ContactListFragment :
    BaseFragment<FragmentContactListBinding>(FragmentContactListBinding::inflate),
    NavigationView.OnNavigationItemSelectedListener {

    private val viewModel: ContactListViewModel by viewModels { vmFactory }

    private var navHeader: NavHeaderContactListBinding? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    private var backupFileNameHint = "something_is_broken.tox"

    private var passwordDialog: AlertDialog? = null
    private var selfChatContact: Contact? = null
    private var normalContacts: List<Contact> = emptyList()
    private val emergencyHandler = Handler(Looper.getMainLooper())
    private var emergencyDialog: Dialog? = null
    private var emergencyTimer: CountDownTimer? = null
    private var logoConnected = false
    private val openEmergencyScreen = Runnable { showEmergencyWipeScreen() }

    private val exportToxSaveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { dest ->
            if (dest == null) return@registerForActivityResult
            viewModel.saveToxBackupTo(dest)
        }

    private val exportAllTextChatsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { dest ->
            if (dest == null) return@registerForActivityResult
            viewModel.exportAllTextChats(dest)
        }

    private val importAllTextChatsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
            if (source == null) return@registerForActivityResult
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_text_chats)
                .setMessage(R.string.import_text_chats_confirm)
                .setPositiveButton(R.string.continue_import) { _, _ ->
                    viewModel.importAllTextChats(source)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        navHeader = NavHeaderContactListBinding.bind(binding.navView.getHeaderView(0))
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?): Unit = binding.run {
        if (!viewModel.isToxRunning()) return@run
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!requireContext().hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!requireContext().hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(left = insets.left)
            navView.updatePadding(left = insets.left)
            contactList.updatePadding(
                bottom = insets.bottom + resources.getDimensionPixelSize(R.dimen.skytox_start_menu_list_bottom_padding),
            )
            compat
        }

        toolbar.title = ""
        toolbar.subtitle = ""
        updateHeaderConnectionLogo(false)
        connectionLogo.setOnTouchListener { logo, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    emergencyHandler.removeCallbacks(openEmergencyScreen)
                    DrawableCompat.setTint((logo as ImageView).drawable.mutate(), Color.RED)
                    logo.animate().scaleX(2f).scaleY(2f).setDuration(2_000L).start()
                    emergencyHandler.postDelayed(openEmergencyScreen, 5_000L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    emergencyHandler.removeCallbacks(openEmergencyScreen)
                    resetConnectionLogo()
                    true
                }
                else -> true
            }
        }
        toolbar.inflateMenu(R.menu.contact_list_toolbar)
        toolbar.menu.findItem(R.id.open_ai_assistant)?.isVisible = localAiSupported()
        (requireActivity() as? MainActivity)?.setMusicAvailabilityListener { enabled ->
            toolbar.menu.findItem(R.id.open_music)?.isEnabled = enabled
            toolbar.menu.findItem(R.id.open_music)?.icon?.alpha = if (enabled) 255 else 90
        }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.open_music -> {
                    (requireActivity() as? MainActivity)?.showMusicWidget()
                    true
                }
                R.id.open_ai_assistant -> {
                    findNavController().navigate(R.id.action_contactListFragment_to_aiAssistantFragment)
                    true
                }
                R.id.open_games -> {
                    findNavController().navigate(R.id.action_contactListFragment_to_gamesFragment)
                    true
                }
                R.id.search_chats -> {
                    SkyToxChatSearchDialog(
                        this@ContactListFragment,
                        search = viewModel::searchChats,
                        openChat = ::openChat,
                    ).show()
                    true
                }
                else -> false
            }
        }

        viewModel.user.observe(viewLifecycleOwner) { user ->
            if (user == null) return@observe

            backupFileNameHint = user.name + ".tox"

            navHeader!!.apply {
                profileName.text = user.name
                profileStatusMessage.text = user.statusMessage

                if (user.online()) {
                    statusIndicator.setColorFilter(colorFromStatus(requireContext(), user.status))
                } else {
                    statusIndicator.setColorFilter(R.color.statusOffline)
                }
            }

            toolbar.title = ""
            toolbar.subtitle = ""
            updateHeaderConnectionLogo(user.online())
        }

        navView.setNavigationItemSelectedListener(this@ContactListFragment)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        val contactAdapter = ContactAdapter(layoutInflater, requireContext())
        contactList.adapter = contactAdapter
        registerForContextMenu(contactList)

        viewModel.friendRequests.observe(viewLifecycleOwner) { friendRequests ->
            contactAdapter.friendRequests = friendRequests
            contactAdapter.notifyDataSetChanged()

            noContactsCallToAction.visibility = if (contactAdapter.isEmpty) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        viewModel.contacts.observe(viewLifecycleOwner) { contacts ->
            normalContacts = contacts
            updateContactAdapter(contactAdapter)
        }

        viewModel.selfChatContact.observe(viewLifecycleOwner) { contact ->
            selfChatContact = contact
            updateContactAdapter(contactAdapter)
        }

        viewModel.pendingMessageContacts.observe(viewLifecycleOwner) { contacts ->
            contactAdapter.pendingMessageContacts = contacts
            contactAdapter.notifyDataSetChanged()
        }

        viewModel.emergencyWipeResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                EmergencyWipeResult.Complete -> {
                    emergencyTimer?.cancel()
                    emergencyDialog?.dismiss()
                    requireActivity().finishAndRemoveTask()
                    emergencyHandler.postDelayed({ Process.killProcess(Process.myPid()) }, 250L)
                }
                is EmergencyWipeResult.Failed -> {
                    emergencyTimer?.cancel()
                    emergencyDialog?.dismiss()
                    Toast.makeText(requireContext(), R.string.emergency_wipe_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

        contactList.setOnItemClickListener { _, _, position, _ ->
            when (contactList.adapter.getItemViewType(position)) {
                ContactListItemType.FriendRequest.ordinal -> {
                    openFriendRequest(contactList.getItemAtPosition(position) as FriendRequest)
                }
                ContactListItemType.Contact.ordinal -> {
                    openChat(contactList.getItemAtPosition(position) as Contact)
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                activity?.finish()
            }
        }

        arguments?.getString(ARG_ADD_CONTACT)?.let { toxId ->
            arguments?.remove(ARG_ADD_CONTACT)
            val id = ToxID(toxId)
            val pk = id.toPublicKey()
            if (viewModel.contactAdded(pk)) {
                openChat(pk.string())
            } else {
                findNavController().navigate(R.id.addContactFragment, bundleOf("toxId" to toxId))
            }
        }

        arguments?.getString(ARG_SHARE)?.let { share ->
            ReceiveShareDialogFragment(
                viewModel.contacts,
                share,
                onContactSelected = {
                    viewModel.onShareText(share, it)
                    openChat(it)
                },
                onDialogDismissed = {
                    arguments?.remove(ARG_SHARE)
                },
            ).show(childFragmentManager, null)
        }

        arguments?.getParcelableArrayList<Uri>(ARG_SHARE_FILES)?.let { files ->
            if (files.isEmpty()) return@let
            val filesToShare = ArrayList(files.take(MAX_FILES_PER_SHARE_BATCH))
            if (files.size > MAX_FILES_PER_SHARE_BATCH) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.file_send_batch_limited, MAX_FILES_PER_SHARE_BATCH),
                    Toast.LENGTH_LONG,
                ).show()
            }
            ReceiveShareDialogFragment(
                viewModel.contacts,
                filesToShare.joinToString(separator = "\n") { it.lastPathSegment ?: it.toString() },
                onContactSelected = {
                    viewModel.onShareFiles(filesToShare, it)
                    openChat(it)
                },
                onDialogDismissed = {
                    arguments?.remove(ARG_SHARE_FILES)
                },
            ).show(childFragmentManager, null)
        }
    }

    override fun onDestroyView() {
        emergencyHandler.removeCallbacks(openEmergencyScreen)
        emergencyTimer?.cancel()
        emergencyDialog?.dismiss()
        (activity as? MainActivity)?.setMusicAvailabilityListener(null)
        navHeader = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
    }

    private fun updateHeaderConnectionLogo(connected: Boolean) = binding.toolbar.run {
        logoConnected = connected
        val color = ContextCompat.getColor(
            requireContext(),
            if (connected) android.R.color.holo_green_light else android.R.color.white,
        )
        val icon = ContextCompat.getDrawable(requireContext(), R.drawable.atox_logo_white)
            ?.mutate()
            ?.let { DrawableCompat.wrap(it) }
            ?: return@run
        DrawableCompat.setTint(icon, color)
        binding.connectionLogo.setImageDrawable(icon)
        logo = null
    }

    private fun resetConnectionLogo() {
        binding.connectionLogo.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
        updateHeaderConnectionLogo(logoConnected)
    }

    private fun showEmergencyWipeScreen() {
        if (emergencyDialog?.isShowing == true || !isAdded) return
        resetConnectionLogo()
        var taps = 0
        val density = resources.displayMetrics.density
        val root = FrameLayout(requireContext()).apply { setBackgroundColor(Color.BLACK) }
        val instruction = TextView(requireContext()).apply {
            text = getString(R.string.emergency_wipe_instruction)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        root.addView(instruction, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = (72 * density).toInt(); marginStart = 24; marginEnd = 24 })
        val logo = ImageView(requireContext()).apply {
            setImageResource(R.drawable.atox_logo_white)
            DrawableCompat.setTint(drawable.mutate(), Color.RED)
            setPadding(12, 12, 12, 12)
        }
        val logoSize = (113 * density).toInt()
        root.addView(logo, FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER))
        val countdown = TextView(requireContext()).apply {
            setTextColor(0x88FFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            text = "10"
        }
        root.addView(countdown, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply { bottomMargin = (24 * density).toInt() })
        val dialog = Dialog(requireContext()).apply {
            setContentView(root)
            setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        emergencyDialog = dialog
        logo.setOnClickListener {
            taps++
            logo.animate().scaleX(2f).scaleY(2f).setDuration(140L).withEndAction {
                logo.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
            }.start()
            if (taps >= 3) {
                logo.isEnabled = false
                emergencyTimer?.cancel()
                viewModel.emergencyWipe()
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        emergencyTimer = object : CountDownTimer(10_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                countdown.text = ((millisUntilFinished + 999L) / 1_000L).toString()
            }
            override fun onFinish() {
                if (taps < 3) dialog.dismiss()
            }
        }.start()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)

        val inflater: MenuInflater = requireActivity().menuInflater
        val info = menuInfo as AdapterView.AdapterContextMenuInfo

        when (binding.contactList.adapter.getItemViewType(info.position)) {
            ContactListItemType.FriendRequest.ordinal -> {
                val f = FriendRequestItemBinding.bind(info.targetView)
                menu.setHeaderTitle(f.publicKey.text)
                inflater.inflate(R.menu.friend_request_context_menu, menu)
            }
            ContactListItemType.Contact.ordinal -> {
                val contact = binding.contactList.adapter.getItem(info.position) as Contact
                if (isSkyToxSelfChat(contact.publicKey)) return
                val c = ContactListViewItemBinding.bind(info.targetView)
                menu.setHeaderTitle(c.name.text)
                inflater.inflate(R.menu.contact_list_context_menu, menu)
            }
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterView.AdapterContextMenuInfo

        return when (info.targetView.id) {
            R.id.friendRequestItem -> {
                val friendRequest = binding.contactList.adapter.getItem(info.position) as FriendRequest
                when (item.itemId) {
                    R.id.accept -> {
                        viewModel.acceptFriendRequest(friendRequest)
                    }
                    R.id.reject -> {
                        viewModel.rejectFriendRequest(friendRequest)
                    }
                }
                true
            }
            R.id.contactListItem -> {
                when (item.itemId) {
                    R.id.profile -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        if (isSkyToxSelfChat(contact.publicKey)) return true
                        openProfile(contact)
                    }
                    R.id.delete -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        if (isSkyToxSelfChat(contact.publicKey)) return true

                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.delete_contact)
                            .setMessage(
                                getString(
                                    R.string.contact_list_delete_contact_confirm,
                                    contact.name.truncated(MAX_CONFIRM_DELETE_STRING_LENGTH),
                                ),
                            )
                            .setPositiveButton(R.string.delete) { _, _ ->
                                viewModel.deleteContact(PublicKey(contact.publicKey))
                            }
                            .setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.drawer_profile -> {
                findNavController().navigate(R.id.action_contactListFragment_to_userProfileFragment)
            }
            R.id.add_contact -> findNavController().navigate(R.id.action_contactListFragment_to_addContactFragment)
            R.id.settings -> findNavController().navigate(R.id.action_contactListFragment_to_settingsFragment)
            R.id.export_tox_save -> viewModel.saveToxBackupToDefault(backupFileNameHint)
            R.id.export_text_chats -> viewModel.exportAllTextChatsToDefault()
            R.id.import_text_chats -> showAllTextChatsImportPicker()
            R.id.import_export_instructions ->
                findNavController().navigate(R.id.action_contactListFragment_to_importExportInstructionsFragment)
            R.id.quit_tox -> {
                if (!viewModel.quittingNeedsConfirmation()) {
                    viewModel.quitTox()
                    activity?.finishAffinity()
                    return false
                }

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.quit_confirm)
                    .setPositiveButton(R.string.quit) { _, _ ->
                        viewModel.quitTox()
                        activity?.finishAffinity()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return false
    }

    private fun allTextChatsFileNameHint(): String =
        "skytox-all-text-chats_${
            SimpleDateFormat("""yyyy-MM-dd'T'HH-mm-ss""", Locale.getDefault()).format(Date())
        }.json"

    private fun showAllTextChatsImportPicker() {
        val files = viewModel.listAllTextChatBackups()
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), R.string.import_text_chats_no_backups, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.import_text_chats)
            .setItems(files.map { it.name }.toTypedArray()) { _, which ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.import_text_chats)
                    .setMessage(R.string.import_text_chats_confirm)
                    .setPositiveButton(R.string.continue_import) { _, _ ->
                        viewModel.importAllTextChats(files[which])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!viewModel.isToxRunning()) viewModel.tryLoadTox(null)
    }

    override fun onStart() {
        super.onStart()
        if (!viewModel.isToxRunning()) {
            when (val status = viewModel.tryLoadTox(null)) {
                ToxSaveStatus.BadProxyHost, ToxSaveStatus.BadProxyPort,
                ToxSaveStatus.BadProxyType, ToxSaveStatus.ProxyNotFound,
                -> {
                    Toast.makeText(requireContext(), getString(R.string.warn_proxy_broken), Toast.LENGTH_LONG).show()
                    findNavController().navigate(R.id.action_contactListFragment_to_settingsFragment)
                }
                ToxSaveStatus.SaveNotFound ->
                    findNavController().navigate(R.id.action_contactListFragment_to_profileFragment)
                ToxSaveStatus.Encrypted -> {
                    view?.visibility = View.INVISIBLE
                    if (passwordDialog != null) return
                    val passwordEdit = EditText(requireContext()).apply {
                        hint = getString(R.string.password)
                        inputType = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                        setSingleLine()
                        transformationMethod = PasswordTransformationMethod()
                    }
                    val passwordLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        LinearLayout(requireContext()).apply {
                            val attr = context.obtainStyledAttributes(intArrayOf(android.R.attr.dialogPreferredPadding))
                            val padding = attr.getDimensionPixelSize(0, 0)
                            attr.recycle()
                            setPadding(padding, 0, padding, 0)
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                            addView(passwordEdit)
                        }
                    } else {
                        null
                    }
                    passwordDialog = AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.unlock_profile))
                        .setView(passwordLayout ?: passwordEdit)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val password = passwordEdit.text.toString()
                            if (viewModel.tryLoadTox(password) == ToxSaveStatus.Ok) {
                                // Hack to reload fragment.
                                parentFragmentManager.beginTransaction().detach(this).commitAllowingStateLoss()
                                parentFragmentManager.beginTransaction().attach(this).commitAllowingStateLoss()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.incorrect_password),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setOnDismissListener {
                            passwordDialog = null
                            if (!viewModel.isToxRunning()) {
                                activity?.finishAffinity()
                            }
                        }
                        .show()
                }
                ToxSaveStatus.Ok -> {
                }
                else -> throw Exception("Unhandled tox save error $status")
            }
        }
    }

    private fun openChat(contact: Contact) = openChat(contact.publicKey)
    private fun openChat(pk: String) = findNavController().navigate(
        R.id.action_contactListFragment_to_chatFragment,
        bundleOf(CONTACT_PUBLIC_KEY to pk),
    )

    private fun openFriendRequest(friendRequest: FriendRequest) = findNavController().navigate(
        R.id.action_contactListFragment_to_friendRequestFragment,
        bundleOf(FRIEND_REQUEST_PUBLIC_KEY to friendRequest.publicKey),
    )

    private fun openProfile(contact: Contact) = findNavController().navigate(
        R.id.action_contactListFragment_to_contactProfileFragment,
        bundleOf(CONTACT_PUBLIC_KEY to contact.publicKey),
    )

    private fun updateContactAdapter(adapter: ContactAdapter) = binding.run {
        adapter.contacts = listOfNotNull(selfChatContact) + normalContacts.sortedByDescending(::contactListSorter)
        adapter.notifyDataSetChanged()
        noContactsCallToAction.visibility = if (adapter.isEmpty) View.VISIBLE else View.GONE
    }
}
