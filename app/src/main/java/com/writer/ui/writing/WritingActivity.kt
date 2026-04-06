package com.writer.ui.writing

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.app.Activity
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
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
import com.writer.model.ColumnData
import com.writer.model.DocumentModel
import com.writer.model.StrokePoint
import com.writer.recognition.TextRecognizer
import com.writer.recognition.TextRecognizerFactory
import com.writer.model.DocumentData
import com.writer.storage.DocumentStorage
import com.writer.storage.DocumentStorageSink
import com.writer.storage.SearchIndexManager
import com.writer.view.CanvasTheme
import com.writer.view.GutterTouchGuard
import com.writer.view.HandwritingCanvasView
import com.writer.view.RecognizedTextView
import com.writer.view.TouchFilter
import com.writer.view.ScreenMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WritingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WritingActivity"
        private const val PREFS_NAME = "writer_prefs"
        private const val PREF_CURRENT_DOC = "current_document"
        private const val PREF_SYNC_FOLDER = "sync_folder_uri"
        private const val PREF_USE_WHISPER = com.writer.ui.settings.SettingsActivity.PREF_USE_WHISPER
        private const val UNDO_REDO_DEBOUNCE_MS = 300L
    }

    private lateinit var inkCanvas: HandwritingCanvasView
    private lateinit var recognizedTextView: RecognizedTextView

    // Cue column views (landscape Cornell Notes)
    private lateinit var cueInkCanvas: HandwritingCanvasView
    private lateinit var cueRecognizedTextView: RecognizedTextView
    private lateinit var cueSplitLayout: View
    private lateinit var columnDivider: View
    private lateinit var cueIndicatorStrip: com.writer.view.CueIndicatorStrip
    private lateinit var contextRail: com.writer.view.CueIndicatorStrip
    private lateinit var mainSplitLayout: View
    private var cueCoordinator: WritingCoordinator? = null
    private var isLandscape = false
    private var isFoldedToCues = false
    private lateinit var columnLayoutLogic: ColumnLayoutLogic

    // View toggle button in gutter (Notes ↔ Cues)
    private lateinit var viewToggleButton: ImageView

    // Active cue peek popup (dismissed on orientation change)
    private var cuePeekPopup: android.widget.PopupWindow? = null

    /** The active coordinator — routes undo/redo to the right column.
     *  In portrait cue view, always use cue coordinator.
     *  In dual-column mode, use whichever canvas the pen last wrote on. */
    private val activeCoordinator: WritingCoordinator?
        get() = if (isFoldedToCues || (columnLayoutLogic.isDualColumn && isCueCanvasActive)) cueCoordinator else coordinator
    private var isCueCanvasActive = false

    // Floating gutter overlay
    private lateinit var gutterOverlay: View
    private lateinit var menuButton: ImageView
    private lateinit var undoButton: ImageView
    private lateinit var redoButton: ImageView
    private lateinit var spaceInsertButton: ImageView
    private lateinit var micButton: ImageView
    private var audioTranscriber: com.writer.recognition.AudioTranscriber? = null
    private var audioCaptureManager: com.writer.audio.AudioCaptureManager? = null
    private var audioRecordCapture: com.writer.audio.AudioRecordCapture? = null
    private val useWhisperTranscriber: Boolean
        get() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_USE_WHISPER, false)
    private var lectureMode = false
    private var lectureRecordingStartMs = 0L
    private val audioQualityMonitor = com.writer.audio.AudioQualityMonitor()
    private var audioQualityWarned = false

    /** True while the stylus is actively drawing — reject finger taps on gutter. */
    private fun isPenBusy(): Boolean =
        (::inkCanvas.isInitialized && inkCanvas.isPenActive()) ||
        (::cueInkCanvas.isInitialized && cueInkCanvas.isPenActive())
    private lateinit var orientationManager: OrientationManager
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

    // Auto-save: initialized in onCreate after lifecycleScope is available
    private lateinit var autoSaver: AutoSaver

    // Debug: remote bug report generation via adb broadcast
    private val bugReportReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val file = coordinator?.generateBugReport()
            Log.i(TAG, if (file != null) "Bug report generated: ${file.absolutePath}" else "No strokes to report")
        }
    }

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
                snapshotAndSaveBlocking()
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
            // Restore documents from the newly configured sync folder
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val count = DocumentStorage.restoreFromSyncFolder(this@WritingActivity, uri)
                if (count > 0) {
                    SearchIndexManager.rebuildIndex(this@WritingActivity)
                    runOnUiThread {
                        Toast.makeText(this@WritingActivity, "Restored $count documents", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        inkCanvas.resumeRawDrawing()
    }

    // Settings activity result
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data?.getBooleanExtra(com.writer.ui.settings.SettingsActivity.RESULT_SHOW_TUTORIAL, false) == true) {
                showTutorial()
                return@registerForActivityResult
            }
            if (data?.getBooleanExtra(com.writer.ui.settings.SettingsActivity.RESULT_DEBUG_RESET, false) == true) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
                tutorialManager.resetSeen()
                val docsDir = java.io.File(filesDir, "documents")
                docsDir.listFiles()?.forEach { it.delete() }
                Toast.makeText(this, "Reset to pristine state — restarting", Toast.LENGTH_SHORT).show()
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
                return@registerForActivityResult
            }
        }
        inkCanvas.reopenRawDrawing()
    }

    // Audio recording permission
    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceMemo()
        } else {
            Toast.makeText(this, "Microphone permission required for voice memo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = android.content.IntentFilter("${packageName}.GENERATE_BUG_REPORT")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bugReportReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bugReportReceiver, filter)
        }

        autoSaver = AutoSaver(lifecycleScope, DocumentStorageSink(applicationContext))

        setContentView(R.layout.activity_writing)

        // Go truly fullscreen — hide status bar and navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Boox-specific: request true fullscreen to eliminate nav bar EPD dead zone
        try {
            com.onyx.android.sdk.utils.DeviceUtils.setFullScreenOnResume(this, true)
        } catch (e: Exception) {
            Log.w(TAG, "DeviceUtils.setFullScreenOnResume not available: ${e.message}")
        }

        inkCanvas = findViewById(R.id.inkCanvas)
        recognizedTextView = findViewById(R.id.recognizedTextView)
        val splitLayout = findViewById<com.writer.view.SplitLayout>(R.id.splitLayout)
        val splitDivider = findViewById<View>(R.id.splitDivider)

        // Cue column views
        cueInkCanvas = findViewById(R.id.cueInkCanvas)
        cueRecognizedTextView = findViewById(R.id.cueRecognizedTextView)
        cueSplitLayout = findViewById(R.id.cueSplitLayout)
        columnDivider = findViewById(R.id.columnDivider)
        cueIndicatorStrip = findViewById(R.id.cueIndicatorStrip)
        contextRail = findViewById(R.id.contextRail)
        mainSplitLayout = splitLayout
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        columnLayoutLogic = ColumnLayoutLogic(object : ColumnLayoutLogic.Host {
            override val isLargeScreen get() = ScreenMetrics.isLargeScreen
            override var isLandscape
                get() = this@WritingActivity.isLandscape
                set(value) { this@WritingActivity.isLandscape = value }
        })

        // Cue canvas defers Onyx init — gets SDK session via hover-based swap in landscape.
        cueInkCanvas.deferOnyxInit = true

        cueIndicatorStrip.onDotLongPress = { lineIndex, screenY -> showCuePeek(lineIndex, screenY) }
        contextRail.alignLeft = true
        contextRail.onDotLongPress = { lineIndex, screenY -> showMainPeek(lineIndex, screenY) }

        // Floating gutter overlay — reject palm touches and long holds on buttons.
        gutterOverlay = findViewById(R.id.gutterOverlay)
        val touchGuard = GutterTouchGuard(
            palmThresholdPx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 30f, resources.displayMetrics),
            maxTapMs = android.view.ViewConfiguration.getLongPressTimeout().toLong(),
            isPenBusy = ::isPenBusy
        )
        val palmGuard = android.view.View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    touchGuard.evaluateDown(event.touchMajor) == GutterTouchGuard.Decision.REJECT
                android.view.MotionEvent.ACTION_UP ->
                    touchGuard.evaluateUp(event.downTime, event.eventTime) == GutterTouchGuard.Decision.REJECT
                else -> false
            }
        }
        menuButton = findViewById(R.id.menuButton)
        undoButton = findViewById(R.id.undoButton)
        redoButton = findViewById(R.id.redoButton)
        spaceInsertButton = findViewById(R.id.spaceInsertButton)
        micButton = findViewById(R.id.micButton)
        viewToggleButton = findViewById(R.id.viewToggleButton)
        val rotateButton = findViewById<ImageView>(R.id.rotateButton)
        orientationManager = OrientationManager(this, rotateButton)

        for (btn in listOf(menuButton, undoButton, redoButton, spaceInsertButton, micButton, viewToggleButton, rotateButton)) {
            btn.setOnTouchListener(palmGuard)
        }

        undoButton.imageAlpha = 77
        redoButton.imageAlpha = 77
        menuButton.setOnClickListener { showMenu() }
        undoButton.setOnClickListener { debounceUndoRedo { activeCoordinator?.undo(); updateUndoRedoButtons() } }
        redoButton.setOnClickListener { debounceUndoRedo { activeCoordinator?.redo(); updateUndoRedoButtons() } }
        spaceInsertButton.setOnClickListener { inkCanvas.spaceInsertMode = !inkCanvas.spaceInsertMode }
        micButton.setOnClickListener { toggleVoiceMemo() }
        micButton.setOnLongClickListener { startLectureCapture(); true }
        viewToggleButton.setOnClickListener { toggleNotesCues() }
        rotateButton.setOnClickListener { orientationManager.toggleOrientation() }

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
        cueIndicatorStrip.touchFilter = touchFilter
        contextRail.touchFilter = touchFilter

        documentModel = DocumentModel()

        val tutorialOverlay = findViewById<com.writer.view.TutorialOverlay>(R.id.tutorialOverlay)

        tutorialManager = TutorialManager(
            context = this,
            inkCanvas = inkCanvas,
            textView = recognizedTextView,
            getCoordinator = { coordinator },
            getPendingRestore = { pendingRestore },
            clearPendingRestore = { pendingRestore = null },
            onClosed = {
                // Unfold from cue view if tutorial ended there
                if (isFoldedToCues) unfoldToNotes()
                // Restore cue data that was cleared for tutorial
                restoreCueDataAfterTutorial()
                updateUndoRedoButtons()
                // Prompt to set up sync folder if not configured yet
                promptSyncFolderIfNeeded()
            }
        )
        tutorialManager.setOverlay(tutorialOverlay)
        tutorialManager.onStepChanged = {
            updateViewToggleIcon()
            onTutorialStepChanged()
        }
        tutorialManager.getContextRailRect = {
            if (contextRail.visibility == View.VISIBLE) {
                contextRail.getContentScreenRect()
            } else null
        }
        tutorialManager.onNextStep = { stepId ->
            when (stepId) {
                "switch_to_cues" -> {
                    if (!isFoldedToCues) foldToCues()
                }
            }
        }

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

        // One-time migration: rebuild AppSearch index from existing documents
        if (!prefs.getBoolean("appsearch_migrated", false)) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                SearchIndexManager.rebuildIndex(this@WritingActivity)
                prefs.edit().putBoolean("appsearch_migrated", true).apply()
                // Clean up legacy search-index.json
                java.io.File(filesDir, "documents/search-index.json").delete()
            }
        }

        // Restore any new documents from sync folder
        val syncUri = prefs.getString(PREF_SYNC_FOLDER, null)
        if (syncUri != null) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val count = DocumentStorage.restoreFromSyncFolder(
                    this@WritingActivity, android.net.Uri.parse(syncUri)
                )
                if (count > 0) {
                    SearchIndexManager.rebuildIndex(this@WritingActivity)
                }
            }
        }

        // Update undo/redo buttons when pen lifts and track active canvas
        inkCanvas.onPenStateChanged = { active ->
            if (active) isCueCanvasActive = false
            if (!active) {
                gutterOverlay.post { updateUndoRedoButtons() }
                scheduleAutoSave()
            }
        }
        cueInkCanvas.onPenStateChanged = { active ->
            if (active) isCueCanvasActive = true
            if (!active) {
                gutterOverlay.post { updateUndoRedoButtons() }
                scheduleAutoSave()
            }
        }

        // Text view scroll drives canvas scroll (complementary views)
        recognizedTextView.onScroll = { dy ->
            // dy > 0 = finger dragged down = see earlier content = scroll canvas up
            val raw = inkCanvas.scrollOffsetY - dy
            inkCanvas.scrollOffsetY = raw.coerceAtLeast(0f)
            inkCanvas.drawToSurface()
            inkCanvas.onManualScroll?.invoke()
            // Sync cue indicator strip scroll
            cueIndicatorStrip.scrollOffsetY = inkCanvas.scrollOffsetY
            // Linked scroll: sync cue column
            if (columnLayoutLogic.isDualColumn) {
                cueInkCanvas.scrollOffsetY = inkCanvas.scrollOffsetY
                cueInkCanvas.drawToSurface()
                cueInkCanvas.onManualScroll?.invoke()
            }
        }
        recognizedTextView.onScrollEnd = {
            inkCanvas.scrollOffsetY = inkCanvas.snapToLine(inkCanvas.scrollOffsetY)
            inkCanvas.drawToSurface()
            inkCanvas.onManualScroll?.invoke()
            cueIndicatorStrip.scrollOffsetY = inkCanvas.scrollOffsetY
            if (columnLayoutLogic.isDualColumn) {
                cueInkCanvas.scrollOffsetY = inkCanvas.scrollOffsetY
                cueInkCanvas.drawToSurface()
                cueInkCanvas.onManualScroll?.invoke()
            }
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
                showTutorial()
            }
        }

        // Set up cue canvas touch filter and scroll sync
        val cueTouchFilter = TouchFilter()
        cueInkCanvas.touchFilter = cueTouchFilter
        cueRecognizedTextView.touchFilter = cueTouchFilter

        // Cue text view scroll drives cue canvas scroll (same pattern as main)
        cueRecognizedTextView.onScroll = { dy ->
            val raw = cueInkCanvas.scrollOffsetY - dy
            cueInkCanvas.scrollOffsetY = raw.coerceAtLeast(0f)
            cueInkCanvas.drawToSurface()
            // Linked scroll: sync main column + context rail
            inkCanvas.scrollOffsetY = cueInkCanvas.scrollOffsetY
            inkCanvas.drawToSurface()
            inkCanvas.onManualScroll?.invoke()
            contextRail.scrollOffsetY = cueInkCanvas.scrollOffsetY
        }
        cueRecognizedTextView.onScrollEnd = {
            cueInkCanvas.scrollOffsetY = cueInkCanvas.snapToLine(cueInkCanvas.scrollOffsetY)
            cueInkCanvas.drawToSurface()
            inkCanvas.scrollOffsetY = cueInkCanvas.scrollOffsetY
            inkCanvas.drawToSurface()
            inkCanvas.onManualScroll?.invoke()
            contextRail.scrollOffsetY = cueInkCanvas.scrollOffsetY
        }

        // Show cue column if dual-column mode, otherwise show indicator strip
        if (columnLayoutLogic.isDualColumn) {
            showCueColumn()
        } else {
            cueIndicatorStrip.visibility = View.VISIBLE
            // Set canvas top offset after layout so dots align with the canvas, not the preview
            inkCanvas.post {
                val canvasLoc = IntArray(2)
                val stripLoc = IntArray(2)
                inkCanvas.getLocationOnScreen(canvasLoc)
                cueIndicatorStrip.getLocationOnScreen(stripLoc)
                cueIndicatorStrip.canvasTopOffset = (canvasLoc[1] - stripLoc[1]).toFloat()
            }
            updateCueIndicatorStrip()
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
                cueCoordinator?.recognizeAllLines()
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

        Log.i(TAG, "Restoring ${data.main.strokes.size} main + ${data.cue.strokes.size} cue strokes, scroll=${data.scrollOffsetY}")

        documentModel.main.activeStrokes.addAll(data.main.strokes)
        documentModel.main.diagramAreas.addAll(data.main.diagramAreas)
        inkCanvas.diagramAreas = data.main.diagramAreas
        inkCanvas.loadStrokes(data.main.strokes)
        inkCanvas.scrollOffsetY = data.scrollOffsetY
        inkCanvas.drawToSurface()

        // Restore cue column data into the model (views are populated in showCueColumn)
        documentModel.cue.activeStrokes.addAll(data.cue.strokes)
        documentModel.cue.diagramAreas.addAll(data.cue.diagramAreas)
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
            columnModel = documentModel.main,
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
        coordinator?.onTutorialAction = { actionId -> tutorialManager.onStepAction(actionId) }
        // Sync cue indicator strip scroll with canvas (portrait mode)
        coordinator?.onLinkedScroll = { cueIndicatorStrip.scrollOffsetY = inkCanvas.scrollOffsetY }
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
        val active = activeCoordinator
        val undoAlpha = if (active?.canUndo() == true) 255 else 77
        val redoAlpha = if (active?.canRedo() == true) 255 else 77
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

    private var documentLoadJob: Job? = null

    private fun switchToDocument(name: String, scrollToLine: Int? = null) {
        snapshotAndSaveBlocking()

        // Clear immediately so the old document disappears
        coordinator?.stop()
        coordinator?.reset()
        cueCoordinator?.stop()
        cueCoordinator?.reset()
        documentModel.main.activeStrokes.clear()
        documentModel.main.diagramAreas.clear()
        documentModel.cue.activeStrokes.clear()
        documentModel.cue.diagramAreas.clear()
        inkCanvas.clear()
        cueInkCanvas.clear()
        recognizedTextView.setParagraphs(emptyList())
        cueRecognizedTextView.setParagraphs(emptyList())

        currentDocumentName = name
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_CURRENT_DOC, name).apply()

        // Cancel any in-flight load, then load on IO thread
        documentLoadJob?.cancel()
        documentLoadJob = lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                DocumentStorage.load(this@WritingActivity, name)
            }
            // Back on main thread
            if (data != null) {
                documentModel.main.activeStrokes.addAll(data.main.strokes)
                documentModel.main.diagramAreas.addAll(data.main.diagramAreas)
                inkCanvas.diagramAreas = data.main.diagramAreas
                inkCanvas.scrollOffsetY = data.scrollOffsetY
                inkCanvas.loadStrokes(data.main.strokes)

                documentModel.cue.activeStrokes.addAll(data.cue.strokes)
                documentModel.cue.diagramAreas.addAll(data.cue.diagramAreas)

                coordinator?.start()
                coordinator?.restoreState(data)

                if (columnLayoutLogic.isDualColumn) {
                    cueInkCanvas.diagramAreas = data.cue.diagramAreas
                    cueInkCanvas.scrollOffsetY = data.scrollOffsetY
                    cueInkCanvas.loadStrokes(data.cue.strokes)
                    cueCoordinator?.start()
                    cueCoordinator?.restoreColumnState(data.cue)
                }
            } else {
                coordinator?.start()
            }
            updateCueIndicatorStrip()

            if (scrollToLine != null) {
                inkCanvas.post { coordinator?.scrollToLine(scrollToLine) }
            }
        }
    }

    private fun newDocument() {
        if (lectureMode) stopLectureCapture()
        snapshotAndSaveBlocking()

        coordinator?.stop()
        coordinator?.reset()
        cueCoordinator?.stop()
        cueCoordinator?.reset()
        documentModel.main.activeStrokes.clear()
        documentModel.main.diagramAreas.clear()
        documentModel.cue.activeStrokes.clear()
        documentModel.cue.diagramAreas.clear()
        inkCanvas.clear()
        cueInkCanvas.clear()
        recognizedTextView.setParagraphs(emptyList())
        cueRecognizedTextView.setParagraphs(emptyList())
        recognizedTextView.showScrollHint = true

        currentDocumentName = DocumentStorage.generateName(this)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_CURRENT_DOC, currentDocumentName).apply()

        coordinator?.start()
        inkCanvas.drawToSurface()
        updateCueIndicatorStrip()
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

        var searchJob: kotlinx.coroutines.Job? = null
        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setOnDismissListener {
                searchJob?.cancel()
                inkCanvas.resumeRawDrawing()
            }
            .create()

        // Title
        val title = android.widget.TextView(this).apply {
            text = "Open Document"
            textSize = 26f
            setTextColor(android.graphics.Color.BLACK)
            setPadding(48, 40, 48, 24)
        }
        container.addView(title)

        // Search field
        val searchField = android.widget.EditText(this).apply {
            hint = "Search documents..."
            textSize = 20f
            setTextColor(android.graphics.Color.BLACK)
            setHintTextColor(android.graphics.Color.parseColor("#999999"))
            setPadding(48, 16, 48, 16)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = null
        }
        container.addView(searchField)

        // Divider under search
        container.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(32, 0, 32, 0) }
            setBackgroundColor(android.graphics.Color.parseColor("#AAAAAA"))
        })

        // Scrollable document list
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val itemContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(itemContainer)
        container.addView(scrollView)

        // Track item views for filtering
        data class DocItem(
            val name: String,
            val nameView: View, val matchContainer: LinearLayout, val dividerView: View
        )
        val docItems = mutableListOf<DocItem>()
        val activity = this

        // Document items
        for (doc in docs) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 32)
            }
            val nameView = android.widget.TextView(this).apply {
                text = doc.name
                textSize = 24f
                setTextColor(android.graphics.Color.BLACK)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    dialog.dismiss()
                    switchToDocument(doc.name)
                }
            }
            itemLayout.addView(nameView)

            // Container for match snippets (populated dynamically during search)
            val matchContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            itemLayout.addView(matchContainer)
            itemContainer.addView(itemLayout)

            // Divider between items
            val divider = android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(32, 0, 32, 0) }
                setBackgroundColor(android.graphics.Color.parseColor("#DDDDDD"))
            }
            itemContainer.addView(divider)
            docItems.add(DocItem(doc.name, itemLayout, matchContainer, divider))
        }

        // Filter documents as user types, querying AppSearch with debounce
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""

                if (query.isEmpty()) {
                    // Show all documents when no query
                    for (doc in docItems) {
                        doc.nameView.visibility = View.VISIBLE
                        doc.dividerView.visibility = View.VISIBLE
                        doc.matchContainer.removeAllViews()
                    }
                    return
                }

                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(150) // debounce
                    val matches = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        SearchIndexManager.search(this@WritingActivity, query)
                    }
                    val lowerQuery = query.lowercase()
                    for (doc in docItems) {
                        val nameMatch = doc.name.lowercase().contains(lowerQuery)
                        val lineMatches = matches[doc.name] ?: emptyList()
                        val visible = nameMatch || lineMatches.isNotEmpty()
                        doc.nameView.visibility = if (visible) View.VISIBLE else View.GONE
                        doc.dividerView.visibility = if (visible) View.VISIBLE else View.GONE

                        // Show clickable match snippets
                        doc.matchContainer.removeAllViews()
                        if (lineMatches.isNotEmpty()) {
                            for (match in lineMatches) {
                                val matchIdx = match.text.indexOf(lowerQuery)
                                if (matchIdx < 0) continue
                                val snippetStart = (matchIdx - 20).coerceAtLeast(0)
                                val snippetEnd = (matchIdx + lowerQuery.length + 40).coerceAtMost(match.text.length)
                                val prefix = if (snippetStart > 0) "..." else ""
                                val suffix = if (snippetEnd < match.text.length) "..." else ""
                                val snippet = "$prefix${match.text.substring(snippetStart, snippetEnd)}$suffix"

                                val matchView = android.widget.TextView(activity).apply {
                                    text = snippet
                                    textSize = 16f
                                    setTextColor(android.graphics.Color.parseColor("#666666"))
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                    setPadding(16, 8, 0, 8)
                                    setBackgroundResource(android.R.drawable.list_selector_background)
                                    setOnClickListener {
                                        dialog.dismiss()
                                        switchToDocument(doc.name, match.lineIndex)
                                    }
                                }
                                doc.matchContainer.addView(matchView)
                            }
                        }
                    }
                }
            }
        })

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
        val maxHeight = (resources.displayMetrics.heightPixels * 0.8).toInt()
        dialog.window?.setLayout(
            (resources.displayMetrics.density * 600).toInt(),
            maxHeight.coerceAtMost(android.view.WindowManager.LayoutParams.WRAP_CONTENT)
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

    // --- Voice Memo ---

    private fun toggleVoiceMemo() {
        // If in lecture mode, stop it
        if (lectureMode) {
            stopLectureCapture()
            return
        }

        val transcriber = audioTranscriber
        if (transcriber != null && transcriber.isListening) {
            transcriber.stop()
            micButton.setImageResource(R.drawable.ic_mic)
            return
        }

        // Check permission
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }

        startVoiceMemo()
    }

    private fun startVoiceMemo() {
        if (!com.writer.recognition.SystemSpeechTranscriber.isAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }

        val transcriber = com.writer.recognition.SystemSpeechTranscriber(this)
        audioTranscriber = transcriber
        micButton.setImageResource(R.drawable.ic_mic_active)

        transcriber.onFinalResult = { text ->
            micButton.setImageResource(R.drawable.ic_mic)
            if (text.isNotBlank()) {
                activeCoordinator?.insertTextBlock(text)
                updateCueIndicatorStrip()
            }
            audioTranscriber = null
        }

        transcriber.onError = { _ ->
            micButton.setImageResource(R.drawable.ic_mic)
            audioTranscriber = null
        }

        transcriber.start(documentModel.language)
    }

    // --- Lecture Capture ---

    private fun startLectureCapture() {
        if (lectureMode) return

        // Check permission
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!com.writer.recognition.SystemSpeechTranscriber.isAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }

        lectureMode = true
        lectureRecordingStartMs = System.currentTimeMillis()
        audioQualityMonitor.reset()
        audioQualityWarned = false
        micButton.setImageResource(R.drawable.ic_mic_active)
        inkCanvas.lectureRecording = true

        // Start foreground service to keep alive when backgrounded
        val serviceIntent = android.content.Intent(this, com.writer.audio.AudioRecordingService::class.java)
        startForegroundService(serviceIntent)

        // Use whisper if enabled (better accuracy, but slower than realtime).
        // Default to Android SpeechRecognizer for real-time transcription.
        if (useWhisperTranscriber) {
            startWhisperLectureRecognition()
        } else {
            // Start concurrent audio capture alongside SpeechRecognizer
            val capture = com.writer.audio.AudioRecordCapture(cacheDir)
            if (capture.start()) {
                audioRecordCapture = capture
                android.util.Log.i("WritingActivity", "Concurrent audio capture started")
            } else {
                android.util.Log.w("WritingActivity", "Concurrent audio capture unavailable")
            }
            startLectureSpeechRecognition()
        }
    }

    private fun startLectureSpeechRecognition() {
        // Close previous transcriber before creating a new one
        audioTranscriber?.close()

        val transcriber = com.writer.recognition.SystemSpeechTranscriber(this)
        audioTranscriber = transcriber

        transcriber.onFinalResult = { text ->
            if (text.isNotBlank() && lectureMode) {
                val now = System.currentTimeMillis()
                val startMs = now - lectureRecordingStartMs - 3000L
                val endMs = now - lectureRecordingStartMs
                val audioFile = audioCaptureManager?.getOutputFile()?.name ?: ""
                activeCoordinator?.insertTextBlock(
                    text, audioFile = audioFile,
                    startMs = startMs.coerceAtLeast(0), endMs = endMs
                )
                updateCueIndicatorStrip()
                android.util.Log.i("WritingActivity", "Lecture transcribed: $text")
            }
            // Restart recognition for the next sentence (continuous mode)
            if (lectureMode) {
                // Post to main looper to allow SpeechRecognizer cleanup
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (lectureMode) startLectureSpeechRecognition()
                }
            }
        }

        var rmsLogCounter = 0
        transcriber.onRmsChanged = { rmsdB ->
            audioQualityMonitor.onRmsChanged(rmsdB)
            rmsLogCounter++
            if (rmsLogCounter % 20 == 0) {
                android.util.Log.i("AudioQuality",
                    "rms=%.1f noiseFloor=%.1f peakSpeech=%.1f snr=%.1f quality=%s".format(
                        rmsdB,
                        audioQualityMonitor.noiseFloorDb,
                        audioQualityMonitor.peakSpeechDb,
                        audioQualityMonitor.snr,
                        audioQualityMonitor.quality.name
                    ))
            }
            if (audioQualityMonitor.shouldWarn && !audioQualityWarned) {
                audioQualityWarned = true
                Toast.makeText(this, audioQualityMonitor.qualityMessage, Toast.LENGTH_LONG).show()
            }
        }

        transcriber.onError = { errorCode ->
            android.util.Log.w("WritingActivity", "Lecture recognition error: $errorCode")
            if (lectureMode) {
                // NO_MATCH (7) and ERROR_SPEECH_TIMEOUT (6) are normal in continuous
                // mode — restart immediately. Other errors get a brief delay.
                val delayMs = if (errorCode == 6 || errorCode == 7) 100L else 1000L
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (lectureMode) startLectureSpeechRecognition()
                }, delayMs)
            }
        }

        transcriber.start(documentModel.language)
    }

    private fun startWhisperLectureRecognition() {
        val transcriber = com.writer.recognition.WhisperTranscriber(this)
        audioTranscriber = transcriber

        transcriber.onStatusUpdate = { status ->
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
        }

        transcriber.onPartialResult = { text ->
            if (text.isNotBlank() && lectureMode) {
                android.util.Log.i("WritingActivity", "Whisper partial: $text")
            }
        }

        transcriber.onFinalResult = { text ->
            android.util.Log.i("WritingActivity", "Whisper final: $text")
            val recordingName = "rec-${lectureRecordingStartMs}.wav"
            if (text.isNotBlank()) {
                activeCoordinator?.insertTextBlock(text, audioFile = recordingName)
                updateCueIndicatorStrip()
            }
            // Save audio to document bundle
            val wavBytes = transcriber.getRecordedWavBytes()
            val audioFiles = if (wavBytes != null) mapOf(recordingName to wavBytes) else emptyMap()

            // Clean up after batch transcription
            lectureMode = false
            audioTranscriber?.close()
            audioTranscriber = null

            // Save document with audio
            val snapshot = createSaveSnapshot()
            if (snapshot != null) {
                DocumentStorage.save(this, snapshot.name, snapshot.state, audioFiles)
            }
            Toast.makeText(this, "Lecture capture done", Toast.LENGTH_SHORT).show()
        }

        transcriber.onError = { _ ->
            android.util.Log.w("WritingActivity", "Whisper error")
            if (lectureMode) {
                Toast.makeText(this, "Whisper transcription error", Toast.LENGTH_SHORT).show()
            }
        }

        transcriber.start(documentModel.language)
    }

    private fun stopLectureCapture() {
        if (!lectureMode) return
        micButton.setImageResource(R.drawable.ic_mic)
        inkCanvas.lectureRecording = false

        if (audioTranscriber is com.writer.recognition.WhisperTranscriber) {
            // Whisper: stop() triggers batch transcription, cleanup in onFinalResult
            audioTranscriber?.stop()
        } else {
            // SpeechRecognizer: close immediately, text blocks already inserted per-sentence
            lectureMode = false
            audioTranscriber?.close()
            audioTranscriber = null

            // Save concurrent audio capture if available
            audioRecordCapture?.stop()
            val wavBytes = audioRecordCapture?.readRecordedBytes()
            audioRecordCapture = null
            if (wavBytes != null) {
                val recordingName = "rec-${lectureRecordingStartMs}.wav"
                val snapshot = createSaveSnapshot()
                if (snapshot != null) {
                    DocumentStorage.save(this, snapshot.name, snapshot.state, mapOf(recordingName to wavBytes))
                }
                android.util.Log.i("WritingActivity", "Saved ${wavBytes.size} bytes audio to bundle")
            } else {
                snapshotAndSaveBlocking()
            }
            Toast.makeText(this, "Lecture capture stopped", Toast.LENGTH_SHORT).show()
        }

        // Stop foreground service
        val serviceIntent = android.content.Intent(this, com.writer.audio.AudioRecordingService::class.java)
        stopService(serviceIntent)
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
        popupView.findViewById<android.view.View>(R.id.menuBugReport).setOnClickListener {
            popup.dismiss()
            generateAndShareBugReport()
        }
        popupView.findViewById<android.view.View>(R.id.menuSettings).setOnClickListener {
            popup.dismiss()
            settingsLauncher.launch(android.content.Intent(this, com.writer.ui.settings.SettingsActivity::class.java))
        }

        // Position to the left of the menu button so it stays visible
        val loc = IntArray(2)
        menuButton.getLocationOnScreen(loc)
        val x = loc[0] - popupWidth
        val y = loc[1]
        popup.showAtLocation(menuButton, Gravity.NO_GRAVITY, x, y)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (nowLandscape == isLandscape) return

        // Dismiss cue peek popup
        cuePeekPopup?.dismiss()

        val wasDualColumn = columnLayoutLogic.isDualColumn

        // Unfold from cue view BEFORE updating orientation — unfoldToNotes()
        // checks isDualColumn and returns early if already dual.
        if (nowLandscape && isFoldedToCues) unfoldToNotes()

        // Update orientation state and refresh ScreenMetrics for new width
        columnLayoutLogic.onOrientationChanged(nowLandscape)
        ScreenMetrics.init(resources.displayMetrics, resources.configuration)

        val nowDualColumn = columnLayoutLogic.isDualColumn

        // Always close shared SDK before views resize — even when staying dual-column,
        // the canvas surfaces change dimensions on rotation and need re-initialization.
        if (wasDualColumn) {
            closeDualCanvasOnyx()
        }

        if (nowDualColumn) {
            cueIndicatorStrip.visibility = View.GONE
        }

        // Reset to weight-based layout so the system recalculates for new orientation
        resetToWeightBasedLayout()

        // After layout settles, compute fixed heights and proceed
        inkCanvas.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    inkCanvas.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    applyFixedSplitHeights()

                    if (nowDualColumn) {
                        showCueColumn()
                    } else {
                        hideCueColumn()
                        cueIndicatorStrip.visibility = View.VISIBLE
                        updateCueIndicatorStrip()
                    }
                }
            }
        )

        orientationManager.updateButtonVisibility()
    }

    /** Reset text/canvas to weight-based sizing so the system can measure for new orientation. */
    private fun resetToWeightBasedLayout() {
        val textParams = recognizedTextView.layoutParams as LinearLayout.LayoutParams
        textParams.height = 0
        textParams.weight = 1f
        recognizedTextView.layoutParams = textParams

        val canvasParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
        canvasParams.height = 0
        canvasParams.weight = 3f
        inkCanvas.layoutParams = canvasParams
    }

    /** Compute and apply fixed pixel heights for the text/canvas split. */
    private fun applyFixedSplitHeights() {
        val totalHeight = recognizedTextView.height + inkCanvas.height
        defaultCanvasHeight = ScreenMetrics.computeDefaultCanvasHeight(totalHeight)
        defaultTextHeight = totalHeight - defaultCanvasHeight
        splitOffset = 0f

        val textParams = recognizedTextView.layoutParams as LinearLayout.LayoutParams
        textParams.height = defaultTextHeight
        textParams.weight = 0f
        recognizedTextView.layoutParams = textParams

        val canvasParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
        canvasParams.height = defaultCanvasHeight
        canvasParams.weight = 0f
        inkCanvas.layoutParams = canvasParams

        Log.i(TAG, "Split heights: text=${defaultTextHeight}px, canvas=${defaultCanvasHeight}px")
    }

    private fun ensureCueCoordinator() {
        if (cueCoordinator == null) {
            cueCoordinator = WritingCoordinator(
                documentModel = documentModel,
                columnModel = documentModel.cue,
                recognizer = recognizer,
                inkCanvas = cueInkCanvas,
                textView = cueRecognizedTextView,
                scope = lifecycleScope,
                onStatusUpdate = {}
            )
        }
    }

    private fun showCueColumn() {
        columnDivider.visibility = View.VISIBLE
        cueSplitLayout.visibility = View.VISIBLE
        // Toggle visibility based on layout logic
        viewToggleButton.visibility = if (columnLayoutLogic.showToggleButton) View.VISIBLE else View.GONE

        // Apply column widths (fixed pixel widths on large screens, weights on small)
        applyColumnWidths()

        // Apply same text/canvas split heights as main column
        applyCueSplitHeights()

        // Restore cue strokes after layout is ready (surface needs to exist first)
        cueInkCanvas.post {
            cueInkCanvas.loadStrokes(documentModel.cue.activeStrokes.toList())
            cueInkCanvas.diagramAreas = documentModel.cue.diagramAreas.toList()
            cueInkCanvas.scrollOffsetY = inkCanvas.scrollOffsetY
            cueInkCanvas.drawToSurface()

            ensureCueCoordinator()
            cueCoordinator?.start()

            val penRecentOnEither = { inkCanvas.isPenRecentlyActive() || cueInkCanvas.isPenRecentlyActive() }

            coordinator?.onLinkedScroll = {
                if (!penRecentOnEither()) {
                    cueInkCanvas.scrollOffsetY = inkCanvas.scrollOffsetY
                    cueInkCanvas.drawToSurface()
                    cueCoordinator?.refreshDisplay()
                }
            }
            cueCoordinator?.onLinkedScroll = {
                if (!penRecentOnEither()) {
                    inkCanvas.scrollOffsetY = cueInkCanvas.scrollOffsetY
                    inkCanvas.drawToSurface()
                    coordinator?.refreshDisplay()
                }
            }

            // Cross-column space insertion: sync via coordinator for undo support
            coordinator?.onSpaceChanged = { anchorLine, delta ->
                cueCoordinator?.syncSpaceChange(anchorLine, delta)
            }
            cueCoordinator?.onSpaceChanged = { anchorLine, delta ->
                coordinator?.syncSpaceChange(anchorLine, delta)
            }

            // Recognize existing cue content
            cueCoordinator?.recognizeAllLines()

            // Initialize shared Onyx SDK after the layout pass completes so
            // canvas dimensions reflect landscape, not stale portrait values.
            inkCanvas.cancelPendingReinit()
            cueInkCanvas.cancelPendingReinit()
            cueInkCanvas.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        cueInkCanvas.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        initDualCanvasOnyx()
                        Log.i(TAG, "Cue column shown (landscape)")
                    }
                }
            )
        }
    }

    /** Apply column widths from ColumnLayoutLogic. Large screens use fixed pixel widths;
     *  small screens use equal layout weights (50/50). */
    private fun applyColumnWidths() {
        val widths = columnLayoutLogic.columnWidths()
        val mainParams = mainSplitLayout.layoutParams as LinearLayout.LayoutParams
        val cueParams = cueSplitLayout.layoutParams as LinearLayout.LayoutParams
        if (widths.mainWidthPx > 0) {
            // Large screen: fixed pixel widths
            mainParams.width = widths.mainWidthPx
            mainParams.weight = 0f
            cueParams.width = widths.cueWidthPx
            cueParams.weight = 0f
        } else {
            // Small screen: equal weights (50/50)
            mainParams.width = 0
            mainParams.weight = 1f
            cueParams.width = 0
            cueParams.weight = 1f
        }
        mainSplitLayout.layoutParams = mainParams
        cueSplitLayout.layoutParams = cueParams
    }

    /** Apply the same text/canvas split heights to the cue column as the main column. */
    private fun applyCueSplitHeights() {
        val textParams = cueRecognizedTextView.layoutParams as LinearLayout.LayoutParams
        textParams.height = defaultTextHeight
        textParams.weight = 0f
        cueRecognizedTextView.layoutParams = textParams

        val canvasParams = cueInkCanvas.layoutParams as LinearLayout.LayoutParams
        canvasParams.height = defaultCanvasHeight
        canvasParams.weight = 0f
        cueInkCanvas.layoutParams = canvasParams
    }

    private fun hideCueColumn() {
        cueCoordinator?.stop()
        cueCoordinator?.onLinkedScroll = null
        cueCoordinator?.onSpaceChanged = null
        coordinator?.onSpaceChanged = null
        // Restore portrait strip scroll sync
        coordinator?.onLinkedScroll = { cueIndicatorStrip.scrollOffsetY = inkCanvas.scrollOffsetY }

        closeDualCanvasOnyx()
        columnDivider.visibility = View.GONE
        cueSplitLayout.visibility = View.GONE
        viewToggleButton.visibility = if (columnLayoutLogic.showToggleButton) View.VISIBLE else View.GONE
        // Reset main column to fill width
        val mainParams = mainSplitLayout.layoutParams as LinearLayout.LayoutParams
        mainParams.width = 0
        mainParams.weight = 1f
        mainSplitLayout.layoutParams = mainParams
        updateViewToggleIcon()

        // Restore main canvas per-view Onyx SDK
        inkCanvas.deferOnyxInit = false
        inkCanvas.reinitializeRawDrawing()

        Log.i(TAG, "Cue column hidden (portrait)")
    }

    /** Portrait: fold from notes to cues view — show cue column full-width, hide main.
     *  Only available on small screens in portrait (not dual-column mode). */
    private fun foldToCues() {
        if (columnLayoutLogic.isDualColumn || isFoldedToCues) return
        isFoldedToCues = true

        // Hide main column, show cue column
        mainSplitLayout.visibility = View.GONE
        cueIndicatorStrip.visibility = View.GONE
        cueSplitLayout.visibility = View.VISIBLE
        contextRail.visibility = View.VISIBLE
        updateViewToggleIcon()

        // Populate context rail with main content indicators
        populateContextRail()

        // Set cue column weight to fill
        val params = cueSplitLayout.layoutParams as LinearLayout.LayoutParams
        params.weight = 1f
        cueSplitLayout.layoutParams = params

        // Apply same split heights to cue column
        applyCueSplitHeights()

        // Close shared SDK if active (shouldn't be in portrait, but safety)
        closeDualCanvasOnyx()
        // Close main canvas SDK, cue will init its own after layout
        inkCanvas.closeRawDrawing()

        // Load cue content after layout is ready (surface needs to exist first)
        cueInkCanvas.post {
            cueInkCanvas.loadStrokes(documentModel.cue.activeStrokes.toList())
            cueInkCanvas.diagramAreas = documentModel.cue.diagramAreas.toList()
            cueInkCanvas.scrollOffsetY = inkCanvas.scrollOffsetY
            cueInkCanvas.drawToSurface()

            ensureCueCoordinator()
            cueCoordinator?.start()
            // Sync context rail scroll with cue canvas
            cueCoordinator?.onLinkedScroll = { contextRail.scrollOffsetY = cueInkCanvas.scrollOffsetY }
            cueCoordinator?.recognizeAllLines()

            // Give Onyx SDK to cue canvas since it's the only visible one
            cueInkCanvas.deferOnyxInit = false
            cueInkCanvas.reinitializeRawDrawing()

            spaceInsertButton.visibility = View.GONE

            Log.i(TAG, "Folded to cues (portrait)")
        }
    }

    /** Portrait: unfold from cues back to notes view.
     *  Only available on small screens in portrait (not dual-column mode). */
    private fun unfoldToNotes() {
        if (columnLayoutLogic.isDualColumn || !isFoldedToCues) return
        isFoldedToCues = false

        // Show main column, hide cue column
        mainSplitLayout.visibility = View.VISIBLE
        cueIndicatorStrip.visibility = View.VISIBLE
        cueSplitLayout.visibility = View.GONE
        contextRail.visibility = View.GONE
        updateViewToggleIcon()

        // Stop cue coordinator, transfer Onyx SDK back to main
        cueCoordinator?.stop()
        cueInkCanvas.closeRawDrawing()
        cueInkCanvas.deferOnyxInit = true

        // Sync scroll position from cue to main (preserve position)
        inkCanvas.scrollOffsetY = cueInkCanvas.scrollOffsetY

        // Restart main canvas with Onyx SDK
        inkCanvas.reinitializeRawDrawing()
        inkCanvas.drawToSurface()

        updateCueIndicatorStrip()

        spaceInsertButton.visibility = View.VISIBLE

        Log.i(TAG, "Unfolded to notes (portrait)")
    }

    /**
     * Initialize per-canvas Onyx SDK with hover-based swap between canvases.
     * Each canvas gets its own TouchHelper bound directly to the SurfaceView,
     * avoiding the root-view dead zone. Only one is active at a time — the
     * SDK session transfers on stylus hover (before pen-down) so the full
     * stroke gets SDK speed with no dead zones.
     */
    private fun initDualCanvasOnyx() {
        // Main canvas keeps its per-view SDK (already initialized or will init via surfaceCreated)
        inkCanvas.deferOnyxInit = false
        cueInkCanvas.deferOnyxInit = true  // cue defers — gets session on hover

        // Wire up hover-based swap
        cueInkCanvas.onRequestOnyxSession = { x, y -> transferOnyxSession(toCue = true, hoverX = x, hoverY = y) }
        inkCanvas.onRequestOnyxSession = { x, y -> transferOnyxSession(toCue = false, hoverX = x, hoverY = y) }

        // Initialize main canvas SDK
        inkCanvas.reinitializeRawDrawing()

        Log.i(TAG, "Dual-canvas Onyx SDK initialized (hover swap)")
    }

    private fun closeDualCanvasOnyx() {
        inkCanvas.onRequestOnyxSession = null
        cueInkCanvas.onRequestOnyxSession = null
        cueInkCanvas.closeRawDrawing()
        cueInkCanvas.deferOnyxInit = true
        Log.i(TAG, "Dual-canvas Onyx SDK closed")
    }

    /** Transfer the Onyx SDK session between canvases on hover.
     *  Uses the hover position to reset the EPD controller's starting point,
     *  preventing errant lines from stale positions. */
    private var lastTransferTime = 0L
    private fun transferOnyxSession(toCue: Boolean, hoverX: Float, hoverY: Float) {
        // Cooldown to prevent rapid bouncing at the divider boundary
        val now = System.currentTimeMillis()
        if (now - lastTransferTime < 300) return
        lastTransferTime = now

        val recipient: HandwritingCanvasView
        val donor: HandwritingCanvasView
        if (toCue) {
            if (cueInkCanvas.isUsingOnyxSdk()) return
            recipient = cueInkCanvas
            donor = inkCanvas
            cueInkCanvas.deferOnyxInit = false
        } else {
            if (inkCanvas.isUsingOnyxSdk()) return
            recipient = inkCanvas
            donor = cueInkCanvas
            cueInkCanvas.deferOnyxInit = true
        }

        donor.closeRawDrawing()
        donor.drawToSurface()
        recipient.reopenRawDrawing()

        Log.d(TAG, "Onyx SDK transferred to ${if (toCue) "cue" else "main"} canvas")
    }

    /** Toggle button handler — behavior depends on screen size and orientation. */
    private fun toggleNotesCues() {
        when (columnLayoutLogic.toggleAction) {
            ColumnLayoutLogic.ToggleAction.NONE -> return
            ColumnLayoutLogic.ToggleAction.EXPAND_CUE -> {
                // Large screen portrait: expand/contract cue column
                columnLayoutLogic.toggleCueExpand()
                applyColumnWidths()
            }
            ColumnLayoutLogic.ToggleAction.FOLD_UNFOLD -> {
                // Small screen portrait: fold/unfold between notes and cues
                // During tutorial basics, toggle is disabled
                if (tutorialManager.isActive && !tutorialManager.isCornellPhase()) return
                if (isFoldedToCues) {
                    unfoldToNotes()
                    tutorialManager.onStepAction("unfolded_to_notes")
                } else {
                    foldToCues()
                    tutorialManager.onStepAction("folded_to_cues")
                }
            }
        }
    }

    /** Update the gutter toggle icon to show what tapping will switch TO. */
    private fun updateViewToggleIcon() {
        if (isFoldedToCues) {
            viewToggleButton.setImageResource(R.drawable.ic_notes)
            viewToggleButton.contentDescription = "Switch to notes"
        } else {
            viewToggleButton.setImageResource(R.drawable.ic_cue)
            viewToggleButton.contentDescription = "Switch to cues"
        }
        // Dim during tutorial basics (inactive), bright during Cornell phase or normal use
        val dimmed = tutorialManager.isActive && !tutorialManager.isCornellPhase()
        viewToggleButton.imageAlpha = if (dimmed) 40 else 255
        // Hide rotate button during tutorial
        val rotateBtn = findViewById<ImageView>(R.id.rotateButton)
        if (tutorialManager.isActive) {
            rotateBtn.visibility = View.GONE
        } else {
            orientationManager.updateButtonVisibility()
        }
    }

    /** Show a floating preview of main content strokes (from context rail long-press). */
    private fun showMainPeek(lineIndex: Int, screenY: Float) {
        showStrokePeek(lineIndex, screenY, documentModel.main.activeStrokes, documentModel.main.diagramAreas, documentModel.main.textBlocks, contextRail)
    }

    /** Show a floating preview of cue strokes (from cue indicator strip long-press). */
    private fun showCuePeek(lineIndex: Int, screenY: Float) {
        showStrokePeek(lineIndex, screenY, documentModel.cue.activeStrokes, documentModel.cue.diagramAreas, documentModel.cue.textBlocks, cueIndicatorStrip)
    }

    /** Show a floating preview of strokes for a contiguous block around [lineIndex]. */
    private fun showStrokePeek(
        lineIndex: Int, screenY: Float,
        strokes: List<com.writer.model.InkStroke>,
        diagramAreas: List<com.writer.model.DiagramArea>,
        textBlocks: List<com.writer.model.TextBlock>,
        anchorView: View
    ) {
        val segmenter = com.writer.recognition.LineSegmenter()

        val strokeLines = strokes.map { segmenter.getStrokeLineIndex(it) }.toSet()
        val diagramLines = mutableSetOf<Int>()
        for (area in diagramAreas) {
            for (l in area.startLineIndex..area.endLineIndex) diagramLines.add(l)
        }
        val textBlockLines = textBlocks.flatMap { it.startLineIndex..it.endLineIndex }.toSet()
        val occupiedLines = strokeLines + diagramLines + textBlockLines
        if (lineIndex !in occupiedLines) return

        var top = lineIndex
        while (top - 1 in occupiedLines) top--
        var bottom = lineIndex
        while (bottom + 1 in occupiedLines) bottom++

        val blockLines = (top..bottom).toSet()
        val blockStrokes = strokes.filter { segmenter.getStrokeLineIndex(it) in blockLines }
        val blockTextBlocks = textBlocks.filter { tb -> (tb.startLineIndex..tb.endLineIndex).any { it in blockLines } }
        if (blockStrokes.isEmpty() && blockTextBlocks.isEmpty()) return

        val previewWidth = (inkCanvas.width * 0.6f).toInt()
        val previewView = com.writer.view.CuePreviewView(this)
        previewView.setStrokes(blockStrokes, previewWidth, blockTextBlocks, inkCanvas.width.toFloat())

        previewView.measure(
            View.MeasureSpec.makeMeasureSpec(previewWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val previewHeight = previewView.measuredHeight.coerceAtMost((inkCanvas.height * 0.6f).toInt())

        val popup = android.widget.PopupWindow(previewView, previewWidth, previewHeight, true)
        popup.elevation = 0f
        popup.setBackgroundDrawable(null)

        // Position near the anchor view
        val anchorLoc = IntArray(2)
        anchorView.getLocationOnScreen(anchorLoc)
        val isLeftSide = anchorLoc[0] < resources.displayMetrics.widthPixels / 2
        val popupX = if (isLeftSide) {
            anchorLoc[0] + anchorView.width + com.writer.view.ScreenMetrics.dp(4f).toInt()
        } else {
            anchorLoc[0] - previewWidth - com.writer.view.ScreenMetrics.dp(4f).toInt()
        }
        val popupY = (screenY - previewHeight / 2).toInt()
            .coerceIn(0, resources.displayMetrics.heightPixels - previewHeight)

        inkCanvas.pauseRawDrawing()
        popup.setOnDismissListener {
            inkCanvas.resumeRawDrawing()
            cuePeekPopup = null
            // Notify tutorial that user dismissed a peek popup
            tutorialManager.onStepAction("cue_peeked")
            tutorialManager.onStepAction("note_peeked")
        }
        cuePeekPopup = popup
        popup.showAtLocation(inkCanvas, android.view.Gravity.NO_GRAVITY, popupX, popupY)
    }

    /** Populate the context rail with main content indicators (dots/segments). */
    private fun populateContextRail() {
        val segmenter = com.writer.recognition.LineSegmenter()
        val mainLines = documentModel.main.activeStrokes.map { segmenter.getStrokeLineIndex(it) }.toSet() +
            documentModel.main.textBlocks.flatMap { it.startLineIndex..it.endLineIndex }.toSet()
        contextRail.cueLineIndices = mainLines
        contextRail.cueDiagramAreas = documentModel.main.diagramAreas.toList()
        contextRail.scrollOffsetY = cueInkCanvas.scrollOffsetY
        // Set canvasTopOffset to match cue canvas position
        cueInkCanvas.post {
            val canvasLoc = IntArray(2)
            val railLoc = IntArray(2)
            cueInkCanvas.getLocationOnScreen(canvasLoc)
            contextRail.getLocationOnScreen(railLoc)
            contextRail.canvasTopOffset = (canvasLoc[1] - railLoc[1]).toFloat()
            contextRail.invalidate()
        }
    }

    private fun updateCueIndicatorStrip() {
        val segmenter = com.writer.recognition.LineSegmenter()
        val cueLines = documentModel.cue.activeStrokes.map { stroke ->
            segmenter.getStrokeLineIndex(stroke)
        }.toSet() +
            documentModel.cue.textBlocks.flatMap { it.startLineIndex..it.endLineIndex }.toSet()
        cueIndicatorStrip.cueLineIndices = cueLines
        cueIndicatorStrip.cueDiagramAreas = documentModel.cue.diagramAreas.toList()
        cueIndicatorStrip.scrollOffsetY = inkCanvas.scrollOffsetY
        // Update canvas top offset in case layout changed
        inkCanvas.post {
            val canvasLoc = IntArray(2)
            val stripLoc = IntArray(2)
            inkCanvas.getLocationOnScreen(canvasLoc)
            cueIndicatorStrip.getLocationOnScreen(stripLoc)
            cueIndicatorStrip.canvasTopOffset = (canvasLoc[1] - stripLoc[1]).toFloat()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!tutorialManager.isActive) {
            inkCanvas.reinitializeRawDrawing()
        }
        orientationManager.start()
    }

    override fun onStop() {
        super.onStop()
        orientationManager.stop()
        snapshotAndSaveBlocking()
    }

    private fun scheduleAutoSave() {
        // Trigger recognition for uncached lines so the save captures all text
        coordinator?.recognizeAllLines()
        cueCoordinator?.recognizeAllLines()
        autoSaver.schedule { createSaveSnapshot() }
    }

    private fun snapshotAndSaveBlocking() {
        val snapshot = createSaveSnapshot() ?: return
        autoSaver.saveBlocking(snapshot)
    }

    private fun snapshotAndSaveAsync() {
        val snapshot = createSaveSnapshot() ?: return
        autoSaver.saveAsync(snapshot)
    }

    private fun createSaveSnapshot(): AutoSaver.Snapshot? {
        if (tutorialManager.isActive) return null
        val mainState = coordinator?.getState() ?: return null
        val cueColumnData = cueCoordinator?.getColumnState() ?: ColumnData(
            strokes = documentModel.cue.activeStrokes.toList(),
            diagramAreas = documentModel.cue.diagramAreas.toList()
        )
        val state = mainState.copy(cue = cueColumnData)
        val syncUriStr = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_SYNC_FOLDER, null)
        val markdown = if (syncUriStr != null) {
            val cueBlocks = cueCoordinator?.getMarkdownBlocks() ?: emptyList()
            coordinator?.getMarkdownText(cueBlocks) ?: ""
        } else null
        return AutoSaver.Snapshot(
            name = currentDocumentName,
            state = state,
            markdown = markdown,
            syncUri = syncUriStr?.let { Uri.parse(it) },
        )
    }

    // --- Bug report ---

    // --- Tutorial helpers ---

    /** Saved cue data cleared during tutorial, restored on close. */
    private var savedCueStrokes: List<com.writer.model.InkStroke>? = null
    private var savedCueDiagramAreas: List<com.writer.model.DiagramArea>? = null

    /** Show tutorial, clearing cue data and hiding the cue indicator strip. */
    private fun showTutorial() {
        // Save cue data before clearing
        savedCueStrokes = documentModel.cue.activeStrokes.toList()
        savedCueDiagramAreas = documentModel.cue.diagramAreas.toList()

        // Clear cue column
        documentModel.cue.activeStrokes.clear()
        documentModel.cue.diagramAreas.clear()

        // Hide cue indicator strip
        cueIndicatorStrip.cueLineIndices = emptySet()
        cueIndicatorStrip.cueDiagramAreas = emptyList()
        cueIndicatorStrip.visibility = View.GONE

        // If in cue portrait view, unfold first
        if (isFoldedToCues) unfoldToNotes()

        tutorialManager.show()
    }

    /** Handle tutorial step changes — load demo content for Cornell Notes steps. */
    private fun onTutorialStepChanged() {
        when (tutorialManager.currentStepId) {
            "switch_to_cues" -> {
                // Pre-load demo cue strokes so content exists when user folds to cue view
                val font = HersheyFont.loadScript(this) ?: return
                val ls = HandwritingCanvasView.LINE_SPACING
                val tm = HandwritingCanvasView.TOP_MARGIN
                val cw = inkCanvas.width.toFloat()
                val cueStrokes = TutorialDemoContent.generateCueStrokes(font, cw, ls, tm)
                documentModel.cue.activeStrokes.addAll(cueStrokes)
                cueIndicatorStrip.visibility = View.VISIBLE
                updateCueIndicatorStrip()
                inkCanvas.pauseRawDrawing()
                inkCanvas.drawToSurface()
                inkCanvas.resumeRawDrawing()
            }
            "peek_note" -> {
                // Ensure context rail shows main content for peeking
                populateContextRail()
            }
        }
    }

    /** Restore cue data after tutorial closes. */
    private fun restoreCueDataAfterTutorial() {
        // Clear tutorial cue content before restoring saved data
        documentModel.cue.activeStrokes.clear()
        documentModel.cue.diagramAreas.clear()

        savedCueStrokes?.let { documentModel.cue.activeStrokes.addAll(it) }
        savedCueDiagramAreas?.let { documentModel.cue.diagramAreas.addAll(it) }
        savedCueStrokes = null
        savedCueDiagramAreas = null

        // Restore cue indicator strip (only on small screens in portrait)
        if (!columnLayoutLogic.isDualColumn) {
            cueIndicatorStrip.visibility = View.VISIBLE
            updateCueIndicatorStrip()
        }
    }

    private fun generateShowcaseDocument() {
        val font = HersheyFont.loadScript(this)
        val ls = com.writer.view.HandwritingCanvasView.LINE_SPACING
        val tm = com.writer.view.HandwritingCanvasView.TOP_MARGIN
        val cw = inkCanvas.width.toFloat()

        // Clear current document
        coordinator?.stop()
        coordinator?.reset()
        documentModel.main.activeStrokes.clear()
        inkCanvas.clear()
        inkCanvas.scrollOffsetY = 0f
        recognizedTextView.setParagraphs(emptyList())

        // Generate demo strokes + diagram area
        val showcase = TutorialDemoContent.generateShowcaseDocument(font, cw, ls, tm)
        documentModel.main.activeStrokes.addAll(showcase.strokes)
        documentModel.main.diagramAreas.add(showcase.diagramArea)
        inkCanvas.loadStrokes(showcase.strokes)
        inkCanvas.diagramAreas = documentModel.main.diagramAreas.toList()
        inkCanvas.drawToSurface()

        // Restart coordinator
        coordinator?.start()
        coordinator?.onHeadingDetected = { heading -> autoRenameFromHeading(heading) }
        coordinator?.onUndoRedoStateChanged = { updateUndoRedoButtons() }
        coordinator?.onTutorialAction = { actionId -> tutorialManager.onStepAction(actionId) }

        // Trigger recognition
        coordinator?.recognizeAllLines()
        inkCanvas.reinitializeRawDrawing()
    }

    private fun promptSyncFolderIfNeeded() {
        val hasSyncFolder = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_SYNC_FOLDER, null) != null
        if (hasSyncFolder) return

        AlertDialog.Builder(this)
            .setTitle("Sync your notes?")
            .setMessage("Choose a folder to back up your notes. You can also pick a cloud folder (Google Drive, OneDrive) for automatic sync.")
            .setPositiveButton("Choose folder") { _, _ ->
                pickSyncFolder.launch(null)
            }
            .setNegativeButton("Maybe later", null)
            .show()
    }

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
                putExtra(Intent.EXTRA_SUBJECT, "Mokke Bug Report")
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
        if (lectureMode) stopLectureCapture()
        unregisterReceiver(bugReportReceiver)
        closeDualCanvasOnyx()
        coordinator?.stop()
        cueCoordinator?.stop()
        recognizer.close()
        audioTranscriber?.close()
        audioTranscriber = null
        audioRecordCapture?.stop()
        audioRecordCapture = null
    }
}
