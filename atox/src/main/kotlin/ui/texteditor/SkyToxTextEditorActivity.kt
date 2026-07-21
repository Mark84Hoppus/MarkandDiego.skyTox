// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.texteditor

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltd.evilcorp.atox.App
import ltd.evilcorp.atox.R
import ltd.evilcorp.core.repository.ContactRepository
import ltd.evilcorp.core.vo.Contact
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.FileTransferManager
import ltd.evilcorp.domain.feature.SkyToxPublicFolders

private const val EXTRA_URI = "uri"
private const val EXTRA_NAME = "name"
private const val MAX_TEXT_FILE_BYTES = 10L * 1024L * 1024L

class SkyToxTextEditorActivity : AppCompatActivity() {
    @Inject
    lateinit var contactRepository: ContactRepository

    @Inject
    lateinit var fileTransferManager: FileTransferManager

    @Inject
    lateinit var scope: CoroutineScope

    private lateinit var sourceUri: Uri
    private lateinit var fileName: String
    private lateinit var toolbar: Toolbar
    private lateinit var content: FrameLayout
    private lateinit var editorScroll: ScrollView
    private lateinit var editor: EditText
    private var readerLayout: LinearLayout? = null
    private var readerTextView: TextView? = null
    private var readerFooter: TextView? = null
    private var originalText = ""
    private var readerMode = false
    private var readerPages: List<String> = listOf("")
    private var readerPageStarts: List<Int> = listOf(0)
    private var readerPage = 0
    private var readerFontSize = 18f

    companion object {
        fun extras(uri: Uri, name: String) = Bundle().apply {
            putString(EXTRA_URI, uri.toString())
            putString(EXTRA_NAME, name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as App).component.inject(this)
        super.onCreate(savedInstanceState)

        sourceUri = intent.getStringExtra(EXTRA_URI)?.toUri() ?: run {
            finish()
            return
        }
        fileName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "document.txt" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        toolbar = Toolbar(this).apply {
            title = getString(R.string.text_editor)
            navigationIcon = null
        }
        editor = EditText(this).apply {
            setTextIsSelectable(true)
            gravity = android.view.Gravity.START or android.view.Gravity.TOP
            minLines = 16
            setSingleLine(false)
            isVerticalScrollBarEnabled = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        editorScroll = ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(editor)
        }
        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(toolbar)
        content.addView(editorScroll)
        root.addView(content)
        setContentView(root)
        setSupportActionBar(toolbar)

        loadText()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (readerMode) {
            menu.add(R.string.go_to_page).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(R.string.font_size).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(R.string.close).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        } else {
            menu.add(R.string.save_as_and_send).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(R.string.open_reader_mode).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(R.string.save_to_file).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(R.string.close).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.title.toString()) {
            getString(R.string.go_to_page) -> showGoToPageDialog(readerPages.size) {
                readerPage = it
                renderReaderPage()
            }
            getString(R.string.font_size) -> showFontSizeDialog(readerTextView ?: return true) {
                readerFontSize = readerTextView?.textSize?.div(resources.displayMetrics.scaledDensity) ?: readerFontSize
                rebuildReaderPages()
                renderReaderPage()
            }
            getString(R.string.save_as_and_send) -> showSaveAsDialog()
            getString(R.string.open_reader_mode) -> openReader()
            getString(R.string.save_to_file) -> saveOriginal()
            getString(R.string.close) -> if (readerMode) closeReader() else closeEditor()
        }
        return true
    }

    private fun loadText() {
        scope.launch(Dispatchers.IO) {
            val bytes = try {
                val size = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
                if (size > MAX_TEXT_FILE_BYTES) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SkyToxTextEditorActivity, R.string.file_too_large, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@launch
                }
                contentResolver.openInputStream(sourceUri)?.use { input ->
                    input.readBytes().also {
                        if (it.size.toLong() > MAX_TEXT_FILE_BYTES) throw IllegalArgumentException("too large")
                    }
                }
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (bytes == null) {
                    Toast.makeText(this@SkyToxTextEditorActivity, R.string.file_not_found, Toast.LENGTH_LONG).show()
                    finish()
                    return@withContext
                }
                originalText = bytes.toString(Charsets.UTF_8)
                editor.setText(originalText)
            }
        }
    }

    private fun saveOriginal() {
        val text = editor.text.toString()
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                contentResolver.openOutputStream(sourceUri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("No output stream")
            }.isSuccess
            withContext(Dispatchers.Main) {
                if (ok) {
                    originalText = text
                    Toast.makeText(this@SkyToxTextEditorActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SkyToxTextEditorActivity, R.string.export_file_failure, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showSaveAsDialog() {
        val input = EditText(this).apply {
            setText(fileName)
            setSelectAllOnFocus(false)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.save_as)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.send) { _, _ ->
                val name = input.text.toString().trim()
                if (!isValidFileName(name)) {
                    Toast.makeText(this, R.string.invalid_file_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveCopyAndChooseContact(name)
            }
            .show()
    }

    private fun saveCopyAndChooseContact(name: String) {
        scope.launch(Dispatchers.IO) {
            val file = runCatching {
                SkyToxPublicFolders.ensureDirectories()
                val dest = uniqueFile(SkyToxPublicFolders.documentDir, name)
                dest.writeText(editor.text.toString(), Charsets.UTF_8)
                dest
            }.getOrNull()

            withContext(Dispatchers.Main) {
                if (file == null) {
                    Toast.makeText(this@SkyToxTextEditorActivity, R.string.export_file_failure, Toast.LENGTH_LONG).show()
                    return@withContext
                }
                chooseContactAndSend(file)
            }
        }
    }

    private fun chooseContactAndSend(file: File) {
        scope.launch {
            contactRepository.getAll().take(1).collect { contacts ->
                withContext(Dispatchers.Main) {
                    val candidates = contacts.sortedBy { it.name.lowercase() }
                    if (candidates.isEmpty()) return@withContext
                    val names = candidates.map { it.name.ifBlank { getString(R.string.contact_default_name) } }.toTypedArray()
                    AlertDialog.Builder(this@SkyToxTextEditorActivity)
                        .setTitle(R.string.forward_to)
                        .setItems(names) { _, which ->
                            val publicKey = PublicKey(candidates[which].publicKey)
                            scope.launch(Dispatchers.IO) {
                                fileTransferManager.create(publicKey, file.toUri())
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@SkyToxTextEditorActivity, R.string.sent, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun openReader() {
        readerMode = true
        toolbar.title = getString(R.string.reader_mode)

        val textView = TextView(this).apply {
            setTextIsSelectable(false)
            textSize = readerFontSize
            setPadding(32, 16, 32, 16)
        }
        readerTextView = textView
        val footer = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }
        readerFooter = footer
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(textView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer)
        }
        readerLayout = layout
        var downX = 0f
        val swipeListener = android.view.View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = event.x
                MotionEvent.ACTION_UP -> {
                    val delta = event.x - downX
                    if (delta < -80f && readerPage < readerPages.lastIndex) {
                        readerPage += 1
                        renderReaderPage()
                    } else if (delta > 80f && readerPage > 0) {
                        readerPage -= 1
                        renderReaderPage()
                    }
                }
            }
            true
        }
        layout.setOnTouchListener(swipeListener)
        textView.setOnTouchListener(swipeListener)
        editorScroll.visibility = android.view.View.GONE
        content.addView(layout)
        textView.post {
            rebuildReaderPages(preserveCurrentOffset = false)
            renderReaderPage()
        }
        invalidateOptionsMenu()
    }

    private fun rebuildReaderPages(preserveCurrentOffset: Boolean = true) {
        val textView = readerTextView ?: return
        val currentOffset = if (preserveCurrentOffset) readerPageStarts.getOrElse(readerPage) { 0 } else 0
        val (pages, starts) = pagesForRenderedText(editor.text.toString(), textView)
        readerPages = pages
        readerPageStarts = starts
        readerPage = starts.indexOfLast { it <= currentOffset }.coerceAtLeast(0)
            .coerceAtMost(readerPages.lastIndex.coerceAtLeast(0))
    }

    private fun renderReaderPage() {
        readerTextView?.text = readerPages.getOrElse(readerPage) { "" }
        readerFooter?.text = "${readerPage + 1}/${readerPages.size.coerceAtLeast(1)}"
    }

    private fun closeReader() {
        readerMode = false
        toolbar.title = getString(R.string.text_editor)
        readerLayout?.let { content.removeView(it) }
        readerLayout = null
        readerTextView = null
        readerFooter = null
        editorScroll.visibility = android.view.View.VISIBLE
        invalidateOptionsMenu()
    }

    private fun showGoToPageDialog(pageCount: Int, onPage: (Int) -> Unit) {
        val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this)
            .setTitle(R.string.page_number)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val page = input.text.toString().toIntOrNull()?.minus(1) ?: 0
                onPage(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFontSizeDialog(textView: TextView, onChanged: () -> Unit) {
        val seek = SeekBar(this).apply {
            max = 30
            progress = ((textView.textSize / resources.displayMetrics.scaledDensity) - 18f).toInt().coerceIn(0, 30)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.font_size)
            .setView(FrameLayout(this).apply {
                setPadding(32, 16, 32, 16)
                addView(seek)
            })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                textView.textSize = 18f + seek.progress
                onChanged()
            }
            .show()
    }

    private fun closeEditor() {
        if (editor.text.toString() == originalText) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.document_not_saved)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pagesForRenderedText(text: String, textView: TextView): Pair<List<String>, List<Int>> {
        if (text.isEmpty()) return Pair(listOf(""), listOf(0))
        val width = (textView.width - textView.paddingLeft - textView.paddingRight).coerceAtLeast(1)
        val height = (textView.height - textView.paddingTop - textView.paddingBottom).coerceAtLeast(1)
        val paint = TextPaint(textView.paint)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(textView.lineSpacingExtra, textView.lineSpacingMultiplier)
            .setIncludePad(textView.includeFontPadding)
            .build()
        val lineHeight = (textView.lineHeight.takeIf { it > 0 } ?: paint.fontMetricsInt.let { it.descent - it.ascent })
            .coerceAtLeast(1)
        val linesPerPage = (height / lineHeight).coerceAtLeast(1)
        val pages = mutableListOf<String>()
        val starts = mutableListOf<Int>()
        var line = 0
        while (line < layout.lineCount) {
            val start = layout.getLineStart(line)
            val endLine = (line + linesPerPage - 1).coerceAtMost(layout.lineCount - 1)
            val end = layout.getLineEnd(endLine).coerceAtLeast(start)
            pages += text.substring(start, end).trimEnd('\n')
            starts += start
            line = endLine + 1
        }
        return Pair(pages.ifEmpty { listOf("") }, starts.ifEmpty { listOf(0) })
    }

    private fun isValidFileName(name: String): Boolean =
        name.isNotBlank() && "." in name && !name.contains(Regex("""[\\/:*?"<>|]"""))

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i).$ext")
            i += 1
        }
        return candidate
    }
}
