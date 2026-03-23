package com.writer.ui.writing

import android.util.Log
import com.writer.model.DiagramArea
import com.writer.model.DiagramNode
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.maxY
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
) : DiagramManagerHost {
    companion object {
        private const val TAG = "WritingCoordinator"
        // Scroll when writing passes this fraction of canvas height from top
        // 25% of canvas ≈ 50% of full screen (since canvas is 75% of screen)
        private const val SCROLL_THRESHOLD = 0.25f
        // Delay before refreshing e-ink display after text view updates
        private const val TEXT_REFRESH_DELAY_MS = 500L
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
    // Diagram lifecycle manager (created in start())
    private var diagramManager: DiagramManager? = null

    /** Always-on ring buffer for bug report capture. */
    val eventLog = StrokeEventLog()
    /** Index of the most recent raw stroke in the event log.
     *  Safe as a single field because stylus input is single-touch on the main thread. */
    private var lastStrokeIndex = -1

    override fun onDiagramAreasChanged() = displayHiddenLines()
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
        scrollAnimating = false
        textRefreshJob?.cancel()
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
        scrollAnimating = false
        textRefreshJob?.cancel()
        diagramManager?.reset()
        lineTextCache.clear()
        recognizingLines.clear()
        pendingRerecognize.clear()
        everHiddenLines.clear()
        highestLineIndex = -1
        currentLineIndex = -1
        userRenamed = false
        documentModel.diagramAreas.clear()
        inkCanvas.diagramAreas = emptyList()
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
     * Core recognition: get strokes for [lineIndex], filter markers, recognize,
     * and cache the result. Returns the recognized text, or null if there were
     * no strokes or recognition failed.
     */
    private suspend fun doRecognizeLine(lineIndex: Int): String? {
        // Diagram lines are recognized by recognizeDiagramArea, not here
        if (diagramManager!!.isDiagramLine(lineIndex)) {
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
            .filter { !diagramManager!!.isDiagramLine(it) }
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
            val textGroups = diagramManager!!.getTextGroups(vis.startLineIndex)
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
