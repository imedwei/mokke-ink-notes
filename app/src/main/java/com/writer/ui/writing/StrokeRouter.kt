package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokeType

/**
 * Pure-logic stroke routing: classifies what should happen when a new stroke
 * is completed (diagram routing, sticky zone expansion, or normal text).
 *
 * Extracted from [WritingCoordinator.onStrokeCompleted] for testability.
 * No Android view dependencies.
 */
class StrokeRouter(private val host: Host) {

    interface Host {
        val diagramAreas: List<DiagramArea>
        fun getStrokeLineIndex(stroke: InkStroke): Int
        fun isDiagramLine(lineIndex: Int): Boolean
        fun hasTextStrokesOnLine(lineIndex: Int, excluding: InkStroke): Boolean
        fun isEverHidden(lineIndex: Int): Boolean
    }

    var currentLineIndex: Int = -1
    var highestLineIndex: Int = -1

    enum class Action {
        /** Stroke is on a diagram line — freehand triggers area recognition. */
        DIAGRAM_STROKE,
        /** Geometric stroke adjacent to a diagram — expand the diagram area. */
        STICKY_EXPAND,
        /** Normal text stroke — invalidate cache, maybe trigger recognition. */
        TEXT_STROKE
    }

    data class Result(
        val action: Action,
        val lineIndex: Int,
        /** For STICKY_EXPAND: the original area that was expanded. */
        val expandedFrom: DiagramArea? = null,
        /** For STICKY_EXPAND: the new expanded area. */
        val expandedTo: DiagramArea? = null,
        /** For DIAGRAM_STROKE: the containing diagram area (if freehand). */
        val diagramArea: DiagramArea? = null,
        /** For TEXT_STROKE: true if a previous line should be eagerly recognized. */
        val recognizePreviousLine: Boolean = false,
        /** For TEXT_STROKE: the previous line to recognize (if recognizePreviousLine). */
        val previousLineIndex: Int = -1,
        /** For TEXT_STROKE: true if this line has rendered text and needs re-recognition. */
        val reRecognizeLine: Boolean = false,
    )

    /**
     * Classify a completed stroke and return the routing decision.
     * Updates [currentLineIndex] and [highestLineIndex] as side-effects.
     */
    fun classifyStroke(stroke: InkStroke): Result {
        val lineIdx = host.getStrokeLineIndex(stroke)

        // Diagram strokes
        if (host.isDiagramLine(lineIdx)) {
            val area = if (stroke.strokeType == StrokeType.FREEHAND) {
                host.diagramAreas.find { it.containsLine(lineIdx) }
            } else null
            return Result(Action.DIAGRAM_STROKE, lineIdx, diagramArea = area)
        }

        // Sticky zone expansion
        val adjacentArea = host.diagramAreas.find {
            lineIdx == it.startLineIndex - 1 || lineIdx == it.endLineIndex + 1
        }
        if (adjacentArea != null && stroke.isGeometric &&
            !host.hasTextStrokesOnLine(lineIdx, excluding = stroke)) {
            val newStart = minOf(adjacentArea.startLineIndex, lineIdx)
            val newEnd = maxOf(adjacentArea.endLineIndex, lineIdx)
            val expanded = adjacentArea.copy(
                startLineIndex = newStart,
                heightInLines = newEnd - newStart + 1
            )
            return Result(Action.STICKY_EXPAND, lineIdx,
                expandedFrom = adjacentArea, expandedTo = expanded)
        }

        // Normal text stroke
        var recognizePrev = false
        var prevLine = -1
        if (lineIdx != currentLineIndex) {
            if (currentLineIndex >= 0) {
                recognizePrev = true
                prevLine = currentLineIndex
            }
            currentLineIndex = lineIdx
        }
        if (lineIdx > highestLineIndex) {
            highestLineIndex = lineIdx
        }

        return Result(
            action = Action.TEXT_STROKE,
            lineIndex = lineIdx,
            recognizePreviousLine = recognizePrev,
            previousLineIndex = prevLine,
            reRecognizeLine = host.isEverHidden(lineIdx)
        )
    }
}
