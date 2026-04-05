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
import com.writer.view.RecognizedTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class WritingCoordinator(
    private val documentModel: DocumentModel,
    private val columnModel: ColumnModel,
    private val recognizer: TextRecognizer,
    private val inkCanvas: HandwritingCanvasView,
    private val textView: RecognizedTextView,
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
                displayManager.scheduleTextRefresh()
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
            override fun eagerRecognizeLine(lineIndex: Int) = recognitionManager.eagerRecognizeLine(lineIndex)
            override fun markRecognizing(lineIndex: Int) { recognitionManager.markRecognizing(lineIndex) }
            override suspend fun doRecognizeLine(lineIndex: Int): String? = recognitionManager.doRecognizeLine(lineIndex)
            override fun isRecognizing(lineIndex: Int): Boolean = recognitionManager.isRecognizing(lineIndex)
        }
        displayManager = DisplayManager(inkCanvas, textView, scope, lineSegmenter, paragraphBuilder, displayHost)
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
            // Clamp text overscroll so text doesn't scroll completely out of view
            val maxOverscroll = (textView.totalTextHeight - textView.height).coerceAtLeast(0).toFloat()
            if (inkCanvas.textOverscroll > maxOverscroll) {
                inkCanvas.textOverscroll = maxOverscroll
            }
            displayManager.displayHiddenLines()
            onTutorialAction?.invoke("manual_scroll")
            onLinkedScroll?.invoke()
        }
        textView.onTextTap = { lineIndex -> displayManager.scrollToLine(lineIndex) }
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
        displayManager.stop()
        diagramManager.stop()
        inkCanvas.onStrokeCompleted = null
        inkCanvas.onIdleTimeout = null
        inkCanvas.onManualScroll = null
        textView.onTextTap = null
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
        displayManager.textRefreshJob?.cancel()
        if (gestureHandler.tryHandle(stroke)) {
            eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.GESTURE_CONSUMED, elapsedMs = elapsedMs)
            if (elapsedMs > PERF_BUDGET_MS) Log.w(TAG, "Slow stroke: ${elapsedMs}ms (gesture)")
            onTutorialAction?.invoke("gesture_consumed")
            return
        }

        val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)
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
        if (overlapping.isEmpty()) return

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

    // --- Text display sync ---

    /** Called when the text view is scrolled via overscroll. */
    fun onManualTextScroll() {
        displayManager.onManualTextScroll(inkCanvas.textOverscroll)
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
            userRenamed = userRenamed
        )
    }

    fun restoreState(data: DocumentData) {
        restoreColumnState(data.main)
        highestLineIndex = data.highestLineIndex
        currentLineIndex = data.currentLineIndex
        userRenamed = data.userRenamed
    }

    /** Insert a transcribed text block after all existing content. */
    fun insertTextBlock(text: String, audioFile: String = "", startMs: Long = 0, endMs: Long = 0) {
        // Place after the highest stroke or text block content
        val highestStrokeLine = if (columnModel.activeStrokes.isNotEmpty()) {
            columnModel.activeStrokes.maxOf { lineSegmenter.getStrokeLineIndex(it) }
        } else -1
        val highestTextBlockLine = if (columnModel.textBlocks.isNotEmpty()) {
            columnModel.textBlocks.maxOf { it.endLineIndex }
        } else -1
        val lineIndex = maxOf(highestStrokeLine, highestTextBlockLine, highestLineIndex) + 1

        // Compute how many ruled lines the text occupies when word-wrapped
        val canvasWidth = inkCanvas.width.toFloat()
        val textLeftMargin = HandwritingCanvasView.LINE_SPACING * 0.3f
        val textWidth = (canvasWidth - 2 * textLeftMargin).toInt().coerceAtLeast(1)
        val textPaint = android.text.TextPaint().apply {
            textSize = com.writer.view.ScreenMetrics.textBody
        }
        val layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, textWidth)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .build()
        val wrappedLineCount = layout.lineCount.coerceAtLeast(1)

        val block = TextBlock(
            startLineIndex = lineIndex,
            heightInLines = wrappedLineCount,
            text = text,
            audioFile = audioFile,
            audioStartMs = startMs,
            audioEndMs = endMs
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
