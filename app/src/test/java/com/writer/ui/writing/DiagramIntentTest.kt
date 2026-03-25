package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.shiftY
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
        strokes = documentModel.main.activeStrokes.toList(),
        scrollOffsetY = 0f,
        lineTextCache = emptyMap(),
        diagramAreas = documentModel.main.diagramAreas.toList()
    )

    private fun saveUndoSnapshot() {
        undoManager.saveSnapshot(currentSnapshot())
    }

    private fun applySnapshot(snapshot: UndoManager.Snapshot) {
        documentModel.main.activeStrokes.clear()
        documentModel.main.activeStrokes.addAll(snapshot.strokes)
        documentModel.main.diagramAreas.clear()
        documentModel.main.diagramAreas.addAll(snapshot.diagramAreas)
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

    /** Create a geometric (shape-snapped) stroke on a single line. */
    private fun makeGeometricStroke(line: Int): InkStroke {
        val y = TM + line * LS + LS * 0.5f
        return InkStroke(
            points = makePoints(100f to y, 200f to y, 200f to y + LS * 0.8f, 100f to y + LS * 0.8f, 100f to y),
            strokeType = StrokeType.RECTANGLE,
            isGeometric = true
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

        // Exact bounding box — no forced padding
        var mergeStart = topLine
        var mergeHeight = bottomLine - mergeStart + 1

        // Merge with adjacent diagram area above
        val above = documentModel.main.diagramAreas.find { it.endLineIndex + 1 >= mergeStart && it.endLineIndex < mergeStart + mergeHeight }
        if (above != null && above.endLineIndex + 1 >= mergeStart) {
            if (above.startLineIndex < mergeStart) {
                mergeHeight += mergeStart - above.startLineIndex
                mergeStart = above.startLineIndex
            }
            documentModel.main.diagramAreas.remove(above)
        }

        // Merge with adjacent diagram area below
        val below = documentModel.main.diagramAreas.find { it.startLineIndex <= mergeStart + mergeHeight && it.startLineIndex >= mergeStart }
        if (below != null && below != above) {
            val belowEnd = below.startLineIndex + below.heightInLines
            if (belowEnd > mergeStart + mergeHeight) {
                mergeHeight = belowEnd - mergeStart
            }
            documentModel.main.diagramAreas.remove(below)
        }

        val newArea = DiagramArea(startLineIndex = mergeStart, heightInLines = mergeHeight)
        documentModel.main.diagramAreas.add(newArea)

        val topY = lineSegmenter.getLineY(mergeStart)
        val bottomY = lineSegmenter.getLineY(mergeStart + mergeHeight)
        return Pair(topY, bottomY)
    }

    /** Simulate full stroke flow: stroke completed → shape detected → diagram created → shape snapped. */
    private fun simulateShapeStroke(stroke: InkStroke, snappedType: StrokeType) {
        // Phase 1: raw stroke completed
        saveUndoSnapshot()
        documentModel.main.activeStrokes.add(stroke)

        // Phase 2: diagram area created
        saveUndoSnapshot()
        simulateDiagramShapeDetected(stroke)

        // Phase 3: shape snapped
        val snappedStroke = stroke.copy(
            strokeType = snappedType,
            isGeometric = true
        )
        saveUndoSnapshot()
        documentModel.main.activeStrokes.removeAll { it.strokeId == stroke.strokeId }
        documentModel.main.activeStrokes.add(snappedStroke)
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

        assertEquals("Should have 1 diagram area", 1, documentModel.main.diagramAreas.size)
        val area = documentModel.main.diagramAreas[0]
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
        assertEquals("No diagram areas should be created", 0, documentModel.main.diagramAreas.size)
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

        val area = documentModel.main.diagramAreas[0]
        // Exact bounding box: lines 4-7
        assertEquals("Start should match shape top", 4, area.startLineIndex)
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
        assertEquals(1, documentModel.main.diagramAreas.size)
    }

    // ── Merge logic ───────────────────────────────────────────────────────────

    @Test
    fun `diagram area merges with overlapping area above`() {
        // Existing area covers lines 0-4
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 0, heightInLines = 5))

        // Shape at lines 3-6 overlaps with existing area
        val stroke = makeRectangleStroke(startLine = 3, endLine = 6)
        simulateDiagramShapeDetected(stroke)

        assertEquals("Should merge into 1 area", 1, documentModel.main.diagramAreas.size)
        val area = documentModel.main.diagramAreas[0]
        assertEquals("Merged area should start at 0", 0, area.startLineIndex)
        assertTrue("Merged area should contain line 6", area.containsLine(6))
    }

    @Test
    fun `diagram area merges with adjacent area below`() {
        // Existing area at lines 7-9
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 7, heightInLines = 3))

        // Shape at lines 5-7 overlaps with existing area at line 7
        val stroke = makeRectangleStroke(startLine = 5, endLine = 7)
        simulateDiagramShapeDetected(stroke)

        assertEquals("Should merge into 1 area", 1, documentModel.main.diagramAreas.size)
        val area = documentModel.main.diagramAreas[0]
        assertEquals("Merged area should start at 5", 5, area.startLineIndex)
        assertTrue("Merged area should contain line 9 (original below end)", area.containsLine(9))
    }

    @Test
    fun `diagram area merges with areas both above and below`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 0, heightInLines = 5))
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 6, heightInLines = 3))

        // Shape spans lines 3-7, overlapping both
        val stroke = makeRectangleStroke(startLine = 3, endLine = 7)
        simulateDiagramShapeDetected(stroke)

        val totalAreas = documentModel.main.diagramAreas.size
        assertTrue("Should merge to 1 or 2 areas, got $totalAreas", totalAreas <= 2)
    }

    // ── Undo / Redo ──────────────────────────────────────────────────────────

    @Test
    fun `undo removes auto-created diagram area and restores raw stroke`() {
        val stroke = makeRectangleStroke(startLine = 3, endLine = 5)
        simulateShapeStroke(stroke, StrokeType.RECTANGLE)

        assertEquals(1, documentModel.main.diagramAreas.size)
        assertEquals(1, documentModel.main.activeStrokes.size)
        assertEquals(StrokeType.RECTANGLE, documentModel.main.activeStrokes[0].strokeType)

        // Undo 1: unsnap → raw freehand (diagram still present)
        assertTrue(undo())
        assertEquals(1, documentModel.main.activeStrokes.size)
        assertEquals(StrokeType.FREEHAND, documentModel.main.activeStrokes[0].strokeType)
        assertEquals(1, documentModel.main.diagramAreas.size)

        // Undo 2: remove diagram area (stroke still present)
        assertTrue(undo())
        assertEquals(1, documentModel.main.activeStrokes.size)
        assertEquals(0, documentModel.main.diagramAreas.size)

        // Undo 3: remove stroke entirely
        assertTrue(undo())
        assertEquals(0, documentModel.main.activeStrokes.size)
        assertEquals(0, documentModel.main.diagramAreas.size)
    }

    @Test
    fun `redo restores auto-created diagram area`() {
        val stroke = makeRectangleStroke(startLine = 3, endLine = 5)
        simulateShapeStroke(stroke, StrokeType.RECTANGLE)

        // Undo all 3 phases
        undo(); undo(); undo()
        assertEquals(0, documentModel.main.activeStrokes.size)
        assertEquals(0, documentModel.main.diagramAreas.size)

        // Redo phase 1: raw stroke
        assertTrue(redo())
        assertEquals(1, documentModel.main.activeStrokes.size)
        assertEquals(StrokeType.FREEHAND, documentModel.main.activeStrokes[0].strokeType)

        // Redo phase 2: diagram area
        assertTrue(redo())
        assertEquals(1, documentModel.main.diagramAreas.size)

        // Redo phase 3: snapped shape
        assertTrue(redo())
        assertEquals(StrokeType.RECTANGLE, documentModel.main.activeStrokes[0].strokeType)
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

        val area = documentModel.main.diagramAreas[0]
        assertEquals("Start line should be 0", 0, area.startLineIndex)
    }

    @Test
    fun `multiple shapes create separate diagram areas`() {
        val stroke1 = makeRectangleStroke(startLine = 2, endLine = 4)
        val stroke2 = makeRectangleStroke(startLine = 10, endLine = 12)

        simulateDiagramShapeDetected(stroke1)
        simulateDiagramShapeDetected(stroke2)

        assertEquals("Should have 2 separate diagram areas", 2, documentModel.main.diagramAreas.size)
    }

    @Test
    fun `second shape overlapping first diagram merges`() {
        // First shape at lines 2-4 → diagram 2-4
        val stroke1 = makeRectangleStroke(startLine = 2, endLine = 4)
        simulateDiagramShapeDetected(stroke1)
        assertEquals(1, documentModel.main.diagramAreas.size)

        // Second shape at lines 4-6 → overlaps at line 4, should merge
        val stroke2 = makeRectangleStroke(startLine = 4, endLine = 6)
        simulateDiagramShapeDetected(stroke2)

        assertEquals("Overlapping diagrams should merge", 1, documentModel.main.diagramAreas.size)
        val area = documentModel.main.diagramAreas[0]
        assertTrue("Merged area should contain line 2", area.containsLine(2))
        assertTrue("Merged area should contain line 6", area.containsLine(6))
    }

    // ── Diagram expansion (stroke overflow) ───────────────────────────────────

    /**
     * Simulate onDiagramStrokeOverflow: when a stroke inside a diagram extends
     * beyond its bounds, shift diagram+below DOWN and expand the area.
     * Text above stays in place.
     */
    /** Convenience for tests without a specific overflow stroke. */
    private fun simulateOverflow(strokeMinY: Float, strokeMaxY: Float) =
        simulateOverflow("", strokeMinY, strokeMaxY)

    private fun simulateOverflow(overflowStrokeId: String, strokeMinY: Float, strokeMaxY: Float) {
        val strokeTopLine = lineSegmenter.getLineIndex(strokeMinY)
        val strokeBottomLine = lineSegmenter.getLineIndex(strokeMaxY)

        val area = documentModel.main.diagramAreas.find {
            strokeTopLine <= it.endLineIndex && strokeBottomLine >= it.startLineIndex
        } ?: return

        val linesAbove = maxOf(0, area.startLineIndex - strokeTopLine + 1) // +1 for padding
        val linesBelow = maxOf(0, strokeBottomLine - area.endLineIndex + 1)

        if (linesAbove == 0 && linesBelow == 0) return

        val ls = LS

        // Upward expansion: shift diagram strokes + overflow stroke DOWN.
        if (linesAbove > 0) {
            val shiftPx = linesAbove * ls
            val shifted = documentModel.main.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                val isInsideDiagram = strokeLine >= area.startLineIndex
                val isOverflowStroke = stroke.strokeId == overflowStrokeId
                if (isInsideDiagram || isOverflowStroke) stroke.shiftY(shiftPx) else stroke
            }
            documentModel.main.activeStrokes.clear()
            documentModel.main.activeStrokes.addAll(shifted)
        }

        // Downward expansion: shift strokes below the diagram DOWN,
        // but NOT the overflow stroke (it stays — it's part of the diagram).
        if (linesBelow > 0) {
            val shiftPx = linesBelow * ls
            val postEndLine = area.endLineIndex + linesAbove
            val shifted = documentModel.main.activeStrokes.map { stroke ->
                val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
                val isOverflowStroke = stroke.strokeId == overflowStrokeId
                if (strokeLine > postEndLine && !isOverflowStroke) stroke.shiftY(shiftPx) else stroke
            }
            documentModel.main.activeStrokes.clear()
            documentModel.main.activeStrokes.addAll(shifted)
        }

        // Expand diagram: keep original startLineIndex, grow height
        documentModel.main.diagramAreas.remove(area)
        documentModel.main.diagramAreas.add(DiagramArea(
            startLineIndex = area.startLineIndex,
            heightInLines = area.heightInLines + linesAbove + linesBelow
        ))
    }

    @Test
    fun `stroke extending below diagram expands it downward`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 3))

        val strokeMinY = TM + 4 * LS + LS * 0.5f
        val strokeMaxY = TM + 7 * LS + LS * 0.5f
        simulateOverflow(strokeMinY, strokeMaxY)

        assertEquals(1, documentModel.main.diagramAreas.size)
        val area = documentModel.main.diagramAreas[0]
        assertEquals("Start should remain at 3", 3, area.startLineIndex)
        assertTrue("Should now contain line 7", area.containsLine(7))
    }

    @Test
    fun `stroke extending above diagram shifts content down and expands`() {
        // Diagram shape on line 6
        val diagramStroke = makeTextStroke(line = 6)
        documentModel.main.activeStrokes.add(diagramStroke)
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 5, heightInLines = 4))

        // Overflow stroke from line 3 to line 6 (crosses diagram start)
        val overflowStroke = makeLineStroke(startLine = 3, endLine = 6)
        documentModel.main.activeStrokes.add(overflowStroke)

        simulateOverflow(overflowStroke.strokeId,
            strokeMinY = TM + 3 * LS + LS * 0.2f,
            strokeMaxY = TM + 6 * LS + LS * 0.5f
        )

        val area = documentModel.main.diagramAreas[0]
        // Diagram keeps its original startLineIndex
        assertEquals("Diagram start stays at 5", 5, area.startLineIndex)
        // Height grew by linesAbove (5 - 3 + 1 = 3)
        assertEquals("Height should grow", 4 + 3, area.heightInLines)

        // Diagram stroke was shifted down (its maxY was inside diagram)
        val shiftedDiagramLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[0])
        assertEquals("Diagram stroke shifted down by 3", 6 + 3, shiftedDiagramLine)

        // Overflow stroke was shifted down (its maxY was inside diagram)
        val shiftedOverflowLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[1])
        assertTrue("Overflow stroke shifted down, now inside diagram",
            area.containsLine(shiftedOverflowLine))
    }

    @Test
    fun `scenario 1 - overflow crosses diagram start, text above stays outside`() {
        // "Hello World" text on line 1
        val helloStroke = makeTextStroke(line = 1)
        documentModel.main.activeStrokes.add(helloStroke)

        // Diagram at lines 4-6 with a shape
        val shapeStroke = makeTextStroke(line = 5)
        documentModel.main.activeStrokes.add(shapeStroke)
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 4, heightInLines = 3))

        // Overflow stroke from line 3 (above diagram) to line 5 (inside diagram)
        val overflowStroke = makeLineStroke(startLine = 3, endLine = 5)
        documentModel.main.activeStrokes.add(overflowStroke)

        simulateOverflow(overflowStroke.strokeId,
            strokeMinY = TM + 3 * LS + LS * 0.2f,
            strokeMaxY = TM + 5 * LS + LS * 0.5f
        )

        // Text stays at line 1 (not shifted — maxY is above diagram)
        val textLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[0])
        assertEquals("Hello World stays at line 1", 1, textLine)

        // Diagram area should NOT contain text line
        val area = documentModel.main.diagramAreas[0]
        assertTrue("Diagram should not contain line 1", !area.containsLine(1))
        assertTrue("Diagram should not contain line 2", !area.containsLine(textLine + 1))

        // All diagram/overflow strokes should be inside the diagram area
        val shapeLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[1])
        assertTrue("Shape should be inside diagram", area.containsLine(shapeLine))
        val overflowLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[2])
        assertTrue("Overflow should be inside diagram", area.containsLine(overflowLine))
    }

    @Test
    fun `scenario 2 - overflow overlaps text, text stays above diagram`() {
        // "Hello World" text on line 2
        val helloStroke = makeTextStroke(line = 2)
        documentModel.main.activeStrokes.add(helloStroke)

        // Diagram at lines 4-6 with a shape
        val shapeStroke = makeTextStroke(line = 5)
        documentModel.main.activeStrokes.add(shapeStroke)
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 4, heightInLines = 3))

        // Overflow stroke from line 2 (SAME line as Hello World) to line 5 (inside diagram)
        val overflowStroke = makeLineStroke(startLine = 2, endLine = 5)
        documentModel.main.activeStrokes.add(overflowStroke)

        simulateOverflow(overflowStroke.strokeId,
            strokeMinY = TM + 2 * LS + LS * 0.2f,
            strokeMaxY = TM + 5 * LS + LS * 0.5f
        )

        // Hello World stays at line 2 (maxY is on line 2, below diagram top at line 4)
        val textLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[0])
        assertEquals("Hello World stays at line 2", 2, textLine)

        // Diagram should not contain Hello World's line
        val area = documentModel.main.diagramAreas[0]
        assertTrue("Diagram should not contain line 2 (Hello World)", !area.containsLine(textLine))

        // Overflow stroke was shifted down — now inside the diagram
        val overflowLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[2])
        assertTrue("Overflow should be inside diagram", area.containsLine(overflowLine))
    }

    @Test
    fun `stroke within diagram bounds does not expand`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 6))

        val strokeMinY = TM + 4 * LS + LS * 0.2f
        val strokeMaxY = TM + 6 * LS + LS * 0.8f
        simulateOverflow(strokeMinY, strokeMaxY)

        val area = documentModel.main.diagramAreas[0]
        assertEquals("Start should remain 3", 3, area.startLineIndex)
        assertEquals("Height should remain 6", 6, area.heightInLines)
    }

    @Test
    fun `scenario 3 - overflow crosses diagram end, text below stays outside`() {
        // Hello World on line 1
        val helloStroke = makeTextStroke(line = 1)
        documentModel.main.activeStrokes.add(helloStroke)

        // Diagram at lines 3-5 with a shape
        val shapeStroke = makeTextStroke(line = 4)
        documentModel.main.activeStrokes.add(shapeStroke)
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 3))

        // Text underneath on line 7
        val textBelow = makeTextStroke(line = 7)
        documentModel.main.activeStrokes.add(textBelow)

        // Overflow stroke from line 4 (inside) to line 6 (below diagram end at 5)
        val overflowStroke = makeLineStroke(startLine = 4, endLine = 6)
        documentModel.main.activeStrokes.add(overflowStroke)

        simulateOverflow(overflowStroke.strokeId,
            strokeMinY = TM + 4 * LS + LS * 0.2f,
            strokeMaxY = TM + 6 * LS + LS * 0.5f
        )

        val area = documentModel.main.diagramAreas[0]

        // Hello World stays at line 1, outside diagram
        assertEquals("Hello stays at 1", 1, lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[0]))
        assertTrue("Hello outside diagram", !area.containsLine(1))

        // Shape inside diagram
        assertTrue("Shape inside diagram", area.containsLine(lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[1])))

        // Overflow stroke inside diagram (not shifted — it's diagram content)
        val overflowLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[3])
        assertTrue("Overflow inside diagram", area.containsLine(overflowLine))

        // Text below shifted down, outside diagram
        val textBelowLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[2])
        assertTrue("Text below outside diagram", !area.containsLine(textBelowLine))
        assertTrue("Text below shifted further down", textBelowLine > 7)
    }

    @Test
    fun `scenario 4 - overflow overlaps text below, text shifts down`() {
        // Hello World on line 1
        val helloStroke = makeTextStroke(line = 1)
        documentModel.main.activeStrokes.add(helloStroke)

        // Diagram at lines 3-5 with a shape
        val shapeStroke = makeTextStroke(line = 4)
        documentModel.main.activeStrokes.add(shapeStroke)
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 3))

        // Text underneath on line 6 (same line as overflow will reach)
        val textBelow = makeTextStroke(line = 6)
        documentModel.main.activeStrokes.add(textBelow)

        // Overflow stroke from line 4 (inside) to line 6 (overlaps text below)
        val overflowStroke = makeLineStroke(startLine = 4, endLine = 6)
        documentModel.main.activeStrokes.add(overflowStroke)

        simulateOverflow(overflowStroke.strokeId,
            strokeMinY = TM + 4 * LS + LS * 0.2f,
            strokeMaxY = TM + 6 * LS + LS * 0.5f
        )

        val area = documentModel.main.diagramAreas[0]

        // Overflow stroke is inside diagram
        val overflowLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[3])
        assertTrue("Overflow inside diagram", area.containsLine(overflowLine))

        // Text below was on same line as overflow — should be shifted out
        val textBelowLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[2])
        assertTrue("Text below outside diagram", !area.containsLine(textBelowLine))
    }

    @Test
    fun `text descender crossing diagram boundary is not shifted`() {
        // Text "World" on line 3 — the "d" descender dips slightly into line 4
        val textWithDescender = InkStroke(
            points = makePoints(
                100f to TM + 3 * LS + LS * 0.3f,  // top of text
                150f to TM + 3 * LS + LS * 0.5f,  // middle
                180f to TM + 4 * LS + LS * 0.1f   // descender dips into line 4
            ),
            strokeType = StrokeType.FREEHAND
        )
        documentModel.main.activeStrokes.add(textWithDescender)

        // Diagram at lines 4-7 with a shape
        val shapeStroke = makeTextStroke(line = 6)
        documentModel.main.activeStrokes.add(shapeStroke)
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 4, heightInLines = 4))

        // Overflow stroke from line 3 to line 5 (crosses diagram start)
        val overflowStroke = makeLineStroke(startLine = 3, endLine = 5)
        documentModel.main.activeStrokes.add(overflowStroke)

        simulateOverflow(overflowStroke.strokeId,
            strokeMinY = TM + 3 * LS + LS * 0.2f,
            strokeMaxY = TM + 5 * LS + LS * 0.5f
        )

        // Text with descender should stay at line 3 (centroid is on line 3)
        val textLine = lineSegmenter.getStrokeLineIndex(documentModel.main.activeStrokes[0])
        assertEquals("Text with descender should stay at line 3", 3, textLine)

        // Text should be outside the diagram
        val area = documentModel.main.diagramAreas[0]
        assertTrue("Text should be outside diagram", !area.containsLine(textLine))
    }

    // ── Freeform dwell creates diagram area ──────────────────────────────────

    @Test
    fun `freehand stroke with dwell outside diagram creates diagram area`() {
        // Simulate: user draws a freehand doodle on line 5 with start-dwell
        val stroke = makeTextStroke(line = 5)
        simulateDiagramShapeDetected(stroke)

        assertEquals("Should have 1 diagram area", 1, documentModel.main.diagramAreas.size)
        val area = documentModel.main.diagramAreas[0]
        assertTrue("Diagram should contain line 5", area.containsLine(5))
    }

    @Test
    fun `freehand dwell diagram merges with adjacent shape diagram`() {
        // First: shape creates diagram at lines 3-5
        val shapeStroke = makeRectangleStroke(startLine = 3, endLine = 5)
        simulateDiagramShapeDetected(shapeStroke)
        assertEquals(1, documentModel.main.diagramAreas.size)

        // Freehand dwell stroke at line 5 (overlapping) → should merge
        val freehandStroke = makeTextStroke(line = 5)
        simulateDiagramShapeDetected(freehandStroke)

        assertEquals("Should merge into 1 area", 1, documentModel.main.diagramAreas.size)
        assertTrue("Merged area covers line 3", documentModel.main.diagramAreas[0].containsLine(3))
        assertTrue("Merged area covers line 5", documentModel.main.diagramAreas[0].containsLine(5))
    }

    @Test
    fun `undo reverses freehand dwell diagram creation`() {
        saveUndoSnapshot()  // baseline

        val stroke = makeTextStroke(line = 5)
        saveUndoSnapshot()
        documentModel.main.activeStrokes.add(stroke)
        simulateDiagramShapeDetected(stroke)

        assertEquals(1, documentModel.main.diagramAreas.size)

        undo()
        assertEquals("Undo should remove diagram area", 0, documentModel.main.diagramAreas.size)
    }

    // ── Sticky zone expansion ────────────────────────────────────────────────

    /** Check if a line has pre-existing strokes not inside a diagram area. */
    private fun hasTextStrokesOnLine(lineIdx: Int, excluding: InkStroke? = null): Boolean =
        documentModel.main.activeStrokes.any {
            it !== excluding &&
            !documentModel.main.diagramAreas.any { area -> area.containsLine(lineSegmenter.getStrokeLineIndex(it)) } &&
            lineSegmenter.getStrokeLineIndex(it) == lineIdx
        }

    /**
     * Simulate the sticky zone logic from WritingCoordinator.onStrokeCompleted:
     * Only geometric (shape-snapped) strokes trigger sticky expansion.
     * Freehand strokes don't expand — they're likely text.
     */
    private fun simulateStickyZoneExpansion(stroke: InkStroke): Boolean {
        val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)
        val adjacentArea = documentModel.main.diagramAreas.find {
            lineIdx == it.startLineIndex - 1 || lineIdx == it.endLineIndex + 1
        } ?: return false

        // Only geometric strokes trigger expansion
        if (!stroke.isGeometric) return false

        // Don't expand into lines with pre-existing text strokes
        if (hasTextStrokesOnLine(lineIdx, excluding = stroke)) return false

        val newStart = minOf(adjacentArea.startLineIndex, lineIdx)
        val newEnd = maxOf(adjacentArea.endLineIndex, lineIdx)
        documentModel.main.diagramAreas.remove(adjacentArea)
        documentModel.main.diagramAreas.add(adjacentArea.copy(
            startLineIndex = newStart,
            heightInLines = newEnd - newStart + 1
        ))
        return true
    }

    @Test
    fun `geometric stroke adjacent below diagram expands via sticky zone`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 3))

        val stroke = makeGeometricStroke(line = 6)
        val expanded = simulateStickyZoneExpansion(stroke)

        assertTrue("Geometric stroke should expand via sticky zone", expanded)
        assertEquals(1, documentModel.main.diagramAreas.size)
        val area = documentModel.main.diagramAreas[0]
        assertEquals("Start should remain 3", 3, area.startLineIndex)
        assertTrue("Should now contain line 6", area.containsLine(6))
    }

    @Test
    fun `geometric stroke adjacent above diagram expands via sticky zone`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 5, heightInLines = 3))

        val stroke = makeGeometricStroke(line = 4)
        val expanded = simulateStickyZoneExpansion(stroke)

        assertTrue("Geometric stroke should expand via sticky zone", expanded)
        assertEquals(1, documentModel.main.diagramAreas.size)
        val area = documentModel.main.diagramAreas[0]
        assertEquals("Start should expand to 4", 4, area.startLineIndex)
        assertTrue("Should contain line 7", area.containsLine(7))
    }

    @Test
    fun `freehand text adjacent to diagram does NOT expand via sticky zone`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 3))

        // Freehand text stroke on line 6 (adjacent below) — should NOT expand
        val stroke = makeTextStroke(line = 6)
        val expanded = simulateStickyZoneExpansion(stroke)

        assertTrue("Freehand text should NOT expand", !expanded)
        assertEquals("Height should remain 3", 3, documentModel.main.diagramAreas[0].heightInLines)
    }

    @Test
    fun `stroke 2 lines away from diagram does not trigger sticky zone`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 3))

        val stroke = makeGeometricStroke(line = 7)
        val expanded = simulateStickyZoneExpansion(stroke)

        assertTrue("Should NOT expand — not adjacent", !expanded)
        assertEquals("Height should remain 3", 3, documentModel.main.diagramAreas[0].heightInLines)
    }

    // ── Text stroke buffer protection ────────────────────────────────────────

    @Test
    fun `sticky zone does not expand for geometric stroke into line with pre-existing text`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 3))

        // Pre-existing text stroke on line 6
        val textStroke = makeTextStroke(line = 6)
        documentModel.main.activeStrokes.add(textStroke)

        // New geometric stroke also on line 6 — geometric but text exists
        val newStroke = makeGeometricStroke(line = 6)
        documentModel.main.activeStrokes.add(newStroke)
        val expanded = simulateStickyZoneExpansion(newStroke)

        assertTrue("Should NOT expand — line 6 has pre-existing text", !expanded)
        assertEquals("Height should remain 3", 3, documentModel.main.diagramAreas[0].heightInLines)
    }

    @Test
    fun `sticky zone expands into empty adjacent line for geometric stroke`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 3))

        // Geometric stroke on line 6 (adjacent below, no pre-existing strokes)
        val newStroke = makeGeometricStroke(line = 6)
        documentModel.main.activeStrokes.add(newStroke)
        val expanded = simulateStickyZoneExpansion(newStroke)

        assertTrue("Should expand — geometric on empty line", expanded)
        assertTrue("Should contain line 6", documentModel.main.diagramAreas[0].containsLine(6))
    }

    @Test
    fun `sticky zone does not expand above into line with pre-existing text`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 5, heightInLines = 3))

        // Pre-existing text stroke on line 4
        val textStroke = makeTextStroke(line = 4)
        documentModel.main.activeStrokes.add(textStroke)

        // New geometric stroke on line 4 (adjacent above diagram, but text exists)
        val newStroke = makeGeometricStroke(line = 4)
        documentModel.main.activeStrokes.add(newStroke)
        val expanded = simulateStickyZoneExpansion(newStroke)

        assertTrue("Should NOT expand — line 4 has pre-existing text", !expanded)
        assertEquals("Start should remain 5", 5, documentModel.main.diagramAreas[0].startLineIndex)
    }

    // ── Padding respects text lines ──────────────────────────────────────────

    @Test
    fun `diagram creation does not absorb adjacent text above`() {
        val textStroke = makeTextStroke(line = 4)
        documentModel.main.activeStrokes.add(textStroke)

        val shapeStroke = makeRectangleStroke(startLine = 5, endLine = 7)
        simulateDiagramShapeDetected(shapeStroke)

        val area = documentModel.main.diagramAreas[0]
        assertEquals("Start should be 5 (no padding into text)", 5, area.startLineIndex)
        assertTrue("Should contain line 7", area.containsLine(7))
        assertTrue("Should NOT contain text line 4", !area.containsLine(4))
    }

    @Test
    fun `diagram creation does not absorb adjacent text below`() {
        val textStroke = makeTextStroke(line = 8)
        documentModel.main.activeStrokes.add(textStroke)

        val shapeStroke = makeRectangleStroke(startLine = 5, endLine = 7)
        simulateDiagramShapeDetected(shapeStroke)

        val area = documentModel.main.diagramAreas[0]
        assertEquals("Start should be 5", 5, area.startLineIndex)
        assertTrue("Should contain line 7", area.containsLine(7))
        assertTrue("Should NOT contain text line 8", !area.containsLine(8))
    }

    @Test
    fun `diagram creation uses exact bounds when no text nearby`() {
        val shapeStroke = makeRectangleStroke(startLine = 5, endLine = 7)
        simulateDiagramShapeDetected(shapeStroke)

        val area = documentModel.main.diagramAreas[0]
        assertEquals("Start should be 5 (exact bounds)", 5, area.startLineIndex)
        assertTrue("Should contain line 7", area.containsLine(7))
    }

    // ── Diagram shrink on scratch-out ────────────────────────────────────────

    /**
     * Simulate shrinkDiagramAfterErase: compute new bounds from remaining strokes,
     * shrink the area, and shift content to reclaim freed lines.
     */
    private fun simulateShrink(area: DiagramArea) {
        val ls = LS
        val remaining = documentModel.main.activeStrokes.filter {
            area.containsLine(lineSegmenter.getStrokeLineIndex(it))
        }

        if (remaining.isEmpty()) {
            val linesFreed = area.heightInLines
            val shiftUpPx = linesFreed * ls
            val oldEnd = area.endLineIndex
            documentModel.main.diagramAreas.remove(area)
            val shifted = documentModel.main.activeStrokes.map { stroke ->
                if (lineSegmenter.getStrokeLineIndex(stroke) > oldEnd) stroke.shiftY(-shiftUpPx) else stroke
            }
            documentModel.main.activeStrokes.clear()
            documentModel.main.activeStrokes.addAll(shifted)
            return
        }

        val minLine = remaining.minOf { lineSegmenter.getLineIndex(it.points.minOf { p -> p.y }) }
        val maxLine = remaining.maxOf { lineSegmenter.getLineIndex(it.points.maxOf { p -> p.y }) }
        val newStart = (minLine - 1).coerceAtLeast(area.startLineIndex)
        val newEnd = (maxLine + 1).coerceAtMost(area.endLineIndex)
        val linesFreedBelow = area.endLineIndex - newEnd
        val linesFreedAbove = newStart - area.startLineIndex
        if (linesFreedAbove + linesFreedBelow == 0) return

        documentModel.main.diagramAreas.remove(area)
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = newStart, heightInLines = newEnd - newStart + 1))

        if (linesFreedBelow > 0) {
            val shiftUpPx = linesFreedBelow * ls
            val shifted = documentModel.main.activeStrokes.map { stroke ->
                if (lineSegmenter.getStrokeLineIndex(stroke) > area.endLineIndex) stroke.shiftY(-shiftUpPx) else stroke
            }
            documentModel.main.activeStrokes.clear()
            documentModel.main.activeStrokes.addAll(shifted)
        }
        if (linesFreedAbove > 0) {
            val shiftUpPx = linesFreedAbove * ls
            val shifted = documentModel.main.activeStrokes.map { stroke ->
                if (lineSegmenter.getStrokeLineIndex(stroke) >= newStart) stroke.shiftY(-shiftUpPx) else stroke
            }
            documentModel.main.activeStrokes.clear()
            documentModel.main.activeStrokes.addAll(shifted)
            val shiftedAreas = documentModel.main.diagramAreas.map { other ->
                if (other.startLineIndex >= newStart)
                    other.copy(startLineIndex = other.startLineIndex - linesFreedAbove)
                else other
            }
            documentModel.main.diagramAreas.clear()
            documentModel.main.diagramAreas.addAll(shiftedAreas)
        }
    }

    @Test
    fun `scenario 1 - erase near bottom shrinks diagram and shifts text up`() {
        // Text above
        val helloStroke = makeTextStroke(line = 1)
        documentModel.main.activeStrokes.add(helloStroke)

        // Diagram at lines 3-7
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 5))
        val topShape = makeTextStroke(line = 4)  // stays
        documentModel.main.activeStrokes.add(topShape)
        val bottomShape = makeTextStroke(line = 6) // will be erased
        documentModel.main.activeStrokes.add(bottomShape)

        // Text below diagram
        val textBelow = makeTextStroke(line = 9)
        documentModel.main.activeStrokes.add(textBelow)

        // Erase bottom shape
        documentModel.main.activeStrokes.remove(bottomShape)

        // Shrink
        simulateShrink(documentModel.main.diagramAreas[0])

        // Diagram should have shrunk
        val area = documentModel.main.diagramAreas[0]
        assertTrue("Diagram should not contain line 6 anymore", !area.containsLine(6))
        assertTrue("Diagram should still contain line 4", area.containsLine(lineSegmenter.getStrokeLineIndex(
            documentModel.main.activeStrokes.find { it.strokeId == topShape.strokeId }!!
        )))

        // Text below should have shifted up
        val textBelowLine = lineSegmenter.getStrokeLineIndex(
            documentModel.main.activeStrokes.find { it.strokeId == textBelow.strokeId }!!
        )
        assertTrue("Text below should have shifted up", textBelowLine < 9)

        // Hello World stays
        assertEquals("Hello stays at 1", 1, lineSegmenter.getStrokeLineIndex(
            documentModel.main.activeStrokes.find { it.strokeId == helloStroke.strokeId }!!
        ))
    }

    @Test
    fun `scenario 2 - erase near top shrinks diagram from top`() {
        val helloStroke = makeTextStroke(line = 1)
        documentModel.main.activeStrokes.add(helloStroke)

        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 5))
        val topShape = makeTextStroke(line = 4)  // will be erased
        documentModel.main.activeStrokes.add(topShape)
        val bottomShape = makeTextStroke(line = 6) // stays
        documentModel.main.activeStrokes.add(bottomShape)

        val textBelow = makeTextStroke(line = 9)
        documentModel.main.activeStrokes.add(textBelow)

        // Erase top shape
        documentModel.main.activeStrokes.remove(topShape)

        simulateShrink(documentModel.main.diagramAreas[0])

        // Diagram should have shrunk from the top
        val area = documentModel.main.diagramAreas[0]
        assertTrue("Diagram should still contain the remaining shape",
            area.containsLine(lineSegmenter.getStrokeLineIndex(
                documentModel.main.activeStrokes.find { it.strokeId == bottomShape.strokeId }!!
            )))
        assertTrue("Diagram height should be smaller than 5", area.heightInLines < 5)
    }

    @Test
    fun `erase all strokes removes diagram entirely`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 5))
        val shape = makeTextStroke(line = 5)
        documentModel.main.activeStrokes.add(shape)

        val textBelow = makeTextStroke(line = 9)
        documentModel.main.activeStrokes.add(textBelow)

        // Erase the only stroke
        documentModel.main.activeStrokes.remove(shape)

        simulateShrink(documentModel.main.diagramAreas[0])

        assertTrue("Diagram area should be removed", documentModel.main.diagramAreas.isEmpty())

        // Text below should have shifted up
        val textBelowLine = lineSegmenter.getStrokeLineIndex(
            documentModel.main.activeStrokes.find { it.strokeId == textBelow.strokeId }!!
        )
        assertTrue("Text below should have shifted up", textBelowLine < 9)
    }

    @Test
    fun `erase middle stroke does not shrink diagram`() {
        documentModel.main.diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 5))
        val topShape = makeTextStroke(line = 4)
        documentModel.main.activeStrokes.add(topShape)
        val middleShape = makeTextStroke(line = 5)  // will be erased
        documentModel.main.activeStrokes.add(middleShape)
        val bottomShape = makeTextStroke(line = 6)
        documentModel.main.activeStrokes.add(bottomShape)

        documentModel.main.activeStrokes.remove(middleShape)

        val areaBefore = documentModel.main.diagramAreas[0].copy()
        simulateShrink(documentModel.main.diagramAreas[0])

        // Diagram should stay the same — strokes still at top and bottom
        val area = documentModel.main.diagramAreas[0]
        assertEquals("Start should be unchanged", areaBefore.startLineIndex, area.startLineIndex)
        assertEquals("Height should be unchanged", areaBefore.heightInLines, area.heightInLines)
    }
}
