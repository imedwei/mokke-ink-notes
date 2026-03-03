package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.minX
import com.writer.recognition.StrokeClassifier

/**
 * Groups recognized text lines into paragraphs using stroke-based
 * heuristics (indentation, list markers, underlines/headings).
 *
 * Shared between the live text view and markdown export so the
 * paragraph-breaking logic lives in one place.
 */
class ParagraphBuilder(private val strokeClassifier: StrokeClassifier) {

    /** Classified info for a single recognized line. */
    data class LineInfo(
        val lineIndex: Int,
        val text: String,
        val isList: Boolean,
        val isHeading: Boolean
    )

    /**
     * Classify a single line: determine whether it has a list marker or underline.
     * Returns null if the line has no usable text.
     */
    fun classifyLine(
        lineIndex: Int,
        text: String?,
        lineStrokes: List<InkStroke>?,
        writingWidth: Float
    ): LineInfo? {
        if (text.isNullOrEmpty() || text == "[?]") return null

        val isList = lineStrokes != null &&
            strokeClassifier.findListMarkerStrokeId(lineStrokes, writingWidth) != null
        val isHeading = lineStrokes != null &&
            strokeClassifier.findUnderlineStrokeId(lineStrokes, lineIndex) != null

        return LineInfo(lineIndex, text, isList, isHeading)
    }

    /**
     * Group classified lines into paragraphs. A paragraph break occurs on:
     * - a list item
     * - a heading (always its own paragraph)
     * - a line following a heading
     * - an indented line (unless continuing a list item)
     * - a non-indented, non-list line after a list item (back to normal text)
     */
    fun groupIntoParagraphs(
        lines: List<LineInfo>,
        strokesByLine: Map<Int, List<InkStroke>>,
        writingWidth: Float
    ): List<List<LineInfo>> {
        val paragraphs = mutableListOf<List<LineInfo>>()
        var current = mutableListOf<LineInfo>()

        for (line in lines) {
            val lineStrokes = strokesByLine[line.lineIndex]

            if (lineStrokes != null && lineStrokes.isNotEmpty() && current.isNotEmpty()) {
                val leftmostX = lineStrokes.minOf { it.minX }
                val isIndented = leftmostX > writingWidth * StrokeClassifier.INDENT_THRESHOLD
                val prevWasList = current.any { it.isList }
                val prevWasHeading = current.any { it.isHeading }

                val shouldBreak = line.isList || line.isHeading || prevWasHeading ||
                    (isIndented && !prevWasList) ||
                    (prevWasList && !isIndented && !line.isList)

                if (shouldBreak) {
                    paragraphs.add(current)
                    current = mutableListOf()
                }
            }

            current.add(line)
        }

        if (current.isNotEmpty()) {
            paragraphs.add(current)
        }

        return paragraphs
    }
}
