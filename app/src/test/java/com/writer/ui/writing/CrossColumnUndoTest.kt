package com.writer.ui.writing

import com.writer.model.ColumnModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for cross-column space insertion undo.
 * When space is inserted in one column via syncSpaceChange, undoing should
 * restore the column to its pre-shift state.
 */
class CrossColumnUndoTest {

    private val lineSegmenter = LineSegmenter()
    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun strokeAtLine(lineIndex: Int, id: String = "s$lineIndex"): InkStroke {
        val y = tm + lineIndex * ls + ls / 2
        return InkStroke(
            strokeId = id,
            points = listOf(
                StrokePoint(10f, y, 0.5f, 0L),
                StrokePoint(100f, y, 0.5f, 100L)
            )
        )
    }

    @Test
    fun syncSpaceInsert_shiftsStrokes() {
        val column = ColumnModel()
        column.activeStrokes.add(strokeAtLine(0))
        column.activeStrokes.add(strokeAtLine(3))

        SpaceInsertMode.insertSpace(column, lineSegmenter, anchorLine = 1, linesToInsert = 2)

        // Stroke at line 0 should stay, stroke at line 3 should shift to line 5
        val line0 = lineSegmenter.getStrokeLineIndex(column.activeStrokes[0])
        val line1 = lineSegmenter.getStrokeLineIndex(column.activeStrokes[1])
        assertEquals(0, line0)
        assertEquals(5, line1)
    }

    @Test
    fun syncSpaceInsert_undoRestoresOriginalPositions() {
        val column = ColumnModel()
        column.activeStrokes.add(strokeAtLine(0))
        column.activeStrokes.add(strokeAtLine(3))

        val undoManager = UndoManager()

        // Save pre-shift snapshot
        val preSnapshot = UndoManager.Snapshot(
            strokes = column.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap(),
            diagramAreas = column.diagramAreas.toList()
        )
        undoManager.saveSnapshot(preSnapshot)

        // Apply shift
        SpaceInsertMode.insertSpace(column, lineSegmenter, anchorLine = 1, linesToInsert = 2)

        // Verify shifted
        assertEquals(5, lineSegmenter.getStrokeLineIndex(column.activeStrokes[1]))

        // Undo
        val currentSnapshot = UndoManager.Snapshot(
            strokes = column.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap(),
            diagramAreas = column.diagramAreas.toList()
        )
        val restored = undoManager.undo(currentSnapshot)!!

        // Apply restored snapshot
        column.activeStrokes.clear()
        column.activeStrokes.addAll(restored.strokes)

        // Verify restored to original positions
        assertEquals(0, lineSegmenter.getStrokeLineIndex(column.activeStrokes[0]))
        assertEquals(3, lineSegmenter.getStrokeLineIndex(column.activeStrokes[1]))
    }

    @Test
    fun syncSpaceRemove_undoRestoresOriginalPositions() {
        val column = ColumnModel()
        column.activeStrokes.add(strokeAtLine(0))
        // Leave lines 1-2 empty
        column.activeStrokes.add(strokeAtLine(3))

        val undoManager = UndoManager()

        // Save pre-shift snapshot
        val preSnapshot = UndoManager.Snapshot(
            strokes = column.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap(),
            diagramAreas = column.diagramAreas.toList()
        )
        undoManager.saveSnapshot(preSnapshot)

        // Remove space
        val removed = SpaceInsertMode.removeSpace(column, lineSegmenter, anchorLine = 3, linesToRemove = 2)
        assertEquals(2, removed)

        // Stroke at line 3 should now be at line 1
        assertEquals(1, lineSegmenter.getStrokeLineIndex(column.activeStrokes[1]))

        // Undo
        val currentSnapshot = UndoManager.Snapshot(
            strokes = column.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap(),
            diagramAreas = column.diagramAreas.toList()
        )
        val restored = undoManager.undo(currentSnapshot)!!
        column.activeStrokes.clear()
        column.activeStrokes.addAll(restored.strokes)

        // Verify restored to original positions
        assertEquals(3, lineSegmenter.getStrokeLineIndex(column.activeStrokes[1]))
    }
}
