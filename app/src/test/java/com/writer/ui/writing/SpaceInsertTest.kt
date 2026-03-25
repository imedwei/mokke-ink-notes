package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.recognition.LineSegmenter
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SpaceInsertMode] — insert/remove blank lines on the canvas.
 */
class SpaceInsertTest {

    private lateinit var documentModel: DocumentModel
    private lateinit var lineSegmenter: LineSegmenter
    private lateinit var undoManager: UndoManager
    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        documentModel = DocumentModel()
        lineSegmenter = LineSegmenter()
        undoManager = UndoManager()
    }

    /** Create a stroke centered on the given line index. */
    private fun strokeOnLine(lineIndex: Int, width: Float = 50f): InkStroke {
        val y = tm + lineIndex * ls + ls * 0.4f  // slightly below line top (centroid on line)
        return InkStroke(
            points = listOf(
                StrokePoint(50f, y, 0.5f, 0L),
                StrokePoint(50f + width, y, 0.5f, 1L),
                StrokePoint(50f + width, y + ls * 0.3f, 0.5f, 2L)
            ),
            strokeType = StrokeType.FREEHAND
        )
    }

    private fun saveSnapshot() {
        undoManager.saveSnapshot(UndoManager.Snapshot(
            strokes = documentModel.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap(),
            diagramAreas = documentModel.diagramAreas.toList()
        ))
    }

    // --- Insert space ---

    @Test
    fun `insert 2 lines at boundary shifts content below down`() {
        // Strokes on lines 0, 1, 2
        documentModel.activeStrokes.add(strokeOnLine(0))
        documentModel.activeStrokes.add(strokeOnLine(1))
        documentModel.activeStrokes.add(strokeOnLine(2))

        val originalY0 = documentModel.activeStrokes[0].points[0].y
        val originalY1 = documentModel.activeStrokes[1].points[0].y
        val originalY2 = documentModel.activeStrokes[2].points[0].y

        // Insert 2 lines at boundary between line 0 and line 1
        SpaceInsertMode.insertSpace(documentModel, lineSegmenter, anchorLine = 1, linesToInsert = 2)

        // Line 0 should not move
        assertEquals(originalY0, documentModel.activeStrokes[0].points[0].y, 0.1f)
        // Lines 1 and 2 should shift down by 2 * LINE_SPACING
        assertEquals(originalY1 + 2 * ls, documentModel.activeStrokes[1].points[0].y, 0.1f)
        assertEquals(originalY2 + 2 * ls, documentModel.activeStrokes[2].points[0].y, 0.1f)
    }

    @Test
    fun `insert at line 0 shifts everything down`() {
        documentModel.activeStrokes.add(strokeOnLine(0))
        documentModel.activeStrokes.add(strokeOnLine(1))

        val originalY0 = documentModel.activeStrokes[0].points[0].y
        val originalY1 = documentModel.activeStrokes[1].points[0].y

        SpaceInsertMode.insertSpace(documentModel, lineSegmenter, anchorLine = 0, linesToInsert = 1)

        assertEquals(originalY0 + ls, documentModel.activeStrokes[0].points[0].y, 0.1f)
        assertEquals(originalY1 + ls, documentModel.activeStrokes[1].points[0].y, 0.1f)
    }

    @Test
    fun `insert space shifts diagram areas below anchor`() {
        documentModel.activeStrokes.add(strokeOnLine(0))
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 2, heightInLines = 3))

        SpaceInsertMode.insertSpace(documentModel, lineSegmenter, anchorLine = 1, linesToInsert = 2)

        // Diagram area should shift down by 2
        assertEquals(4, documentModel.diagramAreas[0].startLineIndex)
        assertEquals(3, documentModel.diagramAreas[0].heightInLines)
    }

    @Test
    fun `insert space inside diagram shifts entire diagram`() {
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 1, heightInLines = 3))
        documentModel.activeStrokes.add(strokeOnLine(2))  // inside diagram

        SpaceInsertMode.insertSpace(documentModel, lineSegmenter, anchorLine = 2, linesToInsert = 2)

        // Diagram should shift as a unit, not expand
        assertEquals(3, documentModel.diagramAreas[0].startLineIndex)
        assertEquals(3, documentModel.diagramAreas[0].heightInLines)
    }

    @Test
    fun `insert space inside diagram shifts all strokes in diagram`() {
        // Diagram on lines 1-3, strokes on lines 1 (above anchor) and 3 (at/below anchor)
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 1, heightInLines = 3))
        documentModel.activeStrokes.add(strokeOnLine(0))  // outside diagram, above anchor
        documentModel.activeStrokes.add(strokeOnLine(1))  // inside diagram, above anchor
        documentModel.activeStrokes.add(strokeOnLine(3))  // inside diagram, at/below anchor

        val originalY0 = documentModel.activeStrokes[0].points[0].y
        val originalY1 = documentModel.activeStrokes[1].points[0].y
        val originalY3 = documentModel.activeStrokes[2].points[0].y

        // Anchor at line 2 (inside diagram), insert 2 lines
        SpaceInsertMode.insertSpace(documentModel, lineSegmenter, anchorLine = 2, linesToInsert = 2)

        // Line 0: outside diagram, above anchor → should NOT shift
        assertEquals(originalY0, documentModel.activeStrokes[0].points[0].y, 0.1f)
        // Line 1: inside diagram, above anchor → SHOULD shift (diagram moves as a unit)
        assertEquals(originalY1 + 2 * ls, documentModel.activeStrokes[1].points[0].y, 0.1f)
        // Line 3: inside diagram, at anchor → should shift
        assertEquals(originalY3 + 2 * ls, documentModel.activeStrokes[2].points[0].y, 0.1f)
    }

    @Test
    fun `insert space does not shift diagram areas above anchor`() {
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 0, heightInLines = 2))
        documentModel.activeStrokes.add(strokeOnLine(3))

        SpaceInsertMode.insertSpace(documentModel, lineSegmenter, anchorLine = 3, linesToInsert = 1)

        // Diagram at line 0 should not move
        assertEquals(0, documentModel.diagramAreas[0].startLineIndex)
    }

    // --- Remove space ---

    @Test
    fun `remove empty lines above anchor`() {
        // Strokes on lines 0 and 4 (lines 1-3 are empty)
        documentModel.activeStrokes.add(strokeOnLine(0))
        documentModel.activeStrokes.add(strokeOnLine(4))

        val originalY0 = documentModel.activeStrokes[0].points[0].y
        val originalY4 = documentModel.activeStrokes[1].points[0].y

        // Anchor at line 4 (where content is), remove 3 empty lines above it
        val removed = SpaceInsertMode.removeSpace(documentModel, lineSegmenter, anchorLine = 4, linesToRemove = 3)

        assertEquals(3, removed)
        // Line 0 should not move (above the gap)
        assertEquals(originalY0, documentModel.activeStrokes[0].points[0].y, 0.1f)
        // Line 4 should shift up by 3 * LINE_SPACING (now at line 1)
        assertEquals(originalY4 - 3 * ls, documentModel.activeStrokes[1].points[0].y, 0.1f)
    }

    @Test
    fun `remove space blocks at content above — only removes empty lines`() {
        // Strokes on lines 0, 2, 4 (lines 1 and 3 are empty)
        documentModel.activeStrokes.add(strokeOnLine(0))
        documentModel.activeStrokes.add(strokeOnLine(2))
        documentModel.activeStrokes.add(strokeOnLine(4))

        // Anchor at line 4, try to remove 4 lines above — should only remove 1 (line 3)
        // because line 2 has content
        val removed = SpaceInsertMode.removeSpace(documentModel, lineSegmenter, anchorLine = 4, linesToRemove = 4)

        assertEquals(1, removed)
        // Line 4 content should shift up by 1 (now at line 3)
        val newLine = lineSegmenter.getStrokeLineIndex(documentModel.activeStrokes[2])
        assertEquals(3, newLine)
    }

    @Test
    fun `remove space with no empty lines above does nothing`() {
        // Strokes on lines 0, 1, 2 — no gaps
        documentModel.activeStrokes.add(strokeOnLine(0))
        documentModel.activeStrokes.add(strokeOnLine(1))
        documentModel.activeStrokes.add(strokeOnLine(2))

        val removed = SpaceInsertMode.removeSpace(documentModel, lineSegmenter, anchorLine = 2, linesToRemove = 2)

        assertEquals(0, removed)
    }

    @Test
    fun `remove space shifts diagram areas up`() {
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 5, heightInLines = 2))
        // Lines 2-4 are empty, content on line 0
        documentModel.activeStrokes.add(strokeOnLine(0))

        // Anchor at line 5 (diagram), remove 3 empty lines above
        val removed = SpaceInsertMode.removeSpace(documentModel, lineSegmenter, anchorLine = 5, linesToRemove = 3)

        assertEquals(3, removed)
        assertEquals(2, documentModel.diagramAreas[0].startLineIndex)
    }

    @Test
    fun `remove space with anchor inside diagram shifts containing diagram`() {
        // Diagram on lines 3-5, empty lines 1-2, content on line 0
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 3))
        documentModel.activeStrokes.add(strokeOnLine(0))
        documentModel.activeStrokes.add(strokeOnLine(4))  // inside diagram

        // Anchor inside diagram (line 4) → scans from diagram top (line 3)
        val removed = SpaceInsertMode.removeSpace(documentModel, lineSegmenter, anchorLine = 4, linesToRemove = 3)

        assertEquals(2, removed)  // lines 1-2 are empty
        // Containing diagram shifted from line 3 to line 1
        assertEquals(1, documentModel.diagramAreas[0].startLineIndex)
        assertEquals(3, documentModel.diagramAreas[0].heightInLines)  // height unchanged
        // Stroke inside diagram also shifted
        val strokeLine = lineSegmenter.getStrokeLineIndex(documentModel.activeStrokes[1])
        assertEquals(2, strokeLine)  // was line 4, shifted up by 2
    }

    @Test
    fun `countEmptyLinesAbove counts correctly`() {
        documentModel.activeStrokes.add(strokeOnLine(0))
        // Lines 1-4 are empty
        documentModel.activeStrokes.add(strokeOnLine(5))

        val count = SpaceInsertMode.countEmptyLinesAbove(documentModel, lineSegmenter, anchorLine = 5)
        assertEquals(4, count)
    }

    @Test
    fun `countEmptyLinesAbove stops at content`() {
        documentModel.activeStrokes.add(strokeOnLine(0))
        documentModel.activeStrokes.add(strokeOnLine(3))
        // Lines 4-5 empty
        documentModel.activeStrokes.add(strokeOnLine(6))

        val count = SpaceInsertMode.countEmptyLinesAbove(documentModel, lineSegmenter, anchorLine = 6)
        assertEquals(2, count)  // lines 4-5 only
    }

    @Test
    fun `countEmptyLinesAbove stops at diagram area`() {
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 1, heightInLines = 2))
        // Lines 3-4 empty
        documentModel.activeStrokes.add(strokeOnLine(5))

        val count = SpaceInsertMode.countEmptyLinesAbove(documentModel, lineSegmenter, anchorLine = 5)
        assertEquals(2, count)  // lines 3-4, stops at diagram on lines 1-2
    }

    // --- Barrier line computation ---

    @Test
    fun `barrierLine without diagram returns line just above empty gap`() {
        // Anchor at line 5, 2 empty lines above → barrier at line 2
        val barrier = SpaceInsertMode.barrierLine(
            anchorLine = 5,
            diagramAreas = emptyList(),
            emptyAbove = 2
        )
        assertEquals(2, barrier)
    }

    @Test
    fun `barrierLine with anchor inside diagram uses diagram top edge`() {
        // Diagram on lines 3-5, anchor at line 4 (inside), 2 empty lines above diagram
        // shiftFrom = 3 (diagram start), barrier = 3 - 2 - 1 = 0
        val areas = listOf(DiagramArea(startLineIndex = 3, heightInLines = 3))
        val barrier = SpaceInsertMode.barrierLine(
            anchorLine = 4,
            diagramAreas = areas,
            emptyAbove = 2
        )
        assertEquals(0, barrier)
    }

    @Test
    fun `barrierLine with anchor inside diagram and zero empty lines`() {
        // Diagram on lines 1-3, anchor at line 2, 0 empty lines above diagram
        // shiftFrom = 1, barrier = 1 - 0 - 1 = 0
        val areas = listOf(DiagramArea(startLineIndex = 1, heightInLines = 3))
        val barrier = SpaceInsertMode.barrierLine(
            anchorLine = 2,
            diagramAreas = areas,
            emptyAbove = 0
        )
        assertEquals(0, barrier)
    }

    @Test
    fun `barrierLine returns -1 when no barrier exists`() {
        // Anchor at line 0, no room above
        val barrier = SpaceInsertMode.barrierLine(
            anchorLine = 0,
            diagramAreas = emptyList(),
            emptyAbove = 0
        )
        assertEquals(-1, barrier)
    }

    @Test
    fun `barrierLine with anchor below diagram is not affected by diagram`() {
        // Diagram on lines 0-1, anchor at line 5, 2 empty lines above
        // Anchor not inside diagram → shiftFrom = 5, barrier = 5 - 2 - 1 = 2
        val areas = listOf(DiagramArea(startLineIndex = 0, heightInLines = 2))
        val barrier = SpaceInsertMode.barrierLine(
            anchorLine = 5,
            diagramAreas = areas,
            emptyAbove = 2
        )
        assertEquals(2, barrier)
    }

    // --- Effective shift line ---

    @Test
    fun `effectiveShiftLine returns anchor when not inside diagram`() {
        assertEquals(5, SpaceInsertMode.effectiveShiftLine(5, emptyList()))
    }

    @Test
    fun `effectiveShiftLine returns diagram start when anchor inside diagram`() {
        val areas = listOf(DiagramArea(startLineIndex = 3, heightInLines = 4))
        assertEquals(3, SpaceInsertMode.effectiveShiftLine(5, areas))
    }

    @Test
    fun `effectiveShiftLine returns anchor when anchor is at diagram start`() {
        val areas = listOf(DiagramArea(startLineIndex = 3, heightInLines = 4))
        assertEquals(3, SpaceInsertMode.effectiveShiftLine(3, areas))
    }

    // --- Undo integration ---

    @Test
    fun `insert space then undo restores original positions`() {
        documentModel.activeStrokes.add(strokeOnLine(0))
        documentModel.activeStrokes.add(strokeOnLine(1))

        val originalY0 = documentModel.activeStrokes[0].points[0].y
        val originalY1 = documentModel.activeStrokes[1].points[0].y

        // Save snapshot before insert
        saveSnapshot()
        SpaceInsertMode.insertSpace(documentModel, lineSegmenter, anchorLine = 1, linesToInsert = 2)

        // Undo
        val current = UndoManager.Snapshot(
            strokes = documentModel.activeStrokes.toList(),
            scrollOffsetY = 0f,
            lineTextCache = emptyMap(),
            diagramAreas = documentModel.diagramAreas.toList()
        )
        val restored = undoManager.undo(current)!!
        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(restored.strokes)

        assertEquals(originalY0, documentModel.activeStrokes[0].points[0].y, 0.1f)
        assertEquals(originalY1, documentModel.activeStrokes[1].points[0].y, 0.1f)
    }
}
