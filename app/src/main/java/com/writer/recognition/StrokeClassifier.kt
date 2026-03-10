package com.writer.recognition

import com.writer.model.InkStroke
import com.writer.model.minX
import com.writer.model.maxX
import com.writer.model.minY
import com.writer.model.xRange
import com.writer.model.yRange
import com.writer.model.pathLength
import com.writer.model.diagonal
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics

/**
 * Classifies strokes as list markers (dash) or underlines (heading),
 * and filters them out before recognition so they don't confuse the
 * handwriting recognizer.
 */
class StrokeClassifier(private val lineSegmenter: LineSegmenter) {

    companion object {
        // A line indented more than ~10% from the left starts a new paragraph
        const val INDENT_THRESHOLD = 0.105f

        // List marker detection (pixel values DPI-scaled via ScreenMetrics)
        private val MARKER_MIN_WIDTH get() = ScreenMetrics.dp(8f)
        private val MARKER_MAX_WIDTH get() = ScreenMetrics.dp(64f)
        private const val MARKER_MAX_HEIGHT_RATIO = 0.4f
        private val MARKER_MIN_GAP get() = ScreenMetrics.dp(11f)

        // Underline detection
        private const val UNDERLINE_MAX_HEIGHT_RATIO = 0.3f
        private val UNDERLINE_MIN_WIDTH get() = ScreenMetrics.dp(54f)
        private const val UNDERLINE_TOP_FRACTION = 0.5f
        private const val UNDERLINE_MIN_TEXT_COVERAGE = 0.8f

        // Simplicity check: max path-to-diagonal ratio
        private const val SIMPLICITY_MAX_RATIO = 2f
    }

    /**
     * Find the list marker stroke on a line, if present: a short, flat horizontal
     * stroke on the far left, separated from the main text by a gap.
     * Returns the marker stroke ID, or null if no marker found.
     */
    fun findListMarkerStrokeId(strokes: List<InkStroke>, writingWidth: Float): String? {
        if (strokes.size < 2) return null

        // Sort strokes by their leftmost x position
        val sorted = strokes.sortedBy { it.minX }
        val first = sorted[0]

        // Must be on the far left
        if (first.minX > writingWidth * INDENT_THRESHOLD) return null

        // Must be short and very flat (a dash, not a letter)
        if (first.xRange < MARKER_MIN_WIDTH || first.xRange > MARKER_MAX_WIDTH) return null
        if (first.yRange > first.xRange * MARKER_MAX_HEIGHT_RATIO) return null

        // A real dash is a simple stroke — reject if the path length is much
        // longer than the bounding box diagonal (indicates curves/loops like letters)
        if (first.pathLength > first.diagonal * SIMPLICITY_MAX_RATIO) return null

        // Must have a gap between the marker and the next stroke
        val gap = sorted[1].minX - first.maxX
        if (gap < MARKER_MIN_GAP) return null

        return first.strokeId
    }

    /**
     * Find an underline stroke on a line, if present: a long horizontal stroke
     * near the bottom of the line that spans at least 80% of the text width.
     * The underline is always assigned to the line where it starts, even if
     * it wiggles into the line below.
     * Returns the underline stroke ID, or null if no underline found.
     */
    fun findUnderlineStrokeId(strokes: List<InkStroke>, lineIndex: Int): String? {
        if (strokes.size < 2) return null

        val lineTop = lineSegmenter.getLineY(lineIndex)

        for (stroke in strokes) {
            // Underline candidate: horizontal, near bottom of line, flat
            if (stroke.yRange > stroke.xRange * UNDERLINE_MAX_HEIGHT_RATIO) continue
            if (stroke.xRange < UNDERLINE_MIN_WIDTH) continue
            if (stroke.minY < lineTop + HandwritingCanvasView.LINE_SPACING * UNDERLINE_TOP_FRACTION) continue

            // Check path simplicity (same as list marker check)
            if (stroke.pathLength > stroke.diagonal * SIMPLICITY_MAX_RATIO) continue

            // Now check that it spans at least 80% of the text on this line
            val textStrokes = strokes.filter { it.strokeId != stroke.strokeId }
            if (textStrokes.isEmpty()) continue
            val textMinX = textStrokes.minOf { s -> s.minX }
            val textMaxX = textStrokes.maxOf { s -> s.maxX }
            val textWidth = textMaxX - textMinX
            if (textWidth <= 0f) continue

            if (stroke.xRange >= textWidth * UNDERLINE_MIN_TEXT_COVERAGE) {
                return stroke.strokeId
            }
        }

        return null
    }

    /**
     * Filter out the list marker and underline strokes before recognition.
     */
    fun filterMarkerStrokes(strokes: List<InkStroke>, writingWidth: Float): List<InkStroke> {
        val excludeIds = mutableSetOf<String>()

        val markerId = findListMarkerStrokeId(strokes, writingWidth)
        if (markerId != null) excludeIds.add(markerId)

        val lineIndex = lineSegmenter.getStrokeLineIndex(strokes.first())
        val underlineId = findUnderlineStrokeId(strokes, lineIndex)
        if (underlineId != null) excludeIds.add(underlineId)

        return if (excludeIds.isEmpty()) strokes else strokes.filter { it.strokeId !in excludeIds }
    }
}
