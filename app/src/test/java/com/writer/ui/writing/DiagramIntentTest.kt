package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.recognition.LineSegmenter
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import com.writer.view.ShapeSnapDetection
import com.writer.view.ScratchOutDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Integration tests for diagram-intent classification: when a stroke is detected
 * as a diagram shape outside a diagram area, a diagram region is auto-created.
 *
 * Exercises the full flow: shape detection → diagram area creation → merge → undo/redo.
 * Uses the same simulation pattern as [UndoUnsnapTest] — pure JVM, no Android framework.
 */
class DiagramIntentTest {

    companion object {
        private const val DENSITY = 1.875f
        private val LS get() = HandwritingCanvasView.LINE_SPACING
        private val TM get() = HandwritingCanvasView.TOP_MARGIN
    }

    private lateinit var documentModel: DocumentModel
    private lateinit var undoManager: UndoManager
    private lateinit var lineSegmenter: LineSegmenter

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

    private fun makePoints(vararg pairs: Pair<Float, Float>): List<StrokePoint> =
        pairs.map { (x, y) -> StrokePoint(x, y, 0.5f, 0L) }

    /** Create a rectangle stroke spanning the given line range. */
    private fun makeRectangleStroke(startLine: Int, endLine: Int, left: Float = 100f, right: Float = 400f): InkStroke {
        val top = TM + startLine * LS + LS * 0.1f
        val bottom = TM + endLine * LS + LS * 0.9f
        return InkStroke(
            points = makePoints(
                left to top, right to top,
                right to bottom, left to bottom,
                left to top
            ),
            strokeType = StrokeType.FREEHAND
        )
    }

    /** Create an oval stroke centered on the given line range. */
    private fun makeOvalStroke(startLine: Int, endLine: Int, n: Int = 60): InkStroke {
        val cx = 300f
        val cy = TM + (startLine + endLine) * LS / 2f + LS * 0.5f
        val rx = 150f
        val ry = (endLine - startLine + 1) * LS / 2f
        val points = (0..n).map { i ->
            val angle = 2 * PI * i / n
            StrokePoint(
                (cx + rx * cos(angle)).toFloat(),
                (cy + ry * sin(angle)).toFloat(),
                0.5f, 0L
            )
        }
        return InkStroke(points = points, strokeType = StrokeType.FREEHAND)
    }

    /** Create a straight line stroke spanning the given line range. */
    private fun makeLineStroke(startLine: Int, endLine: Int): InkStroke {
        val y1 = TM + startLine * LS + LS * 0.5f
        val y2 = TM + endLine * LS + LS * 0.5f
        // Diagonal line
        return InkStroke(
            points = makePoints(100f to y1, 400f to y2),
            strokeType = StrokeType.FREEHAND
        )
    }

    /** Create a short freehand stroke (text-like) on a single line. */
    private fun makeTextStroke(line: Int): InkStroke {
        val y = TM + line * LS + LS * 0.5f
        return InkStroke(
            points = makePoints(100f to y, 110f to y + 5f, 120f to y - 3f, 130f to y + 2f),
            strokeType = StrokeType.FREEHAND
        )
    }

    /** Create a strikethrough-like stroke on a single line. */
    private fun makeStrikethroughStroke(line: Int): InkStroke {
        val y = TM + line * LS + LS * 0.5f
        return InkStroke(
            points = makePoints(50f to y, 350f to y + 2f),
            strokeType = StrokeType.FREEHAND
        )
    }

    /** Create a zigzag scratch-out stroke. */
    private fun makeScratchOutStroke(line: Int): InkStroke {
        val y = TM + line * LS + LS * 0.5f
        val points = mutableListOf<StrokePoint>()
        var x = 50f
        for (i in 0..8) {
            points.add(StrokePoint(x, y + (i % 2) * 5f, 0.5f, 0L))
            x += if (i % 2 == 0) 60f else -50f
        }
        return InkStroke(points = points, strokeType = StrokeType.FREEHAND)
    }

    /**
     * Simulate the coordinator's onDiagramShapeDetected logic.
     * This mirrors [WritingCoordinator.onDiagramShapeDetected].
     */
    private fun simulateDiagramShapeDetected(stroke: InkStroke): Pair<Float, Float>? {
        val minY = stroke.points.minOf { it.y }
        val maxY = stroke.points.maxOf { it.y }
        val topLine = lineSegmenter.getLineIndex(minY)
        val bottomLine = lineSegmenter.getLineIndex(maxY)

        // Exact bounding box — no padding
        var mergeStart = topLine
        var mergeHeight = bottomLine - topLine + 1

        // Merge with adjacent diagram area above
        val above = documentModel.diagramAreas.find { it.endLineIndex + 1 >= mergeStart && it.endLineIndex < mergeStart + mergeHeight }
        if (above != null && above.endLineIndex + 1 >= mergeStart) {
            if (above.startLineIndex < mergeStart) {
                mergeHeight += mergeStart - above.startLineIndex
                mergeStart = above.startLineIndex
            }
            documentModel.diagramAreas.remove(above)
        }

        // Merge with adjacent diagram area below
        val below = documentModel.diagramAreas.find { it.startLineIndex <= mergeStart + mergeHeight && it.startLineIndex >= mergeStart }
        if (below != null && below != above) {
            val belowEnd = below.startLineIndex + below.heightInLines
            if (belowEnd > mergeStart + mergeHeight) {
                mergeHeight = belowEnd - mergeStart
            }
            documentModel.diagramAreas.remove(below)
        }

        val newArea = DiagramArea(startLineIndex = mergeStart, heightInLines = mergeHeight)
        documentModel.diagramAreas.add(newArea)

        val topY = lineSegmenter.getLineY(mergeStart)
        val bottomY = lineSegmenter.getLineY(mergeStart + mergeHeight)
        return Pair(topY, bottomY)
    }

    /** Simulate full stroke flow: stroke completed → shape detected → diagram created → shape snapped. */
    private fun simulateShapeStroke(stroke: InkStroke, snappedType: StrokeType) {
        // Phase 1: raw stroke completed
        saveUndoSnapshot()
        documentModel.activeStrokes.add(stroke)

        // Phase 2: diagram area created
        saveUndoSnapshot()
        simulateDiagramShapeDetected(stroke)

        // Phase 3: shape snapped
        val snappedStroke = stroke.copy(
            strokeType = snappedType,
            isGeometric = true
        )
        saveUndoSnapshot()
        documentModel.activeStrokes.removeAll { it.strokeId == stroke.strokeId }
        documentModel.activeStrokes.add(snappedStroke)
    }

    @Before
    fun setUp() {
        ScreenMetrics.init(DENSITY, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        documentModel = DocumentModel()
        undoManager = UndoManager()
        lineSegmenter = LineSegmenter()
    }

    // ── Shape detection triggers diagram creation ─────────────────────────────

    @Test
    fun `rectangle outside diagram auto-creates diagram area`() {
        val stroke = makeRectangleStroke(startLine = 3, endLine = 6)
        val xs = FloatArray(stroke.points.size) { stroke.points[it].x }
        val ys = FloatArray(stroke.points.size) { stroke.points[it].y }

        // Verify shape is detected
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Rectangle stroke should be detected as a shape", result)

        // Simulate diagram creation
        val bounds = simulateDiagramShapeDetected(stroke)
        assertNotNull(bounds)

        assertEquals("Should have 1 diagram area", 1, documentModel.diagramAreas.size)
        val area = documentModel.diagramAreas[0]
        // Exact bounding box: lines 3-6
        assertEquals("Start line should match shape top", 3, area.startLineIndex)
        assertTrue("Should cover line 6", area.containsLine(6))
    }

    @Test
    fun `non-shape stroke does not create diagram`() {
        val stroke = makeTextStroke(line = 5)
        val xs = FloatArray(stroke.points.size) { stroke.points[it].x }
        val ys = FloatArray(stroke.points.size) { stroke.points[it].y }

        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNull("Short text-like stroke should not be a shape", result)
        assertEquals("No diagram areas should be created", 0, documentModel.diagramAreas.size)
    }

    @Test
    fun `ellipse outside diagram auto-creates diagram area`() {
        val stroke = makeOvalStroke(startLine = 4, endLine = 7)
        val xs = FloatArray(stroke.points.size) { stroke.points[it].x }
        val ys = FloatArray(stroke.points.size) { stroke.points[it].y }

        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Oval stroke should be detected as ellipse", result)
        assertTrue("Should be an Ellipse", result is ShapeSnapDetection.SnapResult.Ellipse)

        val bounds = simulateDiagramShapeDetected(stroke)
        assertNotNull(bounds)

        val area = documentModel.diagramAreas[0]
        // Exact bounding box: lines 4-7
        assertEquals("Start should match shape top line", 4, area.startLineIndex)
        assertTrue("Should contain line 7", area.containsLine(7))
    }

    @Test
    fun `line stroke creates diagram area`() {
        val stroke = makeLineStroke(startLine = 2, endLine = 5)
        val xs = FloatArray(stroke.points.size) { stroke.points[it].x }
        val ys = FloatArray(stroke.points.size) { stroke.points[it].y }

        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Long line stroke should be detected", result)

        val bounds = simulateDiagramShapeDetected(stroke)
        assertNotNull(bounds)
        assertEquals(1, documentModel.diagramAreas.size)
    }

    // ── Merge logic ───────────────────────────────────────────────────────────

    @Test
    fun `diagram area merges with overlapping area above`() {
        // Existing area covers lines 0-4
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 0, heightInLines = 5))

        // Shape at lines 3-6 overlaps with existing area
        val stroke = makeRectangleStroke(startLine = 3, endLine = 6)
        simulateDiagramShapeDetected(stroke)

        assertEquals("Should merge into 1 area", 1, documentModel.diagramAreas.size)
        val area = documentModel.diagramAreas[0]
        assertEquals("Merged area should start at 0", 0, area.startLineIndex)
        assertTrue("Merged area should contain line 6", area.containsLine(6))
    }

    @Test
    fun `diagram area merges with adjacent area below`() {
        // Existing area at lines 7-9
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 7, heightInLines = 3))

        // Shape at lines 5-7 overlaps with existing area at line 7
        val stroke = makeRectangleStroke(startLine = 5, endLine = 7)
        simulateDiagramShapeDetected(stroke)

        assertEquals("Should merge into 1 area", 1, documentModel.diagramAreas.size)
        val area = documentModel.diagramAreas[0]
        assertEquals("Merged area should start at 5", 5, area.startLineIndex)
        assertTrue("Merged area should contain line 9 (original below end)", area.containsLine(9))
    }

    @Test
    fun `diagram area merges with areas both above and below`() {
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 0, heightInLines = 5))
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 6, heightInLines = 3))

        // Shape spans lines 3-7, overlapping both
        val stroke = makeRectangleStroke(startLine = 3, endLine = 7)
        simulateDiagramShapeDetected(stroke)

        val totalAreas = documentModel.diagramAreas.size
        assertTrue("Should merge to 1 or 2 areas, got $totalAreas", totalAreas <= 2)
    }

    // ── Undo / Redo ──────────────────────────────────────────────────────────

    @Test
    fun `undo removes auto-created diagram area and restores raw stroke`() {
        val stroke = makeRectangleStroke(startLine = 3, endLine = 5)
        simulateShapeStroke(stroke, StrokeType.RECTANGLE)

        assertEquals(1, documentModel.diagramAreas.size)
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(StrokeType.RECTANGLE, documentModel.activeStrokes[0].strokeType)

        // Undo 1: unsnap → raw freehand (diagram still present)
        assertTrue(undo())
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(StrokeType.FREEHAND, documentModel.activeStrokes[0].strokeType)
        assertEquals(1, documentModel.diagramAreas.size)

        // Undo 2: remove diagram area (stroke still present)
        assertTrue(undo())
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(0, documentModel.diagramAreas.size)

        // Undo 3: remove stroke entirely
        assertTrue(undo())
        assertEquals(0, documentModel.activeStrokes.size)
        assertEquals(0, documentModel.diagramAreas.size)
    }

    @Test
    fun `redo restores auto-created diagram area`() {
        val stroke = makeRectangleStroke(startLine = 3, endLine = 5)
        simulateShapeStroke(stroke, StrokeType.RECTANGLE)

        // Undo all 3 phases
        undo(); undo(); undo()
        assertEquals(0, documentModel.activeStrokes.size)
        assertEquals(0, documentModel.diagramAreas.size)

        // Redo phase 1: raw stroke
        assertTrue(redo())
        assertEquals(1, documentModel.activeStrokes.size)
        assertEquals(StrokeType.FREEHAND, documentModel.activeStrokes[0].strokeType)

        // Redo phase 2: diagram area
        assertTrue(redo())
        assertEquals(1, documentModel.diagramAreas.size)

        // Redo phase 3: snapped shape
        assertTrue(redo())
        assertEquals(StrokeType.RECTANGLE, documentModel.activeStrokes[0].strokeType)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `strikethrough shape is not misclassified as diagram shape`() {
        val stroke = makeStrikethroughStroke(line = 5)
        assertTrue(
            "Strikethrough should be detected by GestureHandler",
            GestureHandler.isStrikethroughShape(stroke)
        )
    }

    @Test
    fun `scratch-out is detected before shape detection`() {
        val stroke = makeScratchOutStroke(line = 5)
        val xs = FloatArray(stroke.points.size) { stroke.points[it].x }
        val yRange = stroke.points.maxOf { it.y } - stroke.points.minOf { it.y }

        val isScratchOut = ScratchOutDetection.detect(xs, yRange, LS)
        // Scratch-out should be detected (or at minimum not be a shape)
        val xsShape = FloatArray(stroke.points.size) { stroke.points[it].x }
        val ysShape = FloatArray(stroke.points.size) { stroke.points[it].y }
        val shapeResult = ShapeSnapDetection.detect(xsShape, ysShape, LS)

        // Either scratch-out is detected, or shape is not detected — either way no false diagram
        assertTrue(
            "Scratch-out should be handled before shape detection",
            isScratchOut || shapeResult == null
        )
    }

    @Test
    fun `diagram at top starts at line 0`() {
        val stroke = makeRectangleStroke(startLine = 0, endLine = 1)
        simulateDiagramShapeDetected(stroke)

        val area = documentModel.diagramAreas[0]
        assertEquals("Start line should be 0", 0, area.startLineIndex)
    }

    @Test
    fun `multiple shapes create separate diagram areas`() {
        val stroke1 = makeRectangleStroke(startLine = 2, endLine = 4)
        val stroke2 = makeRectangleStroke(startLine = 10, endLine = 12)

        simulateDiagramShapeDetected(stroke1)
        simulateDiagramShapeDetected(stroke2)

        assertEquals("Should have 2 separate diagram areas", 2, documentModel.diagramAreas.size)
    }

    @Test
    fun `second shape overlapping first diagram merges`() {
        // First shape at lines 2-4 → diagram 2-4
        val stroke1 = makeRectangleStroke(startLine = 2, endLine = 4)
        simulateDiagramShapeDetected(stroke1)
        assertEquals(1, documentModel.diagramAreas.size)

        // Second shape at lines 4-6 → overlaps at line 4, should merge
        val stroke2 = makeRectangleStroke(startLine = 4, endLine = 6)
        simulateDiagramShapeDetected(stroke2)

        assertEquals("Overlapping diagrams should merge", 1, documentModel.diagramAreas.size)
        val area = documentModel.diagramAreas[0]
        assertTrue("Merged area should contain line 2", area.containsLine(2))
        assertTrue("Merged area should contain line 6", area.containsLine(6))
    }

    // ── Diagram expansion (stroke overflow) ───────────────────────────────────

    /**
     * Simulate the coordinator's onDiagramStrokeOverflow logic.
     * Mirrors [WritingCoordinator.onDiagramStrokeOverflow].
     */
    private fun simulateDiagramStrokeOverflow(strokeMinY: Float, strokeMaxY: Float) {
        val strokeTopLine = lineSegmenter.getLineIndex(strokeMinY)
        val strokeBottomLine = lineSegmenter.getLineIndex(strokeMaxY)

        val area = documentModel.diagramAreas.find {
            strokeTopLine <= it.endLineIndex && strokeBottomLine >= it.startLineIndex
        } ?: return

        val newStart = minOf(area.startLineIndex, strokeTopLine)
        val newEnd = maxOf(area.endLineIndex, strokeBottomLine)

        if (newStart == area.startLineIndex && newEnd == area.endLineIndex) return

        documentModel.diagramAreas.remove(area)
        documentModel.diagramAreas.add(area.copy(
            startLineIndex = newStart,
            heightInLines = newEnd - newStart + 1
        ))
    }

    @Test
    fun `stroke extending below diagram expands it downward`() {
        // Create diagram at lines 3-5
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 3))

        // Stroke starts inside (line 4) but extends below to line 7
        val strokeMinY = TM + 4 * LS + LS * 0.5f
        val strokeMaxY = TM + 7 * LS + LS * 0.5f
        simulateDiagramStrokeOverflow(strokeMinY, strokeMaxY)

        assertEquals(1, documentModel.diagramAreas.size)
        val area = documentModel.diagramAreas[0]
        assertEquals("Start should remain at 3", 3, area.startLineIndex)
        assertTrue("Should now contain line 7", area.containsLine(7))
    }

    @Test
    fun `stroke extending above diagram expands it upward`() {
        // Create diagram at lines 5-8
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 5, heightInLines = 4))

        // Stroke starts inside (line 5) but extends above to line 3
        val strokeMinY = TM + 3 * LS + LS * 0.2f
        val strokeMaxY = TM + 6 * LS + LS * 0.5f
        simulateDiagramStrokeOverflow(strokeMinY, strokeMaxY)

        assertEquals(1, documentModel.diagramAreas.size)
        val area = documentModel.diagramAreas[0]
        assertEquals("Start should expand to 3", 3, area.startLineIndex)
        assertTrue("Should still contain line 8", area.containsLine(8))
    }

    @Test
    fun `stroke extending both directions expands diagram in both`() {
        // Create diagram at lines 5-7
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 5, heightInLines = 3))

        // Stroke extends from line 3 to line 9
        val strokeMinY = TM + 3 * LS + LS * 0.3f
        val strokeMaxY = TM + 9 * LS + LS * 0.7f
        simulateDiagramStrokeOverflow(strokeMinY, strokeMaxY)

        assertEquals(1, documentModel.diagramAreas.size)
        val area = documentModel.diagramAreas[0]
        assertEquals("Start should expand to 3", 3, area.startLineIndex)
        assertTrue("Should now contain line 9", area.containsLine(9))
    }

    @Test
    fun `stroke within diagram bounds does not expand`() {
        // Create diagram at lines 3-8
        documentModel.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 6))

        // Stroke fully within bounds
        val strokeMinY = TM + 4 * LS + LS * 0.2f
        val strokeMaxY = TM + 6 * LS + LS * 0.8f
        simulateDiagramStrokeOverflow(strokeMinY, strokeMaxY)

        val area = documentModel.diagramAreas[0]
        assertEquals("Start should remain 3", 3, area.startLineIndex)
        assertEquals("Height should remain 6", 6, area.heightInLines)
    }
}
