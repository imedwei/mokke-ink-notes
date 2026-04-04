package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [StrokeRouter] — stroke routing decisions extracted from
 * WritingCoordinator.onStrokeCompleted.
 */
class StrokeRouterTest {

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    private val diagramAreas = mutableListOf<DiagramArea>()
    private val diagramLines = mutableSetOf<Int>()
    private val textStrokeLines = mutableSetOf<Int>()
    private val everHiddenLines = mutableSetOf<Int>()

    private val host = object : StrokeRouter.Host {
        override val diagramAreas get() = this@StrokeRouterTest.diagramAreas.toList()
        override fun getStrokeLineIndex(stroke: InkStroke): Int {
            val yMean = stroke.points.sumOf { it.y.toDouble() }.toFloat() / stroke.points.size
            return ((yMean - tm) / ls).toInt().coerceAtLeast(0)
        }
        override fun isDiagramLine(lineIndex: Int) = lineIndex in diagramLines
        override fun hasTextStrokesOnLine(lineIndex: Int, excluding: InkStroke) = lineIndex in textStrokeLines
        override fun isEverHidden(lineIndex: Int) = lineIndex in everHiddenLines
    }

    private lateinit var router: StrokeRouter

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        diagramAreas.clear()
        diagramLines.clear()
        textStrokeLines.clear()
        everHiddenLines.clear()
        router = StrokeRouter(host)
    }

    private fun strokeOnLine(line: Int, type: StrokeType = StrokeType.FREEHAND, geometric: Boolean = false): InkStroke {
        val y = tm + line * ls + ls * 0.4f
        return InkStroke(
            points = listOf(
                StrokePoint(50f, y, 0.5f, 0L),
                StrokePoint(100f, y, 0.5f, 1L)
            ),
            strokeType = type,
            isGeometric = geometric
        )
    }

    // --- Diagram routing ---

    @Test
    fun `freehand stroke on diagram line returns DIAGRAM_STROKE with area`() {
        val area = DiagramArea(startLineIndex = 3, heightInLines = 2)
        diagramAreas.add(area)
        diagramLines.addAll(listOf(3, 4))

        val result = router.classifyStroke(strokeOnLine(3))
        assertEquals(StrokeRouter.Action.DIAGRAM_STROKE, result.action)
        assertEquals(3, result.lineIndex)
        assertNotNull(result.diagramArea)
    }

    @Test
    fun `geometric stroke on diagram line returns DIAGRAM_STROKE without area`() {
        diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 2))
        diagramLines.addAll(listOf(3, 4))

        val result = router.classifyStroke(strokeOnLine(3, StrokeType.RECTANGLE, geometric = true))
        assertEquals(StrokeRouter.Action.DIAGRAM_STROKE, result.action)
        assertNull("Non-freehand should not get diagramArea", result.diagramArea)
    }

    // --- Sticky zone expansion ---

    @Test
    fun `geometric stroke adjacent to diagram expands area`() {
        val area = DiagramArea(startLineIndex = 3, heightInLines = 2)
        diagramAreas.add(area)
        // Line 2 is adjacent (above), not a diagram line itself
        diagramLines.addAll(listOf(3, 4))

        val result = router.classifyStroke(strokeOnLine(2, StrokeType.RECTANGLE, geometric = true))
        assertEquals(StrokeRouter.Action.STICKY_EXPAND, result.action)
        assertEquals(area, result.expandedFrom)
        assertNotNull(result.expandedTo)
        assertEquals(2, result.expandedTo!!.startLineIndex)
        assertEquals(3, result.expandedTo!!.heightInLines)
    }

    @Test
    fun `freehand stroke adjacent to diagram does NOT expand`() {
        diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 2))
        diagramLines.addAll(listOf(3, 4))

        val result = router.classifyStroke(strokeOnLine(2))
        assertEquals(StrokeRouter.Action.TEXT_STROKE, result.action)
    }

    @Test
    fun `geometric stroke adjacent but text exists on line does NOT expand`() {
        diagramAreas.add(DiagramArea(startLineIndex = 3, heightInLines = 2))
        diagramLines.addAll(listOf(3, 4))
        textStrokeLines.add(2) // existing text on line 2

        val result = router.classifyStroke(strokeOnLine(2, StrokeType.RECTANGLE, geometric = true))
        assertEquals(StrokeRouter.Action.TEXT_STROKE, result.action)
    }

    // --- Normal text strokes ---

    @Test
    fun `normal text stroke returns TEXT_STROKE with correct lineIndex`() {
        val result = router.classifyStroke(strokeOnLine(5))
        assertEquals(StrokeRouter.Action.TEXT_STROKE, result.action)
        assertEquals(5, result.lineIndex)
    }

    @Test
    fun `first stroke does not trigger recognition of previous line`() {
        val result = router.classifyStroke(strokeOnLine(3))
        assertFalse(result.recognizePreviousLine)
    }

    @Test
    fun `switching lines triggers recognition of previous line`() {
        router.classifyStroke(strokeOnLine(3))
        val result = router.classifyStroke(strokeOnLine(5))
        assertTrue(result.recognizePreviousLine)
        assertEquals(3, result.previousLineIndex)
    }

    @Test
    fun `staying on same line does not trigger previous line recognition`() {
        router.classifyStroke(strokeOnLine(3))
        val result = router.classifyStroke(strokeOnLine(3))
        assertFalse(result.recognizePreviousLine)
    }

    @Test
    fun `stroke on ever-hidden line triggers re-recognition`() {
        everHiddenLines.add(3)
        val result = router.classifyStroke(strokeOnLine(3))
        assertTrue(result.reRecognizeLine)
    }

    @Test
    fun `stroke on non-hidden line does not trigger re-recognition`() {
        val result = router.classifyStroke(strokeOnLine(3))
        assertFalse(result.reRecognizeLine)
    }

    @Test
    fun `highestLineIndex only increases`() {
        router.classifyStroke(strokeOnLine(5))
        assertEquals(5, router.highestLineIndex)
        router.classifyStroke(strokeOnLine(2))
        assertEquals(5, router.highestLineIndex)
        router.classifyStroke(strokeOnLine(8))
        assertEquals(8, router.highestLineIndex)
    }
}
