package com.writer.ui.writing

import android.util.Log
import com.writer.model.DiagramArea
import com.writer.model.DiagramNode
import com.writer.model.DocumentModel
import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.maxY
import com.writer.model.shiftY
import com.writer.view.ScratchOutDetection
import com.writer.recognition.TextRecognizer
import com.writer.recognition.LineSegmenter
import com.writer.recognition.StrokeClassifier
import com.writer.model.DocumentData
import com.writer.storage.SvgExporter
import com.writer.view.CanvasTheme
import com.writer.view.HandwritingCanvasView
import com.writer.view.PreviewLayoutCalculator
import com.writer.view.RecognizedTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WritingCoordinator(
    private val documentModel: DocumentModel,
    private val recognizer: TextRecognizer,
    private val inkCanvas: HandwritingCanvasView,
    private val textView: RecognizedTextView,
    private val scope: CoroutineScope,
    private val onStatusUpdate: (String) -> Unit
) {
    companion object {
        private const val TAG = "WritingCoordinator"
        // Scroll when writing passes this fraction of canvas height from top
        // 25% of canvas ≈ 50% of full screen (since canvas is 75% of screen)
        private const val SCROLL_THRESHOLD = 0.25f
        // Delay before refreshing e-ink display after text view updates
        private const val TEXT_REFRESH_DELAY_MS = 500L
        // Delay before running diagram text recognition after a stroke
        private const val DIAGRAM_RECOGNIZE_DELAY_MS = 600L
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
            displayHiddenLines()
        },
        onBeforeMutation = { saveUndoSnapshot() }
    )

    // Eager recognition: cache of recognized text per line index.
    // All shared mutable state below is accessed only from Dispatchers.Main
    // (scope is lifecycleScope; only recognizer.recognizeLine runs on IO).
    private val lineTextCache = mutableMapOf<Int, String>()
    // Track which lines are currently being recognized (avoid duplicates)
    private val recognizingLines = mutableSetOf<Int>()
    // Lines that need re-recognition after current recognition finishes
    private val pendingRerecognize = mutableSetOf<Int>()
    // Track the highest (bottommost) line the user has written on
    private var highestLineIndex = -1
    // Lines that have ever scrolled above the viewport (text stays rendered once shown)
    private val everHiddenLines = mutableSetOf<Int>()
    // Auto-scroll animation
    private var scrollAnimating = false
    // Track which line the user is currently writing on
    private var currentLineIndex = -1
    // Whether the user has manually renamed this document
    var userRenamed = false
    // Callback to notify activity when heading-based rename should happen
    var onHeadingDetected: ((String) -> Unit)? = null
    // Deferred e-ink refresh for text view updates (avoids interrupting active writing)
    private var textRefreshJob: Job? = null
    // Debounced diagram recognition jobs, keyed by area startLineIndex
    private val diagramRecognizeJobs = mutableMapOf<Int, Job>()
    // Diagram text: recognized text groups keyed by diagram area start line
    // Each entry is a list of (text, centerX, centerY, strokeIds)
    data class DiagramTextGroup(
        val text: String,
        val centerX: Float,
        val centerY: Float,
        val strokeIds: Set<String>
    )
    private val diagramTextCache = mutableMapOf<Int, List<DiagramTextGroup>>()

    /** Always-on ring buffer for bug report capture. */
    val eventLog = StrokeEventLog()
    /** Index of the most recent raw stroke in the event log.
     *  Safe as a single field because stylus input is single-touch on the main thread. */
    private var lastStrokeIndex = -1

    fun start() {
        Log.i(TAG, "Coordinator started")
        inkCanvas.documentModel = documentModel
        inkCanvas.onRawStrokeCapture = { points ->
            lastStrokeIndex = eventLog.recordStroke(points)
        }
        inkCanvas.onStrokeCompleted = { stroke -> onStrokeCompleted(stroke) }
        inkCanvas.onIdleTimeout = { onIdle() }
        inkCanvas.onManualScroll = {
            // Clamp text overscroll so text doesn't scroll completely out of view
            val maxOverscroll = (textView.totalTextHeight - textView.height).coerceAtLeast(0).toFloat()
            if (inkCanvas.textOverscroll > maxOverscroll) {
                inkCanvas.textOverscroll = maxOverscroll
            }
            displayHiddenLines()
        }
        textView.onTextTap = { lineIndex -> scrollToLine(lineIndex) }
        inkCanvas.onDiagramShapeDetected = { stroke -> onDiagramShapeDetected(stroke) }
        inkCanvas.onDiagramStrokeOverflow = { strokeId, minY, maxY -> onDiagramStrokeOverflow(strokeId, minY, maxY) }
        inkCanvas.onScratchOut = { scratchPoints, left, top, right, bottom ->
            onScratchOut(scratchPoints, left, top, right, bottom)
        }
        inkCanvas.onStrokeReplaced = { oldStrokeId, newStroke ->
            onStrokeReplaced(oldStrokeId, newStroke)
        }
    }

    fun stop() {
        scrollAnimating = false
        textRefreshJob?.cancel()
        diagramRecognizeJobs.values.forEach { it.cancel() }
        diagramRecognizeJobs.clear()
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
        scrollAnimating = false
        textRefreshJob?.cancel()
        diagramRecognizeJobs.values.forEach { it.cancel() }
        diagramRecognizeJobs.clear()
        lineTextCache.clear()
        diagramTextCache.clear()
        recognizingLines.clear()
        pendingRerecognize.clear()
        everHiddenLines.clear()
        highestLineIndex = -1
        currentLineIndex = -1
        userRenamed = false
        documentModel.diagramAreas.clear()
        inkCanvas.diagramAreas = emptyList()
    }

    /** Check if a line index falls inside any diagram area. */
    private fun isDiagramLine(lineIdx: Int): Boolean =
        documentModel.diagramAreas.any { it.containsLine(lineIdx) }

    /** Check if a line has pre-existing strokes (not inside any diagram area). */
    private fun hasTextStrokesOnLine(lineIdx: Int, excluding: InkStroke? = null): Boolean =
        documentModel.activeStrokes.any {
            it !== excluding &&
            !isDiagramLine(lineSegmenter.getStrokeLineIndex(it)) &&
            lineSegmenter.getStrokeLineIndex(it) == lineIdx
        }

    /**
     * Find the nearest line with pre-existing text strokes in the given direction.
     * Returns the line index, or null if none found within [limit] lines.
     */
    private fun nearestTextLine(fromLine: Int, direction: Int, limit: Int = 3): Int? {
        var line = fromLine
        repeat(limit) {
            line += direction
            if (line < 0) return null
            if (hasTextStrokesOnLine(line)) return line
        }
        return null
    }

    private fun onStrokeCompleted(stroke: InkStroke) {
        textRefreshJob?.cancel()
        if (gestureHandler.tryHandle(stroke)) {
            eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.GESTURE_CONSUMED)
            return
        }

        saveUndoSnapshot()
        documentModel.activeStrokes.add(stroke)
        eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.ADDED, "line=${lineSegmenter.getStrokeLineIndex(stroke)}")

        val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)

        // Diagram strokes: recognize freehand by spatial groups, not by line
        if (isDiagramLine(lineIdx)) {
            if (stroke.strokeType == StrokeType.FREEHAND) {
                val area = documentModel.diagramAreas.find { it.containsLine(lineIdx) }
                if (area != null) recognizeDiagramArea(area)
            }
            return
        }

        // Sticky zone: if a geometric (shape-snapped) stroke lands adjacent to a diagram
        // area, expand it. Freehand strokes do NOT trigger sticky expansion — they're
        // likely text and should stay outside the diagram.
        val adjacentArea = documentModel.diagramAreas.find {
            lineIdx == it.startLineIndex - 1 || lineIdx == it.endLineIndex + 1
        }
        if (adjacentArea != null && stroke.isGeometric && !hasTextStrokesOnLine(lineIdx, excluding = stroke)) {
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
                recognizeDiagramArea(expanded)
            }
            return
        }

        // If the user modified a previously recognized line, invalidate cache
        lineTextCache.remove(lineIdx)

        // Only trigger recognition when the user moves to a DIFFERENT line
        if (lineIdx != currentLineIndex) {
            if (currentLineIndex >= 0) {
                eagerRecognizeLine(currentLineIndex)
            }
            currentLineIndex = lineIdx
        }

        // If editing a line that has rendered text, re-recognize immediately
        if (lineIdx in everHiddenLines) {
            Log.i(TAG, "Stroke on rendered line $lineIdx, triggering re-recognition")
            recognizeRenderedLine(lineIdx)
        } else {
            Log.d(TAG, "Stroke on line $lineIdx (not in everHiddenLines=$everHiddenLines)")
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

        // Invalidate diagram text cache for any affected diagram areas —
        // erased strokes may have been recognized as text labels
        for (area in documentModel.diagramAreas) {
            val cachedGroups = diagramTextCache[area.startLineIndex] ?: continue
            if (cachedGroups.any { group -> group.strokeIds.any { it in idsToRemove } }) {
                recognizeDiagramArea(area)
            }
        }

        // Shrink diagram areas that contained erased strokes.
        // Process one at a time since shrinking modifies the area list and stroke positions.
        val affectedStartLines = overlapping.map { lineSegmenter.getStrokeLineIndex(it) }.toSet()
        for (startLine in affectedStartLines) {
            val area = documentModel.diagramAreas.find { it.containsLine(startLine) } ?: continue
            shrinkDiagramAfterErase(area)
        }

        eventLog.recordEvent(lastStrokeIndex, StrokeEventLog.EventType.SCRATCH_OUT,
            "erased=${idsToRemove.joinToString(",")}")
        Log.i(TAG, "Scratch-out erase: removed ${overlapping.size} strokes in [$left,$top,$right,$bottom]")
    }

    /**
     * After strokes are erased from a diagram, shrink the area to fit remaining
     * content and shift text below up to reclaim freed space.
     */
    private fun shrinkDiagramAfterErase(area: DiagramArea) {
        val ls = HandwritingCanvasView.LINE_SPACING

        // Find all remaining strokes in this diagram area
        val remainingStrokes = documentModel.activeStrokes.filter { stroke ->
            area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
        }

        if (remainingStrokes.isEmpty()) {
            // Diagram is empty — remove it and shift everything below up
            val linesFreed = area.heightInLines
            val shiftUpPx = linesFreed * ls
            val oldEnd = area.endLineIndex

            documentModel.diagramAreas.remove(area)
            diagramTextCache.remove(area.startLineIndex)

            // Shift strokes below the old diagram up
            val shifted = documentModel.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                if (strokeLine > oldEnd) stroke.shiftY(-shiftUpPx) else stroke
            }
            documentModel.activeStrokes.clear()
            documentModel.activeStrokes.addAll(shifted)

            // Shift other diagram areas below
            val shiftedAreas = documentModel.diagramAreas.map { other ->
                if (other.startLineIndex > oldEnd)
                    other.copy(startLineIndex = other.startLineIndex - linesFreed)
                else other
            }
            documentModel.diagramAreas.clear()
            documentModel.diagramAreas.addAll(shiftedAreas)

            // Shift text cache
            val shiftedCache = lineTextCache.entries.associate { (line, text) ->
                (if (line > oldEnd) line - linesFreed else line) to text
            }
            lineTextCache.clear()
            lineTextCache.putAll(shiftedCache)

            inkCanvas.loadStrokes(documentModel.activeStrokes.toList())
            inkCanvas.diagramAreas = documentModel.diagramAreas.toList()
            inkCanvas.pauseRawDrawing()
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
            displayHiddenLines()
            Log.i(TAG, "Removed empty diagram area ${area.startLineIndex}-${area.endLineIndex}, shifted up $linesFreed lines")
            return
        }

        // Compute tight bounds from remaining strokes + 1-line padding
        val minLine = remainingStrokes.minOf { lineSegmenter.getLineIndex(it.points.minOf { p -> p.y }) }
        val maxLine = remainingStrokes.maxOf { lineSegmenter.getLineIndex(it.points.maxOf { p -> p.y }) }
        val newStart = (minLine - 1).coerceAtLeast(area.startLineIndex)
        val newEnd = (maxLine + 1).coerceAtMost(area.endLineIndex)

        val linesFreedBelow = area.endLineIndex - newEnd
        val linesFreedAbove = newStart - area.startLineIndex
        val totalFreed = linesFreedAbove + linesFreedBelow

        if (totalFreed == 0) return  // no change

        // Step 1: Shrink the diagram area first
        documentModel.diagramAreas.remove(area)
        val shrunk = DiagramArea(
            startLineIndex = newStart,
            heightInLines = newEnd - newStart + 1
        )
        documentModel.diagramAreas.add(shrunk)

        // Step 2: Shift content below the old diagram end UP by linesFreedBelow
        if (linesFreedBelow > 0) {
            val shiftUpPx = linesFreedBelow * ls
            val shifted = documentModel.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                if (strokeLine > area.endLineIndex) stroke.shiftY(-shiftUpPx) else stroke
            }
            documentModel.activeStrokes.clear()
            documentModel.activeStrokes.addAll(shifted)

            val shiftedAreas = documentModel.diagramAreas.map { other ->
                if (other.startLineIndex > area.endLineIndex)
                    other.copy(startLineIndex = other.startLineIndex - linesFreedBelow)
                else other
            }
            documentModel.diagramAreas.clear()
            documentModel.diagramAreas.addAll(shiftedAreas)

            val shiftedCache = lineTextCache.entries.associate { (line, text) ->
                (if (line > area.endLineIndex) line - linesFreedBelow else line) to text
            }
            lineTextCache.clear()
            lineTextCache.putAll(shiftedCache)
        }

        // Step 3: Shrink from top — shift diagram content + everything below UP
        if (linesFreedAbove > 0) {
            val shiftUpPx = linesFreedAbove * ls
            val shifted = documentModel.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                if (strokeLine >= newStart) stroke.shiftY(-shiftUpPx) else stroke
            }
            documentModel.activeStrokes.clear()
            documentModel.activeStrokes.addAll(shifted)

            val shiftedAreas = documentModel.diagramAreas.map { other ->
                if (other.startLineIndex >= newStart)
                    other.copy(startLineIndex = other.startLineIndex - linesFreedAbove)
                else other
            }
            documentModel.diagramAreas.clear()
            documentModel.diagramAreas.addAll(shiftedAreas)

            val shiftedCache = lineTextCache.entries.associate { (line, text) ->
                (if (line >= newStart) line - linesFreedAbove else line) to text
            }
            lineTextCache.clear()
            lineTextCache.putAll(shiftedCache)

            inkCanvas.scrollOffsetY = (inkCanvas.scrollOffsetY - shiftUpPx).coerceAtLeast(0f)
        }

        inkCanvas.loadStrokes(documentModel.activeStrokes.toList())
        inkCanvas.diagramAreas = documentModel.diagramAreas.toList()
        inkCanvas.pauseRawDrawing()
        inkCanvas.drawToSurface()
        inkCanvas.resumeRawDrawing()
        displayHiddenLines()

        for (line in 0..area.endLineIndex) { lineTextCache.remove(line) }

        val finalArea = documentModel.diagramAreas.find {
            it.heightInLines == shrunk.heightInLines
        }
        Log.i(TAG, "Shrunk diagram: ${area.startLineIndex}-${area.endLineIndex} → " +
            "${finalArea?.startLineIndex}-${finalArea?.endLineIndex} (freed: ↑$linesFreedAbove ↓$linesFreedBelow)")
    }

    // --- Recognition ---

    /** Recognize all lines that have strokes but no cached text or failed recognition. */
    fun recognizeAllLines() {
        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)
        if (strokesByLine.isEmpty()) return
        // Recognize diagram areas immediately on load (no debounce)
        for (area in documentModel.diagramAreas) {
            recognizeDiagramArea(area, immediate = true)
        }
        scope.launch {
            for (lineIndex in strokesByLine.keys.sorted()) {
                // Re-recognize lines that failed ("[?]") or were never cached
                val cached = lineTextCache[lineIndex]
                if (cached != null && cached != "[?]") continue
                if (recognizingLines.contains(lineIndex)) continue
                recognizingLines.add(lineIndex)
                lineTextCache.remove(lineIndex)
                val text = doRecognizeLine(lineIndex)
                if (text != null) {
                    Log.d(TAG, "Post-load recognized line $lineIndex: \"$text\"")
                    displayHiddenLines()
                }
            }
        }
    }

    private fun eagerRecognizeLine(lineIndex: Int) {
        if (lineTextCache.containsKey(lineIndex)) return
        if (recognizingLines.contains(lineIndex)) return
        recognizingLines.add(lineIndex)

        scope.launch {
            val text = doRecognizeLine(lineIndex) ?: return@launch
            Log.d(TAG, "Eager recognized line $lineIndex: \"$text\"")
            if (lineIndex in everHiddenLines) {
                displayHiddenLines()
            }
        }
    }

    private fun recognizeRenderedLine(lineIndex: Int) {
        if (recognizingLines.contains(lineIndex)) {
            pendingRerecognize.add(lineIndex)
            return
        }
        recognizingLines.add(lineIndex)
        lineTextCache.remove(lineIndex)

        scope.launch {
            val text = doRecognizeLine(lineIndex) ?: return@launch
            Log.d(TAG, "Rendered line recognized $lineIndex: \"$text\"")
            displayHiddenLines()
            scheduleTextRefresh()
            if (lineIndex in pendingRerecognize) {
                pendingRerecognize.remove(lineIndex)
                recognizeRenderedLine(lineIndex)
            }
        }
    }

    /**
     * Recognize all freehand text in a diagram area by spatial groups.
     * Groups strokes that are close together (across all lines in the area),
     * recognizes each group independently, and caches the results.
     */
    /**
     * Schedule diagram text recognition for [area], debounced by default.
     * Rapid calls cancel the previous pending job — only the last one runs.
     * Pass [immediate] = true for initial load (no delay).
     */
    private fun recognizeDiagramArea(area: DiagramArea, immediate: Boolean = false) {
        diagramRecognizeJobs[area.startLineIndex]?.cancel()
        if (immediate) {
            recognizeDiagramAreaNow(area)
            return
        }
        diagramRecognizeJobs[area.startLineIndex] = scope.launch {
            delay(DIAGRAM_RECOGNIZE_DELAY_MS)
            recognizeDiagramAreaNow(area)
        }
    }

    private fun recognizeDiagramAreaNow(area: DiagramArea) {
        // Collect all freehand strokes in the diagram area
        val freehandStrokes = documentModel.activeStrokes.filter { stroke ->
            stroke.strokeType == StrokeType.FREEHAND &&
                area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
        }
        if (freehandStrokes.isEmpty()) {
            diagramTextCache.remove(area.startLineIndex)
            return
        }

        val ls = HandwritingCanvasView.LINE_SPACING

        // Collect geometric (shape) strokes for classification context
        val shapeStrokes = documentModel.activeStrokes.filter { stroke ->
            stroke.strokeType != StrokeType.FREEHAND &&
                !stroke.strokeType.isConnector &&
                area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
        }

        // Classify freehand strokes as text candidates or drawing strokes.
        // Uses per-stroke heuristics (height, complexity, size, closure) and
        // group-level contagion (freehand near drawings/shapes → drawing).
        val (textStrokes, _) = DiagramStrokeClassifier.partition(
            freehandStrokes, shapeStrokes, ls
        )
        if (textStrokes.isEmpty()) {
            diagramTextCache.remove(area.startLineIndex)
            return
        }

        val groups = DiagramTextGrouping.groupByProximity(textStrokes, ls, shapeStrokes)

        scope.launch {
            val results = mutableListOf<DiagramTextGroup>()
            for (group in groups) {
                val yRows = DiagramTextGrouping.splitIntoRowsAdaptive(group, ls * 0.25f)
                val groupIds = group.map { it.strokeId }.toSet()
                for (row in yRows) {
                    val inkLine = InkLine.build(row)
                    try {
                        val preContext = buildPreContext(area.startLineIndex)
                        val text = withContext(Dispatchers.IO) {
                            recognizer.recognizeLine(inkLine, preContext)
                        }.trim()
                        if (text.isNotEmpty() && text != "[?]") {
                            val cx = (inkLine.boundingBox.left + inkLine.boundingBox.right) / 2f
                            val cy = (inkLine.boundingBox.top + inkLine.boundingBox.bottom) / 2f
                            results.add(DiagramTextGroup(
                                text = text,
                                centerX = cx,
                                centerY = cy,
                                strokeIds = groupIds
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Diagram group recognition failed", e)
                    }
                }
            }
            diagramTextCache[area.startLineIndex] = results
            Log.d(TAG, "Diagram area ${area.startLineIndex}: recognized ${results.size} groups: ${results.map { it.text }}")
            displayHiddenLines()
        }
    }

    /**
     * Core recognition: get strokes for [lineIndex], filter markers, recognize,
     * and cache the result. Returns the recognized text, or null if there were
     * no strokes or recognition failed.
     */
    private suspend fun doRecognizeLine(lineIndex: Int): String? {
        // Diagram lines are recognized by recognizeDiagramArea, not here
        if (isDiagramLine(lineIndex)) {
            recognizingLines.remove(lineIndex)
            return null
        }
        try {
            val allStrokes = lineSegmenter.getStrokesForLine(
                documentModel.activeStrokes, lineIndex
            )
            if (allStrokes.isEmpty()) {
                recognizingLines.remove(lineIndex)
                return null
            }
            val strokes = strokeClassifier.filterMarkerStrokes(allStrokes, inkCanvas.width.toFloat())
            if (strokes.isEmpty()) {
                recognizingLines.remove(lineIndex)
                return null
            }

            val line = lineSegmenter.buildInkLine(strokes, lineIndex)
            val preContext = buildPreContext(lineIndex)
            val text = withContext(Dispatchers.IO) {
                recognizer.recognizeLine(line, preContext)
            }.trim()

            lineTextCache[lineIndex] = text
            recognizingLines.remove(lineIndex)
            checkHeadingRename(lineIndex, text, allStrokes)
            return text
        } catch (e: Exception) {
            Log.e(TAG, "Recognition failed for line $lineIndex", e)
            lineTextCache[lineIndex] = "[?]"
            recognizingLines.remove(lineIndex)
            pendingRerecognize.remove(lineIndex)
            return null
        }
    }

    private fun checkHeadingRename(lineIndex: Int, text: String, strokes: List<InkStroke>) {
        if (userRenamed) return
        if (lineIndex != 0) return
        if (text.isEmpty() || text == "[?]") return
        val isHeading = strokeClassifier.findUnderlineStrokeId(strokes, lineIndex) != null
        if (!isHeading) return
        onHeadingDetected?.invoke(text)
    }

    private fun buildPreContext(lineIndex: Int): String {
        val previousLines = lineTextCache.keys.filter { it < lineIndex }.sorted()
        if (previousLines.isEmpty()) return ""
        val lastText = previousLines.map { lineTextCache[it] ?: "" }
            .filter { it.isNotEmpty() && it != "[?]" }
            .joinToString(" ")
        return lastText.takeLast(20)
    }

    // --- Auto-scroll ---

    private fun onIdle() {
        if (currentLineIndex >= 0) {
            eagerRecognizeLine(currentLineIndex)
        }
        checkAutoScroll()
    }

    private fun checkAutoScroll() {
        if (scrollAnimating) return

        val strokes = documentModel.activeStrokes
        if (strokes.isEmpty()) return

        val canvasHeight = inkCanvas.height.toFloat()
        if (canvasHeight <= 0) return

        val bottomLine = lineSegmenter.getBottomOccupiedLine(strokes)
        if (bottomLine < 0) return

        if (currentLineIndex != bottomLine) return

        val bottomLineDocY = lineSegmenter.getLineY(bottomLine) + HandwritingCanvasView.LINE_SPACING
        val bottomLineScreenY = bottomLineDocY - inkCanvas.scrollOffsetY
        val targetY = canvasHeight * SCROLL_THRESHOLD

        if (bottomLineScreenY < 0 || bottomLineScreenY > canvasHeight) return
        if (bottomLineScreenY < targetY) return

        val rawOffset = bottomLineDocY - targetY
        val newOffset = inkCanvas.snapToLine(rawOffset)
        if (newOffset > inkCanvas.scrollOffsetY) {
            animateScroll(inkCanvas.scrollOffsetY, newOffset)
        }
    }

    private fun scrollToLine(lineIndex: Int) {
        if (scrollAnimating) return

        val canvasHeight = inkCanvas.height.toFloat()
        if (canvasHeight <= 0) return

        val lineY = lineSegmenter.getLineY(lineIndex)
        val targetOffset = inkCanvas.snapToLine(lineY).coerceAtLeast(0f)

        if (targetOffset != inkCanvas.scrollOffsetY) {
            Log.i(TAG, "Tap-to-scroll: line $lineIndex → offset ${targetOffset.toInt()}")
            animateScroll(inkCanvas.scrollOffsetY, targetOffset, 500L)
        }
    }

    private fun animateScroll(fromOffset: Float, toOffset: Float, duration: Long = 1000L) {
        scrollAnimating = true
        Log.i(TAG, "AutoScroll: animating from ${fromOffset.toInt()} to ${toOffset.toInt()}")
        inkCanvas.pauseRawDrawing()

        val distance = toOffset - fromOffset

        scope.launch {
            val startTime = System.currentTimeMillis()
            while (scrollAnimating) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= duration) break

                val t = elapsed.toFloat() / duration
                val interpolated = 1f - (1f - t) * (1f - t)
                inkCanvas.scrollOffsetY = fromOffset + distance * interpolated
                inkCanvas.drawToSurface()
                displayHiddenLines()

                kotlinx.coroutines.delay(33) // ~30fps
            }

            inkCanvas.scrollOffsetY = toOffset
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
            scrollAnimating = false
            displayHiddenLines()
        }
    }

    /** Schedule a deferred e-ink refresh so text view updates become visible.
     *  Cancelled if a new stroke arrives, so we never interrupt active writing. */
    private fun scheduleTextRefresh() {
        textRefreshJob?.cancel()
        textRefreshJob = scope.launch {
            delay(TEXT_REFRESH_DELAY_MS)
            inkCanvas.pauseRawDrawing()
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
        }
    }

    // --- Text display sync ---

    private fun displayHiddenLines() {
        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)

        val currentlyHidden = PreviewLayoutCalculator.currentlyHiddenLines(
            strokesByLine.keys, inkCanvas.scrollOffsetY,
            HandwritingCanvasView.TOP_MARGIN, HandwritingCanvasView.LINE_SPACING
        )

        val notYetVisible = PreviewLayoutCalculator.notYetVisibleLines(
            strokesByLine.keys, inkCanvas.scrollOffsetY,
            HandwritingCanvasView.TOP_MARGIN, HandwritingCanvasView.LINE_SPACING
        )

        everHiddenLines.addAll(currentlyHidden)
        everHiddenLines.retainAll(strokesByLine.keys)

        updateTextView(notYetVisible)
        updateTextScrollOffset()

        val uncached = everHiddenLines.filter { !lineTextCache.containsKey(it) && !recognizingLines.contains(it) }
        if (uncached.isNotEmpty()) {
            for (lineIdx in uncached) {
                recognizingLines.add(lineIdx)
            }
            scope.launch {
                for (lineIdx in uncached) {
                    doRecognizeLine(lineIdx)
                }
                val stillNotVisible = PreviewLayoutCalculator.notYetVisibleLines(
                    strokesByLine.keys, inkCanvas.scrollOffsetY,
                    HandwritingCanvasView.TOP_MARGIN, HandwritingCanvasView.LINE_SPACING
                )
                updateTextView(stillNotVisible)
            }
        }
    }

    /** A segment of text within a paragraph, with its own dimming state. */
    data class TextSegment(val text: String, val dimmed: Boolean, val lineIndex: Int, val listItem: Boolean = false, val heading: Boolean = false)

    /** A text label to render inside a diagram at its original stroke position. */
    data class DiagramTextLabel(val text: String, val x: Float, val y: Float)

    /** Diagram display data for inline rendering in the text view. */
    data class DiagramDisplay(
        val startLineIndex: Int,
        val strokes: List<InkStroke>,
        val canvasWidth: Float,
        val heightPx: Float,
        val offsetY: Float,
        /** How much of the diagram's height is scrolled off the canvas (for partial rendering). */
        val visibleHeightPx: Float = heightPx,
        /** Recognized text labels replacing freehand strokes in the diagram. */
        val textLabels: List<DiagramTextLabel> = emptyList()
    )

    private fun updateTextView(currentlyHidden: Set<Int>) {
        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)
        val writingWidth = inkCanvas.width.toFloat()

        val classifiedLines = currentlyHidden.sorted()
            .filter { !isDiagramLine(it) }
            .mapNotNull { lineIdx ->
                paragraphBuilder.classifyLine(lineIdx, lineTextCache[lineIdx], strokesByLine[lineIdx], writingWidth)
            }

        val grouped = paragraphBuilder.groupIntoParagraphs(classifiedLines, strokesByLine, writingWidth, documentModel.diagramAreas)

        val paragraphs = grouped.map { group ->
            group.map { line ->
                TextSegment(
                    text = line.text,
                    dimmed = line.lineIndex !in currentlyHidden,
                    lineIndex = line.lineIndex,
                    listItem = line.isList,
                    heading = line.isHeading
                )
            }
        }

        // Build diagram displays — include as soon as any part scrolls off the canvas
        val strokeMaxYByArea = documentModel.diagramAreas.associate { area ->
            val areaStrokes = documentModel.activeStrokes.filter { stroke ->
                area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
            }
            area.startLineIndex to (if (areaStrokes.isNotEmpty()) areaStrokes.maxOf { it.maxY } else null)
        }.filterValues { it != null }.mapValues { it.value!! }

        val visibilities = PreviewLayoutCalculator.diagramVisibilities(
            areas = documentModel.diagramAreas,
            scrollOffsetY = inkCanvas.scrollOffsetY,
            topMargin = HandwritingCanvasView.TOP_MARGIN,
            lineSpacing = HandwritingCanvasView.LINE_SPACING,
            strokeMaxYByArea = strokeMaxYByArea,
            strokeWidthPadding = CanvasTheme.DEFAULT_STROKE_WIDTH
        )

        val areaByStartLine = documentModel.diagramAreas.associateBy { it.startLineIndex }
        val diagrams = visibilities.map { vis ->
            val area = areaByStartLine[vis.startLineIndex]
            val areaStrokes = if (area != null) {
                documentModel.activeStrokes.filter { stroke ->
                    area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
                }
            } else emptyList()

            // Use diagram text cache for recognized spatial groups
            val textGroups = diagramTextCache[vis.startLineIndex] ?: emptyList()
            val hiddenIds = textGroups.flatMap { it.strokeIds }.toSet()
            val displayStrokes = areaStrokes.filter { it.strokeId !in hiddenIds }
            val textLabels = textGroups.map { group ->
                DiagramTextLabel(text = group.text, x = group.centerX, y = group.centerY)
            }
            DiagramDisplay(
                startLineIndex = vis.startLineIndex,
                strokes = displayStrokes,
                canvasWidth = writingWidth,
                heightPx = vis.fullHeight,
                offsetY = vis.areaTop,
                visibleHeightPx = vis.visibleHeight,
                textLabels = textLabels
            )
        }

        textView.setContent(paragraphs, diagrams)
    }

    /** Called when the text view is scrolled via overscroll. */
    fun onManualTextScroll() {
        textView.textContentScroll = inkCanvas.textOverscroll
        textView.invalidate()
    }

    private fun updateTextScrollOffset() {
        // Content is flush with the divider — preview and canvas are complementary
        textView.textScrollOffset = 0f
    }

    // --- Markdown export ---

    fun getMarkdownText(): String {
        if (lineTextCache.isEmpty() && documentModel.diagramAreas.isEmpty()) return ""

        val strokesByLine = lineSegmenter.groupByLine(documentModel.activeStrokes)
        val writingWidth = inkCanvas.width.toFloat()

        val classifiedLines = lineTextCache.keys.sorted().filter { !isDiagramLine(it) }.mapNotNull { lineIdx ->
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

    // --- Auto-diagram creation (shape-intent detection) ---

    /**
     * Called when a shape is detected outside a diagram area.
     * Creates a diagram area around the shape's bounding box with 1 line padding,
     * merging with adjacent diagram areas.
     * Returns the diagram bounds (topY, bottomY) or null.
     */
    fun onDiagramShapeDetected(stroke: InkStroke): Pair<Float, Float>? {
        val minY = stroke.points.minOf { it.y }
        val maxY = stroke.points.maxOf { it.y }
        val topLine = lineSegmenter.getLineIndex(minY)
        val bottomLine = lineSegmenter.getLineIndex(maxY)

        // Exact bounding box — no forced padding. Overflow expansion will
        // add space as needed when strokes cross the boundary.
        // Don't extend into lines with pre-existing text strokes.
        var mergeStart = topLine
        var mergeHeight = bottomLine - mergeStart + 1

        // Merge with adjacent diagram area above
        val above = documentModel.diagramAreas.find { it.endLineIndex + 1 >= mergeStart && it.endLineIndex < mergeStart + mergeHeight }
        if (above != null && above.endLineIndex + 1 >= mergeStart) {
            if (above.startLineIndex < mergeStart) {
                mergeHeight += mergeStart - above.startLineIndex
                mergeStart = above.startLineIndex
            }
            documentModel.diagramAreas.remove(above)
        }

        // Merge with adjacent diagram area below
        val below = documentModel.diagramAreas.find { it.startLineIndex <= mergeStart + mergeHeight && it.startLineIndex >= mergeStart }
        if (below != null && below != above) {
            val belowEnd = below.startLineIndex + below.heightInLines
            if (belowEnd > mergeStart + mergeHeight) {
                mergeHeight = belowEnd - mergeStart
            }
            documentModel.diagramAreas.remove(below)
        }

        val newArea = DiagramArea(
            startLineIndex = mergeStart,
            heightInLines = mergeHeight
        )
        documentModel.diagramAreas.add(newArea)
        inkCanvas.diagramAreas = documentModel.diagramAreas.toList()

        // Invalidate text cache for affected lines
        for (line in mergeStart until mergeStart + mergeHeight) {
            lineTextCache.remove(line)
        }

        val topY = lineSegmenter.getLineY(mergeStart)
        val bottomY = lineSegmenter.getLineY(mergeStart + mergeHeight)
        Log.i(TAG, "Auto-created diagram area: lines $mergeStart-${mergeStart + mergeHeight - 1}")
        return Pair(topY, bottomY)
    }

    /**
     * Called when a stroke inside a diagram area extends beyond its bounds.
     * Expands the diagram area to cover the stroke's Y range.
     */
    private fun onDiagramStrokeOverflow(overflowStrokeId: String, strokeMinY: Float, strokeMaxY: Float) {
        val strokeTopLine = lineSegmenter.getLineIndex(strokeMinY)
        val strokeBottomLine = lineSegmenter.getLineIndex(strokeMaxY)

        // Find the diagram area that the stroke started in (closest match)
        val area = documentModel.diagramAreas.find {
            // The stroke overlaps this area
            strokeTopLine <= it.endLineIndex && strokeBottomLine >= it.startLineIndex
        } ?: return

        val ls = HandwritingCanvasView.LINE_SPACING
        val linesAbove = maxOf(0, area.startLineIndex - strokeTopLine + 1)  // +1 for padding
        val linesBelow = maxOf(0, strokeBottomLine - area.endLineIndex + 1)

        if (linesAbove == 0 && linesBelow == 0) return

        val diagramTopY = lineSegmenter.getLineY(area.startLineIndex)

        // Upward expansion: shift diagram strokes and the overflow stroke DOWN.
        // Text above stays in place.
        // Use centroid (getStrokeLineIndex) to classify — avoids catching text
        // descenders that barely cross the diagram boundary.
        // The overflow stroke is identified by matching its Y bounds.
        if (linesAbove > 0) {
            val shiftPx = linesAbove * ls
            val shifted = documentModel.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                val isInsideDiagram = strokeLine >= area.startLineIndex
                val isOverflowStroke = stroke.strokeId == overflowStrokeId
                if (isInsideDiagram || isOverflowStroke) stroke.shiftY(shiftPx) else stroke
            }
            documentModel.activeStrokes.clear()
            documentModel.activeStrokes.addAll(shifted)

            // Shift diagram areas at or below
            val shiftedAreas = documentModel.diagramAreas.map { other ->
                if (other.startLineIndex >= area.startLineIndex)
                    other.copy(startLineIndex = other.startLineIndex + linesAbove)
                else other
            }
            documentModel.diagramAreas.clear()
            documentModel.diagramAreas.addAll(shiftedAreas)

            // Shift text cache
            val shiftedCache = lineTextCache.entries.associate { (line, text) ->
                (if (line >= area.startLineIndex) line + linesAbove else line) to text
            }
            lineTextCache.clear()
            lineTextCache.putAll(shiftedCache)

            // Scroll so diagram appears at same screen position
            inkCanvas.scrollOffsetY += shiftPx
        }

        // Downward expansion: shift strokes below the diagram DOWN,
        // but NOT the overflow stroke (it stays — it's part of the diagram).
        // Use centroid to classify. Overflow stroke identified by Y bounds.
        if (linesBelow > 0) {
            val shiftPx = linesBelow * ls
            val postEndLine = area.endLineIndex + linesAbove  // adjusted for upward shift
            val shifted = documentModel.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                val isOverflowStroke = stroke.strokeId == overflowStrokeId
                if (strokeLine > postEndLine && !isOverflowStroke)
                    stroke.shiftY(shiftPx) else stroke
            }
            documentModel.activeStrokes.clear()
            documentModel.activeStrokes.addAll(shifted)

            val shiftedAreas = documentModel.diagramAreas.map { other ->
                if (other.startLineIndex > postEndLine)
                    other.copy(startLineIndex = other.startLineIndex + linesBelow)
                else other
            }
            documentModel.diagramAreas.clear()
            documentModel.diagramAreas.addAll(shiftedAreas)

            val shiftedCache = lineTextCache.entries.associate { (line, text) ->
                (if (line > postEndLine) line + linesBelow else line) to text
            }
            lineTextCache.clear()
            lineTextCache.putAll(shiftedCache)
        }

        // Expand diagram: keep original startLineIndex, grow height.
        // After upward shift, the area moved to startLineIndex + linesAbove.
        // We expand it back to the original startLineIndex.
        val shiftedArea = documentModel.diagramAreas.find {
            it.startLineIndex == area.startLineIndex + linesAbove
        } ?: return
        documentModel.diagramAreas.remove(shiftedArea)
        val expanded = DiagramArea(
            startLineIndex = area.startLineIndex,
            heightInLines = area.heightInLines + linesAbove + linesBelow
        )
        documentModel.diagramAreas.add(expanded)

        // Update canvas and force redraw so expanded bounds are immediately visible
        inkCanvas.loadStrokes(documentModel.activeStrokes.toList())
        inkCanvas.diagramAreas = documentModel.diagramAreas.toList()
        inkCanvas.pauseRawDrawing()
        inkCanvas.drawToSurface()
        inkCanvas.resumeRawDrawing()
        for (line in expanded.startLineIndex..expanded.endLineIndex) {
            lineTextCache.remove(line)
        }
        Log.i(TAG, "Expanded diagram: ${area.startLineIndex}-${area.endLineIndex} → " +
            "${expanded.startLineIndex}-${expanded.endLineIndex} (↓above=$linesAbove ↓below=$linesBelow)")
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
        diagramTextCache.clear()
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
        everHiddenLines.clear()
        displayHiddenLines()
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
            everHiddenLines = everHiddenLines.toSet(),
            highestLineIndex = highestLineIndex,
            currentLineIndex = currentLineIndex,
            userRenamed = userRenamed,
            diagramAreas = documentModel.diagramAreas.toList()
        )
    }

    fun restoreState(data: DocumentData) {
        lineTextCache.putAll(data.lineTextCache)
        everHiddenLines.addAll(data.everHiddenLines)
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
        displayHiddenLines()
    }
}
