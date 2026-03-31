package com.writer.ui.writing

import android.util.Log
import com.writer.model.ColumnModel
import com.writer.model.InkStroke
import com.writer.model.maxX
import com.writer.model.maxY
import com.writer.recognition.RecognitionResult
import com.writer.view.CanvasTheme
import com.writer.view.HandwritingCanvasView
import com.writer.view.PreviewLayoutCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.writer.recognition.LineSegmenter
import com.writer.view.ScreenMetrics

/** Callback interface for DisplayManager to communicate with its host. */
interface DisplayManagerHost {
    val columnModel: ColumnModel
    val diagramManager: DiagramManager
    val lineTextCache: Map<Int, String>
    val highestLineIndex: Int
    val currentLineIndex: Int
    /** Full recognition results with candidates, keyed by line index. */
    val lineRecognitionResults: Map<Int, com.writer.recognition.RecognitionResult>
    fun eagerRecognizeLine(lineIndex: Int)
    /** Batch-recognize a line, adding/removing from recognizingLines internally. */
    fun markRecognizing(lineIndex: Int)
    suspend fun doRecognizeLine(lineIndex: Int): String?
    fun isRecognizing(lineIndex: Int): Boolean
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

class DisplayManager(
    private val inkCanvas: HandwritingCanvasView,
    private val scope: CoroutineScope,
    private val lineSegmenter: LineSegmenter,
    private val paragraphBuilder: ParagraphBuilder,
    private val host: DisplayManagerHost,
    private val hersheyFont: HersheyFont? = null
) {
    companion object {
        private const val TAG = "DisplayManager"
        // Delay before refreshing e-ink display after text view updates
        private const val TEXT_REFRESH_DELAY_MS = 500L
        // Minimum number of distinct candidates to consider low confidence
        private const val MIN_DISTINCT_CANDIDATES = 2
    }

    /** Lines that have ever scrolled above the viewport (text stays rendered once shown). */
    internal val everHiddenLines = mutableSetOf<Int>()

    fun clearEverHiddenLines() { everHiddenLines.clear() }
    fun addEverHiddenLines(lines: Set<Int>) { everHiddenLines.addAll(lines) }
    fun getEverHiddenLinesSnapshot(): Set<Int> = everHiddenLines.toSet()
    fun isEverHidden(lineIndex: Int): Boolean = lineIndex in everHiddenLines
    /** Called during scroll animation so the activity can sync linked columns. */
    var onScrollAnimated: (() -> Unit)? = null
    /** Called when a new recognized word should be shown in the popup. (word, screenX, screenY) */
    var onWordPopup: ((word: String, screenX: Float, screenY: Float) -> Unit)? = null

    /** Scroll animation state. */
    var scrollAnimating = false
        private set
    /** Deferred e-ink refresh for text view updates. */
    var textRefreshJob: Job? = null

    private var lastConsolidatedUpTo = -1
    /** Cached overlays — only rebuilt when lineTextCache changes or currentLineIndex advances. */
    private var cachedOverlays: Map<Int, InlineTextState> = emptyMap()
    private var cachedOverlayTextHash = 0
    private var cachedOverlayLineIndex = -1
    /** Cache of generated Hershey strokes keyed by (lineIndex, displayText) to avoid regeneration. */
    private val hersheyStrokeCache = mutableMapOf<Pair<Int, String>, List<InkStroke>>()

    // Precomputed Hershey metrics (lazy-initialized once)
    private var hScale = 0f
    private var hBaselineY = 9f
    private var hMargin = 0f
    private var hMaxWidthUnits = Float.MAX_VALUE

    private fun ensureHersheyMetrics() {
        if (hScale > 0f) return
        val ls = HandwritingCanvasView.LINE_SPACING
        hScale = ls * 0.8f / 21f
        hMargin = ScreenMetrics.dp(10f)
        val w = inkCanvas.width.toFloat()
        if (w > 0f) hMaxWidthUnits = (w - hMargin * 2) / hScale
    }

    private fun getHersheyStrokes(lineIdx: Int, text: String): List<InkStroke> {
        val font = hersheyFont ?: return emptyList()
        return hersheyStrokeCache.getOrPut(lineIdx to text) {
            val ls = HandwritingCanvasView.LINE_SPACING
            val tm = HandwritingCanvasView.TOP_MARGIN
            val ruledY = tm + (lineIdx + 1) * ls
            font.textToStrokes(text, hMargin, ruledY - hBaselineY * hScale, hScale, 0.5f)
        }
    }

    fun onIdle(currentLineIndex: Int) {
        if (currentLineIndex >= 0) {
            host.eagerRecognizeLine(currentLineIndex)
        }
    }

    fun scrollToLine(lineIndex: Int) {
        if (scrollAnimating) return

        val canvasHeight = inkCanvas.height.toFloat()
        if (canvasHeight <= 0) return

        val lineY = lineSegmenter.getLineY(lineIndex)
        val targetOffset = inkCanvas.snapToLine(lineY).coerceAtLeast(0f)

        if (targetOffset != inkCanvas.scrollOffsetY) {
            Log.i(TAG, "Tap-to-scroll: line $lineIndex -> offset ${targetOffset.toInt()}")
            animateScroll(inkCanvas.scrollOffsetY, targetOffset, 500L)
        }
    }

    fun animateScroll(fromOffset: Float, toOffset: Float, duration: Long = 1000L) {
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
                onScrollAnimated?.invoke()

                kotlinx.coroutines.delay(33) // ~30fps
            }

            inkCanvas.scrollOffsetY = toOffset
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
            scrollAnimating = false
            displayHiddenLines()
            onScrollAnimated?.invoke()
        }
    }

    /** Schedule a deferred e-ink refresh so inline overlay updates become visible.
     *  Cancelled if a new stroke arrives, so we never interrupt active writing.
     *  Skips pause/resume if pen is still active to avoid stroke disappearance. */
    fun scheduleTextRefresh() {
        textRefreshJob?.cancel()
        textRefreshJob = scope.launch {
            delay(TEXT_REFRESH_DELAY_MS)
            if (inkCanvas.isPenRecentlyActive()) return@launch
            inkCanvas.pauseRawDrawing()
            inkCanvas.drawToSurface()
            inkCanvas.resumeRawDrawing()
        }
    }

    fun displayHiddenLines() {
        val strokesByLine = lineSegmenter.groupByLine(host.columnModel.activeStrokes)

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

        updateTextView(notYetVisible, strokesByLine)
        updateTextScrollOffset()

        val uncached = everHiddenLines.filter { !host.lineTextCache.containsKey(it) && !host.isRecognizing(it) }
        if (uncached.isNotEmpty()) {
            for (lineIdx in uncached) {
                host.markRecognizing(lineIdx)
            }
            scope.launch {
                for (lineIdx in uncached) {
                    host.doRecognizeLine(lineIdx)
                }
                val freshStrokesByLine = lineSegmenter.groupByLine(host.columnModel.activeStrokes)
                val stillNotVisible = PreviewLayoutCalculator.notYetVisibleLines(
                    freshStrokesByLine.keys, inkCanvas.scrollOffsetY,
                    HandwritingCanvasView.TOP_MARGIN, HandwritingCanvasView.LINE_SPACING
                )
                updateTextView(stillNotVisible, freshStrokesByLine)
            }
        }

        updateInlineOverlays(host.currentLineIndex)
    }

    private fun updateTextView(currentlyHidden: Set<Int>, strokesByLine: Map<Int, List<InkStroke>>) {
        val writingWidth = inkCanvas.width.toFloat()

        val classifiedLines = currentlyHidden.sorted()
            .filter { !host.diagramManager.isDiagramLine(it) }
            .mapNotNull { lineIdx ->
                paragraphBuilder.classifyLine(lineIdx, host.lineTextCache[lineIdx], strokesByLine[lineIdx], writingWidth, strokesByLine[lineIdx + 1])
            }

        val grouped = paragraphBuilder.groupIntoParagraphs(classifiedLines, strokesByLine, writingWidth, host.columnModel.diagramAreas)

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

        // Build diagram displays -- include as soon as any part scrolls off the canvas
        val strokeMaxYByArea = host.columnModel.diagramAreas.associate { area ->
            val areaStrokes = host.columnModel.activeStrokes.filter { stroke ->
                area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
            }
            area.startLineIndex to (if (areaStrokes.isNotEmpty()) areaStrokes.maxOf { it.maxY } else null)
        }.filterValues { it != null }.mapValues { it.value!! }

        val visibilities = PreviewLayoutCalculator.diagramVisibilities(
            areas = host.columnModel.diagramAreas,
            scrollOffsetY = inkCanvas.scrollOffsetY,
            topMargin = HandwritingCanvasView.TOP_MARGIN,
            lineSpacing = HandwritingCanvasView.LINE_SPACING,
            strokeMaxYByArea = strokeMaxYByArea,
            strokeWidthPadding = CanvasTheme.DEFAULT_STROKE_WIDTH
        )

        val areaByStartLine = host.columnModel.diagramAreas.associateBy { it.startLineIndex }
        val diagrams = visibilities.map { vis ->
            val area = areaByStartLine[vis.startLineIndex]
            val areaStrokes = if (area != null) {
                host.columnModel.activeStrokes.filter { stroke ->
                    area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
                }
            } else emptyList()

            // Use diagram text cache for recognized spatial groups
            val textGroups = host.diagramManager.getTextGroups(vis.startLineIndex)
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

        // Paragraphs and diagrams computed for future use (markdown export, etc.)
    }

    fun buildInlineOverlays(currentLineIndex: Int): Map<Int, InlineTextState> {
        val font = hersheyFont
        val overlays = mutableMapOf<Int, InlineTextState>()

        // Collect lines to consolidate — simple iteration, no chained operators
        val consolidateLines = ArrayList<Map.Entry<Int, String>>()
        if (font != null) {
            ensureHersheyMetrics()
            for (entry in host.lineTextCache) {
                val lineIdx = entry.key
                val text = entry.value
                if (text.isBlank() || text == "[?]") continue
                if (host.diagramManager.isDiagramLine(lineIdx)) continue
                if (lineIdx >= currentLineIndex) continue
                consolidateLines.add(entry)
            }
            consolidateLines.sortBy { it.key }
        }

        // Group consecutive lines into paragraphs (break on gaps or diagram lines)
        val paragraphs = ArrayList<ArrayList<Map.Entry<Int, String>>>()
        var currentGroup = ArrayList<Map.Entry<Int, String>>()
        for (entry in consolidateLines) {
            if (currentGroup.isNotEmpty()) {
                val prevIdx = currentGroup.last().key
                val gap = entry.key - prevIdx
                val hasDiagramBetween = (prevIdx + 1 until entry.key).any { host.diagramManager.isDiagramLine(it) }
                if (gap > 1 || hasDiagramBetween) {
                    paragraphs.add(currentGroup)
                    currentGroup = ArrayList()
                }
            }
            currentGroup.add(entry)
        }
        if (currentGroup.isNotEmpty()) paragraphs.add(currentGroup)

        // For each paragraph, concatenate text and word-wrap
        for (paragraph in paragraphs) {
            if (font == null) break
            val fullText = paragraph.joinToString(" ") { it.value }
            val wrappedLines = font.wordWrap(fullText, hMaxWidthUnits)
            val startLineIdx = paragraph.first().key

            // Generate Hershey strokes for each wrapped line
            for (i in wrappedLines.indices) {
                val lineIdx = startLineIdx + i
                val wrappedText = wrappedLines[i]
                val syntheticStrokes = getHersheyStrokes(lineIdx, wrappedText)
                overlays[lineIdx] = InlineTextState(
                    lineIndex = lineIdx,
                    recognizedText = wrappedText,
                    consolidated = true,
                    syntheticStrokes = syntheticStrokes
                )
            }

            // Mark remaining original lines as consolidated (hide strokes)
            for (entry in paragraph) {
                val lineIdx = entry.key
                if (lineIdx !in overlays) {
                    overlays[lineIdx] = InlineTextState(
                        lineIndex = lineIdx,
                        recognizedText = "",
                        consolidated = true
                    )
                }
            }
        }

        // Non-consolidated overlays for current writing line and other non-consolidated lines
        for ((lineIdx, text) in host.lineTextCache) {
            if (lineIdx in overlays) continue
            if (text.isBlank() || text == "[?]") continue
            if (host.diagramManager.isDiagramLine(lineIdx)) continue

            overlays[lineIdx] = InlineTextState(
                lineIndex = lineIdx,
                recognizedText = text,
                consolidated = false
            )
        }

        return overlays
    }

    /** Track the last recognized text per line to detect any changes. */
    private var lastPopupText = ""
    private var lastPopupLine = -1

    fun updateInlineOverlays(currentLineIndex: Int) {
        val overlays = buildInlineOverlays(currentLineIndex)
        inkCanvas.currentWritingLineIndex = currentLineIndex
        inkCanvas.updateInlineOverlays(overlays)

        // Update the popup card: show last recognized word on the current line
        val currentText = host.lineTextCache[currentLineIndex]
        if (currentText != null && currentText.isNotBlank() && currentText != "[?]") {
            val lastWord = currentText.trim().split(" ").lastOrNull()

            // Fire popup whenever text or line changes
            if (lastWord != null && (currentText != lastPopupText || currentLineIndex != lastPopupLine)) {
                lastPopupText = currentText
                lastPopupLine = currentLineIndex
                val lineStrokes = host.columnModel.activeStrokes.filter { stroke ->
                    val y = (stroke.points.sumOf { it.y.toDouble() }.toFloat() / stroke.points.size)
                    lineSegmenter.getLineIndex(y) == currentLineIndex
                }
                val rightX = lineStrokes.maxOfOrNull { it.maxX } ?: (inkCanvas.width / 2f)
                val lineTop = HandwritingCanvasView.TOP_MARGIN + currentLineIndex * HandwritingCanvasView.LINE_SPACING
                val screenY = lineTop - inkCanvas.scrollOffsetY
                onWordPopup?.invoke(lastWord, rightX, screenY)
            }
        }
    }

    /** Called when the text view is scrolled via overscroll. */
    fun onManualTextScroll(textOverscroll: Float) {
        // No-op: RecognizedTextView removed
    }

    private fun updateTextScrollOffset() {
        // No-op: RecognizedTextView removed
    }

    fun stop() {
        scrollAnimating = false
        textRefreshJob?.cancel()
    }

    fun reset() {
        scrollAnimating = false
        textRefreshJob?.cancel()
        everHiddenLines.clear()
        lastPopupText = ""
        lastPopupLine = -1
        cachedOverlays = emptyMap()
        cachedOverlayTextHash = 0
        cachedOverlayLineIndex = -1
        hersheyStrokeCache.clear()
        hScale = 0f
        hMaxWidthUnits = Float.MAX_VALUE
    }
}
