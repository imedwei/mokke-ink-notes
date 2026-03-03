package com.writer.ui.writing

import com.writer.model.InkStroke

class UndoManager(private val maxHistory: Int = 50) {

    data class Snapshot(
        val strokes: List<InkStroke>,
        val scrollOffsetY: Float,
        val lineTextCache: Map<Int, String>
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
}
