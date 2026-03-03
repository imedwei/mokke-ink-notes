package com.writer.ui.writing

import android.util.Log
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
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

        // Strikethrough: minimum horizontal span to count as a strikethrough
        private const val STRIKETHROUGH_MIN_WIDTH = 100f
        // Strikethrough: max height-to-width ratio (must be very flat)
        private const val STRIKETHROUGH_MAX_HEIGHT_RATIO = 0.3f

        // Delete line (X gesture): minimum size in either dimension
        private const val DELETE_GESTURE_MIN_SIZE = 40f
        // Delete line: max aspect ratio (must be roughly square)
        private const val DELETE_GESTURE_MAX_ASPECT = 3f
        // Delete line: corner detection minimum angle (degrees)
        private const val DELETE_GESTURE_CORNER_ANGLE = 60f
        // Delete line: margin for pattern matching (fraction of range)
        private const val DELETE_GESTURE_MARGIN = 0.15f

        // Insert line: minimum vertical span (as fraction of line spacing)
        private const val INSERT_LINE_MIN_SPANS = 1.5f

        // Underline: must start below this fraction of the line
        private const val UNDERLINE_BOTTOM_FRACTION = 0.8f
        // Underline/marker: max path-to-diagonal ratio for simplicity check
        private const val SIMPLICITY_MAX_RATIO = 2f
        // Underline: must span this fraction of text width
        private const val UNDERLINE_MIN_TEXT_COVERAGE = 0.8f

        /** Check if a stroke has the shape of a strikethrough: wide, flat, horizontal. */
        fun isStrikethroughShape(stroke: InkStroke): Boolean {
            if (stroke.points.size < 2) return false
            return stroke.xRange >= STRIKETHROUGH_MIN_WIDTH &&
                stroke.yRange < stroke.xRange * STRIKETHROUGH_MAX_HEIGHT_RATIO
        }
    }

    /**
     * Try to handle [stroke] as a gesture. Returns true if handled
     * (caller should not add the stroke to the document model).
     */
    fun tryHandle(stroke: InkStroke): Boolean {
        if (isDeleteLineGesture(stroke)) {
            handleDeleteLine(stroke)
            return true
        }
        if (isStrikethroughGesture(stroke)) {
            handleStrikethrough(stroke)
            return true
        }
        if (isVerticalLineGesture(stroke)) {
            handleInsertLine(stroke)
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
     * Detect an X-with-left-side gesture for line deletion.
     * The stroke traces: top-right → bottom-left → top-left → bottom-right.
     *
     * We find corners (sharp direction changes) in the stroke, then check
     * that the 4 key points (start, corner1, corner2, end) match the
     * expected spatial pattern.
     */
    private fun isDeleteLineGesture(stroke: InkStroke): Boolean {
        if (stroke.points.size < 8) return false

        // Must be compact and roughly square-ish
        if (stroke.xRange < DELETE_GESTURE_MIN_SIZE || stroke.yRange < DELETE_GESTURE_MIN_SIZE) return false
        if (stroke.xRange > stroke.yRange * DELETE_GESTURE_MAX_ASPECT || stroke.yRange > stroke.xRange * DELETE_GESTURE_MAX_ASPECT) return false

        // Must fit within one line
        val startLineIdx = lineSegmenter.getLineIndex(stroke.minY)
        val endLineIdx = lineSegmenter.getLineIndex(stroke.maxY)
        if (startLineIdx != endLineIdx) return false

        val corners = findCorners(stroke.points, minAngle = DELETE_GESTURE_CORNER_ANGLE)
        if (corners.size < 2) return false

        // Use the first two corners to define 4 key points
        val p0 = stroke.points.first()
        val p1 = stroke.points[corners[0]]
        val p2 = stroke.points[corners[1]]
        val p3 = stroke.points.last()

        val margin = DELETE_GESTURE_MARGIN
        val xr = stroke.xRange
        val yr = stroke.yRange

        // Pattern A (left-to-right): top-right → bottom-left → top-left → bottom-right
        val patternA = p0.y < p1.y - yr * margin &&
            p0.x > p1.x + xr * margin &&
            p2.y < p1.y - yr * margin &&
            p3.y > p2.y + yr * margin &&
            p3.x > p2.x + xr * margin

        // Pattern B (right-to-left mirror): top-left → bottom-right → top-right → bottom-left
        val patternB = p0.y < p1.y - yr * margin &&
            p0.x < p1.x - xr * margin &&
            p2.y < p1.y - yr * margin &&
            p3.y > p2.y + yr * margin &&
            p3.x < p2.x - xr * margin

        return patternA || patternB
    }

    /**
     * Find indices of "corner" points where the stroke direction changes
     * sharply. We smooth the stroke first by subsampling, then measure
     * the angle at each point between the incoming and outgoing segments.
     */
    private fun findCorners(points: List<StrokePoint>, minAngle: Float): List<Int> {
        // Use a wider lookback/lookahead window to measure direction change.
        // This avoids missing corners that are rounded over several points.
        val window = (points.size / 6).coerceIn(3, 20)

        if (points.size < window * 2 + 1) return emptyList()

        val cosThreshold = kotlin.math.cos(Math.toRadians(minAngle.toDouble())).toFloat()

        // Compute angle at each point using vectors from -window to curr and curr to +window
        data class AngleAt(val index: Int, val angleDeg: Int, val dot: Float)
        val candidates = mutableListOf<AngleAt>()

        for (i in window until points.size - window) {
            val prev = points[i - window]
            val curr = points[i]
            val next = points[i + window]

            val dx1 = curr.x - prev.x
            val dy1 = curr.y - prev.y
            val dx2 = next.x - curr.x
            val dy2 = next.y - curr.y

            val len1 = kotlin.math.sqrt(dx1 * dx1 + dy1 * dy1)
            val len2 = kotlin.math.sqrt(dx2 * dx2 + dy2 * dy2)
            if (len1 < 1f || len2 < 1f) continue

            val dot = (dx1 * dx2 + dy1 * dy2) / (len1 * len2)

            if (dot < cosThreshold) {
                val angleDeg = Math.toDegrees(kotlin.math.acos(dot.coerceIn(-1f, 1f)).toDouble()).toInt()
                candidates.add(AngleAt(i, angleDeg, dot))
            }
        }

        // Merge nearby candidates — keep the sharpest angle within each cluster
        val corners = mutableListOf<AngleAt>()
        val minSeparation = window * 2
        for (c in candidates) {
            val last = corners.lastOrNull()
            if (last != null && c.index - last.index < minSeparation) {
                // Keep the sharper angle (higher angleDeg)
                if (c.angleDeg > last.angleDeg) {
                    corners[corners.lastIndex] = c
                }
            } else {
                corners.add(c)
            }
        }

        return corners.map { it.index }
    }

    private fun isVerticalLineGesture(stroke: InkStroke): Boolean {
        if (stroke.points.size < 2) return false
        if (stroke.yRange < HandwritingCanvasView.LINE_SPACING * INSERT_LINE_MIN_SPANS) return false
        if (stroke.xRange > stroke.yRange * STRIKETHROUGH_MAX_HEIGHT_RATIO) return false
        return true
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

    private fun handleDeleteLine(gestureStroke: InkStroke) {
        onBeforeMutation()

        val startY = gestureStroke.points.first().y
        val lineIdx = lineSegmenter.getLineIndex(startY)

        val lineStrokes = lineSegmenter.getStrokesForLine(documentModel.activeStrokes, lineIdx)
        val idsToRemove = lineStrokes.map { it.strokeId }.toMutableSet()
        idsToRemove.add(gestureStroke.strokeId)
        documentModel.activeStrokes.removeAll { it.strokeId in idsToRemove }

        val shiftAmount = -HandwritingCanvasView.LINE_SPACING
        val replacements = mutableMapOf<String, InkStroke>()
        val newActiveStrokes = mutableListOf<InkStroke>()

        for (stroke in documentModel.activeStrokes) {
            val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
            if (strokeLine > lineIdx) {
                val shifted = shiftStroke(stroke, shiftAmount)
                replacements[stroke.strokeId] = shifted
                newActiveStrokes.add(shifted)
            } else {
                newActiveStrokes.add(stroke)
            }
        }

        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(newActiveStrokes)

        refreshCanvas {
            inkCanvas.replaceStrokes(replacements)
            inkCanvas.removeStrokes(idsToRemove)
        }

        // Invalidate this line and all lines that were shifted
        val invalidated = (lineIdx..lineIdx + replacements.size).toSet()
        onLinesChanged(invalidated)

        Log.i(TAG, "Delete line $lineIdx: removed ${lineStrokes.size} strokes, shifted ${replacements.size} up")
    }

    private fun handleInsertLine(gestureStroke: InkStroke) {
        onBeforeMutation()

        val startY = gestureStroke.points.first().y
        val endY = gestureStroke.points.last().y
        val startLineIdx = lineSegmenter.getLineIndex(startY)
        val drawingDownward = endY > startY

        val shiftFromLine = if (drawingDownward) startLineIdx + 1 else startLineIdx
        val shiftAmount = HandwritingCanvasView.LINE_SPACING

        val replacements = mutableMapOf<String, InkStroke>()
        val newActiveStrokes = mutableListOf<InkStroke>()

        for (stroke in documentModel.activeStrokes) {
            val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)

            if (lineIdx >= shiftFromLine) {
                val shifted = shiftStroke(stroke, shiftAmount)
                replacements[stroke.strokeId] = shifted
                newActiveStrokes.add(shifted)
            } else {
                newActiveStrokes.add(stroke)
            }
        }

        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(newActiveStrokes)

        refreshCanvas {
            inkCanvas.replaceStrokes(replacements)
            inkCanvas.removeStrokes(setOf(gestureStroke.strokeId))
        }

        // Invalidate all shifted lines
        val invalidated = (shiftFromLine..shiftFromLine + replacements.size).toSet()
        onLinesChanged(invalidated)

        val direction = if (drawingDownward) "below" else "above"
        Log.i(TAG, "Insert line $direction line $startLineIdx (shifted ${replacements.size} strokes)")
    }

    private fun shiftStroke(stroke: InkStroke, dy: Float): InkStroke {
        val shiftedPoints = stroke.points.map { pt ->
            StrokePoint(pt.x, pt.y + dy, pt.pressure, pt.timestamp)
        }
        return InkStroke(
            strokeId = stroke.strokeId,
            points = shiftedPoints,
            strokeWidth = stroke.strokeWidth
        )
    }

    private fun refreshCanvas(operations: () -> Unit) {
        inkCanvas.pauseRawDrawing()
        operations()
        inkCanvas.drawToSurface()
        inkCanvas.resumeRawDrawing()
    }
}
