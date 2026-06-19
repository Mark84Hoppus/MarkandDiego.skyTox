// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.instructions

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.view.updateMargins
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.FragmentImportExportInstructionsBinding
import ltd.evilcorp.atox.ui.BaseFragment

class ImportExportInstructionsFragment :
    BaseFragment<FragmentImportExportInstructionsBinding>(FragmentImportExportInstructionsBinding::inflate) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = insets.top)
            v.updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
            compat
        }

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        toolbar.title = getString(R.string.import_export_instructions)
        addInstructions()
    }

    private fun addInstructions() = binding.instructionsContainer.run {
        removeAllViews()

        section(
            "Русская инструкция",
            """
            skyTox экспортирует и импортирует только текстовую историю чатов. Картинки, видео, документы, голосовые сообщения и сами переданные файлы в резервную копию не попадают.

            Папки резервных копий находятся здесь:
            /storage/emulated/0/skyTox files/

            Внутри создаются две папки:
            /storage/emulated/0/skyTox files/skyTox all chat/
            /storage/emulated/0/skyTox files/skyTox user chat/
            """.trimIndent(),
        )
        section(
            "Общий экспорт",
            """
            1. Откройте боковое меню на главном экране.
            2. Нажмите «Экспорт текстовых чатов».
            3. Android откроет стандартный проводник сохранения файла.
            4. Лучше сохранить JSON-файл в папку skyTox all chat.
            """.trimIndent(),
        )
        section(
            "Общий импорт",
            """
            1. Откройте боковое меню на главном экране.
            2. Нажмите «Импорт текстовых чатов».
            3. Выберите JSON-файл из папки skyTox all chat.
            4. Подтвердите предупреждение.

            Важно: при общем импорте текущая история сообщений во всех чатах заменяется данными из выбранного файла. Файлы на телефоне не импортируются, только текст.
            """.trimIndent(),
        )
        section(
            "Один чат",
            """
            Экспорт: откройте нужный чат, нажмите меню с тремя точками, выберите «Экспортировать историю» и сохраните JSON-файл в skyTox user chat.

            Импорт: откройте нужный чат, нажмите меню с тремя точками, выберите «Импортировать историю», выберите JSON-файл из skyTox user chat и подтвердите предупреждение.

            При импорте одного чата заменяется история только текущего открытого чата.
            """.trimIndent(),
        )
        section(
            "Защита от ошибки",
            """
            skyTox проверяет структуру JSON перед импортом. В файле должен быть правильный формат, версия и тип резервной копии.

            Общую резервную копию нельзя импортировать в один чат, а резервную копию одного чата нельзя импортировать как все чаты. Если файл поврежден, вручную испорчен или не относится к skyTox, импорт отменяется и старая история не удаляется.
            """.trimIndent(),
        )
        section(
            "English guide",
            """
            skyTox exports and imports text chat history only. Pictures, videos, documents, voice messages and transferred files are not included in chat backups.

            Backup folders:
            /storage/emulated/0/skyTox files/skyTox all chat/
            /storage/emulated/0/skyTox files/skyTox user chat/
            """.trimIndent(),
        )
        section(
            "Export and import",
            """
            Use Export text chats and Import text chats from the main side menu for full text-chat backups. Keep those files in skyTox all chat.

            Use Export history and Import history from a chat's three-dot menu for one chat. Keep those files in skyTox user chat.

            Import replaces the selected text history. Files on the phone are not imported.
            """.trimIndent(),
        )
    }

    private fun section(title: String, body: String) {
        binding.instructionsContainer.addView(TextView(requireContext()).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                updateMargins(top = 18)
            }
        })
        binding.instructionsContainer.addView(TextView(requireContext()).apply {
            text = body
            textSize = 14f
            setLineSpacing(6f, 1f)
            setTextIsSelectable(true)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
    }
}
