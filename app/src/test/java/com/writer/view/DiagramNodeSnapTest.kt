package com.writer.view

import android.graphics.RectF
import com.writer.model.DiagramNode
import com.writer.model.StrokeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

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

    // ── detectSelfLoop ─────────────────────────────────────────────────────────────────────

    @Test fun selfLoop_bothEndpointsNearSameNode_returnsNodeId() {
        val nodes = makeNodes(Triple("n1", StrokeType.RECTANGLE, floatArrayOf(100f, 100f, 300f, 300f)))
        // Both endpoints near the node, pathLength long enough and curved
        val result = DiagramNodeSnap.detectSelfLoop(
            firstX = 305f, firstY = 200f,  // just outside right edge
            lastX = 200f, lastY = 95f,     // just above top edge
            pathLength = 600f,              // well above minPathLength and curved (endpointDist ~150)
            nodes = nodes, threshold = THRESHOLD, minPathLengthPx = 1.5f * LS
        )
        assertEquals("n1", result)
    }

    @Test fun selfLoop_endpointsNearDifferentNodes_returnsNull() {
        val nodes = makeNodes(
            Triple("a", StrokeType.RECTANGLE, floatArrayOf(0f, 100f, 100f, 200f)),
            Triple("b", StrokeType.RECTANGLE, floatArrayOf(400f, 100f, 500f, 200f))
        )
        val result = DiagramNodeSnap.detectSelfLoop(
            firstX = 50f, firstY = 150f,   // near node a
            lastX = 450f, lastY = 150f,     // near node b
            pathLength = 800f,
            nodes = nodes, threshold = THRESHOLD, minPathLengthPx = 1.5f * LS
        )
        assertNull("Different nodes should return null", result)
    }

    @Test fun selfLoop_pathTooShort_returnsNull() {
        val nodes = makeNodes(Triple("n1", StrokeType.RECTANGLE, floatArrayOf(100f, 100f, 300f, 300f)))
        val result = DiagramNodeSnap.detectSelfLoop(
            firstX = 305f, firstY = 200f,
            lastX = 200f, lastY = 95f,
            pathLength = 50f,  // way below minPathLength
            nodes = nodes, threshold = THRESHOLD, minPathLengthPx = 1.5f * LS
        )
        assertNull("Short path should return null", result)
    }

    @Test fun selfLoop_notCurvedEnough_returnsNull() {
        val nodes = makeNodes(Triple("n1", StrokeType.RECTANGLE, floatArrayOf(100f, 100f, 300f, 300f)))
        // Endpoints 100px apart, pathLength 180 → ratio 1.8 which is ≤ 2.0
        val result = DiagramNodeSnap.detectSelfLoop(
            firstX = 305f, firstY = 200f,
            lastX = 205f, lastY = 200f,     // ~100px apart
            pathLength = 180f,              // ratio = 1.8 ≤ 2.0
            nodes = nodes, threshold = THRESHOLD, minPathLengthPx = 1.5f * LS
        )
        assertNull("Insufficiently curved path should return null", result)
    }

    @Test fun selfLoop_scratchOutZigzagNearNode_shouldReject() {
        // A scratch-out zigzag whose endpoints happen to be near the same node.
        // The zigzag has ≥2 X-reversals — a clear scratch-out, not a self-loop.
        // detectSelfLoop currently accepts this because it only checks endpoints
        // and path curvature, not X-reversal count.
        val nodes = makeNodes(Triple("n1", StrokeType.RECTANGLE, floatArrayOf(100f, 100f, 300f, 300f)))

        // Zigzag: starts near right edge (305,200), zigzags left-right trending
        // toward top edge, ends near top edge (200,95).
        // X positions: 305→250→310→245→300→230→290→215→275→200
        // = 8 X-direction reversals → definite scratch-out
        // Path length of zigzag ≈ 600px, endpoint distance ≈ 148px → ratio 4.0 > 2.0
        val result = DiagramNodeSnap.detectSelfLoop(
            firstX = 305f, firstY = 200f,
            lastX = 200f, lastY = 95f,
            pathLength = 600f,
            nodes = nodes, threshold = THRESHOLD, minPathLengthPx = 1.5f * LS,
            xReversals = 8
        )
        assertNull("Scratch-out zigzag near node should NOT be detected as self-loop", result)
    }

    @Test fun selfLoop_noNearbyNode_returnsNull() {
        val nodes = makeNodes(Triple("n1", StrokeType.RECTANGLE, floatArrayOf(100f, 100f, 300f, 300f)))
        val result = DiagramNodeSnap.detectSelfLoop(
            firstX = 800f, firstY = 800f,
            lastX = 810f, lastY = 790f,
            pathLength = 600f,
            nodes = nodes, threshold = THRESHOLD, minPathLengthPx = 1.5f * LS
        )
        assertNull("No nearby node should return null", result)
    }

    // ── perimeterPointFromDirectionRaw ──────────────────────────────────────────────────────

    @Test fun perimeterFromDirection_rayRight_hitsRightEdge() {
        // Rectangle 100-300 x 100-200, center (200, 150)
        val (x, y) = DiagramNodeSnap.perimeterPointFromDirectionRaw(
            dx = 1f, dy = 0f,
            left = 100f, top = 100f, right = 300f, bottom = 200f,
            shapeType = StrokeType.RECTANGLE
        )
        assertEquals(300f, x, 0.01f)
        assertEquals(150f, y, 0.01f)
    }

    @Test fun perimeterFromDirection_rayUp_hitsTopEdge() {
        val (x, y) = DiagramNodeSnap.perimeterPointFromDirectionRaw(
            dx = 0f, dy = -1f,
            left = 100f, top = 100f, right = 300f, bottom = 200f,
            shapeType = StrokeType.RECTANGLE
        )
        assertEquals(200f, x, 0.01f)  // centerX
        assertEquals(100f, y, 0.01f)  // top edge
    }

    @Test fun perimeterFromDirection_twoDifferentDirections_differentPoints() {
        val bounds = floatArrayOf(100f, 100f, 300f, 200f)
        val p1 = DiagramNodeSnap.perimeterPointFromDirectionRaw(
            1f, 0f, bounds[0], bounds[1], bounds[2], bounds[3], StrokeType.RECTANGLE
        )
        val p2 = DiagramNodeSnap.perimeterPointFromDirectionRaw(
            0f, -1f, bounds[0], bounds[1], bounds[2], bounds[3], StrokeType.RECTANGLE
        )
        assertNotEquals("Different directions should give different perimeter points", p1, p2)
    }

    @Test fun perimeterFromDirection_zeroDirection_ellipse_mustNotReturnCenter() {
        // When consecutive stylus points are identical, direction is (0,0).
        // The function must NOT return the center — it should return a valid perimeter point.
        // Ellipse (circle) 100-300 x 100-300, center (200, 200), radius 100
        val (x, y) = DiagramNodeSnap.perimeterPointFromDirectionRaw(
            dx = 0f, dy = 0f,
            left = 100f, top = 100f, right = 300f, bottom = 300f,
            shapeType = StrokeType.ELLIPSE
        )
        val cx = 200f; val cy = 200f
        val distFromCenter = hypot(x - cx, y - cy)
        // Must be on the perimeter (radius 100), not at center
        assertEquals(
            "Zero direction should still return a perimeter point, not center",
            100f, distFromCenter, 1f
        )
    }

    @Test fun perimeterFromDirection_zeroDirection_rectangle_mustNotReturnCenter() {
        val (x, y) = DiagramNodeSnap.perimeterPointFromDirectionRaw(
            dx = 0f, dy = 0f,
            left = 100f, top = 100f, right = 300f, bottom = 200f,
            shapeType = StrokeType.RECTANGLE
        )
        val cx = 200f; val cy = 150f
        // Must be on a perimeter edge, not at center
        val onPerimeter = x == 100f || x == 300f || y == 100f || y == 200f
        assertTrue("Zero direction should return a perimeter point, not center ($x, $y)", onPerimeter)
    }

    // ── generateSelfLoopArc ─────────────────────────────────────────────────────────────────

    @Test fun selfLoopArc_startsAtStartPoint() {
        val arc = DiagramNodeSnap.generateSelfLoopArc(
            startX = 300f, startY = 200f,  // right edge midpoint
            endX = 200f, endY = 100f,      // top edge midpoint
            cx = 200f, cy = 200f,
            nodeWidth = 200f, nodeHeight = 200f
        )
        assertEquals(300f, arc.first().first, 0.01f)
        assertEquals(200f, arc.first().second, 0.01f)
    }

    @Test fun selfLoopArc_endsAtEndPoint() {
        val arc = DiagramNodeSnap.generateSelfLoopArc(
            startX = 300f, startY = 200f,
            endX = 200f, endY = 100f,
            cx = 200f, cy = 200f,
            nodeWidth = 200f, nodeHeight = 200f
        )
        assertEquals(200f, arc.last().first, 0.01f)
        assertEquals(100f, arc.last().second, 0.01f)
    }

    @Test fun selfLoopArc_bulgesOutwardFromNode() {
        // Node bbox: 100-300 x 100-300, center (200,200)
        val arc = DiagramNodeSnap.generateSelfLoopArc(
            startX = 300f, startY = 200f,   // right edge
            endX = 200f, endY = 100f,        // top edge
            cx = 200f, cy = 200f,
            nodeWidth = 200f, nodeHeight = 200f
        )
        // The arc should bulge significantly outward: its peak distance from center
        // should exceed the node's half-width (perimeter distance)
        val maxDist = arc.maxOf { (px, py) -> hypot(px - 200f, py - 200f) }
        val halfSize = 100f  // node half-width/height
        assertTrue(
            "Arc peak distance ($maxDist) should significantly exceed node half-size ($halfSize)",
            maxDist > halfSize * 1.2f
        )
    }

    @Test fun selfLoopArc_approachesStartFromOutside() {
        val arc = DiagramNodeSnap.generateSelfLoopArc(
            startX = 300f, startY = 200f,
            endX = 200f, endY = 100f,
            cx = 200f, cy = 200f,
            nodeWidth = 200f, nodeHeight = 200f
        )
        val d0 = hypot(arc[0].first - 200f, arc[0].second - 200f)
        val d1 = hypot(arc[1].first - 200f, arc[1].second - 200f)
        assertTrue("point[1] should be further from center than point[0]", d1 > d0)
    }

    @Test fun selfLoopArc_approachesEndFromOutside() {
        val arc = DiagramNodeSnap.generateSelfLoopArc(
            startX = 300f, startY = 200f,
            endX = 200f, endY = 100f,
            cx = 200f, cy = 200f,
            nodeWidth = 200f, nodeHeight = 200f
        )
        val n = arc.size
        val dLast = hypot(arc[n - 1].first - 200f, arc[n - 1].second - 200f)
        val dPrev = hypot(arc[n - 2].first - 200f, arc[n - 2].second - 200f)
        assertTrue("point[N-2] should be further from center than point[N-1]", dPrev > dLast)
    }

    @Test fun selfLoopArc_generatesRequestedPointCount() {
        val arc = DiagramNodeSnap.generateSelfLoopArc(
            startX = 300f, startY = 200f,
            endX = 200f, endY = 100f,
            cx = 200f, cy = 200f,
            nodeWidth = 200f, nodeHeight = 200f,
            numPoints = 20
        )
        assertEquals(21, arc.size)  // numPoints + 1
    }

    @Test fun selfLoopArc_worksForEllipseNode() {
        // Ellipse node 100-300 x 100-300 — perimeter points on the ellipse
        // Use right and top midpoints as snap points
        val arc = DiagramNodeSnap.generateSelfLoopArc(
            startX = 300f, startY = 200f,
            endX = 200f, endY = 100f,
            cx = 200f, cy = 200f,
            nodeWidth = 200f, nodeHeight = 200f
        )
        // Same properties should hold: starts at start, ends at end, bulges outward
        assertEquals(300f, arc.first().first, 0.01f)
        assertEquals(200f, arc.first().second, 0.01f)
        assertEquals(200f, arc.last().first, 0.01f)
        assertEquals(100f, arc.last().second, 0.01f)
        val d0 = hypot(arc[0].first - 200f, arc[0].second - 200f)
        val d1 = hypot(arc[1].first - 200f, arc[1].second - 200f)
        assertTrue("Ellipse: point[1] further from center than point[0]", d1 > d0)
    }

    @Test fun selfLoopArc_closeSnapPoints_stillProducesVisibleArc() {
        // Start and end perimeter points are close together (both near top-right)
        val arc = DiagramNodeSnap.generateSelfLoopArc(
            startX = 300f, startY = 180f,   // right edge, slightly above center
            endX = 280f, endY = 100f,        // top edge, slightly right of center
            cx = 200f, cy = 200f,
            nodeWidth = 200f, nodeHeight = 200f
        )
        // Arc should still bulge significantly outward
        val maxDist = arc.maxOf { (px, py) -> hypot(px - 200f, py - 200f) }
        val nodeHalfDiag = hypot(100f, 100f)
        assertTrue(
            "Arc max distance ($maxDist) should exceed node half-diagonal ($nodeHalfDiag)",
            maxDist > nodeHalfDiag
        )
    }

    @Test fun perimeterFromDirection_ellipse_rayUpRight() {
        // Ellipse 0-200 x 0-100, center (100, 50), a=100, b=50
        val (x, y) = DiagramNodeSnap.perimeterPointFromDirectionRaw(
            dx = 1f, dy = 0f,
            left = 0f, top = 0f, right = 200f, bottom = 100f,
            shapeType = StrokeType.ELLIPSE
        )
        assertEquals(200f, x, 0.01f)  // right edge of ellipse
        assertEquals(50f, y, 0.01f)   // centerY
    }
}
