package com.writer.recognition

import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.view.HandwritingCanvasView

/**
 * Assigns strokes to ruled line positions on the canvas.
 */
class LineSegmenter {

    /** Returns the line index for a given Y coordinate in document space. */
    fun getLineIndex(y: Float): Int {
        return ((y - HandwritingCanvasView.TOP_MARGIN) / HandwritingCanvasView.LINE_SPACING)
            .toInt()
            .coerceAtLeast(0)
    }

    /** Returns the document-space Y for the top of a line index. */
    fun getLineY(lineIndex: Int): Float {
        return HandwritingCanvasView.TOP_MARGIN + lineIndex * HandwritingCanvasView.LINE_SPACING
    }

    /** Group strokes by their line index (using centroid Y). */
    fun groupByLine(strokes: List<InkStroke>): Map<Int, List<InkStroke>> {
        val result = mutableMapOf<Int, MutableList<InkStroke>>()
        for (stroke in strokes) {
            val centroidY = stroke.points.map { it.y }.average().toFloat()
            val lineIdx = getLineIndex(centroidY)
            result.getOrPut(lineIdx) { mutableListOf() }.add(stroke)
        }
        return result
    }

    /** Get strokes assigned to a specific line index. */
    fun getStrokesForLine(strokes: List<InkStroke>, lineIndex: Int): List<InkStroke> {
        return strokes.filter { stroke ->
            val centroidY = stroke.points.map { it.y }.average().toFloat()
            getLineIndex(centroidY) == lineIndex
        }
    }

    /** Get the bottommost occupied line index, or -1 if no strokes. */
    fun getBottomOccupiedLine(strokes: List<InkStroke>): Int {
        if (strokes.isEmpty()) return -1
        return strokes.maxOf { stroke ->
            val centroidY = stroke.points.map { it.y }.average().toFloat()
            getLineIndex(centroidY)
        }
    }

    /** Build an InkLine from strokes for a given line index. */
    fun buildInkLine(strokes: List<InkStroke>, lineIndex: Int): InkLine {
        val lineStrokes = getStrokesForLine(strokes, lineIndex)
        val line = InkLine(strokes = lineStrokes.toMutableList())
        line.computeBoundingBox()
        return line
    }
}
