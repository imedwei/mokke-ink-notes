package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the undo-to-unsnap behavior: when a stroke is snapped to a shape,
 * undo once restores the raw freehand stroke; undo again removes it entirely.
 *
 * Exercises the two-phase commit logic that [WritingCoordinator] uses when
 * [HandwritingCanvasView.onStrokeReplaced] fires after a shape snap.
 */
class UndoUnsnapTest {

    private lateinit var documentModel: DocumentModel
    private lateinit var undoManager: UndoManager

    /** Simulates the state captured by [WritingCoordinator.saveUndoSnapshot]. */
    private fun currentSnapshot() = UndoManager.Snapshot(
        strokes = documentModel.activeStrokes.toList(),
        scrollOffsetY = 0f,
        lineTextCache = emptyMap(),
        diagramAreas = documentModel.diagramAreas.toList()
    )

    private fun saveUndoSnapshot() {
        undoManager.saveSnapshot(currentSnapshot())
    }

    private fun applySnapshot(snapshot: UndoManager.Snapshot) {
        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(snapshot.strokes)
        documentModel.diagramAreas.clear()
        documentModel.diagramAreas.addAll(snapshot.diagramAreas)
    }

    private fun undo(): Boolean {
        val snapshot = undoManager.undo(currentSnapshot()) ?: return false
        applySnapshot(snapshot)
        return true
    }

    private fun redo(): Boolean {
        val snapshot = undoManager.redo(currentSnapshot()) ?: return false
        applySnapshot(snapshot)
        return true
    }

    /** Simulate onStrokeCompleted as WritingCoordinator does. */
    private fun simulateStrokeCompleted(stroke: InkStroke) {
        saveUndoSnapshot()
        documentModel.activeStrokes.add(stroke)
    }

    /** Simulate onStrokeReplaced as WritingCoordinator does. */
    private fun simulateStrokeReplaced(oldStrokeId: String, newStroke: InkStroke) {
        saveUndoSnapshot()
        documentModel.activeStrokes.removeAll { it.strokeId == oldStrokeId }
        documentModel.activeStrokes.add(newStroke)
    }

    private fun makePoints(vararg pairs: Pair<Float, Float>): List<StrokePoint> =
        pairs.map { (x, y) -> StrokePoint(x, y, 0.5f, 0L) }

    @Before
    fun setUp() {
        documentModel = DocumentModel()
        undoManager = UndoManager()
    }

    @Test
    fun `snapped stroke - undo once shows raw freehand`() {
        val rawStroke = InkStroke(
            points = makePoints(10f to 10f, 50f to 50f, 100f to 100f),
            strokeType = StrokeType.FREEHAND
        )
        val snappedStroke = InkStroke(
            points = makePoints(10f to 10f, 100f to 100f),
            strokeType = StrokeType.LINE,
            isGeometric = true
        )

        // Phase 1: raw stroke completed
        simulateStrokeCompleted(rawStroke)
        // Phase 2: replaced with snapped
        simulateStrokeReplaced(rawStroke.strokeId, snappedStroke)

        // Verify current state has snapped stroke
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(StrokeType.LINE, documentModel.activeStrokes[0].strokeType)

        // Undo once → raw freehand
        assertTrue(undo())
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(StrokeType.FREEHAND, documentModel.activeStrokes[0].strokeType)
        assertEquals(rawStroke.strokeId, documentModel.activeStrokes[0].strokeId)
    }

    @Test
    fun `snapped stroke - undo twice removes stroke entirely`() {
        val rawStroke = InkStroke(
            points = makePoints(10f to 10f, 100f to 100f),
            strokeType = StrokeType.FREEHAND
        )
        val snappedStroke = InkStroke(
            points = makePoints(10f to 10f, 100f to 100f),
            strokeType = StrokeType.RECTANGLE,
            isGeometric = true
        )

        simulateStrokeCompleted(rawStroke)
        simulateStrokeReplaced(rawStroke.strokeId, snappedStroke)

        // Undo once → raw stroke
        assertTrue(undo())
        assertEquals(1, documentModel.activeStrokes.size)

        // Undo again → empty
        assertTrue(undo())
        assertEquals(0, documentModel.activeStrokes.size)
    }

    @Test
    fun `snapped stroke - redo from raw restores snapped`() {
        val rawStroke = InkStroke(
            points = makePoints(10f to 10f, 50f to 50f, 100f to 100f),
            strokeType = StrokeType.FREEHAND
        )
        val snappedStroke = InkStroke(
            points = makePoints(10f to 10f, 100f to 100f),
            strokeType = StrokeType.ARROW_HEAD,
            isGeometric = true
        )

        simulateStrokeCompleted(rawStroke)
        simulateStrokeReplaced(rawStroke.strokeId, snappedStroke)

        // Undo to raw
        undo()
        assertEquals(StrokeType.FREEHAND, documentModel.activeStrokes[0].strokeType)

        // Redo → snapped restored
        assertTrue(redo())
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(StrokeType.ARROW_HEAD, documentModel.activeStrokes[0].strokeType)
    }

    @Test
    fun `snapped stroke - redo from empty restores raw then snapped`() {
        val rawStroke = InkStroke(
            points = makePoints(10f to 10f, 100f to 100f),
            strokeType = StrokeType.FREEHAND
        )
        val snappedStroke = InkStroke(
            points = makePoints(10f to 10f, 100f to 100f),
            strokeType = StrokeType.ELLIPSE
        )

        simulateStrokeCompleted(rawStroke)
        simulateStrokeReplaced(rawStroke.strokeId, snappedStroke)

        // Undo twice → empty
        undo()
        undo()
        assertEquals(0, documentModel.activeStrokes.size)

        // Redo → raw
        assertTrue(redo())
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(StrokeType.FREEHAND, documentModel.activeStrokes[0].strokeType)

        // Redo → snapped
        assertTrue(redo())
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(StrokeType.ELLIPSE, documentModel.activeStrokes[0].strokeType)
    }

    @Test
    fun `non-snapped stroke undoes in one step`() {
        val stroke = InkStroke(
            points = makePoints(10f to 10f, 50f to 50f),
            strokeType = StrokeType.FREEHAND
        )

        // Normal stroke — only onStrokeCompleted, no onStrokeReplaced
        simulateStrokeCompleted(stroke)

        assertEquals(1, documentModel.activeStrokes.size)

        // Single undo removes it
        assertTrue(undo())
        assertEquals(0, documentModel.activeStrokes.size)
    }

    @Test
    fun `mixed strokes - snapped and non-snapped undo independently`() {
        // First: a normal freehand stroke
        val freehand = InkStroke(
            points = makePoints(10f to 10f, 50f to 50f),
            strokeType = StrokeType.FREEHAND
        )
        simulateStrokeCompleted(freehand)

        // Second: a snapped rectangle
        val rawStroke = InkStroke(
            points = makePoints(200f to 200f, 250f to 220f, 300f to 300f),
            strokeType = StrokeType.FREEHAND
        )
        val snappedStroke = InkStroke(
            points = makePoints(200f to 200f, 300f to 200f, 300f to 300f, 200f to 300f, 200f to 200f),
            strokeType = StrokeType.RECTANGLE,
            isGeometric = true
        )
        simulateStrokeCompleted(rawStroke)
        simulateStrokeReplaced(rawStroke.strokeId, snappedStroke)

        // State: freehand + snapped rectangle
        assertEquals(2, documentModel.activeStrokes.size)

        // Undo 1: rectangle → raw
        undo()
        assertEquals(2, documentModel.activeStrokes.size)
        assertEquals(StrokeType.FREEHAND, documentModel.activeStrokes[0].strokeType)
        assertEquals(StrokeType.FREEHAND, documentModel.activeStrokes[1].strokeType)
        assertEquals(rawStroke.strokeId, documentModel.activeStrokes[1].strokeId)

        // Undo 2: raw stroke removed
        undo()
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(freehand.strokeId, documentModel.activeStrokes[0].strokeId)

        // Undo 3: freehand removed
        undo()
        assertEquals(0, documentModel.activeStrokes.size)
    }

    @Test
    fun `scrub-based undo walks through snap states`() {
        val rawStroke = InkStroke(
            points = makePoints(10f to 10f, 50f to 50f, 100f to 100f),
            strokeType = StrokeType.FREEHAND
        )
        val snappedStroke = InkStroke(
            points = makePoints(10f to 10f, 100f to 100f),
            strokeType = StrokeType.LINE,
            isGeometric = true
        )

        simulateStrokeCompleted(rawStroke)
        simulateStrokeReplaced(rawStroke.strokeId, snappedStroke)

        // Begin scrub from current (snapped) state
        undoManager.beginScrub(currentSnapshot())

        // Scrub -1 → raw stroke
        val snap1 = undoManager.scrubTo(-1)!!
        applySnapshot(snap1)
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(StrokeType.FREEHAND, documentModel.activeStrokes[0].strokeType)

        // Scrub -2 → empty
        val snap2 = undoManager.scrubTo(-2)!!
        applySnapshot(snap2)
        assertEquals(0, documentModel.activeStrokes.size)

        // Scrub back to 0 → snapped
        val snap3 = undoManager.scrubTo(0)!!
        applySnapshot(snap3)
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(StrokeType.LINE, documentModel.activeStrokes[0].strokeType)

        undoManager.endScrub()
    }

    @Test
    fun `diagram areas preserved through snap undo cycle`() {
        val diagramArea = DiagramArea(startLineIndex = 2, heightInLines = 4)
        documentModel.diagramAreas.add(diagramArea)

        val rawStroke = InkStroke(
            points = makePoints(50f to 300f, 100f to 350f),
            strokeType = StrokeType.FREEHAND
        )
        val snappedStroke = InkStroke(
            points = makePoints(50f to 300f, 100f to 350f),
            strokeType = StrokeType.LINE,
            isGeometric = true
        )

        simulateStrokeCompleted(rawStroke)
        simulateStrokeReplaced(rawStroke.strokeId, snappedStroke)

        // Undo to raw — diagram areas still present
        undo()
        assertEquals(1, documentModel.diagramAreas.size)
        assertEquals(diagramArea, documentModel.diagramAreas[0])

        // Undo to empty — diagram areas still present (was there before the stroke)
        undo()
        assertEquals(0, documentModel.activeStrokes.size)
        assertEquals(1, documentModel.diagramAreas.size)
    }
}
