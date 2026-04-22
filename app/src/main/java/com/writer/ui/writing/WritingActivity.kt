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
import com.writer.storage.AutomergeAdapter
import com.writer.storage.AutomergeSink
import com.writer.storage.AutomergeStorage
import com.writer.storage.VersionHistory
import com.writer.view.VersionHistoryOverlayView
import com.writer.storage.DocumentStorage
import com.writer.storage.DocumentStorageSink
import com.writer.storage.SearchIndexManager
import com.writer.view.CanvasTheme
import com.writer.view.GutterTouchGuard
import com.writer.view.HandwritingCanvasView
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
        private const val UNDO_REDO_DEBOUNCE_MS = 300L
        private const val MEMO_AUTO_STOP_DELAY_MS = 2000L
    }

    private lateinit var inkCanvas: HandwritingCanvasView

    // Cue column views (landscape Cornell Notes)
    private lateinit var cueInkCanvas: HandwritingCanvasView
    private lateinit var columnDivider: View
    private lateinit var cueIndicatorStrip: com.writer.view.CueIndicatorStrip
    private lateinit var contextRail: com.writer.view.CueIndicatorStrip

    // Transcript column (owns all audio-derived TextBlocks)
    private lateinit var transcriptInkCanvas: HandwritingCanvasView
    private lateinit var transcriptColumnDivider: View
    private lateinit var transcriptIndicatorStrip: com.writer.view.CueIndicatorStrip
    private var transcriptCoordinator: WritingCoordinator? = null
    private var cueCoordinator: WritingCoordinator? = null
    private var isLandscape = false
    private lateinit var columnLayoutLogic: ColumnLayoutLogic
    /** Which column is currently on-screen in FOLD mode (small portrait). Mirror of
     *  [ColumnLayoutLogic.activeColumn], kept separately so the view-transition
     *  dispatcher knows the "from" column when the listener fires with the "to". */
    private var currentFoldView: ColumnLayoutLogic.ActiveColumn = ColumnLayoutLogic.ActiveColumn.MAIN
    /** True when the user is currently viewing the cue canvas full-screen (small portrait).
     *  Derived from [ColumnLayoutLogic.activeColumn] so there's one source of truth. */
    private val isFoldedToCues: Boolean
        get() = columnLayoutLogic.activeColumn == ColumnLayoutLogic.ActiveColumn.CUE
    /** True when the user is currently viewing the transcript canvas full-screen. */
    private val isFoldedToTranscript: Boolean
        get() = columnLayoutLogic.activeColumn == ColumnLayoutLogic.ActiveColumn.TRANSCRIPT

    // View toggle button in gutter (Notes ↔ Cues)
    private lateinit var viewToggleButton: ImageView

    // Transcript drawer toggle (only in DRAWER display mode)
    private lateinit var transcriptButton: ImageView

    // Active cue peek popup (dismissed on orientation change)
    private var cuePeekPopup: android.widget.PopupWindow? = null

    /** The active coordinator — routes undo/redo to the right column.
     *  In portrait fold views, the coordinator matches the fold-view canvas.
     *  In dual-column mode, use whichever canvas the pen last wrote on. */
    private val activeCoordinator: WritingCoordinator?
        get() = when {
            isFoldedToTranscript -> transcriptCoordinator
            isFoldedToCues -> cueCoordinator
            columnLayoutLogic.isDualColumn && isCueCanvasActive -> cueCoordinator
            else -> coordinator
        }
    private var isCueCanvasActive = false

    // Floating gutter overlay
    private lateinit var gutterOverlay: View
    private lateinit var menuButton: ImageView
    private lateinit var undoButton: ImageView
    private lateinit var redoButton: ImageView
    private lateinit var spaceInsertButton: ImageView
    private lateinit var micButton: ImageView
    private var audioCaptureManager: com.writer.audio.AudioCaptureManager? = null
    private var audioRecordCapture: com.writer.audio.AudioRecordCapture? = null
    private val sherpaModelManager = com.writer.recognition.SherpaModelManager()
    private val offlineModelManager = com.writer.recognition.OfflineModelManager()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var playbackOverlay: com.writer.view.PlaybackOverlayView
    private lateinit var recordingOverlay: com.writer.view.RecordingOverlayView
    private val playbackController = com.writer.audio.PlaybackController(
        onPlay = { file, seekMs -> playAudio(file, seekMs) },
        onSeek = { seekMs -> onPlaybackSeek(seekMs) },
        onPause = { onPlaybackPause() },
        onResume = { onPlaybackResume() },
        onStop = { stopAudio() }
    )
    // Delegate to coordinator for document-scoped state
    private var lectureMode: Boolean
        get() = coordinator?.lectureMode ?: false
        set(value) { coordinator?.lectureMode = value }
    private var audioTranscriber: com.writer.recognition.AudioTranscriber?
        get() = coordinator?.audioTranscriber
        set(value) { coordinator?.audioTranscriber = value }
    private var lectureRecordingStartMs: Long
        get() = coordinator?.lectureRecordingStartMs ?: 0L
        set(value) { coordinator?.lectureRecordingStartMs = value }
    private val audioQualityMonitor get() = coordinator?.audioQualityMonitor ?: com.writer.audio.AudioQualityMonitor()
    private var audioQualityWarned = false
    private var audioPlaybackFile: java.io.File? = null

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

    // Saved data loaded before coordinator is ready
    private var pendingRestore: DocumentData? = null

    // Current document name
    private var currentDocumentName: String = ""

    // Automerge storage for incremental persistence (no ZIPs during auto-save)
    private lateinit var automergeStorage: AutomergeStorage
    private lateinit var versionHistory: VersionHistory

    // Version history overlay for scrubbing through checkpoints
    private lateinit var historyOverlay: VersionHistoryOverlayView
    private var isHistoryMode = false
    private var preHistoryData: DocumentData? = null
    private var historyDoc: org.automerge.Document? = null

    // Auto-save: initialized in onCreate after lifecycleScope is available
    private lateinit var autoSaver: AutoSaver
    private lateinit var automergeSink: AutomergeSink

    // Idle-timeout checkpointing: checkpoint after 10s of no saves
    private val checkpointHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var checkpointPending = false
    private val checkpointRunnable = Runnable {
        if (checkpointPending) {
            createIdleCheckpoint()
            checkpointPending = false
        }
    }

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
        onBackPressedDispatcher.addCallback(this, recordingBackCallback)

        val filter = android.content.IntentFilter("${packageName}.GENERATE_BUG_REPORT")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bugReportReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bugReportReceiver, filter)
        }

        val docsDir = java.io.File(filesDir, "documents")
        automergeStorage = AutomergeStorage(docsDir)
        versionHistory = VersionHistory(docsDir)
        val exportSink = DocumentStorageSink(applicationContext)
        val automergeSink = AutomergeSink(automergeStorage, exportSink)
        autoSaver = AutoSaver(lifecycleScope, automergeSink)
        this.automergeSink = automergeSink

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

        // Cue column views
        cueInkCanvas = findViewById(R.id.cueInkCanvas)
        columnDivider = findViewById(R.id.columnDivider)
        cueIndicatorStrip = findViewById(R.id.cueIndicatorStrip)
        contextRail = findViewById(R.id.contextRail)
        transcriptIndicatorStrip = findViewById(R.id.transcriptIndicatorStrip)

        // Transcript column views (hidden until Phase 3d wires the drawer/fold UI)
        transcriptInkCanvas = findViewById(R.id.transcriptInkCanvas)
        transcriptColumnDivider = findViewById(R.id.transcriptColumnDivider)
        transcriptInkCanvas.deferOnyxInit = true
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        documentModel = DocumentModel()
        columnLayoutLogic = ColumnLayoutLogic(object : ColumnLayoutLogic.Host {
            override val isLargeScreen get() = ScreenMetrics.isLargeScreen
            override var isLandscape
                get() = this@WritingActivity.isLandscape
                set(value) { this@WritingActivity.isLandscape = value }
            override val hasAnyRecording: Boolean
                get() = documentModel.hasAnyRecording()
        })
        columnLayoutLogic.onTranscriptVisibilityChanged = { visible ->
            onTranscriptVisibilityChanged(visible)
        }
        columnLayoutLogic.onActiveColumnChanged = { newColumn ->
            val from = currentFoldView
            currentFoldView = newColumn
            if (columnLayoutLogic.canFoldUnfold) dispatchFoldTransition(from, newColumn)
        }

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
        var lastButtonTouchMajor = 0f
        val palmGuard = android.view.View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastButtonTouchMajor = event.touchMajor
                    touchGuard.evaluateDown(event.touchMajor) == GutterTouchGuard.Decision.REJECT
                }
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
        transcriptButton = findViewById(R.id.transcriptButton)
        val rotateButton = findViewById<ImageView>(R.id.rotateButton)
        orientationManager = OrientationManager(this, rotateButton)

        for (btn in listOf(menuButton, undoButton, redoButton, spaceInsertButton, micButton, viewToggleButton, transcriptButton, rotateButton)) {
            btn.setOnTouchListener(palmGuard)
        }

        undoButton.imageAlpha = 77
        redoButton.imageAlpha = 77
        menuButton.setOnClickListener { showMenu() }
        undoButton.setOnClickListener { debounceUndoRedo { activeCoordinator?.undo(); updateUndoRedoButtons() } }
        redoButton.setOnClickListener { debounceUndoRedo { activeCoordinator?.redo(); updateUndoRedoButtons() } }
        spaceInsertButton.setOnClickListener { inkCanvas.spaceInsertMode = !inkCanvas.spaceInsertMode }
        micButton.setOnClickListener { toggleVoiceMemo() }
        micButton.setOnLongClickListener {
            // Reject palm — long-press fires before ACTION_UP, so palmGuard can't block it
            if (lastButtonTouchMajor > touchGuard.palmThresholdPx || isPenBusy()) {
                false
            } else {
                startLectureCapture(); true
            }
        }
        viewToggleButton.setOnClickListener { toggleNotesCues() }
        transcriptButton.setOnClickListener {
            columnLayoutLogic.toggleTranscriptDrawer()
            refreshTranscriptPresentation()
        }
        rotateButton.setOnClickListener { orientationManager.toggleOrientation() }

        inkCanvas.onTextBlockTap = { block, wordStartMs -> handleTextBlockTap(block, wordStartMs) }
        inkCanvas.onPlaybackStop = { playbackController.onTapOutside() }

        playbackOverlay = findViewById(R.id.playbackOverlay)
        playbackOverlay.onPauseToggle = { playbackController.onPauseToggle() }

        historyOverlay = findViewById(R.id.historyOverlay)
        historyOverlay.onCheckpointSelected = { checkpoint -> previewCheckpoint(checkpoint) }
        historyOverlay.onRestoreConfirmed = { checkpoint -> exitHistoryMode(restore = true, checkpoint) }
        historyOverlay.onDismiss = { exitHistoryMode(restore = false) }

        recordingOverlay = findViewById(R.id.recordingOverlay)
        recordingOverlay.onAutoStopChanged = { enabled ->
            autoStopOnSilence = enabled
            android.util.Log.i("WritingActivity", "Auto-stop toggled: $enabled")
        }
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
        cueIndicatorStrip.touchFilter = touchFilter
        contextRail.touchFilter = touchFilter

        val tutorialOverlay = findViewById<com.writer.view.TutorialOverlay>(R.id.tutorialOverlay)

        tutorialManager = TutorialManager(
            context = this,
            inkCanvas = inkCanvas,
            getCoordinator = { coordinator },
            getPendingRestore = { pendingRestore },
            clearPendingRestore = { pendingRestore = null },
            onClosed = {
                // Unfold back to main if tutorial ended on a non-main fold view
                if (isFoldedToCues || isFoldedToTranscript) {
                    columnLayoutLogic.activeColumn = ColumnLayoutLogic.ActiveColumn.MAIN
                }
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
                    if (!isFoldedToCues) {
                        columnLayoutLogic.activeColumn = ColumnLayoutLogic.ActiveColumn.CUE
                    }
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
        pendingRestore = loadDocument(currentDocumentName)
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
                saveNow()
            }
        }
        cueInkCanvas.onPenStateChanged = { active ->
            if (active) isCueCanvasActive = true
            if (!active) {
                gutterOverlay.post { updateUndoRedoButtons() }
                saveNow()
            }
        }

        // Pick the best available recognizer synchronously (initialized later in coroutine).
        // OnyxHwrTextRecognizer binds to a system service using applicationContext, so holding
        // it in the activity is safe (no activity leak). GoogleMLKitTextRecognizer is stateless
        // and does not retain a context reference.
        recognizer = TextRecognizerFactory.create(this)

        // Create coordinator early so cached text can be displayed before model loads
        startCoordinator()

        inkCanvas.post {
            // Restore cached text and scroll position immediately (no recognizer needed)
            restoreCoordinatorState()

            // Show tutorial on first launch
            if (tutorialManager.shouldAutoShow()) {
                inkCanvas.pauseRawDrawing()
                showTutorial()
            }
        }

        // Cue canvas touch filter
        val cueTouchFilter = TouchFilter()
        cueInkCanvas.touchFilter = cueTouchFilter

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
        Log.i(TAG, "Loading handwriting recognition model (~20 MB if first run)")
        lifecycleScope.launch {
            try {
                recognizer.initialize(documentModel.language)
                coordinator?.recognizeAllLines()
                cueCoordinator?.recognizeAllLines()
            } catch (e: Exception) {
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

        // Restore transcript column data (coordinator + UI wired up in Phase 3d).
        documentModel.transcript.activeStrokes.addAll(data.transcript.strokes)
        documentModel.transcript.diagramAreas.addAll(data.transcript.diagramAreas)
        documentModel.transcript.textBlocks.addAll(data.transcript.textBlocks)

        // Audio recordings are document-level state; sync them to the runtime model
        // so hasAnyRecording() / transcript visibility reflects what's on disk.
        documentModel.restoreAudioRecordings(data.audioRecordings)
        columnLayoutLogic.onRecordingsChanged()
    }

    /** Restore coordinator state (text cache, hidden lines) — no recognizer needed. */
    private fun restoreCoordinatorState() {
        if (tutorialManager.isActive) return // defer until tutorial closes
        val data = pendingRestore ?: return
        pendingRestore = null

        coordinator?.restoreState(data)
    }

    private fun startCoordinator() {
        coordinator = WritingCoordinator(
            documentModel = documentModel,
            columnModel = documentModel.main,
            recognizer = recognizer,
            inkCanvas = inkCanvas,
            scope = lifecycleScope,
            onStatusUpdate = { status -> Log.i(TAG, "Recognizer status: $status") }
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
        transcriptCoordinator?.stop()
        transcriptCoordinator?.reset()
        documentModel.main.activeStrokes.clear()
        documentModel.main.diagramAreas.clear()
        documentModel.cue.activeStrokes.clear()
        documentModel.cue.diagramAreas.clear()
        documentModel.transcript.activeStrokes.clear()
        documentModel.transcript.diagramAreas.clear()
        documentModel.transcript.textBlocks.clear()
        documentModel.restoreAudioRecordings(emptyList())
        inkCanvas.clear()
        cueInkCanvas.clear()

        currentDocumentName = name
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_CURRENT_DOC, name).apply()

        // Cancel any in-flight load, then load on IO thread
        documentLoadJob?.cancel()
        documentLoadJob = lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                loadDocument(name)
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

                documentModel.transcript.activeStrokes.addAll(data.transcript.strokes)
                documentModel.transcript.diagramAreas.addAll(data.transcript.diagramAreas)
                documentModel.transcript.textBlocks.addAll(data.transcript.textBlocks)
                documentModel.restoreAudioRecordings(data.audioRecordings)
                columnLayoutLogic.onRecordingsChanged()

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

    /** Confirm before interrupting an active recording. */
    private fun confirmIfRecording(action: () -> Unit) {
        if (!lectureMode) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Recording in progress")
            .setMessage("Stop the current recording?")
            .setPositiveButton("Stop") { _, _ ->
                stopLectureCapture()
                action()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newDocument() {
        confirmIfRecording { newDocumentImpl() }
    }

    private fun newDocumentImpl() {
        snapshotAndSaveBlocking()

        coordinator?.stop()
        coordinator?.reset()
        cueCoordinator?.stop()
        cueCoordinator?.reset()
        transcriptCoordinator?.stop()
        transcriptCoordinator?.reset()
        documentModel.main.activeStrokes.clear()
        documentModel.main.diagramAreas.clear()
        documentModel.cue.activeStrokes.clear()
        documentModel.cue.diagramAreas.clear()
        documentModel.transcript.activeStrokes.clear()
        documentModel.transcript.diagramAreas.clear()
        documentModel.transcript.textBlocks.clear()
        documentModel.restoreAudioRecordings(emptyList())
        columnLayoutLogic.onRecordingsChanged()
        inkCanvas.clear()
        cueInkCanvas.clear()

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

    // --- Audio Playback ---

    private fun handleTextBlockTap(block: com.writer.model.TextBlock, wordStartMs: Long? = null) {
        if (block.audioFile.isEmpty()) return
        val seekMs = wordStartMs ?: block.audioStartMs
        playbackController.onWordTapped(block.audioFile, seekMs, block.id)
    }

    private fun playAudio(audioFileName: String, seekMs: Long) {
        val coord = activeCoordinator ?: return
        val player = coord.audioPlayer
        val audioFile = ensureAudioFile(audioFileName)
        if (audioFile == null) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show()
            return
        }

        player.onPositionChanged = { posMs ->
            playbackController.onProgressUpdate(posMs, player.durationMs)
            playbackOverlay.updateProgress(posMs, player.durationMs)

            // Update which text block is visually highlighted
            val allBlocks = documentModel.main.textBlocks.filter { it.audioFile.isNotEmpty() }
            val activeBlock = allBlocks.find { posMs >= it.audioStartMs && posMs < it.audioEndMs }
            if (activeBlock != null && activeBlock.id != inkCanvas.playingTextBlockId) {
                inkCanvas.playingTextBlockId = activeBlock.id
            }
        }

        player.onCompleted = {
            playbackController.onPlaybackCompleted()
            inkCanvas.playingTextBlockId = null
            playbackOverlay.hide()
        }

        inkCanvas.playingTextBlockId = playbackController.playingBlockId
        playbackOverlay.setPaused(false)
        playbackOverlay.updateProgress(0, 0)
        playbackOverlay.show()

        player.play(audioFile, seekMs)
    }

    private fun stopAudio() {
        activeCoordinator?.audioPlayer?.stop()
        inkCanvas.playingTextBlockId = null
        playbackOverlay.hide()
    }

    private fun onPlaybackSeek(seekMs: Long) {
        activeCoordinator?.audioPlayer?.seekTo(seekMs)
        inkCanvas.playingTextBlockId = playbackController.playingBlockId
    }

    private fun onPlaybackPause() {
        activeCoordinator?.audioPlayer?.pause()
        playbackOverlay.setPaused(true)
    }

    private fun onPlaybackResume() {
        activeCoordinator?.audioPlayer?.resume()
        playbackOverlay.setPaused(false)
    }

    /** Extract audio file from the document bundle to a temp file for MediaPlayer. */
    private fun ensureAudioFile(audioFileName: String): java.io.File? {
        val cacheFile = java.io.File(cacheDir, "audio_playback/$audioFileName")
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        // Load from document bundle
        val bundle = DocumentStorage.loadBundle(this, currentDocumentName)
        if (bundle == null) {
            android.util.Log.w("WritingActivity", "ensureAudioFile: bundle null for '$currentDocumentName'")
            return null
        }
        val audioBytes = bundle.audioFiles[audioFileName]
        if (audioBytes == null) {
            android.util.Log.w("WritingActivity", "ensureAudioFile: '$audioFileName' not in bundle. Available: ${bundle.audioFiles.keys}")
            return null
        }

        cacheFile.parentFile?.mkdirs()
        cacheFile.writeBytes(audioBytes)
        return cacheFile
    }

    /** Advance the recording placeholder past all current content. */
    private fun updateRecordingPlaceholder() {
        if (!lectureMode) return
        val segmenter = com.writer.recognition.LineSegmenter()
        val highestStroke = if (documentModel.main.activeStrokes.isNotEmpty())
            documentModel.main.activeStrokes.maxOf { segmenter.getStrokeLineIndex(it) } else -1
        val highestBlock = if (documentModel.main.textBlocks.isNotEmpty())
            documentModel.main.textBlocks.maxOf { it.endLineIndex } else -1
        inkCanvas.recordingPlaceholderLine = maxOf(highestStroke, highestBlock) + 1
    }

    // --- Voice Memo ---

    private fun toggleVoiceMemo() {
        android.util.Log.i("WritingActivity", "toggleVoiceMemo: lectureMode=$lectureMode audioTranscriber=${audioTranscriber != null} isListening=${audioTranscriber?.isListening}")
        // If in lecture mode, stop it
        if (lectureMode) {
            stopLectureCapture()
            return
        }

        val transcriber = audioTranscriber
        if (transcriber != null && transcriber.isListening) {
            stopLectureCapture()
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

    private var memoAutoStopRunnable: Runnable? = null
    private var autoStopOnSilence = false

    /**
     * Quick voice memo — starts lecture capture with auto-stop on silence.
     * Same pipeline: Sherpa + audio recording + partial display + audio save.
     */
    private fun startVoiceMemo() {
        autoStopOnSilence = true
        startLectureCapture()
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

        // Ensure model is ready before entering lecture mode.
        // If not ready, show download progress and defer until the model loads.
        if (sherpaModelManager.getRecognizer() == null) {
            val state = sherpaModelManager.state
            if (state == com.writer.recognition.SherpaModelManager.State.ERROR) {
                sherpaModelManager.release()
            }
            Toast.makeText(this, "Preparing speech engine\u2026", Toast.LENGTH_SHORT).show()
            sherpaModelManager.onStatusUpdate = { status ->
                runOnUiThread {
                    Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
                }
            }
            sherpaModelManager.onReady = {
                runOnUiThread {
                    sherpaModelManager.onReady = null
                    sherpaModelManager.onStatusUpdate = null
                    startLectureCapture() // retry the full flow
                }
            }
            if (sherpaModelManager.state != com.writer.recognition.SherpaModelManager.State.LOADING) {
                sherpaModelManager.preload(this)
            }
            return
        }

        lectureMode = true
        lectureRecordingStartMs = System.currentTimeMillis()
        audioQualityMonitor.reset()
        recordingBackCallback.isEnabled = true
        audioQualityWarned = false
        micButton.setImageResource(R.drawable.ic_mic_active)
        inkCanvas.lectureRecording = true
        recordingOverlay.show(autoStopOnSilence)

        // Show placeholder at the line where TextBlocks will be inserted
        updateRecordingPlaceholder()

        val serviceIntent = android.content.Intent(this, com.writer.audio.AudioRecordingService::class.java)
        startForegroundService(serviceIntent)
        startSherpaLectureRecognition()
    }

    private fun startSherpaLectureRecognition() {
        // Streaming model is guaranteed ready — startLectureCapture checks before entering lectureMode
        val recognizer = sherpaModelManager.getRecognizer()
            ?: run {
                Log.e("WritingActivity", "Sherpa model not ready in startSherpaLectureRecognition — should not happen")
                return
            }

        // Start loading offline model for two-pass (non-blocking, best-effort)
        val offlineState = offlineModelManager.state
        if (offlineState == com.writer.recognition.OfflineModelManager.State.UNLOADED ||
            offlineState == com.writer.recognition.OfflineModelManager.State.ERROR) {
            if (offlineState == com.writer.recognition.OfflineModelManager.State.ERROR) {
                offlineModelManager.release()
            }
            offlineModelManager.preload(this)
        }

        val transcriber = com.writer.recognition.SherpaTranscriber(
            this, sherpaModelManager, offlineModelManager = offlineModelManager
        )
        audioTranscriber = transcriber

        transcriber.onStatusUpdate = { status ->
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
        }

        transcriber.onRmsChanged = { rmsdB ->
            audioQualityMonitor.onRmsChanged(rmsdB)
            if (audioQualityMonitor.shouldWarn && !audioQualityWarned) {
                audioQualityWarned = true
                Toast.makeText(this, audioQualityMonitor.qualityMessage, Toast.LENGTH_LONG).show()
            }
        }

        // Capture recording name at wire time — must not read lectureRecordingStartMs lazily
        // inside the callback, as it may change between recordings in the same session
        val recordingName = "rec-${lectureRecordingStartMs}.ogg"

        transcriber.onPartialResult = { text ->
            if (text.isNotBlank() && lectureMode) {
                inkCanvas.partialTranscriptionText = text
            }
        }

        transcriber.onFinalResultWithWords = { text, words ->
            if (text.isNotBlank() && lectureMode) {
                inkCanvas.partialTranscriptionText = ""
                val startMs = words.firstOrNull()?.startMs ?: 0L
                val endMs = words.lastOrNull()?.endMs ?: 0L
                // All audio-derived TextBlocks live in the transcript column (Phase 3).
                // The "active column at stop" rule is gone — recording always routes here.
                ensureTranscriptCoordinator().insertTextBlock(
                    text, audioFile = recordingName, startMs = startMs, endMs = endMs, words = words
                )
                saveNow()
                updateCueIndicatorStrip()
                updateRecordingPlaceholder()
                android.util.Log.i("WritingActivity", "Sherpa transcribed: $text (${words.size} words) audio=$recordingName")

                // Auto-stop memo after silence following speech
                if (autoStopOnSilence) {
                    memoAutoStopRunnable?.let { handler.removeCallbacks(it) }
                    val stopRunnable = Runnable { stopLectureCapture(); autoStopOnSilence = false }
                    memoAutoStopRunnable = stopRunnable
                    handler.postDelayed(stopRunnable, MEMO_AUTO_STOP_DELAY_MS)
                }
            }
        }

        transcriber.onFinalResult = { _ -> }

        transcriber.onError = { errorCode ->
            android.util.Log.w("WritingActivity", "Sherpa error: $errorCode")
        }

        transcriber.start(documentModel.language)
    }

    private fun stopLectureCapture() {
        if (!lectureMode) return

        // Clear auto-stop timer if active (memo mode)
        memoAutoStopRunnable?.let { handler.removeCallbacks(it) }
        memoAutoStopRunnable = null
        autoStopOnSilence = false

        micButton.setImageResource(R.drawable.ic_mic)
        inkCanvas.lectureRecording = false
        inkCanvas.recordingPlaceholderLine = -1
        recordingOverlay.hide()
        recordingBackCallback.isEnabled = false

        inkCanvas.partialTranscriptionText = ""

        val transcriber = audioTranscriber
        transcriber?.stop()

        val audioBytes = transcriber?.readRecordedBytes()
        lectureMode = false
        audioTranscriber?.close()
        audioTranscriber = null

        val serviceIntent = android.content.Intent(this, com.writer.audio.AudioRecordingService::class.java)
        stopService(serviceIntent)

        if (audioBytes != null) {
            val recordingName = "rec-${lectureRecordingStartMs}.ogg"
            val durationMs = System.currentTimeMillis() - lectureRecordingStartMs
            documentModel.addAudioRecording(
                com.writer.model.AudioRecording(recordingName, lectureRecordingStartMs, durationMs)
            )
            columnLayoutLogic.onRecordingsChanged()
            val snapshot = createSaveSnapshot()
            if (snapshot != null) {
                DocumentStorage.save(this, snapshot.name, snapshot.state, mapOf(recordingName to audioBytes))
            }
            android.util.Log.i("WritingActivity", "Saved ${audioBytes.size} bytes audio as $recordingName")
        } else {
            snapshotAndSaveBlocking()
        }
        flushPendingCheckpoint()
        autoSaver.exportIfDirty { createExportSnapshot() }
        Toast.makeText(this, "Lecture capture stopped", Toast.LENGTH_SHORT).show()
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
            confirmIfRecording { showOpenDialog() }
        }
        popupView.findViewById<android.view.View>(R.id.menuRename).setOnClickListener {
            launchingSaveAs = true
            popup.dismiss()
            showRenameDialog()
        }
        popupView.findViewById<android.view.View>(R.id.menuHistory).setOnClickListener {
            popup.dismiss()
            enterHistoryMode()
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

    private fun enterHistoryMode() {
        val checkpoints = versionHistory.listCheckpoints(currentDocumentName)
        if (checkpoints.isEmpty()) {
            Toast.makeText(this, "No version history yet", Toast.LENGTH_SHORT).show()
            inkCanvas.resumeRawDrawing()
            return
        }

        // Flush any in-flight save before loading the doc for preview
        snapshotAndSaveBlocking()

        isHistoryMode = true
        inkCanvas.pauseRawDrawing()
        gutterOverlay.visibility = View.GONE

        // Cache current state for cancel
        val mainState = coordinator?.getState()
        val cueState = cueCoordinator?.getColumnState() ?: ColumnData(
            strokes = documentModel.cue.activeStrokes.toList(),
            diagramAreas = documentModel.cue.diagramAreas.toList()
        )
        preHistoryData = mainState?.copy(cue = cueState)

        // Load document once — reused for all preview forks
        historyDoc = automergeStorage.load(currentDocumentName)

        historyOverlay.bind(checkpoints)
        historyOverlay.visibility = View.VISIBLE
    }

    private fun exitHistoryMode(restore: Boolean, checkpoint: VersionHistory.Checkpoint? = null) {
        historyOverlay.visibility = View.GONE
        historyDoc?.free()
        historyDoc = null

        if (restore && checkpoint != null) {
            restoreFromCheckpoint(checkpoint)
        } else {
            // Cancel: reload cached state
            preHistoryData?.let { data ->
                documentModel.main.activeStrokes.clear()
                documentModel.main.activeStrokes.addAll(data.main.strokes)
                inkCanvas.diagramAreas = data.main.diagramAreas
                inkCanvas.textBlocks = data.main.textBlocks
                inkCanvas.loadStrokes(data.main.strokes)
            }
        }

        preHistoryData = null
        isHistoryMode = false
        gutterOverlay.visibility = View.VISIBLE
        inkCanvas.resumeRawDrawing()
    }

    private fun previewCheckpoint(checkpoint: VersionHistory.Checkpoint) {
        val doc = historyDoc ?: return
        try {
            val forked = PerfCounters.time(PerfMetric.PREVIEW_FORK) {
                versionHistory.restoreCheckpoint(doc, checkpoint)
            }
            val ls = com.writer.view.ScreenMetrics.lineSpacing
            val viewStartLine = (inkCanvas.scrollOffsetY / ls).toInt()
            val viewEndLine = viewStartLine + (inkCanvas.height / ls).toInt() + 1
            val data = PerfCounters.time(PerfMetric.PREVIEW_READ) {
                AutomergeAdapter.fromAutomerge(forked, viewStartLine, viewEndLine)
            }
            forked.free()
            PerfCounters.time(PerfMetric.PREVIEW_DRAW) {
                inkCanvas.diagramAreas = data.main.diagramAreas
                inkCanvas.textBlocks = data.main.textBlocks
                inkCanvas.loadStrokes(data.main.strokes)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to preview checkpoint: ${e.message}")
        }
    }

    private fun restoreFromCheckpoint(checkpoint: VersionHistory.Checkpoint) {
        val doc = automergeStorage.load(currentDocumentName)
        if (doc == null) {
            Toast.makeText(this, "Could not load document", Toast.LENGTH_SHORT).show()
            return
        }
        val restored = versionHistory.restoreCheckpoint(doc, checkpoint)
        val data = AutomergeAdapter.fromAutomerge(restored)

        // Save restored state as current
        automergeStorage.save(currentDocumentName, restored)
        doc.free()
        restored.free()

        // Reload the UI with restored data
        coordinator?.stop()
        coordinator?.reset()
        cueCoordinator?.stop()
        cueCoordinator?.reset()
        transcriptCoordinator?.stop()
        transcriptCoordinator?.reset()
        documentModel.main.activeStrokes.clear()
        documentModel.main.diagramAreas.clear()
        documentModel.cue.activeStrokes.clear()
        documentModel.cue.diagramAreas.clear()
        documentModel.transcript.activeStrokes.clear()
        documentModel.transcript.diagramAreas.clear()
        documentModel.transcript.textBlocks.clear()
        inkCanvas.clear()
        cueInkCanvas.clear()

        documentModel.main.activeStrokes.addAll(data.main.strokes)
        documentModel.main.diagramAreas.addAll(data.main.diagramAreas)
        inkCanvas.diagramAreas = data.main.diagramAreas
        inkCanvas.loadStrokes(data.main.strokes)
        inkCanvas.drawToSurface()

        documentModel.cue.activeStrokes.addAll(data.cue.strokes)
        documentModel.cue.diagramAreas.addAll(data.cue.diagramAreas)

        documentModel.transcript.activeStrokes.addAll(data.transcript.strokes)
        documentModel.transcript.diagramAreas.addAll(data.transcript.diagramAreas)
        documentModel.transcript.textBlocks.addAll(data.transcript.textBlocks)
        documentModel.restoreAudioRecordings(data.audioRecordings)
        columnLayoutLogic.onRecordingsChanged()

        coordinator?.start()
        coordinator?.restoreState(data)
        updateCueIndicatorStrip()

        Toast.makeText(this, "Restored to: ${checkpoint.label}", Toast.LENGTH_SHORT).show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (nowLandscape == isLandscape) return

        // Dismiss cue peek popup
        cuePeekPopup?.dismiss()

        val wasDualColumn = columnLayoutLogic.isDualColumn

        // Unfold from any non-main fold view BEFORE updating orientation —
        // the listener dispatches the correct teardown while canFoldUnfold is
        // still true. Setting activeColumn here (rather than relying on
        // onOrientationChanged's reset) gives us the from→to transition.
        if (nowLandscape && (isFoldedToCues || isFoldedToTranscript)) {
            columnLayoutLogic.activeColumn = ColumnLayoutLogic.ActiveColumn.MAIN
        }

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

        // After layout settles, proceed with cue column setup for new orientation
        inkCanvas.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    inkCanvas.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    if (nowDualColumn) {
                        showCueColumn()
                    } else {
                        hideCueColumn()
                        cueIndicatorStrip.visibility = View.VISIBLE
                        updateCueIndicatorStrip()
                    }
                    // Orientation change may switch transcriptDisplayMode
                    // (SIDE_BY_SIDE ↔ DRAWER) — refresh the transcript's presentation.
                    refreshTranscriptPresentation()
                }
            }
        )

        orientationManager.updateButtonVisibility()
    }

    /** Re-evaluate transcript column presentation from the canonical source:
     *  [ColumnLayoutLogic.columnWidths]. A nonzero transcriptWidthPx means the
     *  column should be in-flow in the LinearLayout right now (true for
     *  SIDE_BY_SIDE, and for DRAWER with the drawer open). Called on orientation
     *  change, drawer toggle, and after the initial document load. */
    private fun refreshTranscriptPresentation() {
        val hasInlineSlot = columnLayoutLogic.columnWidths().transcriptWidthPx > 0
        if (hasInlineSlot) showTranscriptInline() else hideTranscriptInline()
        updateTranscriptButtonState()
    }

    /** Kept around so ColumnLayoutLogic.onTranscriptVisibilityChanged has a
     *  stable entry point — the actual decision logic now lives in
     *  [refreshTranscriptPresentation]. */
    private fun onTranscriptVisibilityChanged(visible: Boolean) {
        refreshTranscriptPresentation()
    }

    private fun showTranscriptInline() {
        transcriptInkCanvas.visibility = View.VISIBLE
        transcriptColumnDivider.visibility = View.VISIBLE
        ensureTranscriptCoordinator()
        // Load persisted transcript-column strokes / text blocks into the canvas.
        transcriptInkCanvas.loadStrokes(documentModel.transcript.activeStrokes.toList())
        transcriptInkCanvas.diagramAreas = documentModel.transcript.diagramAreas.toList()
        transcriptInkCanvas.textBlocks = documentModel.transcript.textBlocks.toList()
        applyColumnWidths()
        transcriptInkCanvas.post {
            transcriptInkCanvas.drawToSurface()
        }
        Log.i(TAG, "Transcript column shown (inline)")
    }

    private fun hideTranscriptInline() {
        transcriptInkCanvas.visibility = View.GONE
        transcriptColumnDivider.visibility = View.GONE
        applyColumnWidths()
        Log.i(TAG, "Transcript column hidden")
    }

    /** Show the transcript toggle button only in DRAWER mode when the column
     *  is visible at all. Alpha reflects whether the drawer is currently open. */
    private fun updateTranscriptButtonState() {
        val inDrawerMode = columnLayoutLogic.transcriptDisplayMode ==
            ColumnLayoutLogic.TranscriptDisplayMode.DRAWER
        val showButton = columnLayoutLogic.transcriptVisible && inDrawerMode
        transcriptButton.visibility = if (showButton) View.VISIBLE else View.GONE
        transcriptButton.imageAlpha =
            if (columnLayoutLogic.isTranscriptDrawerOpen) 255 else 128
    }

    /** Lazily build the transcript coordinator. Safe to call before the transcript
     *  canvas becomes user-visible (Phase 3d) — it just holds the column's state
     *  and receives TextBlock inserts. Once started, it must survive for the
     *  activity lifetime; we don't tear it down on drawer close. */
    private fun ensureTranscriptCoordinator(): WritingCoordinator {
        val existing = transcriptCoordinator
        if (existing != null) return existing
        val created = WritingCoordinator(
            documentModel = documentModel,
            columnModel = documentModel.transcript,
            recognizer = recognizer,
            inkCanvas = transcriptInkCanvas,
            scope = lifecycleScope,
            onStatusUpdate = {},
        )
        transcriptCoordinator = created
        created.start()
        return created
    }

    private fun ensureCueCoordinator() {
        if (cueCoordinator == null) {
            cueCoordinator = WritingCoordinator(
                documentModel = documentModel,
                columnModel = documentModel.cue,
                recognizer = recognizer,
                inkCanvas = cueInkCanvas,
                scope = lifecycleScope,
                onStatusUpdate = {}
            )
        }
    }

    private fun showCueColumn() {
        columnDivider.visibility = View.VISIBLE
        cueInkCanvas.visibility = View.VISIBLE
        // Toggle visibility based on layout logic
        viewToggleButton.visibility = if (columnLayoutLogic.showToggleButton) View.VISIBLE else View.GONE

        // Apply column widths (fixed pixel widths on large screens, weights on small)
        applyColumnWidths()

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
        val mainParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
        val cueParams = cueInkCanvas.layoutParams as LinearLayout.LayoutParams
        val transcriptParams = transcriptInkCanvas.layoutParams as LinearLayout.LayoutParams
        if (widths.mainWidthPx > 0) {
            // Large screen: fixed pixel widths
            mainParams.width = widths.mainWidthPx
            mainParams.weight = 0f
            cueParams.width = widths.cueWidthPx
            cueParams.weight = 0f
            transcriptParams.width = widths.transcriptWidthPx
            transcriptParams.weight = 0f
        } else {
            // Small screen: equal weights (50/50)
            mainParams.width = 0
            mainParams.weight = 1f
            cueParams.width = 0
            cueParams.weight = 1f
            transcriptParams.width = 0
            transcriptParams.weight = 0f
        }
        inkCanvas.layoutParams = mainParams
        cueInkCanvas.layoutParams = cueParams
        transcriptInkCanvas.layoutParams = transcriptParams
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
        cueInkCanvas.visibility = View.GONE
        viewToggleButton.visibility = if (columnLayoutLogic.showToggleButton) View.VISIBLE else View.GONE
        // Reset main column to fill width
        val mainParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
        mainParams.width = 0
        mainParams.weight = 1f
        inkCanvas.layoutParams = mainParams
        updateViewToggleIcon()

        // Restore main canvas per-view Onyx SDK
        inkCanvas.deferOnyxInit = false
        inkCanvas.reinitializeRawDrawing()

        Log.i(TAG, "Cue column hidden (portrait)")
    }

    /** Portrait: fold from notes to cues view — show cue column full-width, hide main.
     *  Called from the fold-state-change dispatcher; the state transition is owned by
     *  [ColumnLayoutLogic.activeColumn] so this method only handles view work. */
    private fun foldToCues() {
        // Hide main column, show cue column
        inkCanvas.visibility = View.GONE
        cueIndicatorStrip.visibility = View.GONE
        cueInkCanvas.visibility = View.VISIBLE
        contextRail.visibility = View.VISIBLE
        transcriptIndicatorStrip.visibility =
            if (columnLayoutLogic.transcriptVisible) View.VISIBLE else View.GONE
        updateViewToggleIcon()

        // Populate context rail with main content indicators
        populateContextRail()

        // Set cue column weight to fill
        val params = cueInkCanvas.layoutParams as LinearLayout.LayoutParams
        params.weight = 1f
        cueInkCanvas.layoutParams = params

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

    /** Portrait: unfold from cues back to notes view. View-only; state transition
     *  is owned by [ColumnLayoutLogic.activeColumn]. */
    private fun unfoldToNotes() {
        // Show main column, hide cue column
        inkCanvas.visibility = View.VISIBLE
        cueIndicatorStrip.visibility = View.VISIBLE
        cueInkCanvas.visibility = View.GONE
        contextRail.visibility = View.GONE
        transcriptIndicatorStrip.visibility =
            if (columnLayoutLogic.transcriptVisible) View.VISIBLE else View.GONE
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

    /** Portrait: transition from cue view to transcript view (cycleFold step 2 of 3). */
    private fun foldCueToTranscript() {
        // Hide cue, show transcript
        cueInkCanvas.visibility = View.GONE
        contextRail.visibility = View.GONE
        transcriptInkCanvas.visibility = View.VISIBLE
        cueIndicatorStrip.visibility = View.VISIBLE  // cue indicators peekable from transcript
        contextRail.visibility = View.VISIBLE         // main indicators peekable from transcript
        transcriptIndicatorStrip.visibility = View.GONE
        updateViewToggleIcon()

        // Set transcript canvas to fill width
        val params = transcriptInkCanvas.layoutParams as LinearLayout.LayoutParams
        params.weight = 1f
        transcriptInkCanvas.layoutParams = params

        // Transfer Onyx SDK from cue to transcript
        cueCoordinator?.stop()
        cueInkCanvas.closeRawDrawing()
        cueInkCanvas.deferOnyxInit = true

        transcriptInkCanvas.post {
            transcriptInkCanvas.loadStrokes(documentModel.transcript.activeStrokes.toList())
            transcriptInkCanvas.diagramAreas = documentModel.transcript.diagramAreas.toList()
            transcriptInkCanvas.textBlocks = documentModel.transcript.textBlocks.toList()
            transcriptInkCanvas.scrollOffsetY = cueInkCanvas.scrollOffsetY
            transcriptInkCanvas.drawToSurface()

            ensureTranscriptCoordinator()
            transcriptInkCanvas.deferOnyxInit = false
            transcriptInkCanvas.reinitializeRawDrawing()

            Log.i(TAG, "Folded cue → transcript (portrait)")
        }
    }

    /** Portrait: transition from transcript view back to notes view (cycleFold wraps to MAIN). */
    private fun unfoldTranscriptToNotes() {
        // Hide transcript, show main
        transcriptInkCanvas.visibility = View.GONE
        contextRail.visibility = View.GONE
        inkCanvas.visibility = View.VISIBLE
        cueIndicatorStrip.visibility = View.VISIBLE
        transcriptIndicatorStrip.visibility =
            if (columnLayoutLogic.transcriptVisible) View.VISIBLE else View.GONE
        updateViewToggleIcon()

        // Stop transcript coordinator, transfer Onyx SDK back to main
        transcriptCoordinator?.stop()
        transcriptInkCanvas.closeRawDrawing()
        transcriptInkCanvas.deferOnyxInit = true

        inkCanvas.scrollOffsetY = transcriptInkCanvas.scrollOffsetY
        inkCanvas.reinitializeRawDrawing()
        inkCanvas.drawToSurface()

        updateCueIndicatorStrip()

        spaceInsertButton.visibility = View.VISIBLE

        Log.i(TAG, "Unfolded transcript → notes (portrait)")
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
                // Small screen portrait: cycle through main ↔ cue ↔ (transcript).
                // During tutorial basics, toggle is disabled.
                if (tutorialManager.isActive && !tutorialManager.isCornellPhase()) return
                val before = columnLayoutLogic.activeColumn
                columnLayoutLogic.cycleFold() // listener dispatches the view transition
                val after = columnLayoutLogic.activeColumn
                tutorialManager.onStepAction(foldActionForTransition(before, after))
            }
        }
    }

    private fun foldActionForTransition(
        from: ColumnLayoutLogic.ActiveColumn,
        to: ColumnLayoutLogic.ActiveColumn,
    ): String = when (to) {
        ColumnLayoutLogic.ActiveColumn.CUE -> "folded_to_cues"
        ColumnLayoutLogic.ActiveColumn.TRANSCRIPT -> "folded_to_transcript"
        ColumnLayoutLogic.ActiveColumn.MAIN ->
            if (from == ColumnLayoutLogic.ActiveColumn.TRANSCRIPT) "unfolded_from_transcript"
            else "unfolded_to_notes"
    }

    /** Dispatch the FOLD-mode view transition based on the [from]→[to] column change. */
    private fun dispatchFoldTransition(
        from: ColumnLayoutLogic.ActiveColumn,
        to: ColumnLayoutLogic.ActiveColumn,
    ) {
        when (from to to) {
            ColumnLayoutLogic.ActiveColumn.MAIN to ColumnLayoutLogic.ActiveColumn.CUE -> foldToCues()
            ColumnLayoutLogic.ActiveColumn.CUE to ColumnLayoutLogic.ActiveColumn.MAIN -> unfoldToNotes()
            ColumnLayoutLogic.ActiveColumn.CUE to ColumnLayoutLogic.ActiveColumn.TRANSCRIPT -> foldCueToTranscript()
            ColumnLayoutLogic.ActiveColumn.TRANSCRIPT to ColumnLayoutLogic.ActiveColumn.MAIN -> unfoldTranscriptToNotes()
            else -> {
                // Includes MAIN→TRANSCRIPT and TRANSCRIPT→CUE, which cycleFold never produces,
                // and MAIN→MAIN identity.
                Log.w(TAG, "Unexpected fold transition: $from → $to")
            }
        }
    }

    /** Update the gutter toggle icon to show what tapping will switch TO. */
    private fun updateViewToggleIcon() {
        val (iconRes, description) = when (columnLayoutLogic.activeColumn) {
            ColumnLayoutLogic.ActiveColumn.MAIN ->
                R.drawable.ic_cue to "Switch to cues"
            ColumnLayoutLogic.ActiveColumn.CUE ->
                if (columnLayoutLogic.transcriptVisible)
                    R.drawable.ic_transcript to "Switch to transcript"
                else
                    R.drawable.ic_notes to "Switch to notes"
            ColumnLayoutLogic.ActiveColumn.TRANSCRIPT ->
                R.drawable.ic_notes to "Switch to notes"
        }
        viewToggleButton.setImageResource(iconRes)
        viewToggleButton.contentDescription = description
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
        // Pre-load Sherpa model so recording starts instantly
        sherpaModelManager.preload(this)
        if (!tutorialManager.isActive) {
            inkCanvas.reinitializeRawDrawing()
        }
        orientationManager.start()
    }

    override fun onStop() {
        super.onStop()
        // Release Sherpa model when backgrounded (unless recording)
        if (!lectureMode) {
            sherpaModelManager.release()
            offlineModelManager.release()
        }
        orientationManager.stop()
        // Flush debounced save before blocking save
        pendingSaveRunnable?.let {
            checkpointHandler.removeCallbacks(it)
            it.run()
            pendingSaveRunnable = null
        }
        snapshotAndSaveBlocking()
        // Flush any pending idle checkpoint before closing
        flushPendingCheckpoint()
        // Persist local-only state (scroll, lineIndex) to .mok on close
        createSaveSnapshot()?.let { snapshot ->
            DocumentStorage.save(this, snapshot.name, snapshot.state)
        }
        autoSaver.exportIfDirty { createExportSnapshot() }
    }

    // Debounce rapid pen-ups (200ms) to coalesce saves without perceptible delay
    private var pendingSaveRunnable: Runnable? = null

    /**
     * Save after a short debounce. Coalesces rapid pen-ups (multiple strokes
     * in quick succession) into a single save. 200ms is fast enough to feel
     * instant but avoids coroutine churn at 5+ strokes/second.
     */
    private fun saveNow() {
        if (isHistoryMode) return
        pendingSaveRunnable?.let { checkpointHandler.removeCallbacks(it) }
        val runnable = Runnable {
            coordinator?.recognizeAllLines()
            cueCoordinator?.recognizeAllLines()
            val snapshot = createSaveSnapshot() ?: return@Runnable
            autoSaver.saveAsync(snapshot)
        }
        pendingSaveRunnable = runnable
        checkpointHandler.postDelayed(runnable, 200L)

        // Schedule checkpoint after 10s idle (resets on each save)
        checkpointPending = true
        checkpointHandler.removeCallbacks(checkpointRunnable)
        checkpointHandler.postDelayed(checkpointRunnable, 10_000L)
    }

    private fun flushPendingCheckpoint() {
        checkpointHandler.removeCallbacks(checkpointRunnable)
        if (checkpointPending) {
            createIdleCheckpoint()
            checkpointPending = false
        }
    }

    private fun createIdleCheckpoint() {
        automergeSink.withDocument(currentDocumentName) { doc ->
            val existing = versionHistory.listCheckpoints(currentDocumentName)
            val currentHeads = doc.heads
            if (existing.isNotEmpty() && existing.last().heads.contentEquals(currentHeads)) return@withDocument
            val label = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date())
            versionHistory.createCheckpoint(currentDocumentName, doc, label)
        }
    }

    /**
     * Load document data, preferring .automerge over .mok (ZIP).
     * If only .mok exists, migrates to .automerge on first load.
     */
    private fun loadDocument(name: String): DocumentData? {
        // Try .automerge first
        val doc = automergeStorage.load(name)
        if (doc != null) {
            checkpointOnLoad(name, doc)
            val data = AutomergeAdapter.fromAutomerge(doc)
            doc.free()
            // Merge local-only state from .mok if it exists
            val mokData = DocumentStorage.load(this, name)
            return if (mokData != null) {
                data.copy(
                    scrollOffsetY = mokData.scrollOffsetY,
                    highestLineIndex = mokData.highestLineIndex,
                    currentLineIndex = mokData.currentLineIndex,
                    userRenamed = mokData.userRenamed,
                )
            } else data
        }
        // Fall back to .mok with auto-migration
        val mokData = DocumentStorage.load(this, name)
        if (mokData != null) {
            val mokFile = java.io.File(filesDir, "documents/$name.mok")
            DocumentStorage.migrateToAutomerge(mokFile, automergeStorage, name)
            // Checkpoint the freshly migrated document
            val migratedDoc = automergeStorage.load(name)
            if (migratedDoc != null) {
                checkpointOnLoad(name, migratedDoc)
                migratedDoc.free()
            }
        }
        return mokData
    }

    private fun checkpointOnLoad(name: String, doc: org.automerge.Document) {
        val existing = versionHistory.listCheckpoints(name)
        val currentHeads = doc.heads
        // Skip if the latest checkpoint already matches current heads
        if (existing.isNotEmpty() && existing.last().heads.contentEquals(currentHeads)) return
        val label = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date())
        versionHistory.createCheckpoint(name, doc, label)
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
        val transcriptColumnData = transcriptCoordinator?.getColumnState() ?: ColumnData(
            strokes = documentModel.transcript.activeStrokes.toList(),
            diagramAreas = documentModel.transcript.diagramAreas.toList(),
            textBlocks = documentModel.transcript.textBlocks.toList(),
        )
        val state = mainState.copy(cue = cueColumnData, transcript = transcriptColumnData)
        return AutoSaver.Snapshot(
            name = currentDocumentName,
            state = state,
        )
    }

    /** Snapshot with sync folder info for SAF export. */
    private fun createExportSnapshot(): AutoSaver.Snapshot? {
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

        // If on a non-main fold view, unfold first
        if (isFoldedToCues || isFoldedToTranscript) {
            columnLayoutLogic.activeColumn = ColumnLayoutLogic.ActiveColumn.MAIN
        }

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

    /** During lecture recording, back button moves to background instead of closing. */
    private val recordingBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sherpaModelManager.release()
        offlineModelManager.release()
        if (lectureMode) stopLectureCapture()
        recordingBackCallback.isEnabled = false
        unregisterReceiver(bugReportReceiver)
        closeDualCanvasOnyx()
        coordinator?.stop()  // releases audioPlayer, audioTranscriber, lectureMode
        cueCoordinator?.stop()
        recognizer.close()
    }
}
