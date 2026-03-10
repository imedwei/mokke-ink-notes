package com.writer.ui.writing

import android.util.Log
import com.writer.model.DiagramArea
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.minX
import com.writer.model.maxX
import com.writer.model.minY
import com.writer.model.maxY
import com.writer.model.xRange
import com.writer.model.yRange
import com.writer.model.pathLength
import com.writer.model.diagonal
import com.writer.recognition.LineSegmenter
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics

/**
 * Detects and handles ink gestures: strikethrough (delete words),
 * delete line (strikethrough extending to gutter), and insert line
 * (vertical stroke). Extracted from WritingCoordinator to keep
 * gesture logic separate from recognition and scroll orchestration.
 */
class GestureHandler(
    private val documentModel: DocumentModel,
    private val inkCanvas: HandwritingCanvasView,
    private val lineSegmenter: LineSegmenter,
    private val onLinesChanged: (invalidatedLines: Set<Int>) -> Unit,
    private val onBeforeMutation: () -> Unit = {}
) {

    companion object {
        private const val TAG = "GestureHandler"

        // Strikethrough: minimum horizontal span (~8.5 mm, DPI-scaled)
        private val STRIKETHROUGH_MIN_WIDTH get() = ScreenMetrics.dp(54f)
        // Strikethrough: max height-to-width ratio (must be very flat)
        private const val STRIKETHROUGH_MAX_HEIGHT_RATIO = 0.3f
        // Strikethrough: max path-to-diagonal ratio (must be a straight line)
        private const val STRIKETHROUGH_MAX_PATH_RATIO = 1.3f

        // Underline: must start below this fraction of the line
        private const val UNDERLINE_BOTTOM_FRACTION = 0.8f
        // Underline/marker: max path-to-diagonal ratio for simplicity check
        private const val SIMPLICITY_MAX_RATIO = 2f
        // Underline: must span this fraction of text width
        private const val UNDERLINE_MIN_TEXT_COVERAGE = 0.8f

        // Diagram scribble-delete: minimum path-to-diagonal ratio (must be scribble-like)
        private const val SCRIBBLE_MIN_PATH_RATIO = 4.0f
        // Diagram scribble-delete: minimum size of scribble region
        private const val SCRIBBLE_MIN_SIZE = 60f
        // Diagram scribble-delete: minimum number of direction reversals on X axis
        private const val SCRIBBLE_MIN_REVERSALS = 3

        /** Check if a stroke has the shape of a strikethrough: wide, flat, horizontal, and straight. */
        fun isStrikethroughShape(stroke: InkStroke): Boolean {
            if (stroke.points.size < 2) return false
            return stroke.xRange >= STRIKETHROUGH_MIN_WIDTH &&
                stroke.yRange < stroke.xRange * STRIKETHROUGH_MAX_HEIGHT_RATIO &&
                stroke.pathLength <= stroke.diagonal * STRIKETHROUGH_MAX_PATH_RATIO
        }
    }

    /**
     * Try to handle [stroke] as a gesture. Returns true if handled
     * (caller should not add the stroke to the document model).
     */
    fun tryHandle(stroke: InkStroke): Boolean {
        val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
        val diagramArea = documentModel.diagramAreas.find { it.containsLine(strokeLine) }

        if (diagramArea != null) {
            // Inside diagram: only scribble-delete gesture
            if (isScribbleDeleteGesture(stroke)) {
                handleScribbleDelete(stroke, diagramArea)
                return true
            }
            return false
        }

        if (isStrikethroughGesture(stroke)) {
            handleStrikethrough(stroke)
            return true
        }
        return false
    }

    private fun isStrikethroughGesture(stroke: InkStroke): Boolean {
        if (!isStrikethroughShape(stroke)) return false

        val startLineIdx = lineSegmenter.getLineIndex(stroke.points.first().y)
        val endLineIdx = lineSegmenter.getLineIndex(stroke.points.last().y)
        if (startLineIdx != endLineIdx) return false

        // Don't treat heading underlines as strikethroughs
        if (isHeadingUnderline(stroke, startLineIdx)) return false

        return true
    }

    /**
     * Check if a horizontal stroke is a heading underline rather than a strikethrough.
     * A heading underline starts in the bottom 20% of the line and spans at least 80%
     * of the existing text width on that line.
     *
     * Note: StrokeClassifier has a similar underline check with a more lenient
     * threshold (bottom 50%) for recognition filtering. This stricter threshold
     * avoids swallowing legitimate strikethrough gestures.
     */
    private fun isHeadingUnderline(stroke: InkStroke, lineIdx: Int): Boolean {
        val lineTop = lineSegmenter.getLineY(lineIdx)
        val lineSpacing = HandwritingCanvasView.LINE_SPACING

        // Stroke must start in the bottom 20% of the line
        val startY = stroke.points.first().y
        if (startY < lineTop + lineSpacing * UNDERLINE_BOTTOM_FRACTION) return false

        // Must have existing text on this line
        val lineStrokes = lineSegmenter.getStrokesForLine(documentModel.activeStrokes, lineIdx)
        if (lineStrokes.isEmpty()) return false

        // Measure text width from existing strokes
        val textMinX = lineStrokes.minOf { s -> s.minX }
        val textMaxX = lineStrokes.maxOf { s -> s.maxX }
        val textWidth = textMaxX - textMinX
        if (textWidth <= 0f) return false

        // Underline must span at least 80% of text width
        if (stroke.xRange < textWidth * UNDERLINE_MIN_TEXT_COVERAGE) return false

        // Path simplicity check — reject complex strokes
        if (stroke.pathLength > stroke.diagonal * SIMPLICITY_MAX_RATIO) return false

        return true
    }

    /**
     * Detect a scribble-delete gesture for diagram areas.
     * A scribble has high path-length relative to its bounding box diagonal
     * and multiple horizontal direction reversals (back-and-forth motion).
     */
    private fun isScribbleDeleteGesture(stroke: InkStroke): Boolean {
        if (stroke.points.size < 10) return false

        val diag = stroke.diagonal
        if (diag < SCRIBBLE_MIN_SIZE) return false

        val ratio = stroke.pathLength / diag
        if (ratio < SCRIBBLE_MIN_PATH_RATIO) return false

        // Count horizontal direction reversals
        var reversals = 0
        var lastDx = 0f
        for (i in 1 until stroke.points.size) {
            val dx = stroke.points[i].x - stroke.points[i - 1].x
            if (dx * lastDx < 0f) reversals++
            if (dx != 0f) lastDx = dx
        }

        return reversals >= SCRIBBLE_MIN_REVERSALS
    }

    /**
     * Handle scribble-delete: remove all strokes within the scribble's bounding box
     * that are inside the given diagram area.
     */
    private fun handleScribbleDelete(gestureStroke: InkStroke, diagramArea: DiagramArea) {
        val scribbleMinX = gestureStroke.minX
        val scribbleMaxX = gestureStroke.maxX
        val scribbleMinY = gestureStroke.minY
        val scribbleMaxY = gestureStroke.maxY

        // Find all strokes in the diagram area that overlap with the scribble region
        val diagramStrokes = documentModel.activeStrokes.filter { stroke ->
            if (stroke.strokeId == gestureStroke.strokeId) return@filter false
            val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
            if (!diagramArea.containsLine(strokeLine)) return@filter false

            // Check bounding box overlap
            stroke.maxX >= scribbleMinX && stroke.minX <= scribbleMaxX &&
                stroke.maxY >= scribbleMinY && stroke.minY <= scribbleMaxY
        }

        if (diagramStrokes.isEmpty()) {
            // No strokes to delete — just remove the scribble gesture itself
            refreshCanvas { inkCanvas.removeStrokes(setOf(gestureStroke.strokeId)) }
            return
        }

        onBeforeMutation()

        val idsToRemove = diagramStrokes.map { it.strokeId }.toMutableSet()
        idsToRemove.add(gestureStroke.strokeId)

        documentModel.activeStrokes.removeAll { it.strokeId in idsToRemove }
        refreshCanvas { inkCanvas.removeStrokes(idsToRemove) }

        Log.i(TAG, "Scribble-delete in diagram area: removed ${diagramStrokes.size} strokes")
    }

    private fun handleStrikethrough(gestureStroke: InkStroke) {
        val centroidY = gestureStroke.points.map { it.y }.average().toFloat()
        val lineIdx = lineSegmenter.getLineIndex(centroidY)

        val gestureMinX = gestureStroke.minX
        val gestureMaxX = gestureStroke.maxX

        val lineStrokes = lineSegmenter.getStrokesForLine(documentModel.activeStrokes, lineIdx)
        val overlapping = lineStrokes.filter { stroke ->
            stroke.maxX >= gestureMinX && stroke.minX <= gestureMaxX
        }

        if (overlapping.isEmpty()) {
            refreshCanvas { inkCanvas.removeStrokes(setOf(gestureStroke.strokeId)) }
            return
        }

        onBeforeMutation()

        val idsToRemove = overlapping.map { it.strokeId }.toMutableSet()
        idsToRemove.add(gestureStroke.strokeId)

        documentModel.activeStrokes.removeAll { it.strokeId in idsToRemove }
        refreshCanvas { inkCanvas.removeStrokes(idsToRemove) }

        onLinesChanged(setOf(lineIdx))

        Log.i(TAG, "Strikethrough on line $lineIdx: removed ${overlapping.size} strokes")
    }

    private fun refreshCanvas(operations: () -> Unit) {
        inkCanvas.pauseRawDrawing()
        operations()
        inkCanvas.drawToSurface()
        inkCanvas.resumeRawDrawing()
    }
}
