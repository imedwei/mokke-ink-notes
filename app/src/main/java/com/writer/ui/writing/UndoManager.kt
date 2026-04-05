package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.TextBlock

class UndoManager(private val maxHistory: Int = 50) {

    data class Snapshot(
        val strokes: List<InkStroke>,
        val scrollOffsetY: Float,
        val lineTextCache: Map<Int, String>,
        val diagramAreas: List<DiagramArea> = emptyList(),
        val textBlocks: List<TextBlock> = emptyList()
    )

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    fun saveSnapshot(snapshot: Snapshot) {
        undoStack.addLast(snapshot)
        if (undoStack.size > maxHistory) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    fun undo(current: Snapshot): Snapshot? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current)
        return undoStack.removeLast()
    }

    fun redo(current: Snapshot): Snapshot? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(current)
        return redoStack.removeLast()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    // --- Scrub API for gesture-based undo/redo ---

    private var scrubTimeline: List<Snapshot>? = null
    private var scrubStartIndex: Int = 0
    private var scrubCurrentIndex: Int = 0

    fun beginScrub(current: Snapshot) {
        // Timeline: [oldest_undo, ..., newest_undo, current, nearest_redo, ..., furthest_redo]
        val timeline = undoStack.toList() + current + redoStack.reversed()
        scrubTimeline = timeline
        scrubStartIndex = undoStack.size  // index of current in the timeline
        scrubCurrentIndex = scrubStartIndex
    }

    fun scrubTo(absoluteOffset: Int): Snapshot? {
        val timeline = scrubTimeline ?: return null
        val targetIndex = (scrubStartIndex + absoluteOffset).coerceIn(0, timeline.lastIndex)
        if (targetIndex == scrubCurrentIndex) return null
        scrubCurrentIndex = targetIndex
        return timeline[targetIndex]
    }

    fun endScrub() {
        val timeline = scrubTimeline ?: return
        undoStack.clear()
        for (i in 0 until scrubCurrentIndex) {
            undoStack.addLast(timeline[i])
        }
        redoStack.clear()
        for (i in timeline.lastIndex downTo scrubCurrentIndex + 1) {
            redoStack.addLast(timeline[i])
        }
        scrubTimeline = null
    }
}
