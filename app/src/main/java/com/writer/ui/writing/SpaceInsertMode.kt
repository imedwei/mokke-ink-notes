package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.ColumnModel
import com.writer.model.TextBlock
import com.writer.model.shiftY
import com.writer.recognition.LineSegmenter
import com.writer.view.HandwritingCanvasView

/**
 * Handles inserting and removing blank vertical space on the canvas.
 *
 * Insert: shifts all strokes and diagram areas at or below [anchorLine] down.
 * Remove: shifts content up, but only removes truly empty lines — blocks at content.
 *
 * See docs/undo-redo-design.md for the design rationale.
 */
object SpaceInsertMode {

    /**
     * The line from which shifting effectively starts.
     * When [anchorLine] is inside a diagram, returns the diagram's start line
     * so the entire diagram moves as a unit. Otherwise returns [anchorLine].
     */
    fun effectiveShiftLine(anchorLine: Int, diagramAreas: List<DiagramArea>, textBlocks: List<TextBlock> = emptyList()): Int {
        val containingArea = diagramAreas.find { it.containsLine(anchorLine) }
        if (containingArea != null) return containingArea.startLineIndex
        val containingBlock = textBlocks.find { it.containsLine(anchorLine) }
        if (containingBlock != null) return containingBlock.startLineIndex
        return anchorLine
    }

    /**
     * Compute the barrier line index — the line containing blocking content
     * that prevents further upward drag. Returns -1 if no barrier exists.
     */
    fun barrierLine(anchorLine: Int, diagramAreas: List<DiagramArea>, emptyAbove: Int, textBlocks: List<TextBlock> = emptyList()): Int {
        val shiftFrom = effectiveShiftLine(anchorLine, diagramAreas, textBlocks)
        val barrier = shiftFrom - emptyAbove - 1
        return if (barrier >= 0) barrier else -1
    }

    /**
     * Insert [linesToInsert] blank lines at [anchorLine].
     * All strokes and diagram areas at or below [anchorLine] shift down.
     */
    fun insertSpace(
        columnModel: ColumnModel,
        lineSegmenter: LineSegmenter,
        anchorLine: Int,
        linesToInsert: Int
    ) {
        if (linesToInsert <= 0) return
        val shiftPx = linesToInsert * HandwritingCanvasView.LINE_SPACING

        // If anchor is inside a diagram or text block, shift from the start edge
        val shiftFrom = effectiveShiftLine(anchorLine, columnModel.diagramAreas, columnModel.textBlocks)

        // Shift strokes at or below shiftFrom
        val shifted = columnModel.activeStrokes.map { stroke ->
            if (lineSegmenter.getStrokeLineIndex(stroke) >= shiftFrom) {
                stroke.shiftY(shiftPx)
            } else {
                stroke
            }
        }
        columnModel.activeStrokes.clear()
        columnModel.activeStrokes.addAll(shifted)

        // Shift diagram areas at or below the anchor line (or containing it) down as a unit
        val shiftedAreas = columnModel.diagramAreas.map { area ->
            if (area.startLineIndex >= anchorLine || area.containsLine(anchorLine)) {
                area.copy(startLineIndex = area.startLineIndex + linesToInsert)
            } else {
                area
            }
        }
        columnModel.diagramAreas.clear()
        columnModel.diagramAreas.addAll(shiftedAreas)

        // Shift text blocks at or below the anchor line down
        val shiftedBlocks = columnModel.textBlocks.map { block ->
            if (block.startLineIndex >= anchorLine || block.containsLine(anchorLine)) {
                block.copy(startLineIndex = block.startLineIndex + linesToInsert)
            } else {
                block
            }
        }
        columnModel.textBlocks.clear()
        columnModel.textBlocks.addAll(shiftedBlocks)
    }

    /**
     * Count consecutive empty lines immediately above [anchorLine].
     * Stops at content or diagram areas. Used for clamping the drag preview.
     */
    fun countEmptyLinesAbove(
        columnModel: ColumnModel,
        lineSegmenter: LineSegmenter,
        anchorLine: Int,
        maxCount: Int = Int.MAX_VALUE
    ): Int {
        if (anchorLine <= 0) return 0
        val occupiedLines = columnModel.activeStrokes
            .map { lineSegmenter.getStrokeLineIndex(it) }
            .toSet()
        var count = 0
        for (i in 1..maxCount) {
            val checkLine = anchorLine - i
            if (checkLine < 0) break
            if (checkLine in occupiedLines) break
            if (columnModel.diagramAreas.any { it.containsLine(checkLine) }) break
            if (columnModel.textBlocks.any { it.containsLine(checkLine) }) break
            count++
        }
        return count
    }

    /**
     * Remove up to [linesToRemove] blank lines immediately above [anchorLine].
     * Scans upward from anchorLine-1, counting consecutive empty lines.
     * Stops at the first line that contains strokes — never destroys content.
     * Shifts content at/below [anchorLine] up to close the gap.
     *
     * @return the number of lines actually removed
     */
    fun removeSpace(
        columnModel: ColumnModel,
        lineSegmenter: LineSegmenter,
        anchorLine: Int,
        linesToRemove: Int
    ): Int {
        if (linesToRemove <= 0 || anchorLine <= 0) return 0

        // If anchor is inside a diagram or text block, scan from its top edge
        val scanFrom = effectiveShiftLine(anchorLine, columnModel.diagramAreas, columnModel.textBlocks)

        if (scanFrom <= 0) return 0

        // Find which lines have strokes
        val occupiedLines = columnModel.activeStrokes
            .map { lineSegmenter.getStrokeLineIndex(it) }
            .toSet()

        // Count consecutive empty lines above scanFrom (scanning upward)
        var emptyCount = 0
        for (i in 1..linesToRemove) {
            val checkLine = scanFrom - i
            if (checkLine < 0) break
            if (checkLine in occupiedLines) break
            if (columnModel.diagramAreas.any { it.containsLine(checkLine) }) break
            if (columnModel.textBlocks.any { it.containsLine(checkLine) }) break
            emptyCount++
        }

        if (emptyCount == 0) return 0

        val shiftPx = emptyCount * HandwritingCanvasView.LINE_SPACING

        // Shift strokes at/below scanFrom up (includes strokes inside containing diagram)
        val shifted = columnModel.activeStrokes.map { stroke ->
            if (lineSegmenter.getStrokeLineIndex(stroke) >= scanFrom) {
                stroke.shiftY(-shiftPx)
            } else {
                stroke
            }
        }
        columnModel.activeStrokes.clear()
        columnModel.activeStrokes.addAll(shifted)

        // Shift diagram areas at/below scanFrom up.
        // Since scanFrom == containingArea.startLineIndex when anchor is inside a diagram,
        // the >= check correctly includes the containing diagram itself.
        val shiftedAreas = columnModel.diagramAreas.map { area ->
            if (area.startLineIndex >= scanFrom) {
                area.copy(startLineIndex = area.startLineIndex - emptyCount)
            } else {
                area
            }
        }
        columnModel.diagramAreas.clear()
        columnModel.diagramAreas.addAll(shiftedAreas)

        // Shift text blocks at/below scanFrom up
        val shiftedBlocks = columnModel.textBlocks.map { block ->
            if (block.startLineIndex >= scanFrom) {
                block.copy(startLineIndex = block.startLineIndex - emptyCount)
            } else {
                block
            }
        }
        columnModel.textBlocks.clear()
        columnModel.textBlocks.addAll(shiftedBlocks)

        return emptyCount
    }
}
