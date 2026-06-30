// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.importexport

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.FragmentImportExportMenuBinding
import ltd.evilcorp.atox.ui.BaseFragment
import ltd.evilcorp.atox.ui.contactlist.ContactListViewModel
import ltd.evilcorp.atox.vmFactory

class ImportExportMenuFragment : BaseFragment<FragmentImportExportMenuBinding>(FragmentImportExportMenuBinding::inflate) {
    private val viewModel: ContactListViewModel by viewModels { vmFactory }
    private var backupFileNameHint = "skytox-profile.tox"

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = insets.top)
            v.updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
            compat
        }

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        viewModel.user.observe(viewLifecycleOwner) { user ->
            if (user != null) backupFileNameHint = user.name + ".tox"
        }

        exportToxSave.setOnClickListener {
            exportToxSaveLauncher.launch(backupFileNameHint)
        }
        exportTextChats.setOnClickListener {
            exportAllTextChatsLauncher.launch(allTextChatsFileNameHint())
        }
        importTextChats.setOnClickListener {
            importAllTextChatsLauncher.launch(arrayOf("application/json"))
        }
        importExportInstructions.setOnClickListener {
            findNavController().navigate(R.id.action_importExportMenuFragment_to_importExportInstructionsFragment)
        }
    }

    private fun allTextChatsFileNameHint(): String =
        "skytox-all-text-chats_${
            SimpleDateFormat("""yyyy-MM-dd'T'HH-mm-ss""", Locale.getDefault()).format(Date())
        }.json"
}
