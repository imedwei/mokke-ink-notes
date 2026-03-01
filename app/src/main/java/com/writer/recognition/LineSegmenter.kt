package com.writer.recognition

import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.view.HandwritingCanvasView

/**
 * Assigns strokes to ruled line positions on the canvas.
 * Uses the stroke's starting point Y to determine line assignment,
 * so descenders (g, y, p, etc.) stay on the correct line.
 */
class LineSegmenter {

    /** Returns the line index for a given Y coordinate in document space. */
    fun getLineIndex(y: Float): Int {
        return ((y - HandwritingCanvasView.TOP_MARGIN) / HandwritingCanvasView.LINE_SPACING)
            .toInt()
            .coerceAtLeast(0)
    }

    /** Returns the line index for a stroke, based on its starting point. */
    fun getStrokeLineIndex(stroke: InkStroke): Int {
        return getLineIndex(stroke.points.first().y)
    }

    /** Returns the document-space Y for the top of a line index. */
    fun getLineY(lineIndex: Int): Float {
        return HandwritingCanvasView.TOP_MARGIN + lineIndex * HandwritingCanvasView.LINE_SPACING
    }

    /** Group strokes by their line index (using start point Y). */
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
            .sortedBy { stroke -> stroke.points.minOf { it.x } }
        val line = InkLine(strokes = lineStrokes.toMutableList())
        line.computeBoundingBox()
        return line
    }
}
