package com.writer.ui.writing

import android.util.Log
import com.writer.model.ColumnData
import com.writer.model.ColumnModel
import com.writer.model.DiagramNode
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
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
import com.writer.storage.SvgExporter
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
    internal val lineTextCache = mutableMapOf<Int, String>()
    // Track the highest (bottommost) line the user has written on
    private var highestLineIndex = -1
    // Track which line the user is currently writing on
    internal var currentLineIndex = -1
    // Whether the user has manually renamed this document
    var userRenamed = false
    // Callback to notify activity when heading-based rename should happen
    /** Called after any scroll animation — used by the activity for linked scroll sync. */
    var onLinkedScroll: (() -> Unit)? = null
    /** Called after space insert/remove — used by the activity to sync the other column.
     *  Parameters: anchorLine, lineDelta (positive = inserted, negative = removed). */
    var onSpaceChanged: ((anchorLine: Int, lineDelta: Int) -> Unit)? = null
    var onHeadingDetected: ((String) -> Unit)? = null
    /** Called when a new recognized word should be shown in a popup. (word, screenX, screenY) */
    var onWordPopup: ((word: String, screenX: Float, screenY: Float) -> Unit)? = null
    /** Called when user taps a word to see alternatives. (lineIndex, word, candidates, tapX, screenY) */
    var onAlternativesTap: ((lineIndex: Int, word: String, candidates: List<com.writer.recognition.RecognitionCandidate>, tapX: Float, screenY: Float) -> Unit)? = null
    // Callback to notify activity when undo/redo availability changes
    var onUndoRedoStateChanged: (() -> Unit)? = null
    // Callback for tutorial step completion (fires action IDs like "stroke_completed")
    var onTutorialAction: ((String) -> Unit)? = null
    // Hershey font for inline text consolidation
    private var hersheyFont: HersheyFont? = null
    // Diagram lifecycle manager (created in start())
    private lateinit var diagramManager: DiagramManager
    // Display manager (created in start())
    internal lateinit var displayManager: DisplayManager
    // Line recognition manager (created in start())
    private lateinit var recognitionManager: LineRecognitionManager

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

        hersheyFont = try {
            HersheyFont.loadScript(inkCanvas.context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load Hershey font: ${e.message}")
            null
        }

        val displayHost = object : DisplayManagerHost {
            override val columnModel: ColumnModel get() = this@WritingCoordinator.columnModel
            override val diagramManager: DiagramManager get() = this@WritingCoordinator.diagramManager
            override val lineTextCache: Map<Int, String> get() = this@WritingCoordinator.lineTextCache
            override val highestLineIndex: Int get() = this@WritingCoordinator.highestLineIndex
            override val currentLineIndex: Int get() = this@WritingCoordinator.currentLineIndex
            override val lineRecognitionResults get() = recognitionManager.lineRecognitionResults
            override fun eagerRecognizeLine(lineIndex: Int) = recognitionManager.eagerRecognizeLine(lineIndex)
            override fun markRecognizing(lineIndex: Int) { recognitionManager.markRecognizing(lineIndex) }
            override suspend fun doRecognizeLine(lineIndex: Int): String? = recognitionManager.doRecognizeLine(lineIndex)
            override fun isRecognizing(lineIndex: Int): Boolean = recognitionManager.isRecognizing(lineIndex)
        }
        displayManager = DisplayManager(inkCanvas, scope, lineSegmenter, paragraphBuilder, displayHost, hersheyFont)
        displayManager.onScrollAnimated = { onLinkedScroll?.invoke() }
        displayManager.onWordPopup = { word, x, y -> onWordPopup?.invoke(word, x, y) }

        inkCanvas.columnModel = columnModel
        inkCanvas.onPenDown = {
            onTutorialAction?.invoke("pen_down")
            displayManager.reConsolidateAll()
        }
        inkCanvas.onOverlayDoubleTap = { lineIndex ->
            displayManager.toggleUnConsolidate(lineIndex)
        }
        inkCanvas.onOverlayTap = overlayTap@{ lineIndex, tapX ->
            val overlay = inkCanvas.inlineTextOverlays[lineIndex] ?: return@overlayTap
            val text = overlay.recognizedText
            if (text.isBlank()) return@overlayTap

            // Figure out which word was tapped based on X position.
            // Use HersheyFont to measure word positions, or approximate with even spacing.
            val words = text.split(" ")
            if (words.isEmpty()) return@overlayTap

            val tappedWordIndex = if (hersheyFont != null && words.size > 1) {
                // Measure cumulative width of each word to find which one the tap hit
                val margin = com.writer.view.ScreenMetrics.dp(10f)
                val scale = displayManager.hScale
                var x = margin
                var found = 0
                for ((i, word) in words.withIndex()) {
                    val wordWidth = hersheyFont!!.measureWidth(word) * scale
                    val spaceWidth = hersheyFont!!.measureWidth(" ") * scale
                    if (tapX < x + wordWidth + spaceWidth / 2) { found = i; break }
                    x += wordWidth + spaceWidth
                    found = i
                }
                found
            } else 0

            val tappedWord = words[tappedWordIndex]

            // Find word-level candidates from recognition results for this word
            val wordCandidates = mutableListOf(
                com.writer.recognition.RecognitionCandidate(tappedWord, null)
            )

            // Search ALL recognition results for word alternatives at this word position.
            // After reflow, the tapped word might come from any original line.
            for ((_, result) in recognitionManager.lineRecognitionResults) {
                for (candidate in result.candidates) {
                    val candidateWords = candidate.text.split(" ")
                    // Check if this candidate has a word at the same position that differs
                    for (word in candidateWords) {
                        if (word != tappedWord && word.isNotBlank()
                            && wordCandidates.none { it.text == word }
                            // Only include words that look similar (share first letter or close length)
                            && (word.first().equals(tappedWord.first(), ignoreCase = true)
                                || kotlin.math.abs(word.length - tappedWord.length) <= 2)
                        ) {
                            wordCandidates.add(com.writer.recognition.RecognitionCandidate(word, candidate.score))
                            if (wordCandidates.size >= 5) break
                        }
                    }
                }
                if (wordCandidates.size >= 5) break
            }

            val lineTop = HandwritingCanvasView.TOP_MARGIN + lineIndex * HandwritingCanvasView.LINE_SPACING
            val screenY = lineTop - inkCanvas.scrollOffsetY
            onAlternativesTap?.invoke(lineIndex, tappedWord, wordCandidates, tapX, screenY)
            Log.d(TAG, "Overlay tap: line=$lineIndex word=$tappedWordIndex '$tappedWord' candidates=${wordCandidates.size}")
        }
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
        inkCanvas.onOverlayTap = null
        inkCanvas.onOverlayDoubleTap = null
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
        userRenamed = false
        columnModel.activeStrokes.clear()
        columnModel.diagramAreas.clear()
        inkCanvas.diagramAreas = emptyList()
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

        saveSnapshot(UndoCoalescer.ActionType.STROKE_ADDED, lineSegmenter.getStrokeLineIndex(stroke))
        columnModel.activeStrokes.add(stroke)
        eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.ADDED, "line=${lineSegmenter.getStrokeLineIndex(stroke)}", elapsedMs = elapsedMs)
        onTutorialAction?.invoke("stroke_completed")
        if (elapsedMs > PERF_BUDGET_MS) Log.w(TAG, "Slow stroke: ${elapsedMs}ms (added, line=${lineSegmenter.getStrokeLineIndex(stroke)})")

        val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)

        // Diagram strokes: recognize freehand by spatial groups, not by line
        if (diagramManager.isDiagramLine(lineIdx)) {
            if (stroke.strokeType == StrokeType.FREEHAND) {
                val area = columnModel.diagramAreas.find { it.containsLine(lineIdx) }
                if (area != null) diagramManager.recognizeDiagramArea(area)
            }
            return
        }

        // Sticky zone: if a geometric (shape-snapped) stroke lands adjacent to a diagram
        // area, expand it. Freehand strokes do NOT trigger sticky expansion — they're
        // likely text and should stay outside the diagram.
        val adjacentArea = columnModel.diagramAreas.find {
            lineIdx == it.startLineIndex - 1 || lineIdx == it.endLineIndex + 1
        }
        if (adjacentArea != null && stroke.isGeometric && !diagramManager.hasTextStrokesOnLine(lineIdx, excluding = stroke)) {
            val newStart = minOf(adjacentArea.startLineIndex, lineIdx)
            val newEnd = maxOf(adjacentArea.endLineIndex, lineIdx)
            columnModel.diagramAreas.remove(adjacentArea)
            val expanded = adjacentArea.copy(
                startLineIndex = newStart,
                heightInLines = newEnd - newStart + 1
            )
            columnModel.diagramAreas.add(expanded)
            inkCanvas.diagramAreas = columnModel.diagramAreas.toList()
            for (line in newStart..newEnd) {
                lineTextCache.remove(line)
            }
            Log.i(TAG, "Sticky zone: expanded diagram ${adjacentArea.startLineIndex}-${adjacentArea.endLineIndex} → $newStart-$newEnd")
            if (stroke.strokeType == StrokeType.FREEHAND) {
                diagramManager.recognizeDiagramArea(expanded)
            }
            return
        }

        // If the user modified a previously recognized line, invalidate cache
        lineTextCache.remove(lineIdx)

        // Only trigger recognition when the user moves to a DIFFERENT line
        if (lineIdx != currentLineIndex) {
            if (currentLineIndex >= 0) {
                recognitionManager.eagerRecognizeLine(currentLineIndex)
            }
            currentLineIndex = lineIdx
        }

        // Recognition for the current line fires on idle timeout (2s) or line change.
        // Don't trigger on every stroke — causes cascading work on main thread.

        if (lineIdx > highestLineIndex) {
            highestLineIndex = lineIdx
        }
        // Overlays update via onRecognitionComplete → displayHiddenLines, not per-stroke.
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
        // Find strokes within the scratch-out region using bounding-box overlap.
        // isFocusedScratchOut already verified this is a real scratch-out, so
        // bbox proximity is sufficient — no expensive segment intersection needed.
        val radius = ScreenMetrics.dp(ScratchOutDetection.COVERAGE_RADIUS_DP)
        val overlapping = columnModel.activeStrokes.filter { stroke ->
            val sMinX = stroke.minX; val sMaxX = stroke.maxX
            val sMinY = stroke.minY; val sMaxY = stroke.maxY
            sMaxX >= left - radius && sMinX <= right + radius &&
                sMaxY >= top - radius && sMinY <= bottom + radius
        }
        if (overlapping.isEmpty()) return

        // Expand to include the full connected word within the scratch-out region:
        // strokes on the same line that were written close in time and overlap or
        // are near the scratch-out bounding box. This ensures e.g. the crossbar of
        // 't' is removed along with the rest of "entry", but doesn't jump to
        // adjacent words outside the scratch-out region.
        val expanded = expandToConnectedWord(overlapping, columnModel.activeStrokes, left, top, right, bottom)

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

    /**
     * Expand a set of directly-overlapping strokes to include the full connected word
     * within the scratch-out region. A stroke is included if it's on the same line,
     * overlaps or is near the scratch-out bounding box, and was written close in time.
     * This catches fragments like the crossbar of 't' without jumping to adjacent words.
     */
    private fun expandToConnectedWord(
        directHits: List<InkStroke>,
        allStrokes: List<InkStroke>,
        scratchLeft: Float, scratchTop: Float, scratchRight: Float, scratchBottom: Float
    ): List<InkStroke> {
        if (directHits.isEmpty()) return directHits

        val ls = ScreenMetrics.lineSpacing
        val margin = ls * 0.5f  // small margin around scratch-out box for nearby fragments
        val maxTimeGap = 2000L

        val hitLines = directHits.map { lineSegmenter.getStrokeLineIndex(it) }.toSet()

        // Candidates: same line, within or near the scratch-out bounding box
        val candidates = allStrokes.filter { stroke ->
            lineSegmenter.getStrokeLineIndex(stroke) in hitLines &&
                stroke.maxX >= scratchLeft - margin &&
                stroke.minX <= scratchRight + margin &&
                stroke.maxY >= scratchTop - margin &&
                stroke.minY <= scratchBottom + margin
        }

        // Include candidates that are temporally connected to the direct hits
        val included = directHits.map { it.strokeId }.toMutableSet()
        for (candidate in candidates) {
            if (candidate.strokeId in included) continue
            val isTemporallyConnected = directHits.any { hit ->
                kotlin.math.abs(candidate.startTime - hit.endTime) < maxTimeGap ||
                    kotlin.math.abs(hit.startTime - candidate.endTime) < maxTimeGap
            }
            if (isTemporallyConnected) {
                included.add(candidate.strokeId)
            }
        }

        return allStrokes.filter { it.strokeId in included }
    }

    // --- Recognition ---

    /** Recognize all lines that have strokes but no cached text or failed recognition. */
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
            var recognized = 0
            for (lineIndex in strokesByLine.keys.sorted()) {
                val cached = lineTextCache[lineIndex]
                if (cached != null && cached != "[?]") continue
                if (recognitionManager.isRecognizing(lineIndex)) continue
                recognitionManager.markRecognizing(lineIndex)
                lineTextCache.remove(lineIndex)
                val text = recognitionManager.doRecognizeLine(lineIndex)
                if (text != null) {
                    recognized++
                    Log.d(TAG, "Post-load recognized line $lineIndex: \"$text\"")
                }
            }
            // Update overlays once after all lines are recognized
            if (recognized > 0) {
                displayManager.displayHiddenLines()
            }
        }
    }

    // --- Space insert/remove ---

    /** Insert [lines] blank lines at [anchorLine], shifting content below down. */
    fun insertSpace(anchorLine: Int, lines: Int) {
        saveSnapshot(UndoCoalescer.ActionType.SPACE_INSERTED)
        SpaceInsertMode.insertSpace(columnModel, lineSegmenter, anchorLine, lines)
        // Invalidate text cache for affected lines
        lineTextCache.keys.filter { it >= anchorLine }.forEach { lineTextCache.remove(it) }
        inkCanvas.loadStrokes(columnModel.activeStrokes.toList())
        inkCanvas.diagramAreas = columnModel.diagramAreas.toList()
        displayManager.clearEverHiddenLines()
        displayManager.displayHiddenLines()
        Log.i(TAG, "Insert space: $lines lines at anchor=$anchorLine")
        onSpaceChanged?.invoke(anchorLine, lines)
    }

    /** Remove up to [lines] empty lines at [anchorLine]. Returns actual lines removed. */
    fun removeSpace(anchorLine: Int, lines: Int): Int {
        saveSnapshot(UndoCoalescer.ActionType.SPACE_INSERTED)
        val removed = SpaceInsertMode.removeSpace(columnModel, lineSegmenter, anchorLine, lines)
        if (removed == 0) return 0
        lineTextCache.keys.filter { it >= anchorLine }.forEach { lineTextCache.remove(it) }
        inkCanvas.loadStrokes(columnModel.activeStrokes.toList())
        inkCanvas.diagramAreas = columnModel.diagramAreas.toList()
        displayManager.clearEverHiddenLines()
        displayManager.displayHiddenLines()
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
        lineTextCache.keys.filter { it >= anchorLine }.forEach { lineTextCache.remove(it) }
        inkCanvas.loadStrokes(columnModel.activeStrokes.toList())
        inkCanvas.diagramAreas = columnModel.diagramAreas.toList()
        displayManager.clearEverHiddenLines()
        displayManager.displayHiddenLines()
        Log.i(TAG, "Sync space change from other column: delta=$lineDelta at anchor=$anchorLine")
    }

    // --- Text display sync ---


    // --- Markdown export ---

    /**
     * Build markdown blocks from this coordinator's column, each tagged with its line range.
     * Returns a list of (startLine, endLine, markdownText) triples.
     */
    data class MdBlock(val startLine: Int, val endLine: Int, val text: String)

    fun getMarkdownBlocks(): List<MdBlock> {
        if (lineTextCache.isEmpty() && columnModel.diagramAreas.isEmpty()) return emptyList()

        val strokesByLine = lineSegmenter.groupByLine(columnModel.activeStrokes)
        val writingWidth = inkCanvas.width.toFloat()

        val classifiedLines = lineTextCache.keys.sorted().filter { !diagramManager.isDiagramLine(it) }.mapNotNull { lineIdx ->
            paragraphBuilder.classifyLine(lineIdx, lineTextCache[lineIdx], strokesByLine[lineIdx], writingWidth, strokesByLine[lineIdx + 1])
        }

        val grouped = paragraphBuilder.groupIntoParagraphs(classifiedLines, strokesByLine, writingWidth, columnModel.diagramAreas)

        val blocks = mutableListOf<MdBlock>()

        for (group in grouped) {
            val joined = group.joinToString(" ") { it.text }
            val first = group.first()
            val last = group.last()
            val prefix = if (first.isHeading) "## " else if (first.isList) "- " else ""
            blocks.add(MdBlock(first.lineIndex, last.lineIndex, "$prefix$joined"))
        }

        // Insert diagram SVGs at correct positions
        for (area in columnModel.diagramAreas.sortedBy { it.startLineIndex }) {
            val diagramStrokes = columnModel.activeStrokes.filter { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                area.containsLine(strokeLine)
            }
            if (diagramStrokes.isEmpty()) continue

            val areaTop = lineSegmenter.getLineY(area.startLineIndex)
            val areaHeight = area.heightInLines * HandwritingCanvasView.LINE_SPACING
            val svg = SvgExporter.strokesToSvg(
                diagramStrokes, writingWidth, areaHeight,
                offsetX = 0f, offsetY = areaTop
            )
            val dataUri = SvgExporter.toBase64DataUri(svg)
            blocks.add(MdBlock(area.startLineIndex, area.endLineIndex, "![diagram]($dataUri)"))
        }

        return blocks.sortedBy { it.startLine }
    }

    fun getMarkdownText(): String {
        return getMarkdownText(cueBlocks = emptyList())
    }

    /**
     * Generate markdown with interleaved cue blockquotes.
     * Cue blocks from the cue column are appended after each main content paragraph
     * that overlaps their line range.
     */
    fun getMarkdownText(cueBlocks: List<MdBlock>): String {
        val mainBlocks = getMarkdownBlocks()
        if (mainBlocks.isEmpty()) return ""

        val result = StringBuilder()
        for (block in mainBlocks) {
            if (result.isNotEmpty()) result.append("\n\n")
            result.append(block.text)

            // Find cue blocks that overlap this main block's line range
            val overlapping = cueBlocks.filter { cue ->
                cue.startLine <= block.endLine && cue.endLine >= block.startLine
            }
            if (overlapping.isNotEmpty()) {
                result.append("\n\n")
                if (overlapping.size == 1) {
                    result.append("> **Cue:** ${overlapping[0].text}")
                } else {
                    result.append("> **Cue:**")
                    for (cue in overlapping) {
                        result.append("\n> ${cue.text}")
                    }
                }
            }
        }

        return result.toString()
    }

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
        // Compute which strokes changed so we can scroll to them if off-screen
        val oldStrokeIds = columnModel.activeStrokes.map { it.strokeId }.toSet()
        val newStrokeIds = snapshot.strokes.map { it.strokeId }.toSet()
        val changedIds = (oldStrokeIds - newStrokeIds) + (newStrokeIds - oldStrokeIds)

        columnModel.activeStrokes.clear()
        columnModel.activeStrokes.addAll(snapshot.strokes)
        columnModel.diagramAreas.clear()
        columnModel.diagramAreas.addAll(snapshot.diagramAreas)
        diagramManager.clearTextCache()
        rebuildDiagramNodes(snapshot.strokes)
        inkCanvas.diagramAreas = snapshot.diagramAreas
        inkCanvas.loadStrokes(snapshot.strokes)

        // Keep current scroll position, but scroll to show changed strokes if off-screen
        val currentScroll = inkCanvas.scrollOffsetY
        if (changedIds.isNotEmpty()) {
            // Find bounds of changed strokes (in both old and new sets)
            val changedStrokes = (snapshot.strokes.filter { it.strokeId in changedIds })
            if (changedStrokes.isNotEmpty()) {
                val minY = changedStrokes.minOf { it.minY }
                val maxY = changedStrokes.maxOf { it.maxY }
                val visibleTop = currentScroll
                val visibleBottom = currentScroll + inkCanvas.height
                if (minY < visibleTop || maxY > visibleBottom) {
                    // Scroll so the changed region is centered in view
                    val centerY = (minY + maxY) / 2f
                    inkCanvas.scrollOffsetY = (centerY - inkCanvas.height / 2f).coerceAtLeast(0f)
                }
            }
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
        diagramAreas = columnModel.diagramAreas.toList()
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
            diagramAreas = columnModel.diagramAreas.toList()
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

    /** Restore state for this coordinator's column from a ColumnData snapshot. */
    fun restoreColumnState(col: ColumnData) {
        lineTextCache.putAll(col.lineTextCache)
        displayManager.addEverHiddenLines(col.everHiddenLines)
        columnModel.diagramAreas.clear()
        columnModel.diagramAreas.addAll(col.diagramAreas)
        inkCanvas.diagramAreas = col.diagramAreas
        rebuildDiagramNodes(columnModel.activeStrokes)
        displayManager.displayHiddenLines()
    }
}
