package com.writer.ui.writing

import android.util.Log
import com.writer.model.ColumnData
import com.writer.model.ColumnModel
import com.writer.model.DiagramNode
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.TextBlock
import com.writer.model.minX
import com.writer.model.minY
import com.writer.model.maxX
import com.writer.model.maxY
import com.writer.view.ScratchOutDetection
import com.writer.view.ScreenMetrics
import com.writer.recognition.TextRecognizer
import com.writer.recognition.LineSegmenter
import com.writer.recognition.StrokeClassifier
import com.writer.model.DocumentData
import com.writer.view.HandwritingCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class WritingCoordinator(
    private val documentModel: DocumentModel,
    private val columnModel: ColumnModel,
    private val recognizer: TextRecognizer,
    private val inkCanvas: HandwritingCanvasView,
    private val scope: CoroutineScope,
    private val onStatusUpdate: (String) -> Unit
) : DiagramManagerHost {
    companion object {
        private const val TAG = "WritingCoordinator"
        private const val PERF_BUDGET_MS = 50L
    }

    private val lineSegmenter = LineSegmenter()
    private val strokeClassifier = StrokeClassifier(lineSegmenter)
    private val paragraphBuilder = ParagraphBuilder(strokeClassifier)
    private val undoManager = UndoManager()
    private val undoCoalescer = UndoCoalescer(undoManager)

    private val gestureHandler = GestureHandler(
        columnModel = columnModel,
        inkCanvas = inkCanvas,
        lineSegmenter = lineSegmenter,
        onLinesChanged = { invalidatedLines ->
            for (line in invalidatedLines) {
                lineTextCache.remove(line)
            }
            displayManager.displayHiddenLines()
        },
        onBeforeMutation = { saveSnapshot(UndoCoalescer.ActionType.GESTURE_CONSUMED) }
    )

    // Eager recognition: cache of recognized text per line index.
    // All shared mutable state below is accessed only from Dispatchers.Main
    // (scope is lifecycleScope; only recognizer.recognizeLine runs on IO).
    private val lineTextCache = mutableMapOf<Int, String>()
    // Track the highest (bottommost) line the user has written on
    private var highestLineIndex = -1
    // Track which line the user is currently writing on
    private var currentLineIndex = -1
    /** Audio player for TextBlock playback — lifecycle tied to document. */
    val audioPlayer = com.writer.audio.AudioPlayer(inkCanvas.context)

    /** Lecture capture state — document-scoped. */
    var audioTranscriber: com.writer.recognition.AudioTranscriber? = null
    var lectureMode = false
    var lectureRecordingStartMs = 0L
    val audioQualityMonitor = com.writer.audio.AudioQualityMonitor()

    // Whether the user has manually renamed this document
    var userRenamed = false
    // Callback to notify activity when heading-based rename should happen
    /** Called after any scroll animation — used by the activity for linked scroll sync. */
    var onLinkedScroll: (() -> Unit)? = null
    /** Called after space insert/remove — used by the activity to sync the other column.
     *  Parameters: anchorLine, lineDelta (positive = inserted, negative = removed). */
    var onSpaceChanged: ((anchorLine: Int, lineDelta: Int) -> Unit)? = null
    var onHeadingDetected: ((String) -> Unit)? = null
    // Callback to notify activity when undo/redo availability changes
    var onUndoRedoStateChanged: (() -> Unit)? = null
    // Callback for tutorial step completion (fires action IDs like "stroke_completed")
    var onTutorialAction: ((String) -> Unit)? = null
    // Diagram lifecycle manager (created in start())
    private lateinit var diagramManager: DiagramManager
    // Display manager (created in start())
    private lateinit var displayManager: DisplayManager
    // Line recognition manager (created in start())
    private lateinit var recognitionManager: LineRecognitionManager
    // Stroke routing logic (created in start())
    private lateinit var strokeRouter: StrokeRouter

    /** Always-on ring buffer for bug report capture. */
    val eventLog = StrokeEventLog()
    /** Index of the most recent raw stroke in the event log.
     *  Safe as a single field because stylus input is single-touch on the main thread. */
    private var lastStrokeIndex = -1

    override fun onDiagramAreasChanged() = displayManager.displayHiddenLines()
    override fun getLineTextCache() = lineTextCache

    fun start() {
        Log.i(TAG, "Coordinator started")

        val canvasAdapter = object : DiagramCanvas {
            override var diagramAreas
                get() = inkCanvas.diagramAreas
                set(v) { inkCanvas.diagramAreas = v }
            override var scrollOffsetY
                get() = inkCanvas.scrollOffsetY
                set(v) { inkCanvas.scrollOffsetY = v }
            override fun loadStrokes(strokes: List<InkStroke>) = inkCanvas.loadStrokes(strokes)
            override fun pauseAndRedraw() {
                inkCanvas.pauseRawDrawing()
                inkCanvas.drawToSurface()
                inkCanvas.resumeRawDrawing()
            }
        }
        diagramManager = DiagramManager(columnModel, lineSegmenter, recognizer, canvasAdapter, this, scope)

        val recognitionHost = object : RecognitionManagerHost {
            override val lineTextCache: MutableMap<Int, String> get() = this@WritingCoordinator.lineTextCache
            override val userRenamed: Boolean get() = this@WritingCoordinator.userRenamed
            override val everHiddenLines: Set<Int> get() = displayManager.getEverHiddenLinesSnapshot()
            override fun onHeadingDetected(heading: String) { this@WritingCoordinator.onHeadingDetected?.invoke(heading) }
            override fun isDiagramLine(lineIndex: Int): Boolean = diagramManager.isDiagramLine(lineIndex)
            override fun onRecognitionComplete(lineIndex: Int) {
                displayManager.displayHiddenLines()
            }
        }
        recognitionManager = LineRecognitionManager(
            columnModel, recognizer, lineSegmenter, strokeClassifier, scope,
            recognitionHost, canvasWidthProvider = { inkCanvas.width.toFloat() }
        )

        val displayHost = object : DisplayManagerHost {
            override val columnModel: ColumnModel get() = this@WritingCoordinator.columnModel
            override val diagramManager: DiagramManager get() = this@WritingCoordinator.diagramManager
            override val lineTextCache: Map<Int, String> get() = this@WritingCoordinator.lineTextCache
            override val highestLineIndex: Int get() = this@WritingCoordinator.highestLineIndex
            override val currentLineIndex: Int get() = this@WritingCoordinator.currentLineIndex
            override fun eagerRecognizeLine(lineIndex: Int) = recognitionManager.eagerRecognizeLine(lineIndex)
            override fun markRecognizing(lineIndex: Int) { recognitionManager.markRecognizing(lineIndex) }
            override suspend fun doRecognizeLine(lineIndex: Int): String? = recognitionManager.doRecognizeLine(lineIndex)
            override fun isRecognizing(lineIndex: Int): Boolean = recognitionManager.isRecognizing(lineIndex)
        }
        displayManager = DisplayManager(inkCanvas, scope, lineSegmenter, paragraphBuilder, displayHost)
        displayManager.onScrollAnimated = { onLinkedScroll?.invoke() }

        val routerHost = object : StrokeRouter.Host {
            override val diagramAreas get() = columnModel.diagramAreas.toList()
            override fun getStrokeLineIndex(stroke: InkStroke) = lineSegmenter.getStrokeLineIndex(stroke)
            override fun isDiagramLine(lineIndex: Int) = diagramManager.isDiagramLine(lineIndex)
            override fun hasTextStrokesOnLine(lineIndex: Int, excluding: InkStroke) =
                diagramManager.hasTextStrokesOnLine(lineIndex, excluding = excluding)
            override fun isEverHidden(lineIndex: Int) = displayManager.isEverHidden(lineIndex)
        }
        strokeRouter = StrokeRouter(routerHost)
        strokeRouter.currentLineIndex = currentLineIndex
        strokeRouter.highestLineIndex = highestLineIndex

        inkCanvas.columnModel = columnModel
        inkCanvas.onPenDown = { onTutorialAction?.invoke("pen_down") }
        inkCanvas.onRawStrokeCapture = { points ->
            lastStrokeIndex = eventLog.recordStroke(points)
        }
        inkCanvas.onStrokeCompleted = { stroke -> onStrokeCompleted(stroke) }
        inkCanvas.onIdleTimeout = { displayManager.onIdle(currentLineIndex) }
        inkCanvas.onManualScroll = {
            displayManager.displayHiddenLines()
            onTutorialAction?.invoke("manual_scroll")
            onLinkedScroll?.invoke()
        }
        inkCanvas.onDiagramShapeDetected = { stroke ->
            val result = diagramManager.onShapeDetected(stroke)
            onTutorialAction?.invoke("diagram_created")
            result
        }
        inkCanvas.onDiagramStrokeOverflow = { strokeId, minY, maxY -> diagramManager.onStrokeOverflow(strokeId, minY, maxY) }
        inkCanvas.onScratchOut = { scratchPoints, left, top, right, bottom ->
            onScratchOut(scratchPoints, left, top, right, bottom)
        }
        inkCanvas.onStrokeReplaced = { oldStrokeId, newStroke ->
            onStrokeReplaced(oldStrokeId, newStroke)
        }
    }

    fun stop() {
        audioPlayer.release()
        audioTranscriber?.close()
        audioTranscriber = null
        lectureMode = false
        displayManager.stop()
        diagramManager.stop()
        inkCanvas.onStrokeCompleted = null
        inkCanvas.onIdleTimeout = null
        inkCanvas.onManualScroll = null
        inkCanvas.onDiagramShapeDetected = null
        inkCanvas.onDiagramStrokeOverflow = null
        inkCanvas.onScratchOut = null
        inkCanvas.onStrokeReplaced = null
        inkCanvas.onPenDown = null
    }

    fun reset() {
        displayManager.reset()
        recognitionManager.reset()
        diagramManager.reset()
        undoManager.clear()
        undoCoalescer.reset()
        lineTextCache.clear()
        highestLineIndex = -1
        currentLineIndex = -1
        strokeRouter.currentLineIndex = -1
        strokeRouter.highestLineIndex = -1
        userRenamed = false
        columnModel.activeStrokes.clear()
        columnModel.diagramAreas.clear()
        columnModel.textBlocks.clear()
        inkCanvas.diagramAreas = emptyList()
        inkCanvas.textBlocks = emptyList()
    }

    private fun onStrokeCompleted(stroke: InkStroke) {
        val elapsedMs = inkCanvas.lastFinishStrokeMs
        if (gestureHandler.tryHandle(stroke)) {
            eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.GESTURE_CONSUMED, elapsedMs = elapsedMs)
            if (elapsedMs > PERF_BUDGET_MS) Log.w(TAG, "Slow stroke: ${elapsedMs}ms (gesture)")
            onTutorialAction?.invoke("gesture_consumed")
            return
        }

        val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)

        // Check if stroke lands on a TextBlock line — treat as replacement text
        val targetBlock = columnModel.textBlocks.find { it.containsLine(lineIdx) }
        if (targetBlock != null) {
            onTextBlockReplacementStroke(stroke, targetBlock, lineIdx, elapsedMs)
            return
        }

        saveSnapshot(UndoCoalescer.ActionType.STROKE_ADDED, lineIdx)
        columnModel.activeStrokes.add(stroke)
        eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.ADDED, "line=$lineIdx", elapsedMs = elapsedMs)
        onTutorialAction?.invoke("stroke_completed")
        if (elapsedMs > PERF_BUDGET_MS) Log.w(TAG, "Slow stroke: ${elapsedMs}ms (added, line=$lineIdx)")

        val result = strokeRouter.classifyStroke(stroke)
        currentLineIndex = strokeRouter.currentLineIndex
        highestLineIndex = strokeRouter.highestLineIndex

        when (result.action) {
            StrokeRouter.Action.DIAGRAM_STROKE -> {
                result.diagramArea?.let { diagramManager.recognizeDiagramArea(it) }
            }
            StrokeRouter.Action.STICKY_EXPAND -> {
                columnModel.diagramAreas.remove(result.expandedFrom)
                columnModel.diagramAreas.add(result.expandedTo!!)
                inkCanvas.diagramAreas = columnModel.diagramAreas.toList()
                for (line in result.expandedTo.startLineIndex..result.expandedTo.endLineIndex) {
                    lineTextCache.remove(line)
                }
                Log.i(TAG, "Sticky zone: expanded diagram ${result.expandedFrom!!.startLineIndex}-${result.expandedFrom.endLineIndex} → ${result.expandedTo.startLineIndex}-${result.expandedTo.endLineIndex}")
                if (stroke.strokeType == StrokeType.FREEHAND) {
                    diagramManager.recognizeDiagramArea(result.expandedTo)
                }
            }
            StrokeRouter.Action.TEXT_STROKE -> {
                lineTextCache.remove(result.lineIndex)
                if (result.recognizePreviousLine) {
                    recognitionManager.eagerRecognizeLine(result.previousLineIndex)
                }
                if (result.reRecognizeLine) {
                    Log.i(TAG, "Stroke on rendered line ${result.lineIndex}, triggering re-recognition")
                    recognitionManager.recognizeRenderedLine(result.lineIndex)
                } else {
                    Log.d(TAG, "Stroke on line ${result.lineIndex} (not in everHiddenLines=${displayManager.getEverHiddenLinesSnapshot()})")
                }
            }
        }
    }

    private fun onStrokeReplaced(oldStrokeId: String, newStroke: InkStroke) {
        saveSnapshot(UndoCoalescer.ActionType.STROKE_REPLACED)  // captures state with raw stroke (state N+1)
        columnModel.activeStrokes.removeAll { it.strokeId == oldStrokeId }
        columnModel.activeStrokes.add(newStroke)
        eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.REPLACED, newStroke.strokeType.name, elapsedMs = inkCanvas.lastFinishStrokeMs)

        // Track shape nodes for magnetic arrow snapping
        if (!newStroke.strokeType.isConnector && newStroke.strokeType != StrokeType.FREEHAND) {
            val bounds = android.graphics.RectF(
                newStroke.minX, newStroke.minY, newStroke.maxX, newStroke.maxY
            )
            columnModel.diagram.nodes[newStroke.strokeId] = DiagramNode(
                strokeId = newStroke.strokeId,
                shapeType = newStroke.strokeType,
                bounds = bounds
            )
        }

        Log.i(TAG, "Stroke replaced: $oldStrokeId → ${newStroke.strokeId} (${newStroke.strokeType})")
        onTutorialAction?.invoke("stroke_replaced")
    }

    private fun onScratchOut(scratchPoints: List<StrokePoint>, left: Float, top: Float, right: Float, bottom: Float) {
        val radius = ScreenMetrics.dp(ScratchOutDetection.COVERAGE_RADIUS_DP)
        val overlapping = StrokeEraser.findOverlappingStrokes(
            scratchPoints, columnModel.activeStrokes, left, top, right, bottom, radius
        )

        // Check if scratch-out hits a TextBlock (even if no strokes overlap)
        if (overlapping.isEmpty()) {
            val tbResult = TextBlockEraser.findAndErase(
                left, top, right, bottom,
                columnModel.textBlocks,
                HandwritingCanvasView.LINE_SPACING,
                HandwritingCanvasView.TOP_MARGIN,
                canvasWidth = inkCanvas.width.toFloat()
            )
            if (tbResult != null) {
                val (block, eraseResult) = tbResult
                saveSnapshot(UndoCoalescer.ActionType.SCRATCH_OUT)
                if (eraseResult.deleteBlock) {
                    columnModel.textBlocks.remove(block)
                    activeGap = null
                } else {
                    val idx = columnModel.textBlocks.indexOf(block)
                    if (idx >= 0) {
                        // Insert placeholder spaces matching the visual width of the removed word + padding
                        val paint = android.text.TextPaint().apply { textSize = com.writer.view.ScreenMetrics.textBody }
                        val removedWidth = paint.measureText(eraseResult.removedWords)
                        val spaceWidth = paint.measureText(" ")
                        val targetWidth = removedWidth + spaceWidth * 4 // original width + padding
                        val numSpaces = (targetWidth / spaceWidth).toInt().coerceAtLeast(6)
                        val gapPlaceholder = " ".repeat(numSpaces)
                        val newText = if (eraseResult.gapCharIndex <= 0) {
                            "$gapPlaceholder ${eraseResult.newText}"
                        } else if (eraseResult.gapCharIndex >= eraseResult.newText.length) {
                            "${eraseResult.newText} $gapPlaceholder"
                        } else {
                            val before = eraseResult.newText.substring(0, eraseResult.gapCharIndex).trimEnd()
                            val after = eraseResult.newText.substring(eraseResult.gapCharIndex).trimStart()
                            "$before $gapPlaceholder $after"
                        }
                        updateTextBlockText(idx, newText)
                        activeGap = TextBlockGap(
                            blockId = block.id,
                            gapCharIndex = eraseResult.gapCharIndex,
                            removedWord = eraseResult.removedWords
                        )
                    }
                }
                inkCanvas.textBlocks = columnModel.textBlocks.toList()
                inkCanvas.drawToSurface()
                displayManager.displayHiddenLines()
                onUndoRedoStateChanged?.invoke()
                Log.i(TAG, "Scratch-out on TextBlock: ${if (eraseResult.deleteBlock) "deleted" else "gap for '${eraseResult.removedWords}'"}")
            }
            return
        }

        val expanded = StrokeEraser.expandToConnectedWord(
            overlapping, columnModel.activeStrokes,
            left, top, right, bottom,
            ScreenMetrics.lineSpacing,
            lineSegmenter::getStrokeLineIndex
        )

        saveSnapshot(UndoCoalescer.ActionType.SCRATCH_OUT)

        val idsToRemove = expanded.map { it.strokeId }.toSet()
        columnModel.activeStrokes.removeAll { it.strokeId in idsToRemove }
        for (id in idsToRemove) { columnModel.diagram.nodes.remove(id) }

        inkCanvas.removeStrokes(idsToRemove)

        // Delegate diagram cache invalidation and shrinking to DiagramManager.
        // If it redraws (diagram shrink), skip our own drawToSurface to avoid double-draw.
        val diagramRedrew = diagramManager.onStrokesErased(idsToRemove, expanded)
        if (!diagramRedrew) {
            inkCanvas.drawToSurface()
        }

        eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.SCRATCH_OUT,
            "erased=${idsToRemove.joinToString(",")}", elapsedMs = inkCanvas.lastFinishStrokeMs)
        Log.i(TAG, "Scratch-out erase: removed ${expanded.size} strokes (${overlapping.size} direct + ${expanded.size - overlapping.size} connected) in [$left,$top,$right,$bottom]")
        onTutorialAction?.invoke("scratch_out")
    }

    // --- Recognition ---

    /** Refresh the text preview display (e.g. after linked scroll from another column). */
    fun refreshDisplay() {
        displayManager.displayHiddenLines()
    }

    fun scrollToLine(lineIndex: Int) {
        displayManager.scrollToLine(lineIndex)
    }

    fun recognizeAllLines() {
        val strokesByLine = lineSegmenter.groupByLine(columnModel.activeStrokes)
        if (strokesByLine.isEmpty()) return
        // Recognize diagram areas immediately on load (no debounce)
        for (area in columnModel.diagramAreas) {
            diagramManager.recognizeDiagramArea(area, immediate = true)
        }
        scope.launch {
            for (lineIndex in strokesByLine.keys.sorted()) {
                // Re-recognize lines that failed ("[?]") or were never cached
                val cached = lineTextCache[lineIndex]
                if (cached != null && cached != "[?]") continue
                if (recognitionManager.isRecognizing(lineIndex)) continue
                recognitionManager.markRecognizing(lineIndex)
                lineTextCache.remove(lineIndex)
                val text = recognitionManager.doRecognizeLine(lineIndex)
                if (text != null) {
                    Log.d(TAG, "Post-load recognized line $lineIndex: \"$text\"")
                    displayManager.displayHiddenLines()
                }
            }
        }
    }

    // --- Space insert/remove ---

    /** Insert [lines] blank lines at [anchorLine], shifting content below down. */
    fun insertSpace(anchorLine: Int, lines: Int) {
        saveSnapshot(UndoCoalescer.ActionType.SPACE_INSERTED)
        SpaceInsertMode.insertSpace(columnModel, lineSegmenter, anchorLine, lines)
        reloadAfterSpaceChange(anchorLine)
        Log.i(TAG, "Insert space: $lines lines at anchor=$anchorLine")
        onSpaceChanged?.invoke(anchorLine, lines)
    }

    /** Remove up to [lines] empty lines at [anchorLine]. Returns actual lines removed. */
    fun removeSpace(anchorLine: Int, lines: Int): Int {
        saveSnapshot(UndoCoalescer.ActionType.SPACE_INSERTED)
        val removed = SpaceInsertMode.removeSpace(columnModel, lineSegmenter, anchorLine, lines)
        if (removed == 0) return 0
        reloadAfterSpaceChange(anchorLine)
        Log.i(TAG, "Remove space: $removed lines at anchor=$anchorLine")
        onSpaceChanged?.invoke(anchorLine, -removed)
        return removed
    }

    /**
     * Apply a space change originating from the other column.
     * Saves an undo snapshot so the shift can be independently undone.
     * Does NOT fire [onSpaceChanged] to avoid recursion.
     */
    fun syncSpaceChange(anchorLine: Int, lineDelta: Int) {
        saveSnapshot(UndoCoalescer.ActionType.SPACE_INSERTED)
        if (lineDelta > 0) {
            SpaceInsertMode.insertSpace(columnModel, lineSegmenter, anchorLine, lineDelta)
        } else if (lineDelta < 0) {
            SpaceInsertMode.removeSpace(columnModel, lineSegmenter, anchorLine, -lineDelta)
        }
        reloadAfterSpaceChange(anchorLine)
        Log.i(TAG, "Sync space change from other column: delta=$lineDelta at anchor=$anchorLine")
    }

    private fun reloadAfterSpaceChange(anchorLine: Int) {
        lineTextCache.keys.filter { it >= anchorLine }.forEach { lineTextCache.remove(it) }
        inkCanvas.loadStrokes(columnModel.activeStrokes.toList())
        inkCanvas.diagramAreas = columnModel.diagramAreas.toList()
        inkCanvas.textBlocks = columnModel.textBlocks.toList()
        displayManager.clearEverHiddenLines()
        displayManager.displayHiddenLines()
    }

    // --- Markdown export ---

    fun getMarkdownBlocks(): List<MarkdownExporter.MdBlock> = MarkdownExporter.buildBlocks(
        lineTextCache, columnModel.activeStrokes, columnModel.diagramAreas,
        columnModel.textBlocks, inkCanvas.width.toFloat(), paragraphBuilder, lineSegmenter,
        isDiagramLine = { diagramManager.isDiagramLine(it) }
    )

    fun getMarkdownText(): String = getMarkdownText(cueBlocks = emptyList())

    fun getMarkdownText(cueBlocks: List<MarkdownExporter.MdBlock>): String =
        MarkdownExporter.buildText(getMarkdownBlocks(), cueBlocks)

    // --- Diagram node rebuild ---

    private fun rebuildDiagramNodes(strokes: List<InkStroke>) {
        columnModel.diagram.nodes.clear()
        for (stroke in strokes) {
            if (!stroke.strokeType.isConnector && stroke.strokeType != StrokeType.FREEHAND) {
                val bounds = android.graphics.RectF(
                    stroke.minX, stroke.minY, stroke.maxX, stroke.maxY
                )
                columnModel.diagram.nodes[stroke.strokeId] = DiagramNode(
                    strokeId = stroke.strokeId, shapeType = stroke.strokeType, bounds = bounds
                )
            }
        }
    }

    // --- Undo / Redo ---

    private fun saveSnapshot(
        action: UndoCoalescer.ActionType,
        lineIndex: Int = -1
    ) {
        undoCoalescer.maybeSave(action, lineIndex, currentSnapshot())
        onUndoRedoStateChanged?.invoke()
    }

    private fun applySnapshot(snapshot: UndoManager.Snapshot) {
        val oldStrokeIds = columnModel.activeStrokes.map { it.strokeId }.toSet()

        columnModel.activeStrokes.clear()
        columnModel.activeStrokes.addAll(snapshot.strokes)
        columnModel.diagramAreas.clear()
        columnModel.diagramAreas.addAll(snapshot.diagramAreas)
        columnModel.textBlocks.clear()
        columnModel.textBlocks.addAll(snapshot.textBlocks)
        diagramManager.clearTextCache()
        rebuildDiagramNodes(snapshot.strokes)
        inkCanvas.diagramAreas = snapshot.diagramAreas
        inkCanvas.textBlocks = snapshot.textBlocks
        inkCanvas.loadStrokes(snapshot.strokes)

        val scrollDecision = UndoScrollCalculator.computeScroll(
            oldStrokeIds, snapshot.strokes,
            inkCanvas.scrollOffsetY, inkCanvas.height.toFloat()
        )
        if (scrollDecision.shouldScroll) {
            inkCanvas.scrollOffsetY = scrollDecision.newScrollOffsetY
        }

        inkCanvas.drawToSurface()
        lineTextCache.clear()
        lineTextCache.putAll(snapshot.lineTextCache)
        displayManager.clearEverHiddenLines()
        displayManager.displayHiddenLines()
    }

    private fun currentSnapshot() = UndoManager.Snapshot(
        strokes = columnModel.activeStrokes.toList(),
        scrollOffsetY = inkCanvas.scrollOffsetY,
        lineTextCache = lineTextCache.toMap(),
        diagramAreas = columnModel.diagramAreas.toList(),
        textBlocks = columnModel.textBlocks.toList()
    )

    fun canUndo(): Boolean = undoManager.canUndo()
    fun canRedo(): Boolean = undoManager.canRedo()

    fun undo() {
        val snapshot = undoManager.undo(currentSnapshot()) ?: return
        inkCanvas.pauseRawDrawing()
        applySnapshot(snapshot)
        inkCanvas.resumeRawDrawing()
        onUndoRedoStateChanged?.invoke()
        Log.i(TAG, "Undo: restored ${snapshot.strokes.size} strokes")
    }

    fun redo() {
        val snapshot = undoManager.redo(currentSnapshot()) ?: return
        inkCanvas.pauseRawDrawing()
        applySnapshot(snapshot)
        inkCanvas.resumeRawDrawing()
        onUndoRedoStateChanged?.invoke()
        Log.i(TAG, "Redo: restored ${snapshot.strokes.size} strokes")
    }



    // --- Bug report ---

    /**
     * Generate a bug report file containing device info, recent stroke history,
     * processing decisions, and current document state.
     * @return the file, or null if no strokes in the buffer
     */
    fun generateBugReport(): java.io.File? {
        val snapshot = eventLog.snapshot()
        if (snapshot.strokes.isEmpty()) return null

        val json = BugReport.serialize(
            eventSnapshot = snapshot,
            activeStrokes = columnModel.activeStrokes.toList(),
            diagramAreas = columnModel.diagramAreas.toList(),
            lineTextCache = lineTextCache.toMap()
        )

        val outDir = java.io.File(
            inkCanvas.context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS),
            "bug-reports"
        )
        outDir.mkdirs()

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val outFile = java.io.File(outDir, "bug-report-$timestamp.json")
        outFile.writeText(json.toString(2))

        Log.i(TAG, "Bug report generated: ${outFile.absolutePath} " +
            "(${snapshot.strokes.size} strokes, ${snapshot.events.size} events)")
        return outFile
    }

    // --- State persistence ---

    /** Get a snapshot of the recognized text cache. */
    fun getLineTextCacheSnapshot(): Map<Int, String> = lineTextCache.toMap()


    fun getColumnState(): ColumnData {
        return ColumnData(
            strokes = inkCanvas.getStrokes(),
            lineTextCache = lineTextCache.toMap(),
            everHiddenLines = displayManager.getEverHiddenLinesSnapshot(),
            diagramAreas = columnModel.diagramAreas.toList(),
            textBlocks = columnModel.textBlocks.toList()
        )
    }

    fun getState(): DocumentData {
        return DocumentData(
            main = getColumnState(),
            scrollOffsetY = inkCanvas.scrollOffsetY,
            highestLineIndex = highestLineIndex,
            currentLineIndex = currentLineIndex,
            userRenamed = userRenamed,
            audioRecordings = documentModel.audioRecordings.toList(),
        )
    }

    fun restoreState(data: DocumentData) {
        restoreColumnState(data.main)
        highestLineIndex = data.highestLineIndex
        currentLineIndex = data.currentLineIndex
        userRenamed = data.userRenamed
        documentModel.restoreAudioRecordings(data.audioRecordings)
    }

    // Debounce timer for TextBlock replacement recognition
    private var textBlockReplaceJob: kotlinx.coroutines.Job? = null
    private var pendingReplaceBlock: TextBlock? = null
    private var pendingReplaceLineIdx: Int = -1
    private val REPLACE_DEBOUNCE_MS = 800L

    /**
     * Active gap in a TextBlock from scratch-out, awaiting replacement strokes.
     * The gap is rendered as blank space on the canvas so the user can write in it.
     */
    data class TextBlockGap(
        val blockId: String,
        val gapCharIndex: Int,
        val removedWord: String
    )
    private var activeGap: TextBlockGap? = null

    /**
     * Handle a stroke written on a TextBlock line — accumulate strokes and
     * debounce recognition until the user pauses writing.
     */
    private fun onTextBlockReplacementStroke(
        stroke: InkStroke, block: TextBlock, lineIdx: Int, elapsedMs: Long
    ) {
        saveSnapshot(UndoCoalescer.ActionType.STROKE_ADDED, lineIdx)
        columnModel.activeStrokes.add(stroke)
        pendingReplaceBlock = block
        pendingReplaceLineIdx = lineIdx

        // Cancel previous debounce and restart
        textBlockReplaceJob?.cancel()
        textBlockReplaceJob = scope.launch {
            kotlinx.coroutines.delay(REPLACE_DEBOUNCE_MS)
            commitTextBlockReplacement()
        }

        eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.ADDED,
            "textblock_replace line=$lineIdx", elapsedMs = elapsedMs)
    }

    private suspend fun commitTextBlockReplacement() {
        val savedBlock = pendingReplaceBlock ?: return
        val lineIdx = pendingReplaceLineIdx
        pendingReplaceBlock = null
        // Look up the current version of the block (may have been modified by scratch-out gap insertion)
        val block = columnModel.textBlocks.find { it.id == savedBlock.id } ?: return

        val lineStrokes = columnModel.activeStrokes.filter {
            lineSegmenter.getStrokeLineIndex(it) == lineIdx
        }
        if (lineStrokes.isEmpty()) return

        // Compute pre-context from the TextBlock text
        val textLeftMargin = HandwritingCanvasView.LINE_SPACING * 0.3f
        val strokeMinX = lineStrokes.minOf { it.minX }
        val strokeMaxX = lineStrokes.maxOf { it.maxX }
        val strokeCenterX = (strokeMinX + strokeMaxX) / 2f
        val relativeX = strokeCenterX - textLeftMargin
        val charWidth = TextBlockEraser.estimateCharWidth(
            block.text, inkCanvas.width.toFloat(), textLeftMargin
        )
        val approxCharPos = (relativeX / charWidth).toInt().coerceIn(0, block.text.length)
        val preContext = block.text.take(approxCharPos).takeLast(20)

        val recognized = try {
            val line = lineSegmenter.buildInkLine(lineStrokes, lineIdx)
            recognizer.recognizeLine(line, preContext)
        } catch (e: Exception) {
            Log.w(TAG, "Recognition failed for replacement stroke", e)
            ""
        }

        // Remove all replacement strokes from the canvas
        val idsToRemove = lineStrokes.map { it.strokeId }.toSet()
        columnModel.activeStrokes.removeAll { it.strokeId in idsToRemove }
        inkCanvas.removeStrokes(idsToRemove)

        if (recognized.isBlank()) {
            inkCanvas.pauseRawDrawing()
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
            return
        }

        // Replace the gap placeholder with the recognized text.
        // The gap is a run of spaces inserted during scratch-out.
        val newText = if (activeGap != null && activeGap!!.blockId == block.id) {
            // Find and replace the gap placeholder (run of spaces) with recognized text
            val gapRegex = Regex("  +") // 2+ consecutive spaces = gap placeholder
            val currentText = block.text
            val match = gapRegex.find(currentText)
            if (match != null) {
                val before = currentText.substring(0, match.range.first).trimEnd()
                val after = currentText.substring(match.range.last + 1).trimStart()
                "$before ${recognized.trim()} $after"
            } else {
                // Fallback: append
                "${block.text} ${recognized.trim()}"
            }
        } else {
            // No active gap — insert at X position using measured word widths
            val paint = android.text.TextPaint().apply { textSize = com.writer.view.ScreenMetrics.textBody }
            val spaceW = paint.measureText(" ")
            val words = block.text.split(" ").filter { it.isNotEmpty() }.toMutableList()
            var xPos = 0f
            var insertIdx = words.size
            for (i in words.indices) {
                val wordEnd = xPos + paint.measureText(words[i])
                if (relativeX < wordEnd) { insertIdx = i; break }
                xPos = wordEnd + spaceW
            }
            words.add(insertIdx, recognized.trim())
            words.joinToString(" ")
        }

        // Clean up gap state
        activeGap = null

        val idx = columnModel.textBlocks.indexOfFirst { it.id == block.id }
        if (idx >= 0) {
            updateTextBlockText(idx, newText.replace(Regex("  +"), " ").trim())
        }

        inkCanvas.textBlocks = columnModel.textBlocks.toList()
        inkCanvas.pauseRawDrawing()
        inkCanvas.drawToSurface()
        inkCanvas.resumeRawDrawing()
        displayManager.displayHiddenLines()
        onUndoRedoStateChanged?.invoke()
        Log.i(TAG, "TextBlock replacement: inserted '$recognized' → '$newText'")
    }

    /** Compute the number of wrapped lines for [text] at the current canvas width. */
    private fun computeHeightInLines(text: String): Int {
        if (text.isBlank()) return 1
        val canvasWidth = inkCanvas.width.toFloat().takeIf { it > 0 } ?: 800f
        val textLeftMargin = HandwritingCanvasView.LINE_SPACING * 0.3f
        val textWidth = (canvasWidth - 2 * textLeftMargin).toInt().coerceAtLeast(100)
        val paint = android.text.TextPaint().apply { textSize = com.writer.view.ScreenMetrics.textBody }
        val layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .build()
        return layout.lineCount.coerceAtLeast(1)
    }

    /** Update a TextBlock's text and recalculate its heightInLines. */
    private fun updateTextBlockText(index: Int, newText: String) {
        val block = columnModel.textBlocks[index]
        columnModel.textBlocks[index] = block.copy(
            text = newText,
            heightInLines = computeHeightInLines(newText)
        )
    }

    /** Insert a transcribed text block after all existing content. */
    fun insertTextBlock(text: String, audioFile: String = "", startMs: Long = 0, endMs: Long = 0, words: List<com.writer.model.WordInfo> = emptyList()) {
        // Place after the highest stroke or text block content
        val highestStrokeLine = if (columnModel.activeStrokes.isNotEmpty()) {
            columnModel.activeStrokes.maxOf { lineSegmenter.getStrokeLineIndex(it) }
        } else -1
        val highestTextBlockLine = if (columnModel.textBlocks.isNotEmpty()) {
            columnModel.textBlocks.maxOf { it.endLineIndex }
        } else -1
        val lineIndex = maxOf(highestStrokeLine, highestTextBlockLine) + 1

        val block = TextBlock(
            startLineIndex = lineIndex,
            heightInLines = computeHeightInLines(text),
            text = text,
            audioFile = audioFile,
            audioStartMs = startMs,
            audioEndMs = endMs,
            words = words
        )
        saveSnapshot(UndoCoalescer.ActionType.STROKE_ADDED, lineIndex)
        columnModel.textBlocks.add(block)
        inkCanvas.textBlocks = columnModel.textBlocks.toList()
        inkCanvas.drawToSurface()
        displayManager.displayHiddenLines()
        onUndoRedoStateChanged?.invoke()
    }

    /** Restore state for this coordinator's column from a ColumnData snapshot. */
    fun restoreColumnState(col: ColumnData) {
        lineTextCache.putAll(col.lineTextCache)
        displayManager.addEverHiddenLines(col.everHiddenLines)
        columnModel.diagramAreas.clear()
        columnModel.diagramAreas.addAll(col.diagramAreas)
        columnModel.textBlocks.clear()
        columnModel.textBlocks.addAll(col.textBlocks)
        inkCanvas.diagramAreas = col.diagramAreas
        inkCanvas.textBlocks = col.textBlocks
        rebuildDiagramNodes(columnModel.activeStrokes)
        displayManager.displayHiddenLines()
    }
}
