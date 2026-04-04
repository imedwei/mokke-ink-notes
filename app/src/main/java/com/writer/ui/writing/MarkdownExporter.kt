package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.recognition.LineSegmenter
import com.writer.storage.SvgExporter
import com.writer.view.HandwritingCanvasView

/**
 * Pure-logic markdown export: builds markdown blocks from recognized text,
 * paragraph structure, and diagram SVGs.
 *
 * Extracted from [WritingCoordinator] for testability.
 */
object MarkdownExporter {

    /**
     * Build markdown blocks from recognized text, paragraph classification, and diagrams.
     *
     * @param isDiagramLine returns true if a line index is inside a diagram area
     * @param svgEncoder converts diagram strokes into a markdown image reference
     *                   (default: SVG → base64 data URI via [SvgExporter])
     */
    fun buildBlocks(
        lineTextCache: Map<Int, String>,
        activeStrokes: List<InkStroke>,
        diagramAreas: List<DiagramArea>,
        writingWidth: Float,
        paragraphBuilder: ParagraphBuilder,
        lineSegmenter: LineSegmenter,
        isDiagramLine: (Int) -> Boolean,
        svgEncoder: (strokes: List<InkStroke>, width: Float, height: Float, offsetY: Float) -> String
            = { strokes, width, height, offsetY ->
                val svg = SvgExporter.strokesToSvg(strokes, width, height, offsetX = 0f, offsetY = offsetY)
                "![diagram](${SvgExporter.toBase64DataUri(svg)})"
            }
    ): List<WritingCoordinator.MdBlock> {
        if (lineTextCache.isEmpty() && diagramAreas.isEmpty()) return emptyList()

        val strokesByLine = lineSegmenter.groupByLine(activeStrokes)

        val classifiedLines = lineTextCache.keys.sorted()
            .filter { !isDiagramLine(it) }
            .mapNotNull { lineIdx ->
                paragraphBuilder.classifyLine(
                    lineIdx, lineTextCache[lineIdx],
                    strokesByLine[lineIdx], writingWidth, strokesByLine[lineIdx + 1]
                )
            }

        val grouped = paragraphBuilder.groupIntoParagraphs(
            classifiedLines, strokesByLine, writingWidth, diagramAreas
        )

        val blocks = mutableListOf<WritingCoordinator.MdBlock>()

        for (group in grouped) {
            val joined = group.joinToString(" ") { it.text }
            val first = group.first()
            val last = group.last()
            val prefix = if (first.isHeading) "## " else if (first.isList) "- " else ""
            blocks.add(WritingCoordinator.MdBlock(first.lineIndex, last.lineIndex, "$prefix$joined"))
        }

        // Insert diagram blocks at correct positions
        for (area in diagramAreas.sortedBy { it.startLineIndex }) {
            val diagramStrokes = activeStrokes.filter { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                area.containsLine(strokeLine)
            }
            if (diagramStrokes.isEmpty()) continue

            val areaTop = lineSegmenter.getLineY(area.startLineIndex)
            val areaHeight = area.heightInLines * HandwritingCanvasView.LINE_SPACING
            val text = svgEncoder(diagramStrokes, writingWidth, areaHeight, areaTop)
            blocks.add(WritingCoordinator.MdBlock(area.startLineIndex, area.endLineIndex, text))
        }

        return blocks.sortedBy { it.startLine }
    }

    /**
     * Generate markdown with interleaved cue blockquotes.
     * Cue blocks are appended after each main content paragraph
     * that overlaps their line range.
     */
    fun buildText(
        mainBlocks: List<WritingCoordinator.MdBlock>,
        cueBlocks: List<WritingCoordinator.MdBlock>
    ): String {
        if (mainBlocks.isEmpty()) return ""

        val result = StringBuilder()
        for (block in mainBlocks) {
            if (result.isNotEmpty()) result.append("\n\n")
            result.append(block.text)

            val overlapping = cueBlocks.filter { cue ->
                cue.startLine <= block.endLine && cue.endLine >= block.startLine
            }
            if (overlapping.isNotEmpty()) {
                result.append("\n\n")
                if (overlapping.size == 1) {
                    result.append("> **Cue:** ${overlapping[0].text}")
                } else {
                    result.append("> **Cue:**")
                    for (cue in overlapping) {
                        result.append("\n> ${cue.text}")
                    }
                }
            }
        }

        return result.toString()
    }
}
