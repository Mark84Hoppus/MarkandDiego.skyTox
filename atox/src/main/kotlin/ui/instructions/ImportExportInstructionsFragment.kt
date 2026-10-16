// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.instructions

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateMargins
import androidx.core.view.updatePadding
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
            skyTox делает резервные копии только текстовой истории. Картинки, видео, документы, голосовые сообщения и сами переданные файлы в экспорт чатов не входят.

            Все публичные папки создаются здесь:
            /storage/emulated/0/skyTox files/

            Внутри используются отдельные папки:
            /storage/emulated/0/skyTox files/skyTox all chat/
            /storage/emulated/0/skyTox files/skyTox user chat/
            /storage/emulated/0/skyTox files/skyTox profile/
            """.trimIndent(),
        )
        section(
            "Экспорт всех текстовых чатов",
            """
            1. Откройте нижнее меню «Чаты».
            2. Нажмите «Экспорт текстовых чатов».
            3. skyTox сам сохранит JSON-файл в папку skyTox all chat.
            4. После успешного сохранения появится уведомление.

            Проводник Android больше не открывается: приложение сохраняет файл в свою публичную папку автоматически.
            """.trimIndent(),
        )
        section(
            "Импорт всех текстовых чатов",
            """
            1. Откройте нижнее меню «Чаты».
            2. Нажмите «Импорт текстовых чатов».
            3. Выберите нужный JSON-файл из списка внутри приложения.
            4. Подтвердите предупреждение.

            При общем импорте текущая текстовая история заменяется данными из выбранного файла. Если в резервной копии есть чат с контактом, которого уже нет в профиле, такой чат будет пропущен.
            """.trimIndent(),
        )
        section(
            "Экспорт и импорт одного чата",
            """
            Экспорт: откройте нужный чат, нажмите меню с тремя точками и выберите «Экспортировать историю». JSON-файл сохранится в skyTox user chat.

            Импорт: откройте нужный чат, нажмите меню с тремя точками и выберите «Импортировать историю». skyTox покажет список резервных копий для этого чата. После подтверждения заменится только история текущего чата.
            """.trimIndent(),
        )
        section(
            "Экспорт профиля Tox",
            """
            Пункт «Экспортировать профиль Tox» сохраняет профиль в:
            /storage/emulated/0/skyTox files/skyTox profile/

            Если Android не дал доступ к общей памяти или папку создать не удалось, приложение покажет ошибку экспорта.
            """.trimIndent(),
        )
        section(
            "Проверка файлов",
            """
            Перед импортом skyTox проверяет тип резервной копии, версию и структуру JSON.

            Общую резервную копию нельзя импортировать как один чат. Резервную копию одного чата нельзя импортировать как все чаты. Если JSON поврежден, вручную испорчен или не относится к skyTox, импорт отменяется, а старая история остается на месте.
            """.trimIndent(),
        )
        section(
            "English guide",
            """
            skyTox backs up text chat history only. Images, videos, documents, voice messages and transferred files are not included in chat exports.

            Public folders are created here:
            /storage/emulated/0/skyTox files/

            skyTox uses these folders:
            /storage/emulated/0/skyTox files/skyTox all chat/
            /storage/emulated/0/skyTox files/skyTox user chat/
            /storage/emulated/0/skyTox files/skyTox profile/
            """.trimIndent(),
        )
        section(
            "Export all text chats",
            """
            1. Open the bottom «Chats» menu.
            2. Tap «Export text chats».
            3. skyTox saves the JSON file directly to skyTox all chat.
            4. A success notification is shown when the export is complete.

            Android's file picker is no longer opened for this action.
            """.trimIndent(),
        )
        section(
            "Import all text chats",
            """
            1. Open the bottom «Chats» menu.
            2. Tap «Import text chats».
            3. Select a JSON backup from the in-app list.
            4. Confirm the warning.

            Full import replaces the current text history with the selected backup. Chats for contacts that are missing from the current Tox profile are skipped.
            """.trimIndent(),
        )
        section(
            "One chat",
            """
            Export: open a chat, tap the three-dot menu and select «Export history». The backup is saved to skyTox user chat.

            Import: open a chat, tap the three-dot menu and select «Import history». skyTox shows backups for that chat. After confirmation, only the current chat history is replaced.
            """.trimIndent(),
        )
        section(
            "Tox profile",
            """
            «Export Tox profile» saves the profile to:
            /storage/emulated/0/skyTox files/skyTox profile/

            If Android storage access is missing or the folder cannot be created, skyTox shows an export error.
            """.trimIndent(),
        )
        section(
            "Validation",
            """
            skyTox validates the backup type, version and JSON structure before importing.

            A full-chat backup cannot be imported into a single chat. A single-chat backup cannot be imported as all chats. If the JSON is broken, edited incorrectly or does not belong to skyTox, the import is cancelled and the old history remains untouched.
            """.trimIndent(),
        )
    }

    private fun section(title: String, body: String) {
        binding.instructionsContainer.addView(TextView(requireContext()).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
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
