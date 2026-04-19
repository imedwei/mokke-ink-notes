package com.writer.ui.writing

import android.util.Log
import com.writer.model.ColumnModel
import com.writer.model.InkStroke
import com.writer.view.HandwritingCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.writer.recognition.LineSegmenter

/** Callback interface for DisplayManager to communicate with its host. */
interface DisplayManagerHost {
    val columnModel: ColumnModel
    val diagramManager: DiagramManager
    val lineTextCache: Map<Int, String>
    val highestLineIndex: Int
    val currentLineIndex: Int
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

/** Diagram display data for inline rendering. */
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

/** Text block display data for inline rendering. */
data class TextBlockDisplay(
    val startLineIndex: Int,
    val text: String
)

class DisplayManager(
    private val inkCanvas: HandwritingCanvasView,
    private val scope: CoroutineScope,
    private val lineSegmenter: LineSegmenter,
    private val paragraphBuilder: ParagraphBuilder,
    private val host: DisplayManagerHost
) {
    companion object {
        private const val TAG = "DisplayManager"
    }

    /** Lines that have ever scrolled above the viewport (still tracked for recognition scheduling). */
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

    /**
     * Track which lines have scrolled above the viewport and schedule recognition for any
     * that have not yet been recognized. With the inline-overlay redesign there is no
     * separate text panel to populate — this exists purely to keep the recognition queue
     * fed as the user scrolls, so cached text is ready when Phase 2 adds inline overlays.
     */
    fun displayHiddenLines() {
        val strokesByLine = lineSegmenter.groupByLine(host.columnModel.activeStrokes)

        val scrollOffset = inkCanvas.scrollOffsetY
        val topMargin = HandwritingCanvasView.TOP_MARGIN
        val lineSpacing = HandwritingCanvasView.LINE_SPACING
        val currentlyHidden = strokesByLine.keys.filterTo(mutableSetOf()) { lineIdx ->
            val lineBottom = topMargin + lineIdx * lineSpacing + lineSpacing
            lineBottom <= scrollOffset
        }

        everHiddenLines.addAll(currentlyHidden)
        everHiddenLines.retainAll(strokesByLine.keys)

        inkCanvas.inlineTextRegions =
            buildInlineTextRegions(host.lineTextCache, host.currentLineIndex)

        val uncached = everHiddenLines.filter { !host.lineTextCache.containsKey(it) && !host.isRecognizing(it) }
        if (uncached.isNotEmpty()) {
            for (lineIdx in uncached) {
                host.markRecognizing(lineIdx)
            }
            scope.launch {
                for (lineIdx in uncached) {
                    host.doRecognizeLine(lineIdx)
                }
            }
        }
    }

    fun stop() {
        scrollAnimating = false
    }

    fun reset() {
        scrollAnimating = false
        everHiddenLines.clear()
    }
}
