package com.writer.ui.writing

import android.util.Log
import com.writer.model.DiagramNode
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.view.ScratchOutDetection
import com.writer.recognition.TextRecognizer
import com.writer.recognition.LineSegmenter
import com.writer.recognition.StrokeClassifier
import com.writer.model.DocumentData
import com.writer.storage.SvgExporter
import com.writer.view.HandwritingCanvasView
import com.writer.view.RecognizedTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class WritingCoordinator(
    private val documentModel: DocumentModel,
    private val recognizer: TextRecognizer,
    private val inkCanvas: HandwritingCanvasView,
    private val textView: RecognizedTextView,
    private val scope: CoroutineScope,
    private val onStatusUpdate: (String) -> Unit
) : DiagramManagerHost {
    companion object {
        private const val TAG = "WritingCoordinator"
    }

    private val lineSegmenter = LineSegmenter()
    private val strokeClassifier = StrokeClassifier(lineSegmenter)
    private val paragraphBuilder = ParagraphBuilder(strokeClassifier)
    private val undoManager = UndoManager()

    private val gestureHandler = GestureHandler(
        documentModel = documentModel,
        inkCanvas = inkCanvas,
        lineSegmenter = lineSegmenter,
        onLinesChanged = { invalidatedLines ->
            for (line in invalidatedLines) {
                lineTextCache.remove(line)
            }
            displayManager?.displayHiddenLines()
        },
        onBeforeMutation = { saveUndoSnapshot() }
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
    var onHeadingDetected: ((String) -> Unit)? = null
    // Diagram lifecycle manager (created in start())
    private var diagramManager: DiagramManager? = null
    // Display manager (created in start())
    private var displayManager: DisplayManager? = null
    // Line recognition manager (created in start())
    private var recognitionManager: LineRecognitionManager? = null

    /** Always-on ring buffer for bug report capture. */
    val eventLog = StrokeEventLog()
    /** Index of the most recent raw stroke in the event log.
     *  Safe as a single field because stylus input is single-touch on the main thread. */
    private var lastStrokeIndex = -1

    override fun onDiagramAreasChanged() = displayManager!!.displayHiddenLines()
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
        diagramManager = DiagramManager(documentModel, lineSegmenter, recognizer, canvasAdapter, this, scope)

        val recognitionHost = object : RecognitionManagerHost {
            override val lineTextCache: MutableMap<Int, String> get() = this@WritingCoordinator.lineTextCache
            override val userRenamed: Boolean get() = this@WritingCoordinator.userRenamed
            override val everHiddenLines: Set<Int> get() = displayManager!!.everHiddenLines
            override fun onHeadingDetected(heading: String) { this@WritingCoordinator.onHeadingDetected?.invoke(heading) }
            override fun isDiagramLine(lineIndex: Int): Boolean = diagramManager!!.isDiagramLine(lineIndex)
            override fun onRecognitionComplete(lineIndex: Int) {
                displayManager!!.displayHiddenLines()
                displayManager!!.scheduleTextRefresh()
            }
        }
        recognitionManager = LineRecognitionManager(
            documentModel, recognizer, lineSegmenter, strokeClassifier, scope,
            recognitionHost, canvasWidthProvider = { inkCanvas.width.toFloat() }
        )

        val displayHost = object : DisplayManagerHost {
            override val documentModel: DocumentModel get() = this@WritingCoordinator.documentModel
            override val diagramManager: DiagramManager? get() = this@WritingCoordinator.diagramManager
            override val lineTextCache: Map<Int, String> get() = this@WritingCoordinator.lineTextCache
            override val highestLineIndex: Int get() = this@WritingCoordinator.highestLineIndex
            override fun eagerRecognizeLine(lineIndex: Int) = recognitionManager!!.eagerRecognizeLine(lineIndex)
            override fun markRecognizing(lineIndex: Int) { recognitionManager!!.recognizingLines.add(lineIndex) }
            override suspend fun doRecognizeLine(lineIndex: Int): String? = recognitionManager!!.doRecognizeLine(lineIndex)
            override fun isRecognizing(lineIndex: Int): Boolean = recognitionManager!!.recognizingLines.contains(lineIndex)
        }
        displayManager = DisplayManager(inkCanvas, textView, scope, lineSegmenter, paragraphBuilder, displayHost)

        inkCanvas.documentModel = documentModel
        inkCanvas.onRawStrokeCapture = { points ->
            lastStrokeIndex = eventLog.recordStroke(points)
        }
        inkCanvas.onStrokeCompleted = { stroke -> onStrokeCompleted(stroke) }
        inkCanvas.onIdleTimeout = { displayManager!!.onIdle(currentLineIndex) }
        inkCanvas.onManualScroll = {
            // Clamp text overscroll so text doesn't scroll completely out of view
            val maxOverscroll = (textView.totalTextHeight - textView.height).coerceAtLeast(0).toFloat()
            if (inkCanvas.textOverscroll > maxOverscroll) {
                inkCanvas.textOverscroll = maxOverscroll
            }
            displayManager!!.displayHiddenLines()
        }
        textView.onTextTap = { lineIndex -> displayManager!!.scrollToLine(lineIndex) }
        inkCanvas.onDiagramShapeDetected = { stroke -> diagramManager!!.onShapeDetected(stroke) }
        inkCanvas.onDiagramStrokeOverflow = { strokeId, minY, maxY -> diagramManager!!.onStrokeOverflow(strokeId, minY, maxY) }
        inkCanvas.onScratchOut = { scratchPoints, left, top, right, bottom ->
            onScratchOut(scratchPoints, left, top, right, bottom)
        }
        inkCanvas.onStrokeReplaced = { oldStrokeId, newStroke ->
            onStrokeReplaced(oldStrokeId, newStroke)
        }
    }

    fun stop() {
        displayManager?.stop()
        diagramManager?.stop()
        inkCanvas.onStrokeCompleted = null
        inkCanvas.onIdleTimeout = null
        inkCanvas.onManualScroll = null
        textView.onTextTap = null
        inkCanvas.onDiagramShapeDetected = null
        inkCanvas.onDiagramStrokeOverflow = null
        inkCanvas.onScratchOut = null
        inkCanvas.onStrokeReplaced = null
    }

    fun reset() {
        displayManager?.reset()
        recognitionManager?.reset()
        diagramManager?.reset()
        lineTextCache.clear()
        highestLineIndex = -1
        currentLineIndex = -1
        userRenamed = false
        documentModel.diagramAreas.clear()
        inkCanvas.diagramAreas = emptyList()
    }

    private fun onStrokeCompleted(stroke: InkStroke) {
        displayManager!!.textRefreshJob?.cancel()
        if (gestureHandler.tryHandle(stroke)) {
            eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.GESTURE_CONSUMED)
            return
        }

        saveUndoSnapshot()
        documentModel.activeStrokes.add(stroke)
        eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.ADDED, "line=${lineSegmenter.getStrokeLineIndex(stroke)}")

        val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)

        // Diagram strokes: recognize freehand by spatial groups, not by line
        if (diagramManager!!.isDiagramLine(lineIdx)) {
            if (stroke.strokeType == StrokeType.FREEHAND) {
                val area = documentModel.diagramAreas.find { it.containsLine(lineIdx) }
                if (area != null) diagramManager!!.recognizeDiagramArea(area)
            }
            return
        }

        // Sticky zone: if a geometric (shape-snapped) stroke lands adjacent to a diagram
        // area, expand it. Freehand strokes do NOT trigger sticky expansion — they're
        // likely text and should stay outside the diagram.
        val adjacentArea = documentModel.diagramAreas.find {
            lineIdx == it.startLineIndex - 1 || lineIdx == it.endLineIndex + 1
        }
        if (adjacentArea != null && stroke.isGeometric && !diagramManager!!.hasTextStrokesOnLine(lineIdx, excluding = stroke)) {
            val newStart = minOf(adjacentArea.startLineIndex, lineIdx)
            val newEnd = maxOf(adjacentArea.endLineIndex, lineIdx)
            documentModel.diagramAreas.remove(adjacentArea)
            val expanded = adjacentArea.copy(
                startLineIndex = newStart,
                heightInLines = newEnd - newStart + 1
            )
            documentModel.diagramAreas.add(expanded)
            inkCanvas.diagramAreas = documentModel.diagramAreas.toList()
            for (line in newStart..newEnd) {
                lineTextCache.remove(line)
            }
            Log.i(TAG, "Sticky zone: expanded diagram ${adjacentArea.startLineIndex}-${adjacentArea.endLineIndex} → $newStart-$newEnd")
            if (stroke.strokeType == StrokeType.FREEHAND) {
                diagramManager!!.recognizeDiagramArea(expanded)
            }
            return
        }

        // If the user modified a previously recognized line, invalidate cache
        lineTextCache.remove(lineIdx)

        // Only trigger recognition when the user moves to a DIFFERENT line
        if (lineIdx != currentLineIndex) {
            if (currentLineIndex >= 0) {
                recognitionManager!!.eagerRecognizeLine(currentLineIndex)
            }
            currentLineIndex = lineIdx
        }

        // If editing a line that has rendered text, re-recognize immediately
        if (lineIdx in displayManager!!.everHiddenLines) {
            Log.i(TAG, "Stroke on rendered line $lineIdx, triggering re-recognition")
            recognitionManager!!.recognizeRenderedLine(lineIdx)
        } else {
            Log.d(TAG, "Stroke on line $lineIdx (not in everHiddenLines=${displayManager!!.everHiddenLines})")
        }

        if (lineIdx > highestLineIndex) {
            highestLineIndex = lineIdx
        }
    }

    private fun onStrokeReplaced(oldStrokeId: String, newStroke: InkStroke) {
        saveUndoSnapshot()  // captures state with raw stroke (state N+1)
        documentModel.activeStrokes.removeAll { it.strokeId == oldStrokeId }
        documentModel.activeStrokes.add(newStroke)
        eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.REPLACED, newStroke.strokeType.name)

        // Track shape nodes for magnetic arrow snapping
        if (!newStroke.strokeType.isConnector && newStroke.strokeType != StrokeType.FREEHAND) {
            val bounds = android.graphics.RectF(
                newStroke.points.minOf { it.x },
                newStroke.points.minOf { it.y },
                newStroke.points.maxOf { it.x },
                newStroke.points.maxOf { it.y }
            )
            documentModel.diagram.nodes[newStroke.strokeId] = DiagramNode(
                strokeId = newStroke.strokeId,
                shapeType = newStroke.strokeType,
                bounds = bounds
            )
        }

        Log.i(TAG, "Stroke replaced: $oldStrokeId → ${newStroke.strokeId} (${newStroke.strokeType})")
    }

    private fun onScratchOut(scratchPoints: List<StrokePoint>, left: Float, top: Float, right: Float, bottom: Float) {
        // Erase strokes that the scratch-out physically intersects.
        // The caller (checkPostStrokeScratchOut) already verified this is a focused
        // scratch-out via isFocusedScratchOut — most of its path covers existing strokes.
        val overlapping = documentModel.activeStrokes.filter { stroke ->
            ScratchOutDetection.strokesIntersect(scratchPoints, stroke.points)
                || stroke.strokeType.isConnector
                    && ScratchOutDetection.strokeIntersectsRect(stroke.points, left, top, right, bottom)
        }
        if (overlapping.isEmpty()) return

        saveUndoSnapshot()

        val idsToRemove = overlapping.map { it.strokeId }.toSet()
        documentModel.activeStrokes.removeAll { it.strokeId in idsToRemove }
        for (id in idsToRemove) { documentModel.diagram.nodes.remove(id) }

        inkCanvas.removeStrokes(idsToRemove)
        inkCanvas.drawToSurface()

        // Delegate diagram cache invalidation and shrinking to DiagramManager
        diagramManager!!.onStrokesErased(idsToRemove, overlapping)

        eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.SCRATCH_OUT,
            "erased=${idsToRemove.joinToString(",")}")
        Log.i(TAG, "Scratch-out erase: removed ${overlapping.size} strokes in [$left,$top,$right,$bottom]")
    }

    // --- Recognition ---

    /** Recognize all lines that have strokes but no cached text or failed recognition. */
    fun recognizeAllLines() {
        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)
        if (strokesByLine.isEmpty()) return
        // Recognize diagram areas immediately on load (no debounce)
        for (area in documentModel.diagramAreas) {
            diagramManager!!.recognizeDiagramArea(area, immediate = true)
        }
        scope.launch {
            for (lineIndex in strokesByLine.keys.sorted()) {
                // Re-recognize lines that failed ("[?]") or were never cached
                val cached = lineTextCache[lineIndex]
                if (cached != null && cached != "[?]") continue
                if (recognitionManager!!.recognizingLines.contains(lineIndex)) continue
                recognitionManager!!.recognizingLines.add(lineIndex)
                lineTextCache.remove(lineIndex)
                val text = recognitionManager!!.doRecognizeLine(lineIndex)
                if (text != null) {
                    Log.d(TAG, "Post-load recognized line $lineIndex: \"$text\"")
                    displayManager!!.displayHiddenLines()
                }
            }
        }
    }

    // --- Text display sync ---

    /** Called when the text view is scrolled via overscroll. */
    fun onManualTextScroll() {
        displayManager!!.onManualTextScroll(inkCanvas.textOverscroll)
    }

    // --- Markdown export ---

    fun getMarkdownText(): String {
        if (lineTextCache.isEmpty() && documentModel.diagramAreas.isEmpty()) return ""

        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)
        val writingWidth = inkCanvas.width.toFloat()

        val classifiedLines = lineTextCache.keys.sorted().filter { !diagramManager!!.isDiagramLine(it) }.mapNotNull { lineIdx ->
            paragraphBuilder.classifyLine(lineIdx, lineTextCache[lineIdx], strokesByLine[lineIdx], writingWidth)
        }

        val grouped = paragraphBuilder.groupIntoParagraphs(classifiedLines, strokesByLine, writingWidth, documentModel.diagramAreas)

        // Build text paragraphs with their starting line index
        data class MdBlock(val lineIndex: Int, val text: String)
        val blocks = mutableListOf<MdBlock>()

        for (group in grouped) {
            val joined = group.joinToString(" ") { it.text }
            val first = group.first()
            val prefix = if (first.isHeading) "## " else if (first.isList) "- " else ""
            blocks.add(MdBlock(first.lineIndex, "$prefix$joined"))
        }

        // Insert diagram SVGs at correct positions
        for (area in documentModel.diagramAreas.sortedBy { it.startLineIndex }) {
            val diagramStrokes = documentModel.activeStrokes.filter { stroke ->
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
            blocks.add(MdBlock(area.startLineIndex, "![diagram]($dataUri)"))
        }

        return blocks.sortedBy { it.lineIndex }.joinToString("\n\n") { it.text }
    }

    // --- Undo / Redo ---

    private fun saveUndoSnapshot() {
        undoManager.saveSnapshot(UndoManager.Snapshot(
            strokes = documentModel.activeStrokes.toList(),
            scrollOffsetY = inkCanvas.scrollOffsetY,
            lineTextCache = lineTextCache.toMap(),
            diagramAreas = documentModel.diagramAreas.toList()
        ))
    }

    private fun applySnapshot(snapshot: UndoManager.Snapshot) {
        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(snapshot.strokes)
        documentModel.diagramAreas.clear()
        documentModel.diagramAreas.addAll(snapshot.diagramAreas)
        diagramManager?.clearTextCache()
        // Rebuild diagram model nodes from restored strokes
        documentModel.diagram.nodes.clear()
        for (stroke in snapshot.strokes) {
            if (!stroke.strokeType.isConnector && stroke.strokeType != StrokeType.FREEHAND) {
                val bounds = android.graphics.RectF(
                    stroke.points.minOf { it.x }, stroke.points.minOf { it.y },
                    stroke.points.maxOf { it.x }, stroke.points.maxOf { it.y }
                )
                documentModel.diagram.nodes[stroke.strokeId] = DiagramNode(
                    strokeId = stroke.strokeId, shapeType = stroke.strokeType, bounds = bounds
                )
            }
        }
        inkCanvas.diagramAreas = snapshot.diagramAreas
        inkCanvas.loadStrokes(snapshot.strokes)
        inkCanvas.scrollOffsetY = snapshot.scrollOffsetY
        inkCanvas.drawToSurface()
        lineTextCache.clear()
        lineTextCache.putAll(snapshot.lineTextCache)
        displayManager!!.everHiddenLines.clear()
        displayManager!!.displayHiddenLines()
    }

    private fun currentSnapshot() = UndoManager.Snapshot(
        strokes = documentModel.activeStrokes.toList(),
        scrollOffsetY = inkCanvas.scrollOffsetY,
        lineTextCache = lineTextCache.toMap(),
        diagramAreas = documentModel.diagramAreas.toList()
    )

    fun undo() {
        val snapshot = undoManager.undo(currentSnapshot()) ?: return
        inkCanvas.pauseRawDrawing()
        applySnapshot(snapshot)
        inkCanvas.resumeRawDrawing()
        Log.i(TAG, "Undo: restored ${snapshot.strokes.size} strokes")
    }

    fun redo() {
        val snapshot = undoManager.redo(currentSnapshot()) ?: return
        inkCanvas.pauseRawDrawing()
        applySnapshot(snapshot)
        inkCanvas.resumeRawDrawing()
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
            activeStrokes = documentModel.activeStrokes.toList(),
            diagramAreas = documentModel.diagramAreas.toList(),
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

    fun getState(): DocumentData {
        return DocumentData(
            strokes = inkCanvas.getStrokes(),
            scrollOffsetY = inkCanvas.scrollOffsetY,
            lineTextCache = lineTextCache.toMap(),
            everHiddenLines = displayManager?.everHiddenLines?.toSet() ?: emptySet(),
            highestLineIndex = highestLineIndex,
            currentLineIndex = currentLineIndex,
            userRenamed = userRenamed,
            diagramAreas = documentModel.diagramAreas.toList()
        )
    }

    fun restoreState(data: DocumentData) {
        lineTextCache.putAll(data.lineTextCache)
        displayManager!!.everHiddenLines.addAll(data.everHiddenLines)
        highestLineIndex = data.highestLineIndex
        currentLineIndex = data.currentLineIndex
        userRenamed = data.userRenamed
        documentModel.diagramAreas.clear()
        documentModel.diagramAreas.addAll(data.diagramAreas)
        inkCanvas.diagramAreas = data.diagramAreas
        // Rebuild diagram model nodes from loaded strokes
        documentModel.diagram.nodes.clear()
        for (stroke in documentModel.activeStrokes) {
            if (!stroke.strokeType.isConnector && stroke.strokeType != StrokeType.FREEHAND) {
                val bounds = android.graphics.RectF(
                    stroke.points.minOf { it.x }, stroke.points.minOf { it.y },
                    stroke.points.maxOf { it.x }, stroke.points.maxOf { it.y }
                )
                documentModel.diagram.nodes[stroke.strokeId] = DiagramNode(
                    strokeId = stroke.strokeId, shapeType = stroke.strokeType, bounds = bounds
                )
            }
        }
        displayManager!!.displayHiddenLines()
    }
}
