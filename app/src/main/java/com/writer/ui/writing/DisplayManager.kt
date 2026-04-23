package com.writer.ui.writing

import android.util.Log
import com.writer.model.ColumnModel
import com.writer.model.InkStroke
import com.writer.model.maxY
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
import com.writer.recognition.LineSegmenter

/** Callback interface for DisplayManager to communicate with its host. */
interface DisplayManagerHost {
    val columnModel: ColumnModel
    val diagramManager: DiagramManager
    val lineTextCache: Map<Int, String>
    val highestLineIndex: Int
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

/** Text block display data for inline rendering in the text view. */
data class TextBlockDisplay(
    val startLineIndex: Int,
    val text: String
)

class DisplayManager(
    private val inkCanvas: HandwritingCanvasView,
    private val textView: RecognizedTextView,
    private val scope: CoroutineScope,
    private val lineSegmenter: LineSegmenter,
    private val paragraphBuilder: ParagraphBuilder,
    private val host: DisplayManagerHost
) {
    companion object {
        private const val TAG = "DisplayManager"
        // Scroll when writing passes this fraction of canvas height from top
        // Delay before refreshing e-ink display after text view updates
        private const val TEXT_REFRESH_DELAY_MS = 500L
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
    }

    private data class TextViewPayload(
        val paragraphs: List<List<TextSegment>>,
        val diagrams: List<DiagramDisplay>,
        val textBlockDisplays: List<TextBlockDisplay>,
    )

    private var pendingTextViewUpdate: Job? = null

    private fun updateTextView(currentlyHidden: Set<Int>, strokesByLine: Map<Int, List<InkStroke>>) {
        // The classify/group/layout work iterates every stroke and every line of
        // a growing document — on a busy page it can run for multiple seconds.
        // Doing it on the UI thread stalls the MotionEvent queue and shows up
        // as multi-second ink latency spikes after each recognition completes.
        // Snapshot the read-only inputs, compute off-main, apply on main.
        val writingWidth = inkCanvas.width.toFloat()
        val scrollOffsetY = inkCanvas.scrollOffsetY
        val diagramAreas = host.columnModel.diagramAreas.toList()
        val activeStrokes = host.columnModel.activeStrokes.toList()
        val textBlocks = host.columnModel.textBlocks.toList()

        // Only one text-view update queued at a time — supersede any older one
        // that's still in flight so the TextView always shows the latest state.
        pendingTextViewUpdate?.cancel()
        pendingTextViewUpdate = scope.launch {
            val payload = withContext(Dispatchers.Default) {
                computeTextViewPayload(
                    currentlyHidden, strokesByLine, writingWidth, scrollOffsetY,
                    diagramAreas, activeStrokes, textBlocks,
                )
            }
            textView.setContent(payload.paragraphs, payload.diagrams, payload.textBlockDisplays)
        }
    }

    private fun computeTextViewPayload(
        currentlyHidden: Set<Int>,
        strokesByLine: Map<Int, List<InkStroke>>,
        writingWidth: Float,
        scrollOffsetY: Float,
        diagramAreas: List<com.writer.model.DiagramArea>,
        activeStrokes: List<InkStroke>,
        textBlocks: List<com.writer.model.TextBlock>,
    ): TextViewPayload {
        val classifiedLines = currentlyHidden.sorted()
            .filter { !host.diagramManager.isDiagramLine(it) }
            .mapNotNull { lineIdx ->
                paragraphBuilder.classifyLine(lineIdx, host.lineTextCache[lineIdx], strokesByLine[lineIdx], writingWidth, strokesByLine[lineIdx + 1])
            }

        val grouped = paragraphBuilder.groupIntoParagraphs(classifiedLines, strokesByLine, writingWidth, diagramAreas)

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
        val strokeMaxYByArea = diagramAreas.associate { area ->
            val areaStrokes = activeStrokes.filter { stroke ->
                area.containsLine(lineSegmenter.getStrokeLineIndex(stroke))
            }
            area.startLineIndex to (if (areaStrokes.isNotEmpty()) areaStrokes.maxOf { it.maxY } else null)
        }.filterValues { it != null }.mapValues { it.value!! }

        val visibilities = PreviewLayoutCalculator.diagramVisibilities(
            areas = diagramAreas,
            scrollOffsetY = scrollOffsetY,
            topMargin = HandwritingCanvasView.TOP_MARGIN,
            lineSpacing = HandwritingCanvasView.LINE_SPACING,
            strokeMaxYByArea = strokeMaxYByArea,
            strokeWidthPadding = CanvasTheme.DEFAULT_STROKE_WIDTH
        )

        val areaByStartLine = diagramAreas.associateBy { it.startLineIndex }
        val diagrams = visibilities.map { vis ->
            val area = areaByStartLine[vis.startLineIndex]
            val areaStrokes = if (area != null) {
                activeStrokes.filter { stroke ->
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

        // Build text block displays for transcribed text
        val textBlockDisplays = textBlocks
            .filter { it.text.isNotBlank() }
            .map { TextBlockDisplay(startLineIndex = it.startLineIndex, text = it.text) }

        return TextViewPayload(paragraphs, diagrams, textBlockDisplays)
    }

    /** Called when the text view is scrolled via overscroll. */
    fun onManualTextScroll(textOverscroll: Float) {
        textView.textContentScroll = textOverscroll
        textView.invalidate()
    }

    private fun updateTextScrollOffset() {
        // Content is flush with the divider -- preview and canvas are complementary
        textView.textScrollOffset = 0f
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
