// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.myskytox.files

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.net.URLConnection
import java.text.Collator
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import ltd.evilcorp.atox.App
import ltd.evilcorp.atox.BuildConfig
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.ui.chat.SkyToxSystemShare
import ltd.evilcorp.atox.ui.media.MediaOpenTarget
import ltd.evilcorp.atox.ui.media.MediaViewerLauncher
import ltd.evilcorp.atox.ui.media.MediaViewerMode
import ltd.evilcorp.atox.ui.media.MediaViewerPolicy
import ltd.evilcorp.atox.ui.myskytox.files.audio.FileManagerAudioPreviewBar
import ltd.evilcorp.atox.ui.myskytox.files.audio.FileManagerAudioPreviewController
import ltd.evilcorp.atox.ui.myskytox.files.filesystem.MySkyToxEntry
import ltd.evilcorp.atox.ui.myskytox.files.filesystem.MySkyToxFileRepository
import ltd.evilcorp.atox.ui.myskytox.files.workspace.FileManagerClipboard
import ltd.evilcorp.atox.ui.myskytox.files.workspace.FolderViewMode
import ltd.evilcorp.atox.ui.myskytox.files.workspace.FolderWindowState
import ltd.evilcorp.atox.ui.myskytox.files.workspace.MAX_FOLDER_WINDOWS
import ltd.evilcorp.atox.ui.myskytox.files.workspace.MySkyToxWorkspaceSession
import ltd.evilcorp.atox.ui.myskytox.files.workspace.SortMode
import ltd.evilcorp.atox.ui.myskytox.files.workspace.TextEditorWindowState

private const val PREFS = "skytox_my_file_manager"
private const val CELL_W_DP = 84
private const val CELL_H_DP = 96
private const val EXIT_BAR_DP = 48
private const val HEADER_DP = 48
private const val MAX_TEXT_BYTES = 4L * 1024L * 1024L
private val FILE_MANAGER_AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

class MySkyToxFileManagerActivity : AppCompatActivity() {
    @Inject lateinit var repository: MySkyToxFileRepository
    @Inject lateinit var session: MySkyToxWorkspaceSession

    private lateinit var root: FrameLayout
    private lateinit var desktopLayer: FrameLayout
    private lateinit var windowLayer: FrameLayout
    private lateinit var exitBar: LinearLayout
    private lateinit var audioPreviewBar: FileManagerAudioPreviewBar
    private lateinit var audioPreviewController: FileManagerAudioPreviewController
    private val folderViews = linkedMapOf<String, FolderWindowView>()
    private var editorView: TextEditorWindowView? = null
    private var dragGhost: TextView? = null
    private var activePopup: PopupWindow? = null
    private var workspaceGestureInProgress = false
    private var refreshPending = false
    private val desktopViews = linkedMapOf<String, View>()
    private var desktopEntries: List<MySkyToxEntry> = emptyList()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val collator by lazy { Collator.getInstance(Locale.getDefault()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as App).component.inject(this)
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        buildWorkspace()
        lifecycleScope.launch {
            runCatching { repository.ensureRoot() }.onFailure(::showError)
            root.post {
                restoreSessionWindows()
                refreshAll()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) refreshAll()
    }

    override fun finish() {
        if (::audioPreviewController.isInitialized) audioPreviewController.close()
        activePopup?.dismiss()
        clearDragGhost()
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onStop() {
        if (::audioPreviewController.isInitialized) audioPreviewController.close()
        super.onStop()
    }

    override fun onDestroy() {
        if (::audioPreviewController.isInitialized) audioPreviewController.release()
        super.onDestroy()
    }

    private fun buildWorkspace() {
        root = FrameLayout(this).apply { setBackgroundColor(ContextCompat.getColor(this@MySkyToxFileManagerActivity, R.color.ocean_background)) }
        desktopLayer = FrameLayout(this)
        windowLayer = FrameLayout(this)
        audioPreviewBar = FileManagerAudioPreviewBar(this)
        audioPreviewController = FileManagerAudioPreviewController(this, audioPreviewBar) {
            Toast.makeText(this, R.string.filemanager_audio_error, Toast.LENGTH_LONG).show()
        }
        exitBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
            background = solid(ContextCompat.getColor(this@MySkyToxFileManagerActivity, R.color.colorPrimary), 0f)
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_fm_exit)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = getString(R.string.filemanager_exit)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        root.addView(desktopLayer, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(EXIT_BAR_DP) })
        root.addView(windowLayer, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(EXIT_BAR_DP) })
        root.addView(audioPreviewBar, FrameLayout.LayoutParams(dp(180), audioPreviewHeight()).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(EXIT_BAR_DP + 8)
        })
        root.addView(exitBar, FrameLayout.LayoutParams(-1, dp(EXIT_BAR_DP)).apply { gravity = Gravity.BOTTOM })
        setContentView(root)
        root.post {
            (audioPreviewBar.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.width = (windowLayer.width * .5f).roundToInt().coerceAtLeast(dp(180))
                params.height = audioPreviewHeight()
                audioPreviewBar.layoutParams = params
                audioPreviewBar.background = solid(
                    ContextCompat.getColor(this, R.color.ocean_card),
                    params.height / 2f,
                    ContextCompat.getColor(this, R.color.filemanager_window_border),
                )
            }
        }

        val desktopDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onLongPress(e: MotionEvent) { showEmptyMenuAt("", e.rawX, e.rawY) }
        })
        desktopLayer.setOnTouchListener { _, event -> desktopDetector.onTouchEvent(event) }
    }

    private fun restoreSessionWindows() {
        val snapshot = session.state.value
        snapshot.folderWindows.forEach { createFolderWindowView(it, raise = false) }
        snapshot.textEditor?.let { createEditorWindowView(it, raise = false) }
        val ordered = buildList<Pair<Long, View>> {
            snapshot.folderWindows.forEach { state -> folderViews[state.id]?.let { add(state.z to it) } }
            snapshot.textEditor?.let { state -> editorView?.let { add(state.z to it) } }
        }.sortedBy { it.first }
        ordered.forEach { (_, view) -> windowLayer.bringChildToFront(view) }
    }

    private fun refreshAll() {
        if (workspaceGestureInProgress) { refreshPending = true; return }
        lifecycleScope.launch {
            runCatching { repository.list("") }
                .onSuccess {
                    if (workspaceGestureInProgress) refreshPending = true
                    else { desktopEntries = it; renderDesktop() }
                }
                .onFailure(::showError)
            folderViews.values.toList().forEach { it.refresh() }
        }
    }

    private fun finishWorkspaceGesture() {
        workspaceGestureInProgress = false
        if (refreshPending) { refreshPending = false; refreshAll() }
    }

    private fun renderDesktop() {
        desktopLayer.removeAllViews()
        desktopViews.clear()
        val occupied = mutableSetOf<Pair<Int, Int>>()
        desktopEntries.forEach { entry ->
            val cell = storedOrFreeCell(entry.relativePath, occupied)
            occupied += cell
            val view = createDesktopIcon(entry)
            desktopViews[entry.relativePath] = view
            desktopLayer.addView(view, FrameLayout.LayoutParams(dp(CELL_W_DP), dp(CELL_H_DP)).apply {
                leftMargin = cell.second * dp(CELL_W_DP)
                topMargin = cell.first * dp(CELL_H_DP)
            })
        }
    }

    private fun createDesktopIcon(entry: MySkyToxEntry): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            addView(ImageView(context).apply {
                setImageResource(iconFor(entry))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(TextView(context).apply {
                text = displayName(entry)
                gravity = Gravity.CENTER
                maxLines = 2
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.ocean_text_primary))
            }, LinearLayout.LayoutParams(-1, dp(40)))
        }
        bindItemGesture(layout, entry, null, desktopItem = true)
        return layout
    }

    private fun bindItemGesture(view: View, entry: MySkyToxEntry, sourceWindow: FolderWindowView?, desktopItem: Boolean) {
        var contextShown = false
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleEntryDoubleTap(entry, sourceWindow)
                return true
            }
            override fun onLongPress(e: MotionEvent) { contextShown = true; showItemMenuAt(entry, sourceWindow?.state?.currentRelativePath ?: "", e.rawX, e.rawY) }
        })
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startRawX = 0f
        var startRawY = 0f
        var originalX = 0f
        var originalY = 0f
        var dragging = false
        view.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY; originalX = view.x; originalY = view.y; dragging = false; contextShown = false
                    bringWindowToFront(sourceWindow)
                }
                MotionEvent.ACTION_MOVE -> if (!contextShown && event.pointerCount == 1 && (dragging || abs(event.rawX - startRawX) > slop || abs(event.rawY - startRawY) > slop)) {
                    if (!dragging && !desktopItem) showDragGhost(entry, event.rawX, event.rawY)
                    dragging = true
                    workspaceGestureInProgress = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    if (desktopItem) {
                        view.x = originalX + event.rawX - startRawX
                        view.y = originalY + event.rawY - startRawY
                    } else {
                        moveDragGhost(event.rawX, event.rawY)
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    clearDragGhost()
                    val target = dropTarget(event.rawX, event.rawY, entry)
                    if (entry.recycleId != null || entry.protected) {
                        if (desktopItem) snapDesktopIcon(entry.relativePath, view)
                    } else if (target == "Recycle Bin") {
                        recycleEntry(entry)
                    } else if (target != null && target != entry.relativePath.substringBeforeLast('/', "")) {
                        moveWithConflict(entry.relativePath, target)
                    } else if (desktopItem) {
                        snapDesktopIcon(entry.relativePath, view)
                    }
                    finishWorkspaceGesture()
                }
                MotionEvent.ACTION_CANCEL -> { clearDragGhost(); if (desktopItem) { view.x = originalX; view.y = originalY }; finishWorkspaceGesture() }
                MotionEvent.ACTION_POINTER_DOWN -> { clearDragGhost(); if (desktopItem) { view.x = originalX; view.y = originalY }; dragging = false; finishWorkspaceGesture() }
            }
            true
        }
    }

    private fun showDragGhost(entry: MySkyToxEntry, rawX: Float, rawY: Float) {
        clearDragGhost()
        dragGhost = TextView(this).apply {
            text = entry.name
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = solid(0xCCFFFFFF.toInt(), dp(6).toFloat(), Color.GRAY)
            alpha = .75f
        }.also { ghost ->
            root.addView(ghost, FrameLayout.LayoutParams(dp(140), dp(56)))
            moveDragGhost(rawX, rawY)
        }
    }

    private fun moveDragGhost(rawX: Float, rawY: Float) {
        val location = IntArray(2); root.getLocationOnScreen(location)
        dragGhost?.x = rawX - location[0] - dp(70)
        dragGhost?.y = rawY - location[1] - dp(28)
    }

    private fun clearDragGhost() { dragGhost?.let(root::removeView); dragGhost = null }

    private fun finishDrop(entry: MySkyToxEntry, rawX: Float, rawY: Float) {
        val target = dropTarget(rawX, rawY, entry)
        if (entry.recycleId != null || entry.protected) return
        if (target == "Recycle Bin") recycleEntry(entry)
        else if (target != null && target != entry.relativePath.substringBeforeLast('/', "")) moveWithConflict(entry.relativePath, target)
    }

    private fun openFolderWindow(path: String) {
        folderViews.values.firstOrNull { it.state.currentRelativePath == path }?.let { bringWindowToFront(it); return }
        val current = session.state.value
        if (current.folderWindows.size >= MAX_FOLDER_WINDOWS) {
            Toast.makeText(this, R.string.filemanager_window_limit, Toast.LENGTH_LONG).show(); return
        }
        val offset = current.folderWindows.size * dp(18)
        val state = FolderWindowState(UUID.randomUUID().toString(), path, dp(24) + offset, dp(24) + offset, session.nextZ())
        session.update { it.copy(folderWindows = it.folderWindows + state) }
        createFolderWindowView(state)
    }

    private fun createFolderWindowView(initial: FolderWindowState, raise: Boolean = true) {
        if (folderViews.containsKey(initial.id)) return
        if (windowLayer.width == 0 || windowLayer.height == 0) { windowLayer.post { createFolderWindowView(initial, raise) }; return }
        val view = FolderWindowView(initial)
        folderViews[initial.id] = view
        val lp = FrameLayout.LayoutParams((windowLayer.width * .8f).roundToInt().coerceAtLeast(dp(280)), (windowLayer.height * .5f).roundToInt().coerceAtLeast(dp(240)))
        lp.leftMargin = initial.x.coerceIn(0, (windowLayer.width - lp.width).coerceAtLeast(0))
        lp.topMargin = initial.y.coerceIn(0, (windowLayer.height - lp.height).coerceAtLeast(0))
        windowLayer.addView(view, lp)
        view.refresh()
        if (raise) raiseWindow(view)
    }

    private fun closeFolderWindow(view: FolderWindowView) {
        folderViews.remove(view.state.id)
        windowLayer.removeView(view)
        session.update { it.copy(folderWindows = it.folderWindows.filterNot { state -> state.id == view.state.id }) }
    }

    private fun bringWindowToFront(view: FolderWindowView?) { if (view != null) raiseWindow(view) }

    private fun raiseWindow(view: View) {
        if (view.parent !== windowLayer) return
        windowLayer.bringChildToFront(view)
        val z = session.nextZ()
        when (view) {
            is FolderWindowView -> { view.state = view.state.copy(z = z); updateFolderState(view.state) }
            is TextEditorWindowView -> { view.state = view.state.copy(z = z); session.update { it.copy(textEditor = view.state) } }
        }
    }

    private fun updateFolderState(state: FolderWindowState) {
        session.update { current -> current.copy(folderWindows = current.folderWindows.map { if (it.id == state.id) state else it }) }
    }

    private fun openTextEditor(path: String) {
        editorView?.let { current ->
            if (current.state.relativePath == path) { bringEditorToFront(); return }
            if (current.documentDirty) {
                AlertDialog.Builder(this).setMessage(R.string.filemanager_save_changes)
                    .setPositiveButton(R.string.filemanager_yes) { _, _ -> current.save { closeEditor(true); openTextEditor(path) } }
                    .setNegativeButton(R.string.filemanager_no) { _, _ -> closeEditor(true); openTextEditor(path) }
                    .setNeutralButton(R.string.filemanager_cancel, null).show()
                return
            }
            closeEditor(true)
        }
        lifecycleScope.launch {
            runCatching { repository.readText(path, MAX_TEXT_BYTES) }
                .onSuccess { text ->
                    val state = TextEditorWindowState(path, dp(36), dp(36), session.nextZ(), text, text)
                    session.update { it.copy(textEditor = state) }
                    createEditorWindowView(state)
                }.onFailure(::showError)
        }
    }

    private fun createEditorWindowView(state: TextEditorWindowState, raise: Boolean = true) {
        if (editorView != null) return
        if (windowLayer.width == 0 || windowLayer.height == 0) { windowLayer.post { createEditorWindowView(state, raise) }; return }
        val view = TextEditorWindowView(state)
        editorView = view
        val lp = FrameLayout.LayoutParams((windowLayer.width * .8f).roundToInt().coerceAtLeast(dp(280)), (windowLayer.height * .5f).roundToInt().coerceAtLeast(dp(240))).apply {
            leftMargin = state.x.coerceIn(0, (windowLayer.width - width).coerceAtLeast(0)); topMargin = state.y.coerceIn(0, (windowLayer.height - height).coerceAtLeast(0))
        }
        windowLayer.addView(view, lp)
        if (raise) raiseWindow(view)
    }

    private fun bringEditorToFront() {
        editorView?.let { view ->
            raiseWindow(view)
        }
    }

    private fun closeEditor(force: Boolean = false) {
        val view = editorView ?: return
        if (!force && view.documentDirty) {
            AlertDialog.Builder(this).setMessage(R.string.filemanager_save_changes)
                .setPositiveButton(R.string.filemanager_yes) { _, _ -> view.save { closeEditor(true) } }
                .setNegativeButton(R.string.filemanager_no) { _, _ -> closeEditor(true) }
                .setNeutralButton(R.string.filemanager_cancel, null).show()
            return
        }
        windowLayer.removeView(view); editorView = null; session.update { it.copy(textEditor = null) }
    }

    private fun showEmptyMenuAt(parentPath: String, rawX: Float, rawY: Float) {
        if (parentPath == "Recycle Bin") return
        val actions = mutableListOf(
            getString(R.string.filemanager_create_folder) to { promptName(R.string.filemanager_create_folder, getString(R.string.filemanager_new_folder)) { name -> runOperation(action = { repository.createFolder(parentPath, name) }) } },
            getString(R.string.filemanager_create_text) to { promptName(R.string.filemanager_create_text, getString(R.string.filemanager_new_text)) { name -> runOperation(action = { repository.createText(parentPath, name) }) } },
        )
        if (session.state.value.clipboard != null) actions += getString(R.string.filemanager_paste) to { pasteInto(parentPath) }
        showActionsAt(rawX, rawY, actions)
    }

    private fun showItemMenuAt(entry: MySkyToxEntry, parentPath: String, rawX: Float, rawY: Float) {
        if (entry.relativePath == "Downloads") return
        if (entry.relativePath == "Recycle Bin") {
            showActionsAt(rawX, rawY, listOf(getString(R.string.filemanager_empty_recycle_bin) to ::confirmClearRecycleBin))
            return
        }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (entry.recycleId != null) {
            actions += getString(R.string.filemanager_restore) to { restoreEntry(entry) }
            actions += getString(R.string.filemanager_delete_forever) to { confirmDeleteForever(entry) }
        } else {
            if (!entry.protected) actions += getString(R.string.filemanager_rename) to { promptName(R.string.filemanager_rename, entry.name) { name -> renameEntry(entry, name) } }
            actions += getString(R.string.filemanager_copy) to { session.update { it.copy(clipboard = FileManagerClipboard(listOf(entry.relativePath))) } }
            if (session.state.value.clipboard != null) actions += getString(R.string.filemanager_paste) to { pasteInto(if (entry.isDirectory) entry.relativePath else parentPath) }
            if (!entry.protected) actions += getString(R.string.filemanager_delete) to { recycleEntry(entry) }
            if (!entry.isDirectory) actions += getString(R.string.filemanager_share) to { SkyToxSystemShare.launchFiles(this, listOf(repository.fileForShare(entry.relativePath))) }
        }
        showActionsAt(rawX, rawY, actions)
    }

    private fun confirmClearRecycleBin() {
        AlertDialog.Builder(this)
            .setTitle(R.string.filemanager_empty_recycle_bin)
            .setMessage(R.string.filemanager_empty_recycle_bin_message)
            .setPositiveButton(R.string.filemanager_empty) { _, _ -> runOperation(action = repository::clearRecycleBin) }
            .setNegativeButton(R.string.filemanager_cancel, null)
            .show()
    }

    private fun showActionsAt(rawX: Float, rawY: Float, actions: List<Pair<String, () -> Unit>>) {
        if (actions.isEmpty()) return
        activePopup?.dismiss()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = solid(ContextCompat.getColor(context, R.color.ocean_card), dp(10).toFloat(), ContextCompat.getColor(context, R.color.colorPrimaryDark))
            actions.forEach { (label, action) ->
                addView(TextView(context).apply {
                    text = label; textSize = 16f; gravity = Gravity.CENTER_VERTICAL
                    setTextColor(ContextCompat.getColor(context, R.color.ocean_text_primary)); setPadding(dp(16), 0, dp(16), 0)
                    setOnClickListener { activePopup?.dismiss(); action() }
                }, LinearLayout.LayoutParams(dp(250), dp(48)))
            }
        }
        content.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(windowLayer.height, View.MeasureSpec.AT_MOST))
        val popup = PopupWindow(content, content.measuredWidth, content.measuredHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable = true; elevation = dp(8).toFloat(); animationStyle = 0
        }
        activePopup = popup
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        val x = (rawX - rootLocation[0] - content.measuredWidth / 2f).roundToInt().coerceIn(0, (root.width - content.measuredWidth).coerceAtLeast(0))
        val preferredY = (rawY - rootLocation[1] + dp(8)).roundToInt()
        val y = preferredY.coerceIn(0, (windowLayer.height - content.measuredHeight).coerceAtLeast(0))
        popup.setOnDismissListener { if (activePopup === popup) activePopup = null }
        popup.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
    }

    private fun pasteInto(target: String) {
        val sources = session.state.value.clipboard?.sources.orEmpty()
        if (sources.isEmpty()) return
        lifecycleScope.launch {
            runCatching { repository.copy(sources, target, false) }
                .onSuccess { refreshAll() }
                .onFailure { error ->
                    if (error.message?.contains("exists", true) == true) confirmReplace { runOperation(action = { repository.copy(sources, target, true) }) }
                    else showError(error)
                }
        }
    }

    private fun moveWithConflict(path: String, target: String) {
        closeAudioPreviewIfAffected(path)
        lifecycleScope.launch {
            runCatching { repository.move(path, target, false) }
                .onSuccess { newPath -> onPathMoved(path, newPath); refreshAll() }
                .onFailure { error ->
                    if (error.message?.contains("exists", true) == true) confirmReplace {
                        runOperation({ repository.move(path, target, true) }) { newPath -> onPathMoved(path, newPath) }
                    } else showError(error)
                }
        }
    }

    private fun renameEntry(entry: MySkyToxEntry, name: String) {
        closeAudioPreviewIfAffected(entry.relativePath)
        lifecycleScope.launch {
            runCatching { repository.rename(entry.relativePath, name, false) }
                .onSuccess { newPath -> onPathMoved(entry.relativePath, newPath); refreshAll() }
                .onFailure { error ->
                    if (error.message?.contains("exists", true) == true) confirmReplace {
                        runOperation({ repository.rename(entry.relativePath, name, true) }) { newPath -> onPathMoved(entry.relativePath, newPath) }
                    } else showError(error)
                }
        }
    }

    private fun onPathMoved(oldPath: String, newPath: String) {
        val oldPos = prefs.getString("pos:$oldPath", null)
        prefs.edit().remove("pos:$oldPath").apply { if (oldPos != null && !newPath.contains('/')) putString("pos:$newPath", oldPos) }.apply()
        session.rewritePrefix(oldPath, newPath)
        folderViews.values.forEach { it.syncFromSession() }
        editorView?.syncFromSession()
    }

    private fun recycleEntry(entry: MySkyToxEntry) {
        closeAudioPreviewIfAffected(entry.relativePath)
        val editor = editorView
        if (editor != null && (editor.state.relativePath == entry.relativePath || editor.state.relativePath.startsWith(entry.relativePath + "/")) && editor.documentDirty) {
            AlertDialog.Builder(this).setMessage(R.string.filemanager_save_changes)
                .setPositiveButton(R.string.filemanager_yes) { _, _ -> editor.save { closeEditor(true); recycleEntry(entry) } }
                .setNegativeButton(R.string.filemanager_no) { _, _ -> closeEditor(true); recycleEntry(entry) }
                .setNeutralButton(R.string.filemanager_cancel, null).show(); return
        }
        runOperation({ repository.recycle(entry.relativePath) }) {
            if (editorView?.state?.relativePath == entry.relativePath || editorView?.state?.relativePath?.startsWith(entry.relativePath + "/") == true) closeEditor(true)
            session.update { current -> current.copy(folderWindows = current.folderWindows.filterNot { state -> state.currentRelativePath == entry.relativePath || state.currentRelativePath.startsWith(entry.relativePath + "/") }) }
            folderViews.values.filter { it.state.currentRelativePath == entry.relativePath || it.state.currentRelativePath.startsWith(entry.relativePath + "/") }.toList().forEach(::closeFolderWindow)
            prefs.edit().remove("pos:${entry.relativePath}").apply()
        }
    }

    private fun restoreEntry(entry: MySkyToxEntry) {
        val id = entry.recycleId ?: return
        lifecycleScope.launch {
            runCatching { repository.restore(id, false) }
                .onSuccess { refreshAll() }
                .onFailure { error ->
                    if (error.message?.contains("exists", true) == true) confirmReplace { runOperation(action = { repository.restore(id, true) }) }
                    else showError(error)
                }
        }
    }

    private fun confirmDeleteForever(entry: MySkyToxEntry) {
        AlertDialog.Builder(this).setTitle(R.string.filemanager_delete_forever).setMessage(entry.name)
            .setPositiveButton(R.string.filemanager_delete) { _, _ -> entry.recycleId?.let { id -> runOperation(action = { repository.permanentDelete(id) }) } }
            .setNegativeButton(R.string.filemanager_cancel, null).show()
    }

    private fun confirmReplace(action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(R.string.filemanager_replace_title).setMessage(R.string.filemanager_replace_message)
            .setPositiveButton(R.string.filemanager_replace) { _, _ -> action() }
            .setNegativeButton(R.string.filemanager_cancel, null).show()
    }

    private fun promptName(title: Int, initial: String, action: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); selectAll() }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> action(input.text.toString()) }
            .setNegativeButton(R.string.filemanager_cancel, null).show()
    }

    private fun <T> runOperation(action: suspend () -> T, success: (T) -> Unit = {}) {
        lifecycleScope.launch { runCatching { action() }.onSuccess { success(it); refreshAll() }.onFailure(::showError) }
    }

    private fun dropTarget(rawX: Float, rawY: Float, dragged: MySkyToxEntry): String? {
        for (index in windowLayer.childCount - 1 downTo 0) {
            val child = windowLayer.getChildAt(index)
            if (!pointInside(child, rawX, rawY)) continue
            return (child as? FolderWindowView)?.targetAt(rawX, rawY, dragged)
        }
        desktopEntries.asSequence().filter { it.isDirectory && validDropPath(it.relativePath, dragged) }
            .firstOrNull { entry -> desktopViews[entry.relativePath]?.let { pointInside(it, rawX, rawY) } == true }?.let { return it.relativePath }
        return if (pointInside(desktopLayer, rawX, rawY)) "" else null
    }

    private fun validDropPath(target: String, dragged: MySkyToxEntry): Boolean =
        target != dragged.relativePath && (!dragged.isDirectory || !target.startsWith(dragged.relativePath + "/"))

    private fun snapDesktopIcon(path: String, view: View) {
        val cols = (desktopLayer.width / dp(CELL_W_DP)).coerceAtLeast(1)
        val rows = (desktopLayer.height / dp(CELL_H_DP)).coerceAtLeast(1)
        var col = (view.x / dp(CELL_W_DP)).roundToInt().coerceIn(0, cols - 1)
        var row = (view.y / dp(CELL_H_DP)).roundToInt().coerceIn(0, rows - 1)
        val occupied = desktopViews.filterKeys { it != path }.values.map { ((it.y / dp(CELL_H_DP)).roundToInt()) to ((it.x / dp(CELL_W_DP)).roundToInt()) }.toSet()
        if (row to col in occupied) {
            val free = (0 until rows).flatMap { r -> (0 until cols).map { c -> r to c } }.filterNot { it in occupied }.minByOrNull { abs(it.first - row) + abs(it.second - col) }
            if (free != null) { row = free.first; col = free.second }
        }
        view.x = (col * dp(CELL_W_DP)).toFloat(); view.y = (row * dp(CELL_H_DP)).toFloat()
        prefs.edit().putString("pos:$path", "$row,$col").apply()
    }

    private fun storedOrFreeCell(path: String, occupied: Set<Pair<Int, Int>>): Pair<Int, Int> {
        prefs.getString("pos:$path", null)?.split(',')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 2 }?.let { if (it[0] to it[1] !in occupied) return it[0] to it[1] }
        val cols = (root.resources.displayMetrics.widthPixels / dp(CELL_W_DP)).coerceAtLeast(1)
        var index = 0
        while (index / cols to index % cols in occupied) index++
        val cell = index / cols to index % cols
        prefs.edit().putString("pos:$path", "${cell.first},${cell.second}").apply()
        return cell
    }

    private fun pointInside(view: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2); view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX < location[0] + view.width && rawY >= location[1] && rawY < location[1] + view.height
    }

    private fun displayName(entry: MySkyToxEntry): String = when (entry.relativePath) {
        "Downloads" -> getString(R.string.filemanager_downloads)
        "Recycle Bin" -> getString(R.string.filemanager_recycle_bin)
        else -> entry.name
    }

    private fun iconFor(entry: MySkyToxEntry): Int {
        val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
        entry.relativePath == "Downloads" -> R.drawable.ic_fm_downloads
        entry.relativePath == "Recycle Bin" -> R.drawable.ic_fm_recycle
        entry.isDirectory -> R.drawable.ic_fm_folder
        isTextFile(entry.name) -> R.drawable.ic_fm_text
        extension in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> R.drawable.ic_fm_image
        extension in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v") -> R.drawable.ic_fm_video
        extension in FILE_MANAGER_AUDIO_EXTENSIONS -> R.drawable.ic_fm_audio
        extension == "pdf" -> R.drawable.ic_fm_pdf
        extension in setOf("doc", "docx", "odt", "rtf") -> R.drawable.ic_fm_doc
        extension in setOf("xls", "xlsx", "ods") -> R.drawable.ic_fm_xls
        extension in setOf("zip", "7z", "rar", "tar", "gz") -> R.drawable.ic_fm_archive
        extension == "apk" -> R.drawable.ic_fm_apk
        else -> R.drawable.ic_fm_file
        }
    }

    private fun isTextFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.US) in setOf("txt", "json", "md", "markdown", "html", "htm", "xml", "csv", "log", "ini", "conf", "cfg", "yml", "yaml", "kt", "java", "js", "ts", "css", "sh", "bat", "ps1", "py", "c", "cpp", "h", "hpp")

    private fun isFileManagerAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.US) in FILE_MANAGER_AUDIO_EXTENSIONS

    private fun handleEntryDoubleTap(entry: MySkyToxEntry, sourceWindow: FolderWindowView?) {
        if (entry.recycleId != null) return
        if (entry.isDirectory) {
            if (sourceWindow == null) openFolderWindow(entry.relativePath) else sourceWindow.navigate(entry.relativePath)
            return
        }
        if (isTextFile(entry.name)) { openTextEditor(entry.relativePath); return }
        if (isFileManagerAudio(entry.name)) { openAudioPreview(entry); return }
        openMediaFromFileManager(entry)
    }

    private fun openAudioPreview(entry: MySkyToxEntry) {
        runCatching { repository.fileForShare(entry.relativePath) }
            .onSuccess { file -> audioPreviewController.open(entry.relativePath, file) }
            .onFailure(::showError)
    }

    private fun closeAudioPreviewIfAffected(path: String) {
        val current = audioPreviewController.currentRelativePath ?: return
        if (current == path || current.startsWith("$path/")) audioPreviewController.close()
    }

    private fun openMediaFromFileManager(entry: MySkyToxEntry): Boolean {
        if (entry.isDirectory || entry.recycleId != null) return false
        val file = runCatching { repository.fileForShare(entry.relativePath) }.getOrElse { showError(it); return true }
        val mime = resolveLocalMimeType(file.name)
        val target = MediaViewerPolicy.classify(file.name, mime)
        if (target == MediaOpenTarget.EXTERNAL) return false
        val uri = runCatching { FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file) }
            .getOrElse { showError(it); return true }
        audioPreviewController.close()
        MediaViewerLauncher.openInternal(
            this,
            uri,
            file.name,
            mime,
            if (target == MediaOpenTarget.INTERNAL_IMAGE) MediaViewerMode.IMAGE else MediaViewerMode.VIDEO,
        )
        overridePendingTransition(0, 0)
        return true
    }

    private fun resolveLocalMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        return URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }

    private fun showError(error: Throwable) {
        val raw = error.message.orEmpty()
        val message = when {
            raw.contains("too large", true) -> getString(R.string.filemanager_text_too_large)
            raw.contains("Cannot move folder into itself", true) || raw.contains("Cannot copy folder into itself", true) || raw.contains("Source and destination are the same", true) -> getString(R.string.filemanager_error_self_move)
            raw.contains("Source", true) && (raw.contains("not found", true) || raw.contains("no longer exists", true)) -> getString(R.string.filemanager_error_source_missing)
            raw.contains("Target folder", true) || raw.contains("destination", true) -> getString(R.string.filemanager_error_target_missing)
            raw.contains("Document", true) || raw.contains("Not a document", true) -> getString(R.string.filemanager_error_document_missing)
            raw.contains("Invalid name", true) -> getString(R.string.filemanager_error_invalid_name)
            raw.contains("Protected folder", true) -> getString(R.string.filemanager_error_protected)
            else -> getString(R.string.filemanager_operation_failed, raw.ifBlank { error.javaClass.simpleName })
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun audioPreviewHeight(): Int = ((10f / 25.4f) * resources.displayMetrics.ydpi).roundToInt().coerceIn(dp(36), dp(52))
    private fun solid(color: Int, radius: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = radius; stroke?.let { setStroke(dp(1), it) } }

    private inner class FolderWindowView(initial: FolderWindowState) : LinearLayout(this@MySkyToxFileManagerActivity) {
        var state = initial
        private val title = TextView(context)
        private lateinit var upButton: Button
        private var adapterView: AbsListView? = null
        private var entries: List<MySkyToxEntry> = emptyList()

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) raiseWindow(this)
            return super.dispatchTouchEvent(event)
        }

        init {
            orientation = VERTICAL
            background = solid(Color.WHITE, dp(4).toFloat(), ContextCompat.getColor(context, R.color.colorPrimaryDark))
            foreground = solid(Color.TRANSPARENT, dp(4).toFloat(), ContextCompat.getColor(context, R.color.filemanager_window_border))
            val header = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0); background = solid(ContextCompat.getColor(context, R.color.colorPrimary), 0f)
            }
            fun button(icon: Int, description: Int, click: (View) -> Unit) = ImageButton(context).apply { setImageResource(icon); setBackgroundColor(Color.TRANSPARENT); contentDescription = getString(description); setOnClickListener(click) }
            upButton = Button(context).apply { setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_fm_up, 0, 0, 0); contentDescription = getString(R.string.filemanager_up); setOnClickListener { navigate(state.currentRelativePath.substringBeforeLast('/', "")) }; text = "" }
            header.addView(upButton, LayoutParams(dp(44), dp(HEADER_DP)))
            header.addView(button(R.drawable.ic_fm_sort, R.string.filemanager_sort) { showSortMenu(it) }, LayoutParams(dp(48), dp(HEADER_DP)))
            header.addView(button(R.drawable.ic_fm_view, R.string.filemanager_view) { showViewMenu(it) }, LayoutParams(dp(48), dp(HEADER_DP)))
            header.addView(title.apply { gravity = Gravity.CENTER; maxLines = 1 }, LayoutParams(0, dp(HEADER_DP), 1f))
            header.addView(button(R.drawable.ic_close, R.string.filemanager_close) { closeFolderWindow(this@FolderWindowView) }, LayoutParams(dp(48), dp(HEADER_DP)))
            addView(header, LayoutParams(-1, dp(HEADER_DP)))
            bindWindowDrag(header, this) { x, y -> state = state.copy(x = x, y = y); updateFolderState(state) }
            rebuildContent()
        }

        fun refresh() {
            lifecycleScope.launch {
                runCatching { repository.list(state.currentRelativePath) }.onSuccess { list ->
                    if (workspaceGestureInProgress) { refreshPending = true; return@onSuccess }
                    entries = sort(list); (adapterView?.adapter as? EntryAdapter)?.items = entries; (adapterView?.adapter as? BaseAdapter)?.notifyDataSetChanged(); title.text = if (state.currentRelativePath.isBlank()) getString(R.string.my_skytox_file_manager) else state.currentRelativePath.substringAfterLast('/')
                    upButton.isEnabled = state.currentRelativePath.isNotBlank() && state.currentRelativePath != "Recycle Bin"
                }.onFailure(::showError)
            }
        }

        fun navigate(path: String) {
            state = state.copy(currentRelativePath = path); updateFolderState(state); refresh()
        }

        fun targetAt(rawX: Float, rawY: Float, dragged: MySkyToxEntry): String? {
            val list = adapterView ?: return state.currentRelativePath.takeIf { validDropPath(it, dragged) }
            val location = IntArray(2); list.getLocationOnScreen(location)
            val position = list.pointToPosition((rawX - location[0]).toInt(), (rawY - location[1]).toInt())
            val pointed = entries.getOrNull(position)
            if (pointed != null && pointed.isDirectory && pointed.recycleId == null) return pointed.relativePath.takeIf { validDropPath(it, dragged) }
            return state.currentRelativePath.takeIf { validDropPath(it, dragged) }
        }

        fun syncFromSession() { session.state.value.folderWindows.firstOrNull { it.id == state.id }?.let { state = it; refresh() } }

        fun entryAt(position: Int): MySkyToxEntry? = entries.getOrNull(position)

        private fun rebuildContent() {
            adapterView?.let(::removeView)
            val view: AbsListView = if (state.viewMode == FolderViewMode.LIST) ListView(context) else GridView(context).apply { numColumns = 3; verticalSpacing = dp(8); horizontalSpacing = dp(4) }
            view.setBackgroundColor(Color.WHITE)
            view.adapter = EntryAdapter(entries, state.viewMode)
            FolderContentGestureController(view, this)
            adapterView = view
            addView(view, LayoutParams(-1, 0, 1f))
        }

        private fun sort(items: List<MySkyToxEntry>): List<MySkyToxEntry> = items.sortedWith(compareByDescending<MySkyToxEntry> { it.isDirectory }.thenComparator { a, b ->
            if (state.sortMode == SortMode.DATE) b.modified.compareTo(a.modified) else collator.compare(a.name, b.name)
        })

        private fun showSortMenu(anchor: View) = PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, R.string.filemanager_sort_name); menu.add(0, 2, 1, R.string.filemanager_sort_date)
            setOnMenuItemClickListener { state = state.copy(sortMode = if (it.itemId == 1) SortMode.NAME else SortMode.DATE); updateFolderState(state); refresh(); true }
        }.show()

        private fun showViewMenu(anchor: View) = PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, R.string.filemanager_view_list); menu.add(0, 2, 1, R.string.filemanager_view_tiles)
            setOnMenuItemClickListener { state = state.copy(viewMode = if (it.itemId == 1) FolderViewMode.LIST else FolderViewMode.TILES); updateFolderState(state); rebuildContent(); refresh(); true }
        }.show()

        private inner class EntryAdapter(var items: List<MySkyToxEntry>, private val mode: FolderViewMode) : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val entry = getItem(position)
                val row = LinearLayout(context).apply {
                    orientation = if (mode == FolderViewMode.LIST) HORIZONTAL else VERTICAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)); setBackgroundColor(Color.WHITE)
                    addView(ImageView(context).apply { setImageResource(iconFor(entry)); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LayoutParams(dp(44), dp(44)))
                    addView(TextView(context).apply { text = entry.name; setTextColor(Color.BLACK); gravity = Gravity.CENTER; maxLines = 2 }, if (mode == FolderViewMode.LIST) LayoutParams(0, dp(52), 1f) else LayoutParams(-1, dp(40)))
                }
                return row
            }
        }
    }

    private inner class FolderContentGestureController(
        private val list: AbsListView,
        private val window: FolderWindowView,
    ) : View.OnTouchListener {
        private val handler = Handler(Looper.getMainLooper())
        private val slop = ViewConfiguration.get(this@MySkyToxFileManagerActivity).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0f
        private var downY = 0f
        private var downPosition = AdapterView.INVALID_POSITION
        private var dragArmed = false
        private var dragging = false
        private var scrolling = false
        private var menuShown = false
        private var draggedEntry: MySkyToxEntry? = null
        private var lastTapTime = 0L
        private var lastTapPosition = AdapterView.INVALID_POSITION

        private val armDrag = Runnable { if (!scrolling && !menuShown) dragArmed = true }
        private val showContext = Runnable {
            if (scrolling || dragging) return@Runnable
            menuShown = true
            dragArmed = false
            val now = android.os.SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0).also { cancel -> list.onTouchEvent(cancel); cancel.recycle() }
            val entry = window.entryAt(downPosition)
            if (entry == null) showEmptyMenuAt(window.state.currentRelativePath, downRawX, downRawY)
            else showItemMenuAt(entry, window.state.currentRelativePath, downRawX, downRawY)
        }

        init { list.setOnTouchListener(this) }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelCallbacks()
                    downRawX = event.rawX; downRawY = event.rawY; downX = event.x; downY = event.y
                    downPosition = list.pointToPosition(event.x.toInt(), event.y.toInt())
                    dragArmed = false; dragging = false; scrolling = false; menuShown = false; draggedEntry = null
                    handler.postDelayed(armDrag, 220L)
                    handler.postDelayed(showContext, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = abs(event.rawX - downRawX) > slop || abs(event.rawY - downRawY) > slop
                    if (dragging) { moveDragGhost(event.rawX, event.rawY); return true }
                    if (moved && dragArmed) {
                        val entry = window.entryAt(downPosition)
                        if (entry != null && !entry.protected && entry.recycleId == null) {
                            cancelCallbacks(); dragging = true; draggedEntry = entry; workspaceGestureInProgress = true
                            list.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.obtain(event).also { cancel -> cancel.action = MotionEvent.ACTION_CANCEL; list.onTouchEvent(cancel); cancel.recycle() }
                            showDragGhost(entry, event.rawX, event.rawY)
                            return true
                        }
                        scrolling = true; cancelCallbacks()
                    }
                    if (moved && !dragArmed) { scrolling = true; cancelCallbacks() }
                }
                MotionEvent.ACTION_UP -> {
                    cancelCallbacks()
                    if (dragging) {
                        val entry = draggedEntry
                        clearDragGhost(); list.parent?.requestDisallowInterceptTouchEvent(false)
                        if (entry != null) finishDrop(entry, event.rawX, event.rawY)
                        resetGesture(); finishWorkspaceGesture(); return true
                    }
                    if (menuShown) { resetGesture(); return true }
                    if (!scrolling && abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                        val now = event.eventTime
                        if (downPosition != AdapterView.INVALID_POSITION && downPosition == lastTapPosition && now - lastTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
                            window.entryAt(downPosition)?.let { handleEntryDoubleTap(it, window) }
                            lastTapTime = 0L; lastTapPosition = AdapterView.INVALID_POSITION
                        } else { lastTapTime = now; lastTapPosition = downPosition }
                    }
                    resetGesture()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelCallbacks(); clearDragGhost(); list.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragging) finishWorkspaceGesture()
                    resetGesture()
                }
            }
            return dragging || menuShown
        }

        private fun cancelCallbacks() { handler.removeCallbacks(armDrag); handler.removeCallbacks(showContext) }
        private fun resetGesture() { dragArmed = false; dragging = false; scrolling = false; menuShown = false; draggedEntry = null }
    }

    private inner class TextEditorWindowView(initial: TextEditorWindowState) : LinearLayout(this@MySkyToxFileManagerActivity) {
        var state = initial
        private val editor = EditText(context)
        val documentDirty get() = editor.text.toString() != state.originalText

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) raiseWindow(this)
            return super.dispatchTouchEvent(event)
        }

        init {
            orientation = VERTICAL; background = solid(Color.WHITE, dp(4).toFloat(), Color.GRAY)
            foreground = solid(Color.TRANSPARENT, dp(4).toFloat(), ContextCompat.getColor(context, R.color.filemanager_window_border))
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.LTGRAY) }
            header.addView(ImageButton(context).apply { setImageResource(R.drawable.ic_fm_save); setBackgroundColor(Color.TRANSPARENT); contentDescription = getString(R.string.filemanager_save); setOnClickListener { save() } }, LayoutParams(dp(52), dp(HEADER_DP)))
            header.addView(TextView(context).apply { text = state.relativePath.substringAfterLast('/'); gravity = Gravity.CENTER; setTextColor(Color.BLACK) }, LayoutParams(0, dp(HEADER_DP), 1f))
            header.addView(ImageButton(context).apply { setImageResource(R.drawable.ic_close); setBackgroundColor(Color.TRANSPARENT); contentDescription = getString(R.string.filemanager_close); setOnClickListener { closeEditor() } }, LayoutParams(dp(52), dp(HEADER_DP)))
            addView(header, LayoutParams(-1, dp(HEADER_DP)))
            editor.setText(state.currentText); editor.gravity = Gravity.TOP or Gravity.START; editor.setTextColor(Color.BLACK); editor.setBackgroundColor(Color.WHITE); editor.isHorizontalScrollBarEnabled = false; editor.setHorizontallyScrolling(false); editor.setSingleLine(false)
            editor.doAfterTextChanged { text -> state = state.copy(currentText = text?.toString().orEmpty()); session.update { it.copy(textEditor = state) } }
            addView(editor, LayoutParams(-1, 0, 1f))
            bindWindowDrag(header, this) { x, y -> state = state.copy(x = x, y = y); session.update { it.copy(textEditor = state) } }
        }

        fun save(after: () -> Unit = {}) {
            val text = editor.text.toString()
            runOperation({ repository.saveText(state.relativePath, text) }) {
                state = state.copy(currentText = text, originalText = text); session.update { current -> current.copy(textEditor = state) }; after()
            }
        }

        fun syncFromSession() { session.state.value.textEditor?.let { state = it } }
    }

    private fun bindWindowDrag(header: View, window: View, onMoved: (Int, Int) -> Unit) {
        var downRawX = 0f; var downRawY = 0f; var startX = 0; var startY = 0
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = (window.layoutParams as FrameLayout.LayoutParams).leftMargin; startY = (window.layoutParams as FrameLayout.LayoutParams).topMargin
                    raiseWindow(window)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = window.layoutParams as FrameLayout.LayoutParams
                    lp.leftMargin = (startX + event.rawX - downRawX).roundToInt().coerceIn(0, (windowLayer.width - window.width).coerceAtLeast(0))
                    lp.topMargin = (startY + event.rawY - downRawY).roundToInt().coerceIn(0, (windowLayer.height - window.height).coerceAtLeast(0))
                    window.layoutParams = lp; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val lp = window.layoutParams as FrameLayout.LayoutParams; onMoved(lp.leftMargin, lp.topMargin); true }
                else -> false
            }
        }
    }
}
