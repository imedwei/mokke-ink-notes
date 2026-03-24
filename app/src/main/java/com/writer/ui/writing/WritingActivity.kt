package com.writer.ui.writing

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.app.Activity
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.writer.R
import com.writer.model.DocumentModel
import com.writer.recognition.TextRecognizer
import com.writer.recognition.TextRecognizerFactory
import com.writer.model.DocumentData
import com.writer.storage.DocumentStorage
import com.writer.view.HandwritingCanvasView
import com.writer.view.RecognizedTextView
import com.writer.view.TouchFilter
import com.writer.view.ScreenMetrics
import kotlinx.coroutines.launch

class WritingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WritingActivity"
        private const val PREFS_NAME = "writer_prefs"
        private const val PREF_CURRENT_DOC = "current_document"
        private const val PREF_SYNC_FOLDER = "sync_folder_uri"
        private const val UNDO_REDO_DEBOUNCE_MS = 300L
    }

    private lateinit var inkCanvas: HandwritingCanvasView
    private lateinit var recognizedTextView: RecognizedTextView

    // Floating gutter overlay
    private lateinit var gutterOverlay: View
    private lateinit var menuButton: TextView
    private lateinit var undoButton: ImageView
    private lateinit var redoButton: ImageView
    private lateinit var documentModel: DocumentModel
    private lateinit var recognizer: TextRecognizer
    @androidx.annotation.VisibleForTesting
    internal var coordinator: WritingCoordinator? = null
    private lateinit var tutorialManager: TutorialManager

    // Split resize state
    private var defaultTextHeight = 0
    private var defaultCanvasHeight = 0
    private var splitOffset = 0f

    // Saved data loaded before coordinator is ready
    private var pendingRestore: DocumentData? = null

    // Current document name
    private var currentDocumentName: String = ""

    // Rename handwriting activity
    private val renameLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val rawName = result.data?.getStringExtra(SaveAsActivity.EXTRA_RESULT_NAME)
            val name = rawName?.let { DocumentStorage.headingToFileName(it) }
            if (!name.isNullOrBlank() && name != currentDocumentName) {
                val oldName = currentDocumentName
                currentDocumentName = name
                coordinator?.userRenamed = true
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_CURRENT_DOC, name).apply()
                saveDocument()
                DocumentStorage.delete(this, oldName)
                Toast.makeText(this, "Renamed to \"$name\"", Toast.LENGTH_SHORT).show()
            }
        }
        inkCanvas.reopenRawDrawing()
    }

    // SAF folder picker
    private val pickSyncFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_SYNC_FOLDER, uri.toString())
                .apply()
            Toast.makeText(this, "Sync folder set", Toast.LENGTH_SHORT).show()
        }
        inkCanvas.resumeRawDrawing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_writing)

        // Go truly fullscreen — hide status bar and navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        inkCanvas = findViewById(R.id.inkCanvas)
        recognizedTextView = findViewById(R.id.recognizedTextView)
        val splitLayout = findViewById<com.writer.view.SplitLayout>(R.id.splitLayout)
        val splitDivider = findViewById<View>(R.id.splitDivider)

        // Floating gutter overlay
        gutterOverlay = findViewById(R.id.gutterOverlay)
        menuButton = findViewById(R.id.menuButton)
        undoButton = findViewById(R.id.undoButton)
        redoButton = findViewById(R.id.redoButton)

        val spaceInsertButton = findViewById<ImageView>(R.id.spaceInsertButton)

        undoButton.imageAlpha = 77
        redoButton.imageAlpha = 77
        menuButton.setOnClickListener { showMenu() }
        undoButton.setOnClickListener { debounceUndoRedo { coordinator?.undo(); updateUndoRedoButtons() } }
        redoButton.setOnClickListener { debounceUndoRedo { coordinator?.redo(); updateUndoRedoButtons() } }
        spaceInsertButton.setOnClickListener { inkCanvas.spaceInsertMode = !inkCanvas.spaceInsertMode }

        inkCanvas.onSpaceInsert = { anchorLine, lines ->
            if (lines > 0) {
                coordinator?.insertSpace(anchorLine, lines)
            } else if (lines < 0) {
                coordinator?.removeSpace(anchorLine, -lines)
            }
            updateUndoRedoButtons()
        }

        val touchFilter = TouchFilter()
        inkCanvas.touchFilter = touchFilter
        recognizedTextView.touchFilter = touchFilter

        documentModel = DocumentModel()

        tutorialManager = TutorialManager(
            context = this,
            inkCanvas = inkCanvas,
            textView = recognizedTextView,
            getCoordinator = { coordinator },
            getPendingRestore = { pendingRestore },
            clearPendingRestore = { pendingRestore = null },
            onClosed = {}
        )

        // Migrate old single-file storage if needed, then determine current document
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val migrated = DocumentStorage.migrateIfNeeded(this)
        currentDocumentName = migrated
            ?: prefs.getString(PREF_CURRENT_DOC, null)
            ?: DocumentStorage.generateName(this)
        prefs.edit().putString(PREF_CURRENT_DOC, currentDocumentName).apply()

        // Load saved document data and restore strokes immediately (no recognizer needed)
        pendingRestore = DocumentStorage.load(this, currentDocumentName)
        restoreDocumentVisuals()

        // Update undo/redo buttons when pen lifts (new strokes may have been added)
        inkCanvas.onPenStateChanged = { active ->
            if (!active) {
                gutterOverlay.post { updateUndoRedoButtons() }
            }
        }

        // Text view scroll drives canvas scroll (complementary views)
        recognizedTextView.onScroll = { dy ->
            // dy > 0 = finger dragged down = see earlier content = scroll canvas up
            val raw = inkCanvas.scrollOffsetY - dy
            inkCanvas.scrollOffsetY = raw.coerceAtLeast(0f)
            inkCanvas.drawToSurface()
            inkCanvas.onManualScroll?.invoke()
        }
        recognizedTextView.onScrollEnd = {
            inkCanvas.scrollOffsetY = inkCanvas.snapToLine(inkCanvas.scrollOffsetY)
            inkCanvas.drawToSurface()
            inkCanvas.onManualScroll?.invoke()
        }

        // Pick the best available recognizer synchronously (initialized later in coroutine).
        // OnyxHwrTextRecognizer binds to a system service using applicationContext, so holding
        // it in the activity is safe (no activity leak). GoogleMLKitTextRecognizer is stateless
        // and does not retain a context reference.
        recognizer = TextRecognizerFactory.create(this)

        // Create coordinator early so cached text can be displayed before model loads
        startCoordinator()

        // Capture default heights after initial layout, then wire up the divider drag.
        // Override the XML weight-based split with an adaptive calculation so that
        // all supported screen sizes get a proportional canvas/text split.
        recognizedTextView.post {
            val totalHeight = recognizedTextView.height + inkCanvas.height
            defaultCanvasHeight = ScreenMetrics.computeDefaultCanvasHeight(totalHeight)
            defaultTextHeight = totalHeight - defaultCanvasHeight

            val textParams = recognizedTextView.layoutParams as LinearLayout.LayoutParams
            textParams.height = defaultTextHeight
            textParams.weight = 0f
            recognizedTextView.layoutParams = textParams

            val canvasParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
            canvasParams.height = defaultCanvasHeight
            canvasParams.weight = 0f
            inkCanvas.layoutParams = canvasParams

            setupSplitDrag(splitLayout, splitDivider)

            // Restore cached text and scroll position immediately (no recognizer needed)
            restoreCoordinatorState()

            // Show tutorial on first launch
            if (tutorialManager.shouldAutoShow()) {
                inkCanvas.pauseRawDrawing()
                tutorialManager.show()
            }
        }

        // Initialize recognizer in the background
        recognizedTextView.statusMessage = "Loading handwriting recognition model..."
        recognizedTextView.statusSubtext = "This may take a minute (~20 MB download)"
        lifecycleScope.launch {
            try {
                recognizer.initialize(documentModel.language)
                recognizedTextView.statusMessage = ""
                recognizedTextView.statusSubtext = ""
                coordinator?.recognizeAllLines()
            } catch (e: Exception) {
                recognizedTextView.statusMessage = "Error loading model"
                Toast.makeText(
                    this@WritingActivity,
                    "Failed to load recognition model: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Restore strokes and scroll to canvas immediately — no recognizer needed. */
    private fun restoreDocumentVisuals() {
        val data = pendingRestore ?: return

        Log.i(TAG, "Restoring ${data.strokes.size} strokes, scroll=${data.scrollOffsetY}")

        documentModel.activeStrokes.addAll(data.strokes)
        documentModel.diagramAreas.addAll(data.diagramAreas)
        inkCanvas.diagramAreas = data.diagramAreas
        inkCanvas.loadStrokes(data.strokes)
        inkCanvas.scrollOffsetY = data.scrollOffsetY
        inkCanvas.drawToSurface()
    }

    /** Restore coordinator state (text cache, hidden lines) — no recognizer needed. */
    private fun restoreCoordinatorState() {
        if (tutorialManager.isActive) return // defer until tutorial closes
        val data = pendingRestore ?: return
        pendingRestore = null

        coordinator?.restoreState(data)
    }

    private fun setupSplitDrag(splitLayout: com.writer.view.SplitLayout, divider: View) {
        splitLayout.dividerView = divider
        splitLayout.onSplitDragStart = { inkCanvas.pauseRawDrawing() }
        splitLayout.onSplitDragEnd = { inkCanvas.resumeRawDrawing() }
        splitLayout.onSplitDrag = { delta ->
            val totalHeight = defaultTextHeight + defaultCanvasHeight
            val minTextHeight = (totalHeight * 0.25f).toInt()
            val maxOffset = (totalHeight - minTextHeight).toFloat()
            splitOffset = (splitOffset + delta).coerceIn(0f, maxOffset)

            val newTextHeight = defaultTextHeight + splitOffset.toInt()
            val newCanvasHeight = defaultCanvasHeight - splitOffset.toInt()

            val textParams = recognizedTextView.layoutParams as LinearLayout.LayoutParams
            textParams.height = newTextHeight
            textParams.weight = 0f
            recognizedTextView.layoutParams = textParams

            val canvasParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
            canvasParams.height = newCanvasHeight.coerceAtLeast(0)
            canvasParams.weight = 0f
            inkCanvas.layoutParams = canvasParams
        }
    }

    private fun startCoordinator() {
        coordinator = WritingCoordinator(
            documentModel = documentModel,
            recognizer = recognizer,
            inkCanvas = inkCanvas,
            textView = recognizedTextView,
            scope = lifecycleScope,
            onStatusUpdate = { status ->
                runOnUiThread {
                    recognizedTextView.statusMessage = status
                }
            }
        )
        coordinator?.onHeadingDetected = { heading -> autoRenameFromHeading(heading) }
        coordinator?.onUndoRedoStateChanged = { updateUndoRedoButtons() }
        coordinator?.start()

    }

    private fun autoRenameFromHeading(heading: String) {
        val newName = DocumentStorage.generateNameFromHeading(this, heading) ?: return
        if (newName == currentDocumentName) return
        val oldName = currentDocumentName
        DocumentStorage.rename(this, oldName, newName)
        currentDocumentName = newName
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_CURRENT_DOC, newName).apply()
        Log.i(TAG, "Auto-renamed document: \"$oldName\" → \"$newName\"")
    }

    private var lastUndoRedoTapMs = 0L

    /** Debounce undo/redo taps to prevent accidental double-taps. */
    private inline fun debounceUndoRedo(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastUndoRedoTapMs < UNDO_REDO_DEBOUNCE_MS) return
        lastUndoRedoTapMs = now
        action()
    }

    private var lastUndoAlpha = 77
    private var lastRedoAlpha = 77

    /** Update undo/redo button visibility based on availability.
     *  Only forces e-ink refresh when the state actually changes. */
    fun updateUndoRedoButtons() {
        val undoAlpha = if (coordinator?.canUndo() == true) 255 else 77
        val redoAlpha = if (coordinator?.canRedo() == true) 255 else 77
        if (undoAlpha == lastUndoAlpha && redoAlpha == lastRedoAlpha) return
        lastUndoAlpha = undoAlpha
        lastRedoAlpha = redoAlpha
        undoButton.imageAlpha = undoAlpha
        redoButton.imageAlpha = redoAlpha
        // Force e-ink refresh: Onyx SDK suppresses View invalidation while raw drawing is active
        inkCanvas.pauseRawDrawing()
        inkCanvas.drawToSurface()
        inkCanvas.resumeRawDrawing()
    }

    // --- Document operations ---

    private fun switchToDocument(name: String) {
        saveDocument()

        // Clear current state
        coordinator?.stop()
        coordinator?.reset()
        documentModel.activeStrokes.clear()
        inkCanvas.clear()
        recognizedTextView.setParagraphs(emptyList())

        // Load new document
        currentDocumentName = name
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_CURRENT_DOC, name).apply()

        val data = DocumentStorage.load(this, name)
        if (data != null) {
            documentModel.activeStrokes.addAll(data.strokes)
            inkCanvas.loadStrokes(data.strokes)
            inkCanvas.scrollOffsetY = data.scrollOffsetY
            inkCanvas.drawToSurface()
            coordinator?.start()
            coordinator?.restoreState(data)
        } else {
            inkCanvas.drawToSurface()
            coordinator?.start()
        }
    }

    private fun newDocument() {
        saveDocument()

        coordinator?.stop()
        coordinator?.reset()
        documentModel.activeStrokes.clear()
        inkCanvas.clear()
        recognizedTextView.setParagraphs(emptyList())
        recognizedTextView.showScrollHint = true

        currentDocumentName = DocumentStorage.generateName(this)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_CURRENT_DOC, currentDocumentName).apply()

        coordinator?.start()
        inkCanvas.drawToSurface()
    }

    private fun showOpenDialog() {
        val docs = DocumentStorage.listDocuments(this)
        if (docs.isEmpty()) {
            Toast.makeText(this, "No saved documents", Toast.LENGTH_SHORT).show()
            return
        }

        inkCanvas.pauseRawDrawing()
        val names = docs.map { it.name }.toTypedArray()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
            setBackgroundResource(R.drawable.bg_dialog_border)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setOnDismissListener { inkCanvas.resumeRawDrawing() }
            .create()

        // Title
        val title = android.widget.TextView(this).apply {
            text = "Open Document"
            textSize = 26f
            setTextColor(android.graphics.Color.BLACK)
            setPadding(48, 40, 48, 24)
        }
        container.addView(title)

        // Divider under title
        container.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(32, 0, 32, 0) }
            setBackgroundColor(android.graphics.Color.parseColor("#AAAAAA"))
        })

        // Document items
        for (name in names) {
            val item = android.widget.TextView(this).apply {
                text = name
                textSize = 24f
                setTextColor(android.graphics.Color.BLACK)
                setPadding(48, 32, 48, 32)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    dialog.dismiss()
                    switchToDocument(name)
                }
            }
            container.addView(item)

            // Divider between items
            container.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(32, 0, 32, 0) }
                setBackgroundColor(android.graphics.Color.parseColor("#DDDDDD"))
            })
        }

        // Cancel button
        val cancel = android.widget.TextView(this).apply {
            text = "Cancel"
            textSize = 22f
            setTextColor(android.graphics.Color.BLACK)
            setPadding(48, 24, 48, 24)
            gravity = Gravity.END
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(cancel)

        dialog.show()

        // Remove default dialog background so our border isn't clipped
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.density * 600).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showRenameDialog() {
        // Fully close the Onyx SDK so SaveAsActivity's touch input works
        inkCanvas.closeRawDrawing()
        val intent = Intent(this, SaveAsActivity::class.java).apply {
            putExtra(SaveAsActivity.EXTRA_CURRENT_NAME, currentDocumentName)
        }
        renameLauncher.launch(intent)
    }

    // --- Menu ---

    private fun showMenu() {
        inkCanvas.pauseRawDrawing()

        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu, null)

        // Measure to get actual content size
        popupView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        val popup = PopupWindow(popupView, popupWidth, popupHeight, true)
        popup.elevation = 0f // no shadow for e-ink

        var openingTutorial = false
        var launchingSaf = false
        var launchingSaveAs = false

        popup.setOnDismissListener {
            if (!openingTutorial && !launchingSaf && !launchingSaveAs) {
                inkCanvas.resumeRawDrawing()
            }
        }

        popupView.findViewById<android.view.View>(R.id.menuNew).setOnClickListener {
            popup.dismiss()
            newDocument()
        }
        popupView.findViewById<android.view.View>(R.id.menuOpen).setOnClickListener {
            popup.dismiss()
            showOpenDialog()
        }
        popupView.findViewById<android.view.View>(R.id.menuRename).setOnClickListener {
            launchingSaveAs = true
            popup.dismiss()
            showRenameDialog()
        }
        popupView.findViewById<android.view.View>(R.id.menuSyncFolder).setOnClickListener {
            launchingSaf = true
            popup.dismiss()
            pickSyncFolder.launch(null)
        }
        popupView.findViewById<android.view.View>(R.id.menuTutorial).setOnClickListener {
            openingTutorial = true
            popup.dismiss()
            tutorialManager.show()
        }
        popupView.findViewById<android.view.View>(R.id.menuBugReport).setOnClickListener {
            popup.dismiss()
            generateAndShareBugReport()
        }
        popupView.findViewById<android.view.View>(R.id.menuClose).setOnClickListener {
            popup.dismiss()
            saveDocument()
            finish()
        }
        popupView.findViewById<android.view.View>(R.id.menuDebugReset).setOnClickListener {
            popup.dismiss()
            tutorialManager.resetSeen()
            Toast.makeText(this, "Tutorial reset — will show on next launch", Toast.LENGTH_SHORT).show()
        }

        // Position to the left of the floating icon, at the top of the text view
        val loc = IntArray(2)
        recognizedTextView.getLocationOnScreen(loc)
        val x = loc[0] + recognizedTextView.width - popupWidth
        val y = loc[1]
        popup.showAtLocation(recognizedTextView, Gravity.NO_GRAVITY, x, y)
    }

    override fun onResume() {
        super.onResume()
        // Reinitialize Onyx SDK to clear stale system-level state from other apps
        // (e.g. toolbar exclude rects that persist across app switches)
        if (!tutorialManager.isActive) {
            inkCanvas.reinitializeRawDrawing()
        }
    }

    override fun onStop() {
        super.onStop()
        saveDocument()
    }

    private fun saveDocument() {
        if (tutorialManager.isActive) return // Don't save tutorial state as a document
        val state = coordinator?.getState() ?: return
        DocumentStorage.save(this, currentDocumentName, state)

        // Export to sync folder if configured
        val syncUri = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_SYNC_FOLDER, null)
        if (syncUri != null) {
            val markdown = coordinator?.getMarkdownText() ?: ""
            DocumentStorage.exportToSyncFolder(
                this, currentDocumentName, state, markdown, Uri.parse(syncUri)
            )
        }
    }

    // --- Bug report ---

    private fun generateAndShareBugReport() {
        val file = coordinator?.generateBugReport()
        if (file == null) {
            Toast.makeText(this, "No strokes to report", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Bug report saved", Toast.LENGTH_SHORT).show()

        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "InkUp Bug Report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Bug Report"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share bug report: ${e.message}")
            Toast.makeText(this, "Report saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator?.stop()
        recognizer.close()
    }
}
