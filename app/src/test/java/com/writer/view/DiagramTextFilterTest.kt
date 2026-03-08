package com.writer.view

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DiagramTextFilter]: strokes inside diagram shapes must not
 * appear in text paragraphs (they are shape labels, already shown in the diagram).
 */
class DiagramTextFilterTest {

    // Helper: a stroke whose single point sits at (cx, cy)
    private fun stroke(cx: Float, cy: Float, type: StrokeType = StrokeType.FREEHAND): InkStroke {
        val pt = StrokePoint(cx, cy, 1f, 0L)
        return InkStroke(points = listOf(pt), strokeType = type)
    }

    // Node bounds as [left, top, right, bottom]
    private fun bounds(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, b)

    // ── single line fully inside one node ──────────────────────────────────────

    @Test fun lineFullyInsideNode_isExcluded() {
        val strokesByLine = mapOf(
            0 to listOf(stroke(50f, 50f))
        )
        val nodes = listOf(bounds(0f, 0f, 100f, 100f))
        val result = DiagramTextFilter.diagramOnlyLines(strokesByLine, nodes)
        assertTrue("Line 0 should be excluded (inside node)", 0 in result)
    }

    // ── single line fully outside all nodes ───────────────────────────────────

    @Test fun lineOutsideAllNodes_isNotExcluded() {
        val strokesByLine = mapOf(
            0 to listOf(stroke(200f, 200f))
        )
        val nodes = listOf(bounds(0f, 0f, 100f, 100f))
        val result = DiagramTextFilter.diagramOnlyLines(strokesByLine, nodes)
        assertFalse("Line 0 should NOT be excluded (outside node)", 0 in result)
    }

    // ── line has strokes both inside and outside → not excluded ───────────────

    @Test fun lineMixed_isNotExcluded() {
        val strokesByLine = mapOf(
            0 to listOf(stroke(50f, 50f), stroke(200f, 50f))
        )
        val nodes = listOf(bounds(0f, 0f, 100f, 100f))
        val result = DiagramTextFilter.diagramOnlyLines(strokesByLine, nodes)
        assertFalse("Line with mixed strokes should NOT be excluded", 0 in result)
    }

    // ── two separate lines: one inside, one outside ────────────────────────────

    @Test fun twoLines_onlyInsideLineExcluded() {
        val strokesByLine = mapOf(
            0 to listOf(stroke(50f, 50f)),   // inside node
            1 to listOf(stroke(200f, 200f))  // outside
        )
        val nodes = listOf(bounds(0f, 0f, 100f, 100f))
        val result = DiagramTextFilter.diagramOnlyLines(strokesByLine, nodes)
        assertTrue("Line 0 inside node should be excluded", 0 in result)
        assertFalse("Line 1 outside should NOT be excluded", 1 in result)
    }

    // ── stroke on node boundary is treated as inside ──────────────────────────

    @Test fun strokeOnBoundary_isTreatedAsInside() {
        val strokesByLine = mapOf(
            0 to listOf(stroke(0f, 0f))  // exactly on corner
        )
        val nodes = listOf(bounds(0f, 0f, 100f, 100f))
        val result = DiagramTextFilter.diagramOnlyLines(strokesByLine, nodes)
        assertTrue("Stroke on boundary should be treated as inside", 0 in result)
    }

    // ── no nodes → nothing excluded ───────────────────────────────────────────

    @Test fun noNodes_nothingExcluded() {
        val strokesByLine = mapOf(
            0 to listOf(stroke(50f, 50f))
        )
        val result = DiagramTextFilter.diagramOnlyLines(strokesByLine, emptyList())
        assertTrue("With no nodes, result should be empty", result.isEmpty())
    }

    // ── non-freehand strokes on a line don't block exclusion ──────────────────

    @Test fun nonFreehandStrokesIgnored_lineStillExcluded() {
        val strokesByLine = mapOf(
            0 to listOf(
                stroke(50f, 50f, StrokeType.FREEHAND),    // inside, freehand
                stroke(50f, 50f, StrokeType.RECTANGLE)     // inside, but not freehand → ignored
            )
        )
        val nodes = listOf(bounds(0f, 0f, 100f, 100f))
        val result = DiagramTextFilter.diagramOnlyLines(strokesByLine, nodes)
        assertTrue("Non-freehand strokes should not block exclusion", 0 in result)
    }

    // ── line with only non-freehand strokes → excluded (no freehand = no text) ─

    @Test fun lineWithNoFreehandStrokes_isExcluded() {
        val strokesByLine = mapOf(
            0 to listOf(stroke(50f, 50f, StrokeType.RECTANGLE))
        )
        val nodes = listOf(bounds(0f, 0f, 100f, 100f))
        val result = DiagramTextFilter.diagramOnlyLines(strokesByLine, nodes)
        assertTrue("Line with no freehand strokes has no text to show", 0 in result)
    }
}
