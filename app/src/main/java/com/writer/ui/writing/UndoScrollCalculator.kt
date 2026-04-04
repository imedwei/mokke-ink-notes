package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.minY
import com.writer.model.maxY

/**
 * Pure-logic scroll calculation after undo/redo: determines whether to
 * scroll the canvas so that changed strokes are visible.
 *
 * Extracted from [WritingCoordinator.applySnapshot] for testability.
 */
object UndoScrollCalculator {

    data class ScrollDecision(
        val shouldScroll: Boolean,
        val newScrollOffsetY: Float = 0f
    )

    /**
     * Compute whether the canvas should scroll after an undo/redo operation.
     *
     * If strokes were added or removed and the affected region is off-screen,
     * returns a scroll offset that centers the changed region in the viewport.
     */
    fun computeScroll(
        oldStrokeIds: Set<String>,
        newStrokes: List<InkStroke>,
        currentScrollY: Float,
        viewportHeight: Float
    ): ScrollDecision {
        val newStrokeIds = newStrokes.map { it.strokeId }.toSet()
        val changedIds = (oldStrokeIds - newStrokeIds) + (newStrokeIds - oldStrokeIds)
        if (changedIds.isEmpty()) return ScrollDecision(shouldScroll = false)

        val changedStrokes = newStrokes.filter { it.strokeId in changedIds }
        if (changedStrokes.isEmpty()) return ScrollDecision(shouldScroll = false)

        val minY = changedStrokes.minOf { it.minY }
        val maxY = changedStrokes.maxOf { it.maxY }
        val visibleTop = currentScrollY
        val visibleBottom = currentScrollY + viewportHeight

        if (minY >= visibleTop && maxY <= visibleBottom) {
            return ScrollDecision(shouldScroll = false)
        }

        val centerY = (minY + maxY) / 2f
        val newScroll = (centerY - viewportHeight / 2f).coerceAtLeast(0f)
        return ScrollDecision(shouldScroll = true, newScrollOffsetY = newScroll)
    }
}
