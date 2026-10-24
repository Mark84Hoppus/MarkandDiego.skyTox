// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Window
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.DialogReceiveShareBinding
import ltd.evilcorp.atox.truncated
import ltd.evilcorp.atox.ui.contactlist.ContactAdapter
import ltd.evilcorp.core.vo.Contact

private const val SHARE_TEXT_PREVIEW_LENGTH = 128

/** Discards share dialogs restored from releases that used a non-restorable Fragment constructor. */
@Deprecated("Kept only for restoring old FragmentManager state")
class ReceiveShareDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = Dialog(requireContext())

    override fun onStart() {
        super.onStart()
        dismissAllowingStateLoss()
    }
}

class ReceiveShareDialog(
    ctx: Context,
    private val sharePreview: String,
    private val onContactSelected: (Contact) -> Unit,
) : Dialog(ctx, R.style.DialogSlideAnimation) {
    private lateinit var binding: DialogReceiveShareBinding
    private var contacts: List<Contact> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogReceiveShareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        binding.sharingText.text = String.format("%s", sharePreview.truncated(SHARE_TEXT_PREVIEW_LENGTH))

        binding.contacts.let {
            it.adapter = ContactAdapter(layoutInflater, context)
            it.setOnItemClickListener { _, _, position, _ ->
                val contact = binding.contacts.getItemAtPosition(position) as Contact
                onContactSelected(contact)
                dismiss()
            }
        }
        applyContacts()
    }

    fun setContacts(contacts: List<Contact>) {
        this.contacts = contacts
        if (::binding.isInitialized) applyContacts()
    }

    private fun applyContacts() {
        (binding.contacts.adapter as ContactAdapter?)?.let {
            it.contacts = contacts
            it.notifyDataSetChanged()
        }
    }
}
