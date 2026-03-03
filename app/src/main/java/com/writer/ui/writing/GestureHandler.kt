package com.writer.ui.writing

import android.util.Log
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
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
        if (stroke.points.size < 2) return false

        val minX = stroke.points.minOf { it.x }
        val maxX = stroke.points.maxOf { it.x }
        val minY = stroke.points.minOf { it.y }
        val maxY = stroke.points.maxOf { it.y }

        val xRange = maxX - minX
        val yRange = maxY - minY

        if (xRange < 100f) return false
        if (yRange > xRange * 0.3f) return false

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
     */
    private fun isHeadingUnderline(stroke: InkStroke, lineIdx: Int): Boolean {
        val lineTop = lineSegmenter.getLineY(lineIdx)
        val lineSpacing = HandwritingCanvasView.LINE_SPACING

        // Stroke must start in the bottom 20% of the line
        val startY = stroke.points.first().y
        if (startY < lineTop + lineSpacing * 0.8f) return false

        // Must have existing text on this line
        val lineStrokes = lineSegmenter.getStrokesForLine(documentModel.activeStrokes, lineIdx)
        if (lineStrokes.isEmpty()) return false

        // Measure text width from existing strokes
        val textMinX = lineStrokes.minOf { s -> s.points.minOf { it.x } }
        val textMaxX = lineStrokes.maxOf { s -> s.points.maxOf { it.x } }
        val textWidth = textMaxX - textMinX
        if (textWidth <= 0f) return false

        // Underline must span at least 80% of text width
        val strokeWidth = stroke.points.maxOf { it.x } - stroke.points.minOf { it.x }
        if (strokeWidth < textWidth * 0.8f) return false

        // Path simplicity check — reject complex strokes
        val strokeHeight = stroke.points.maxOf { it.y } - stroke.points.minOf { it.y }
        val pathLength = stroke.points.zipWithNext { a, b ->
            val dx = b.x - a.x; val dy = b.y - a.y
            kotlin.math.sqrt(dx * dx + dy * dy)
        }.sum()
        val diagonal = kotlin.math.sqrt(strokeWidth * strokeWidth + strokeHeight * strokeHeight)
        if (pathLength > diagonal * 2f) return false

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

        val minX = stroke.points.minOf { it.x }
        val maxX = stroke.points.maxOf { it.x }
        val minY = stroke.points.minOf { it.y }
        val maxY = stroke.points.maxOf { it.y }

        val xRange = maxX - minX
        val yRange = maxY - minY

        // Must be compact and roughly square-ish
        if (xRange < 40f || yRange < 40f) return false
        if (xRange > yRange * 3f || yRange > xRange * 3f) return false

        // Must fit within one line
        val startLineIdx = lineSegmenter.getLineIndex(minY)
        val endLineIdx = lineSegmenter.getLineIndex(maxY)
        if (startLineIdx != endLineIdx) return false

        val corners = findCorners(stroke.points, minAngle = 60f)
        if (corners.size < 2) return false

        // Use the first two corners to define 4 key points
        val p0 = stroke.points.first()
        val p1 = stroke.points[corners[0]]
        val p2 = stroke.points[corners[1]]
        val p3 = stroke.points.last()

        val margin = 0.15f

        // Pattern A (left-to-right): top-right → bottom-left → top-left → bottom-right
        val patternA = p0.y < p1.y - yRange * margin &&
            p0.x > p1.x + xRange * margin &&
            p2.y < p1.y - yRange * margin &&
            p3.y > p2.y + yRange * margin &&
            p3.x > p2.x + xRange * margin

        // Pattern B (right-to-left mirror): top-left → bottom-right → top-right → bottom-left
        val patternB = p0.y < p1.y - yRange * margin &&
            p0.x < p1.x - xRange * margin &&
            p2.y < p1.y - yRange * margin &&
            p3.y > p2.y + yRange * margin &&
            p3.x < p2.x - xRange * margin

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

        val minX = stroke.points.minOf { it.x }
        val maxX = stroke.points.maxOf { it.x }
        val minY = stroke.points.minOf { it.y }
        val maxY = stroke.points.maxOf { it.y }

        val xRange = maxX - minX
        val yRange = maxY - minY

        if (yRange < HandwritingCanvasView.LINE_SPACING * 1.5f) return false
        if (xRange > yRange * 0.3f) return false

        return true
    }

    private fun handleStrikethrough(gestureStroke: InkStroke) {
        val centroidY = gestureStroke.points.map { it.y }.average().toFloat()
        val lineIdx = lineSegmenter.getLineIndex(centroidY)

        val gestureMinX = gestureStroke.points.minOf { it.x }
        val gestureMaxX = gestureStroke.points.maxOf { it.x }

        val lineStrokes = lineSegmenter.getStrokesForLine(documentModel.activeStrokes, lineIdx)
        val overlapping = lineStrokes.filter { stroke ->
            val strokeMinX = stroke.points.minOf { it.x }
            val strokeMaxX = stroke.points.maxOf { it.x }
            strokeMaxX >= gestureMinX && strokeMinX <= gestureMaxX
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
