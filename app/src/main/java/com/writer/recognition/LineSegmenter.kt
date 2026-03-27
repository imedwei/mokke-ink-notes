package com.writer.recognition

import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.model.minX
import com.writer.view.HandwritingCanvasView

/**
 * Assigns strokes to ruled line positions on the canvas.
 *
 * Uses the stroke's vertical centroid (midpoint of Y bounding box) to determine
 * line assignment. This is more robust than using the first point because:
 * - Cursive strokes may start above the baseline (e.g. "t" crossbar)
 * - Descenders hang below but the letter body is on the line above
 * - Words written at slightly different heights are assigned to the same line
 */
class LineSegmenter {

    /** Returns the line index for a given Y coordinate in document space. */
    fun getLineIndex(y: Float): Int {
        return ((y - HandwritingCanvasView.TOP_MARGIN) / HandwritingCanvasView.LINE_SPACING)
            .toInt()
            .coerceAtLeast(0)
    }

    /**
     * Returns the line index for a stroke, based on the vertical center of mass
     * of all sample points.
     *
     * Unlike the bounding box centroid ((minY + maxY) / 2), the center of mass
     * naturally weights the x-height zone where most points concentrate. A 'p'
     * has many points in the letter body and fewer in the descender, so the
     * center of mass stays on the correct line. This is the approach recommended
     * by handwriting recognition literature for stroke-to-line assignment.
     */
    fun getStrokeLineIndex(stroke: InkStroke): Int {
        if (stroke.points.isEmpty()) return 0
        // Center of mass of all Y-coordinates — weighted toward the letter body
        val yMean = stroke.points.sumOf { it.y.toDouble() }.toFloat() / stroke.points.size
        return getLineIndex(yMean)
    }

    /** Returns the document-space Y for the top of a line index. */
    fun getLineY(lineIndex: Int): Float {
        return HandwritingCanvasView.TOP_MARGIN + lineIndex * HandwritingCanvasView.LINE_SPACING
    }

    /** Group strokes by their line index (using Y centroid). */
    fun groupByLine(strokes: List<InkStroke>): Map<Int, List<InkStroke>> {
        val result = mutableMapOf<Int, MutableList<InkStroke>>()
        for (stroke in strokes) {
            val lineIdx = getStrokeLineIndex(stroke)
            result.getOrPut(lineIdx) { mutableListOf() }.add(stroke)
        }
        return result
    }

    /** Get strokes assigned to a specific line index. */
    fun getStrokesForLine(strokes: List<InkStroke>, lineIndex: Int): List<InkStroke> {
        return strokes.filter { getStrokeLineIndex(it) == lineIndex }
    }

    /** Get the bottommost occupied line index, or -1 if no strokes. */
    fun getBottomOccupiedLine(strokes: List<InkStroke>): Int {
        if (strokes.isEmpty()) return -1
        return strokes.maxOf { getStrokeLineIndex(it) }
    }

    /** Build an InkLine from strokes for a given line index, sorted left-to-right. */
    fun buildInkLine(strokes: List<InkStroke>, lineIndex: Int): InkLine {
        val lineStrokes = getStrokesForLine(strokes, lineIndex)
            .sortedBy { it.minX }
        return InkLine.build(lineStrokes)
    }
}
