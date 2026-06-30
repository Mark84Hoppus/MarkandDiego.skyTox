// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.search

import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import java.text.DateFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ltd.evilcorp.atox.R
import ltd.evilcorp.domain.feature.search.SkyToxChatSearch

class SkyToxChatSearchDialog(
    private val fragment: Fragment,
    private val search: suspend (String) -> List<SkyToxChatSearch.Result>,
    private val openChat: (String) -> Unit,
) {
    private var results = emptyList<SkyToxChatSearch.Result>()
    private var searchJob: Job? = null

    fun show() {
        val context = fragment.requireContext()
        val input = EditText(context).apply {
            hint = context.getString(R.string.search_chats_hint)
            setSingleLine()
        }
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(context).apply {
            this.adapter = adapter
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
            addView(list)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.search_chats)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            results.getOrNull(position)?.let {
                dialog.dismiss()
                openChat(it.publicKey)
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = fragment.lifecycleScope.launch {
                    results = search(s?.toString().orEmpty())
                    adapter.clear()
                    adapter.addAll(results.map(::formatResult))
                    if (results.isEmpty() && !s.isNullOrBlank()) {
                        adapter.add(context.getString(R.string.search_chats_no_results))
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        dialog.setOnShowListener {
            input.requestFocus()
            context.getSystemService<InputMethodManager>()?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun formatResult(result: SkyToxChatSearch.Result): String {
        val time = if (result.timestamp > 0) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(result.timestamp)
        } else {
            ""
        }
        val snippet = result.message.replace('\n', ' ').let {
            if (it.length > MAX_SNIPPET) it.take(MAX_SNIPPET - 1) + "..." else it
        }
        return listOf(result.contactName, time, snippet).filter { it.isNotBlank() }.joinToString("\n")
    }

    companion object {
        private const val MAX_SNIPPET = 96
    }
}
