package com.writer.view

import android.graphics.RectF
import com.writer.model.DiagramNode
import com.writer.model.StrokeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.hypot

/**
 * Tests for [DiagramNodeSnap]: magnetic snap of arrow endpoints to diagram-node perimeters.
 *
 * All geometry tests use the `*Raw` internal functions that accept plain floats,
 * avoiding the android.graphics.RectF stub issue in JVM unit tests.
 *
 * ── Bug 2 regression ─────────────────────────────────────────────────────────────────────
 * When both arrow endpoints are on opposite sides of the *same* node edge (one just outside,
 * one just inside), [DiagramNodeSnap.nearestPerimeterPointRaw] maps them to the **same**
 * perimeter coordinate.  The resulting arrow has a zero-length line — only the arrowhead
 * is visible at the node.
 *
 * [DiagramNodeSnap.snapArrowEndpointsRaw] must guard against this by leaving the FROM
 * endpoint unsnapped when snapping would collapse both ends to the same point.
 */
class DiagramNodeSnapTest {

    companion object {
        private const val LS = 118f
        private val THRESHOLD = 1.5f * LS  // 177 px — same as HandwritingCanvasView

        /** Build a simple rectangle DiagramNode using raw bounds (avoids RectF stub). */
        private fun rectNodeRaw(
            left: Float, top: Float, right: Float, bottom: Float, id: String = "n1"
        ): Triple<String, StrokeType, FloatArray> =
            Triple(id, StrokeType.RECTANGLE, floatArrayOf(left, top, right, bottom))

        /** Nearest perimeter point on a rectangle defined by raw bounds. */
        private fun perimRaw(px: Float, py: Float, bounds: FloatArray): Pair<Float, Float> =
            DiagramNodeSnap.nearestPerimeterPointRaw(
                px, py, bounds[0], bounds[1], bounds[2], bounds[3], StrokeType.RECTANGLE
            )

        /** distToBbox using raw bounds array [left, top, right, bottom]. */
        private fun distRaw(px: Float, py: Float, bounds: FloatArray): Float =
            DiagramNodeSnap.distToBboxRaw(px, py, bounds[0], bounds[1], bounds[2], bounds[3])

        // Convenience: create a Map<String, DiagramNode> for snapArrowEndpointsRaw.
        // DiagramNode is used only as a carrier here; its bounds are set via public fields
        // (field writes work even in JVM unit tests with isReturnDefaultValues = true).
        private fun makeNodes(vararg defs: Triple<String, StrokeType, FloatArray>): Map<String, DiagramNode> {
            return defs.associate { (id, type, b) ->
                val bounds = RectF()
                bounds.left = b[0]; bounds.top = b[1]; bounds.right = b[2]; bounds.bottom = b[3]
                id to DiagramNode(id, type, bounds)
            }
        }
    }

    // ── nearestPerimeterPointRaw: basic behaviour ─────────────────────────────────────────

    @Test fun outsideLeftEdge_snapsToLeftEdge() {
        val b = floatArrayOf(100f, 100f, 300f, 200f)
        val (x, y) = perimRaw(50f, 150f, b)
        assertEquals(100f, x, 0.01f)
        assertEquals(150f, y, 0.01f)
    }

    @Test fun outsideRightEdge_snapsToRightEdge() {
        val b = floatArrayOf(100f, 100f, 300f, 200f)
        val (x, y) = perimRaw(350f, 150f, b)
        assertEquals(300f, x, 0.01f)
        assertEquals(150f, y, 0.01f)
    }

    @Test fun insideNearLeftEdge_snapsToLeftEdge() {
        val b = floatArrayOf(100f, 100f, 300f, 200f)
        val (x, y) = perimRaw(105f, 150f, b)
        assertEquals(100f, x, 0.01f)
        assertEquals(150f, y, 0.01f)
    }

    @Test fun insideNearRightEdge_snapsToRightEdge() {
        val b = floatArrayOf(100f, 100f, 300f, 200f)
        val (x, y) = perimRaw(295f, 150f, b)
        assertEquals(300f, x, 0.01f)
        assertEquals(150f, y, 0.01f)
    }

    /**
     * Documents the root cause of Bug 2:
     * a point 1 px *outside* the left edge and a point 1 px *inside* the left edge
     * both project to the exact same perimeter coordinate (left, py).
     *
     * This test currently PASSES — it is here to document the raw geometric fact.
     * The fix lives in [snapArrowEndpointsRaw], not in [nearestPerimeterPointRaw].
     */
    @Test fun justOutsideAndJustInsideSameEdge_documentsBug_samePerimeterPoint() {
        val b = floatArrayOf(100f, 100f, 300f, 200f)
        val outside = perimRaw(99f, 150f, b)   // 1 px outside left edge
        val inside  = perimRaw(101f, 150f, b)  // 1 px inside left edge
        // Both collapse to (100, 150) — the root cause of the zero-length arrow bug.
        assertEquals(
            "Both sides of edge map to same perimeter point (Bug 2 root cause)",
            outside, inside
        )
    }

    // ── snapArrowEndpointsRaw: Bug 2 failing tests ────────────────────────────────────────

    /**
     * BUG 2 — CURRENTLY FAILS.
     *
     * When the user draws a short arrow that crosses one edge of a node (start just outside,
     * end just inside), both raw perimeter snaps produce the same coordinate → zero-length
     * line → only the arrowhead renders at the node.
     *
     * [snapArrowEndpointsRaw] must detect the degenerate collapse and leave the FROM endpoint
     * at its original position, preserving a visible arrow line.
     */
    @Test fun arrowCrossingNodeEdge_notDegenerate() {
        val nodes = makeNodes(Triple("n1", StrokeType.RECTANGLE, floatArrayOf(100f, 100f, 300f, 200f)))

        val (from, to, _) = DiagramNodeSnap.snapArrowEndpointsRaw(
            fromPx = 99f, fromPy = 150f,   // 1 px outside left edge
            toPx   = 101f, toPy  = 150f,   // 1 px inside left edge
            nodes = nodes, threshold = THRESHOLD
        )
        assertNotEquals("Arrow endpoints must not collapse to the same point (Bug 2)", from, to)
    }

    /**
     * BUG 2 variant — start well outside, end just inside same edge.
     * Even a slightly-longer stroke that barely enters the node must have a visible line.
     */
    @Test fun arrowBarelyEnteringNode_endNotCollapsingToStart() {
        val nodes = makeNodes(Triple("n1", StrokeType.RECTANGLE, floatArrayOf(100f, 100f, 300f, 200f)))

        val (from, to, _) = DiagramNodeSnap.snapArrowEndpointsRaw(
            fromPx = 80f,  fromPy = 150f,   // 20 px outside left edge (within threshold)
            toPx   = 110f, toPy  = 150f,    // 10 px inside left edge
            nodes = nodes, threshold = THRESHOLD
        )
        assertNotEquals(
            "Arrow endpoints must differ even when start and end are both near the same edge (Bug 2)",
            from, to
        )
    }

    // ── snapArrowEndpointsRaw: normal (non-degenerate) cases must still work ─────────────

    @Test fun arrowBetweenTwoNodes_snapsCorrectly() {
        // nodeA at left (0–200 × 100–200), nodeB at right (300–500 × 100–200).
        // Use points close to the shared gap so they clearly snap to the facing edges.
        val nodes = makeNodes(
            Triple("a", StrokeType.RECTANGLE, floatArrayOf(  0f, 100f, 200f, 200f)),
            Triple("b", StrokeType.RECTANGLE, floatArrayOf(300f, 100f, 500f, 200f))
        )

        val (from, to, ids) = DiagramNodeSnap.snapArrowEndpointsRaw(
            fromPx = 180f, fromPy = 150f,   // inside nodeA, 20 px from right edge → snaps to (200, 150)
            toPx   = 320f, toPy  = 150f,    // inside nodeB, 20 px from left edge  → snaps to (300, 150)
            nodes = nodes, threshold = THRESHOLD
        )
        assertNotEquals("Arrow between two nodes must have distinct endpoints", from, to)
        assertEquals("FROM should snap to nodeA right edge", Pair(200f, 150f), from)
        assertEquals("TO should snap to nodeB left edge",   Pair(300f, 150f), to)
        assertEquals("fromNodeId should be 'a'", "a", ids.first)
        assertEquals("toNodeId should be 'b'",   "b", ids.second)
    }

    @Test fun arrowFromUnconnectedPointToNode_fromUnsnapped() {
        // FROM is 250 px from nodeB (beyond threshold 177) → unsnapped.
        val nodes = makeNodes(
            Triple("b", StrokeType.RECTANGLE, floatArrayOf(300f, 100f, 500f, 200f))
        )

        val (from, to, ids) = DiagramNodeSnap.snapArrowEndpointsRaw(
            fromPx = 50f,  fromPy = 150f,   // far left, distToBbox = 250 px > threshold
            toPx   = 400f, toPy  = 150f,    // inside nodeB
            nodes = nodes, threshold = THRESHOLD
        )
        assertEquals("Unconnected FROM should stay at original position", Pair(50f, 150f), from)
        assertNotEquals("TO should be snapped to node perimeter", Pair(400f, 150f), to)
        assertNull("fromNodeId should be null (unsnapped)", ids.first)
        assertNotNull("toNodeId should be non-null", ids.second)
    }

    @Test fun arrowFromNodeToEmptySpace_toUnsnapped() {
        val nodes = makeNodes(
            Triple("a", StrokeType.RECTANGLE, floatArrayOf(0f, 100f, 200f, 200f))
        )

        val (from, to, ids) = DiagramNodeSnap.snapArrowEndpointsRaw(
            fromPx = 100f, fromPy = 150f,   // inside nodeA
            toPx   = 800f, toPy  = 150f,    // far right, no node nearby
            nodes = nodes, threshold = THRESHOLD
        )
        assertNotNull("fromNodeId should be set", ids.first)
        assertNull("toNodeId should be null (no nearby node)", ids.second)
        assertEquals("TO should stay at original position", Pair(800f, 150f), to)
    }

    // ── distToBboxRaw ─────────────────────────────────────────────────────────────────────

    @Test fun distToBbox_pointInside_returnsZero() {
        assertEquals(0f, distRaw(100f, 50f, floatArrayOf(0f, 0f, 200f, 100f)), 0.01f)
    }

    @Test fun distToBbox_pointOutsideLeft_returnsHorizontalDist() {
        assertEquals(50f, distRaw(50f, 100f, floatArrayOf(100f, 0f, 300f, 200f)), 0.01f)
    }

    @Test fun distToBbox_pointOutsideCorner_returnsDiagonalDist() {
        val expected = hypot(50f, 50f)
        assertEquals(expected, distRaw(150f, 150f, floatArrayOf(0f, 0f, 100f, 100f)), 0.01f)
    }
}
