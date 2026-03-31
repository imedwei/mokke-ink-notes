package com.writer.ui.writing

import android.util.Log
import com.writer.model.ColumnModel
import com.writer.model.InkStroke
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
        // Score threshold below which inline alternatives are shown
        private const val LOW_CONFIDENCE_THRESHOLD = 0.8f
    }

    /** Lines that have ever scrolled above the viewport (text stays rendered once shown). */
    internal val everHiddenLines = mutableSetOf<Int>()

    fun clearEverHiddenLines() { everHiddenLines.clear() }
    fun addEverHiddenLines(lines: Set<Int>) { everHiddenLines.addAll(lines) }
    fun getEverHiddenLinesSnapshot(): Set<Int> = everHiddenLines.toSet()
    fun isEverHidden(lineIndex: Int): Boolean = lineIndex in everHiddenLines
    /** Called during scroll animation so the activity can sync linked columns. */
    var onScrollAnimated: (() -> Unit)? = null

    /** Scroll animation state. */
    var scrollAnimating = false
        private set
    /** Deferred e-ink refresh for text view updates. */
    var textRefreshJob: Job? = null

    private var lastConsolidatedUpTo = -1

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

    /** Schedule a deferred e-ink refresh so text view updates become visible.
     *  Cancelled if a new stroke arrives, so we never interrupt active writing. */
    fun scheduleTextRefresh() {
        textRefreshJob?.cancel()
        textRefreshJob = scope.launch {
            delay(TEXT_REFRESH_DELAY_MS)
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
        val overlays = mutableMapOf<Int, InlineTextState>()
        val font = hersheyFont
        val ls = HandwritingCanvasView.LINE_SPACING
        val tm = HandwritingCanvasView.TOP_MARGIN
        val margin = ScreenMetrics.dp(10f)
        val canvasWidth = inkCanvas.width.toFloat()
        // Hershey script font metrics (from scripts.jhf analysis):
        //   Capitals/ascenders top: Y = -12,  lowercase bottom (baseline): Y = 9
        //   Descenders bottom: Y = 21
        // The "baseline" (bottom of 'a', 'H') is at Y=9 in glyph coordinates.
        // Scale so capital height (Y -12 to 9 = 21 units) fills ~80% of line spacing.
        // Baseline positioned on the ruled line; descenders extend below.
        val capitalHeight = 21f  // -12 to 9
        val baselineGlyphY = 9f // Y coordinate of the baseline in glyph space
        val scale = if (font != null) ls * 0.8f / capitalHeight else 1f
        val availableWidthUnits = if (font != null) (canvasWidth - margin * 2) / scale else Float.MAX_VALUE

        // Collect lines to consolidate and group into paragraphs for reflow
        val consolidateLines = host.lineTextCache.entries
            .filter { (lineIdx, text) ->
                text.isNotBlank() && text != "[?]"
                    && !host.diagramManager.isDiagramLine(lineIdx)
                    && lineIdx < currentLineIndex
                    && font != null
            }
            .sortedBy { it.key }

        // Group consecutive lines into paragraphs (break on gaps or diagram lines)
        val paragraphs = mutableListOf<List<Map.Entry<Int, String>>>()
        var currentGroup = mutableListOf<Map.Entry<Int, String>>()
        for (entry in consolidateLines) {
            if (currentGroup.isNotEmpty()) {
                val prevIdx = currentGroup.last().key
                val gap = entry.key - prevIdx
                val hasDiagramBetween = (prevIdx + 1 until entry.key).any { host.diagramManager.isDiagramLine(it) }
                if (gap > 1 || hasDiagramBetween) {
                    paragraphs.add(currentGroup)
                    currentGroup = mutableListOf()
                }
            }
            currentGroup.add(entry)
        }
        if (currentGroup.isNotEmpty()) paragraphs.add(currentGroup)

        // For each paragraph, concatenate text and word-wrap to fill line width
        for (paragraph in paragraphs) {
            if (font == null) break
            val fullText = paragraph.joinToString(" ") { it.value }
            val wrappedLines = font.wordWrap(fullText, availableWidthUnits)
            val startLineIdx = paragraph.first().key
            val originalLineIndices = paragraph.map { it.key }.toSet()

            // Generate Hershey strokes for the wrapped lines
            for ((i, wrappedText) in wrappedLines.withIndex()) {
                val lineIdx = startLineIdx + i
                // Position so glyph baseline (Y=9 in glyph coords) aligns with the ruled line.
                // startY + baselineGlyphY * scale = ruledLineY  →  startY = ruledLineY - baselineGlyphY * scale
                val ruledLineY = tm + (lineIdx + 1) * ls
                val startY = ruledLineY - baselineGlyphY * scale
                val syntheticStrokes = font.textToStrokes(
                    wrappedText,
                    startX = margin,
                    startY = startY,
                    scale = scale,
                    jitter = 0.5f
                ).map { it.copy(strokeType = com.writer.model.StrokeType.SYNTHETIC) }

                overlays[lineIdx] = InlineTextState(
                    lineIndex = lineIdx,
                    recognizedText = wrappedText,
                    consolidated = true,
                    syntheticStrokes = syntheticStrokes
                )
            }

            // Mark remaining original lines as consolidated (hide strokes) even if
            // the reflowed text doesn't use them — prevents overlap
            for (lineIdx in originalLineIndices) {
                if (lineIdx !in overlays) {
                    overlays[lineIdx] = InlineTextState(
                        lineIndex = lineIdx,
                        recognizedText = "",
                        consolidated = true,
                        syntheticStrokes = emptyList()
                    )
                }
            }
        }

        // Add non-consolidated overlays (lines with recognized text but not yet consolidated)
        for ((lineIdx, text) in host.lineTextCache) {
            if (lineIdx in overlays) continue
            if (text.isBlank() || text == "[?]") continue
            if (host.diagramManager.isDiagramLine(lineIdx)) continue

            val result = host.lineRecognitionResults[lineIdx]
            val candidates = result?.candidates ?: emptyList()
            val isLowConfidence = candidates.size > 1 && (candidates[0].score?.let { it < LOW_CONFIDENCE_THRESHOLD } ?: false)
            val wordAlts = if (isLowConfidence) findWordAlternatives(candidates) else emptyList()

            overlays[lineIdx] = InlineTextState(
                lineIndex = lineIdx,
                recognizedText = text,
                consolidated = false,
                candidates = candidates,
                lowConfidence = isLowConfidence,
                wordAlternatives = wordAlts
            )
        }

        return overlays
    }

    fun updateInlineOverlays(currentLineIndex: Int) {
        val overlays = buildInlineOverlays(currentLineIndex)
        inkCanvas.updateInlineOverlays(overlays)
        scheduleTextRefresh()
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
    }
}
