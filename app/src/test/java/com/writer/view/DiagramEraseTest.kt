package com.writer.view

import android.graphics.RectF
import com.writer.model.DiagramEdge
import com.writer.model.DiagramModel
import com.writer.model.DiagramNode
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that erasing a diagram shape removes it from the Mermaid output.
 *
 * Erase is modelled as removing node/edge entries from [DiagramModel] —
 * exactly what [WritingCoordinator.onScratchOut] does after finding overlapping strokes.
 * [DiagramMarkdown.buildMermaidBlock] is then used to verify the output changes.
 */
class DiagramEraseTest {

    private fun rect() = RectF(0f, 0f, 100f, 60f)

    // ── Nodes ─────────────────────────────────────────────────────────────────

    @Test fun erasingNode_removesItFromMermaid() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.RECTANGLE, rect(), "Process")

        val before = DiagramMarkdown.buildMermaidBlock(diagram)
        assertTrue("node should appear before erase", before.contains("Process"))

        // Simulate erase
        diagram.nodes.remove("s1")

        val after = DiagramMarkdown.buildMermaidBlock(diagram)
        assertTrue("mermaid should be empty after erasing sole node", after.isEmpty())
    }

    @Test fun erasingOneOfTwoNodes_keepsOtherNode() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.RECTANGLE, rect(), "Start")
        diagram.nodes["s2"] = DiagramNode("s2", StrokeType.ELLIPSE, rect(), "End")

        // Erase "Start"
        diagram.nodes.remove("s1")

        val mermaid = DiagramMarkdown.buildMermaidBlock(diagram)
        assertFalse("erased node label should not appear", mermaid.contains("Start"))
        assertTrue("surviving node label should still appear", mermaid.contains("End"))
    }

    @Test fun erasingNode_mermaidUsesCorrectShapeSyntax() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.DIAMOND, rect(), "Valid?")

        assertTrue(DiagramMarkdown.buildMermaidBlock(diagram).contains("{Valid?}"))

        diagram.nodes.remove("s1")

        assertFalse(DiagramMarkdown.buildMermaidBlock(diagram).contains("Valid?"))
    }

    // ── Edges ─────────────────────────────────────────────────────────────────

    @Test fun erasingEdge_removesItFromMermaid() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.RECTANGLE, rect(), "A")
        diagram.nodes["s2"] = DiagramNode("s2", StrokeType.RECTANGLE, rect(), "B")
        diagram.edges["e1"] = DiagramEdge("e1", "s1", "s2")

        val edgeTypes = mapOf("e1" to StrokeType.ARROW_HEAD)
        val before = DiagramMarkdown.buildMermaidBlock(diagram, edgeTypes)
        assertTrue("edge connector should appear before erase", before.contains("-->"))

        // Erase the arrow stroke
        diagram.edges.remove("e1")

        val after = DiagramMarkdown.buildMermaidBlock(diagram, emptyMap())
        // Nodes become orphans — they still appear, but no connector line
        assertFalse("edge connector should not appear after erase", after.contains("-->"))
        assertTrue("node A should remain", after.contains("A"))
        assertTrue("node B should remain", after.contains("B"))
    }

    @Test fun erasingConnectedNode_removesEdgeToo() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.RECTANGLE, rect(), "Alpha")
        diagram.nodes["s2"] = DiagramNode("s2", StrokeType.RECTANGLE, rect(), "Beta")
        diagram.edges["e1"] = DiagramEdge("e1", "s1", "s2")

        // Erase both the node and its arrow (as onScratchOut does when they overlap)
        diagram.nodes.remove("s1")
        diagram.edges.remove("e1")

        val mermaid = DiagramMarkdown.buildMermaidBlock(diagram, emptyMap())
        assertFalse("erased node label absent", mermaid.contains("Alpha"))
        assertFalse("edge connector absent", mermaid.contains("-->"))
        assertTrue("surviving node still present", mermaid.contains("Beta"))
    }

    // ── Scratch-out overlap detection ─────────────────────────────────────────
    //
    // WritingCoordinator.onScratchOut finds overlapping strokes by checking if
    // any stored stroke point falls inside the scratch-out bounding box.
    // For geometric arrows (2 endpoints only), scribbling over the arrow's
    // midpoint never hits either endpoint — so the arrow cannot be erased.

    /**
     * Replicates the overlap check from WritingCoordinator.onScratchOut.
     * Checks point containment for all strokes. Segment intersection is only
     * applied to arrow/line strokes (sparse points) — applying it to shape
     * outlines would cause nearby shapes to be erased when targeting an arrow.
     */
    private fun findOverlappingStrokes(
        strokes: List<InkStroke>,
        left: Float, top: Float, right: Float, bottom: Float
    ): List<InkStroke> = strokes.filter { stroke ->
        stroke.points.any { pt -> pt.x in left..right && pt.y in top..bottom }
            || stroke.strokeType.isArrowOrLine
                && ScratchOutDetection.strokeIntersectsRect(stroke.points, left, top, right, bottom)
    }

    @Test fun scratchOut_overArrowMidpoint_findsGeometricArrow() {
        // Arrow from (100, 300) to (500, 300) — a horizontal geometric arrow.
        // Only 2 stored points: the two endpoints.
        val arrow = InkStroke(
            strokeId = "arrow1",
            points = listOf(
                StrokePoint(100f, 300f, 0.5f, 0L),
                StrokePoint(500f, 300f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.ARROW_HEAD
        )

        // Scratch-out region covers the middle of the arrow (x: 250-350, y: 280-320)
        // but NOT either endpoint.
        val overlapping = findOverlappingStrokes(
            listOf(arrow),
            left = 250f, top = 280f, right = 350f, bottom = 320f
        )

        assertTrue(
            "Scratch-out over arrow midpoint should find the arrow stroke",
            overlapping.any { it.strokeId == "arrow1" }
        )
    }

    @Test fun scratchOut_overArrowMidpoint_findsDiagonalArrow() {
        // Diagonal arrow from (100, 100) to (500, 500)
        val arrow = InkStroke(
            strokeId = "arrow2",
            points = listOf(
                StrokePoint(100f, 100f, 0.5f, 0L),
                StrokePoint(500f, 500f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.ARROW_HEAD
        )

        // Scratch-out region covers the middle section of the diagonal
        val overlapping = findOverlappingStrokes(
            listOf(arrow),
            left = 270f, top = 270f, right = 330f, bottom = 330f
        )

        assertTrue(
            "Scratch-out over diagonal arrow midpoint should find the arrow",
            overlapping.any { it.strokeId == "arrow2" }
        )
    }

    @Test fun scratchOut_overSelfLoopArc_findsArrow() {
        // Self-loop arrow: ~21 Bezier arc points. Scratch-out over the arc's
        // apex (far from the node) should find it even if it misses individual points.
        // Generate arc points for a node at (100,100)-(300,300)
        val arcPoints = DiagramNodeSnap.generateSelfLoopArc(
            startX = 300f, startY = 200f,
            endX = 200f, endY = 100f,
            cx = 200f, cy = 200f,
            nodeWidth = 200f, nodeHeight = 200f
        )
        val selfLoop = InkStroke(
            strokeId = "selfloop1",
            points = arcPoints.map { (x, y) -> StrokePoint(x, y, 0.5f, 0L) },
            isGeometric = false,
            strokeType = StrokeType.ARROW_HEAD
        )

        // Find the arc's apex (farthest from center) and scratch near it
        val apex = arcPoints.maxByOrNull { (px, py) ->
            kotlin.math.hypot((px - 200f).toDouble(), (py - 200f).toDouble())
        }!!
        // Small scratch region near the apex
        val overlapping = findOverlappingStrokes(
            listOf(selfLoop),
            left = apex.first - 5f, top = apex.second - 5f,
            right = apex.first + 5f, bottom = apex.second + 5f
        )

        assertTrue(
            "Scratch-out near self-loop apex should find the arrow",
            overlapping.any { it.strokeId == "selfloop1" }
        )
    }

    @Test fun scratchOut_overLineMidpoint_findsGeometricLine() {
        // A plain LINE (no arrowheads) from (100,300) to (500,300).
        // Scribbling over the midpoint should erase it.
        val line = InkStroke(
            strokeId = "line1",
            points = listOf(
                StrokePoint(100f, 300f, 0.5f, 0L),
                StrokePoint(500f, 300f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.LINE
        )

        val overlapping = findOverlappingStrokes(
            listOf(line),
            left = 250f, top = 280f, right = 350f, bottom = 320f
        )

        assertTrue(
            "Scratch-out over line midpoint should find the line stroke",
            overlapping.any { it.strokeId == "line1" }
        )
    }

    @Test fun scratchOut_overSnappedArrowMidpoint_findsArrow() {
        // Arrow between two nodes, endpoints magnetically snapped to perimeters.
        // Node A: (100,100)-(300,300), Node B: (600,100)-(800,300)
        // Arrow snaps from A's right edge (300,200) to B's left edge (600,200).
        // User scribbles over the arrow's midpoint (around x=450).
        val arrow = InkStroke(
            strokeId = "snappedArrow",
            points = listOf(
                StrokePoint(300f, 200f, 0.5f, 0L),
                StrokePoint(600f, 200f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.ARROW_HEAD
        )

        // Tight scratch-out region at midpoint: x 420-480, y 190-210
        val overlapping = findOverlappingStrokes(
            listOf(arrow),
            left = 420f, top = 190f, right = 480f, bottom = 210f
        )

        assertTrue(
            "Scratch-out over magnetically-snapped arrow midpoint should find it",
            overlapping.any { it.strokeId == "snappedArrow" }
        )
    }

    @Test fun scratchOut_overFreehandArrowMidpoint_findsArrow() {
        // A freehand stroke classified as ARROW_HEAD (e.g. a non-geometric arrow
        // that didn't pass shape-snap but was classified as an arrow via dwell).
        // With strokeType = FREEHAND (default), segment intersection is NOT applied.
        // This tests that freehand-typed arrows can still be erased at their midpoint
        // via point containment — but with sparse points, mid-segment may be missed.
        val arrow = InkStroke(
            strokeId = "freehandArrow",
            points = listOf(
                StrokePoint(100f, 300f, 0.5f, 0L),
                StrokePoint(200f, 300f, 0.5f, 0L),
                StrokePoint(300f, 300f, 0.5f, 0L),
                StrokePoint(400f, 300f, 0.5f, 0L),
                StrokePoint(500f, 300f, 0.5f, 0L)
            ),
            isGeometric = false,
            strokeType = StrokeType.ARROW_HEAD
        )

        // Scratch covers x: 250-350, y: 280-320 — between stored points at 200 and 300
        val overlapping = findOverlappingStrokes(
            listOf(arrow),
            left = 250f, top = 280f, right = 350f, bottom = 320f
        )

        assertTrue(
            "Scratch-out between stored points of an arrow should find it via segment intersection",
            overlapping.any { it.strokeId == "freehandArrow" }
        )
    }

    @Test fun scratchOut_overArrowNearNode_doesNotCatchNode() {
        // Rectangle node at (100,100)-(300,300), outlined with 5 geometric points.
        // Arrow from node's right edge (300,200) to a distant point (500,200).
        // Scratch-out covers the arrow body near the node (290-360, 190-210)
        // but should NOT catch the node shape — only the arrow.
        val nodeStroke = InkStroke(
            strokeId = "node1",
            points = listOf(
                StrokePoint(100f, 100f, 0.5f, 0L),
                StrokePoint(300f, 100f, 0.5f, 0L),
                StrokePoint(300f, 300f, 0.5f, 0L),
                StrokePoint(100f, 300f, 0.5f, 0L),
                StrokePoint(100f, 100f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.RECTANGLE
        )
        val arrowStroke = InkStroke(
            strokeId = "arrow1",
            points = listOf(
                StrokePoint(300f, 200f, 0.5f, 0L),
                StrokePoint(500f, 200f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.ARROW_HEAD
        )

        // Scratch-out region straddles the node edge — covers arrow body near node
        val overlapping = findOverlappingStrokes(
            listOf(nodeStroke, arrowStroke),
            left = 290f, top = 190f, right = 360f, bottom = 210f
        )

        assertTrue(
            "Arrow should be found (its segment crosses the scratch region)",
            overlapping.any { it.strokeId == "arrow1" }
        )
        assertFalse(
            "Node should NOT be found (scratch targets the arrow, not the shape)",
            overlapping.any { it.strokeId == "node1" }
        )
    }

    @Test fun scratchOut_missesArrowCompletely_doesNotFind() {
        // Arrow from (100, 300) to (500, 300). Scratch-out is far away.
        val arrow = InkStroke(
            strokeId = "arrow3",
            points = listOf(
                StrokePoint(100f, 300f, 0.5f, 0L),
                StrokePoint(500f, 300f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.ARROW_HEAD
        )

        // Scratch-out region nowhere near the arrow
        val overlapping = findOverlappingStrokes(
            listOf(arrow),
            left = 100f, top = 500f, right = 500f, bottom = 520f
        )

        assertFalse(
            "Scratch-out far from arrow should not find it",
            overlapping.any { it.strokeId == "arrow3" }
        )
    }

    // ── Scratch-out detection → erase pipeline ────────────────────────────────

    @Test fun scratchOutGesture_overRectangleStroke_detectsAndFindsOverlap() {
        // End-to-end: a scratch-out gesture is drawn over a rectangle shape.
        // 1. ScratchOutDetection.detect() should recognize the zigzag as scratch-out.
        // 2. findOverlappingStrokes() should find the rectangle underneath.
        val rectangle = InkStroke(
            strokeId = "rect1",
            points = listOf(
                StrokePoint(100f, 100f, 0.5f, 0L),
                StrokePoint(300f, 100f, 0.5f, 0L),
                StrokePoint(300f, 250f, 0.5f, 0L),
                StrokePoint(100f, 250f, 0.5f, 0L),
                StrokePoint(100f, 100f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.RECTANGLE
        )

        // Scratch-out zigzag drawn over the rectangle's top edge (x: 90-310, y ≈ 100)
        val scratchXs = floatArrayOf(90f, 310f, 90f, 310f, 90f)
        val scratchYRange = 25f
        val lineSpacing = 118f

        // Step 1: detection
        val detected = ScratchOutDetection.detect(scratchXs, scratchYRange, lineSpacing)
        assertTrue("Zigzag should be detected as scratch-out (xs=${scratchXs.toList()}, yRange=$scratchYRange, ls=$lineSpacing)",
            detected)

        // Step 2: overlap (bounding box of scratch gesture)
        // The scratch covers the rectangle's top edge (y=100) — points at (100,100) and (300,100)
        // fall inside the scratch bbox.
        val left = 90f; val top = 90f; val right = 310f; val bottom = 115f
        val overlapping = findOverlappingStrokes(listOf(rectangle), left, top, right, bottom)
        assertTrue("Rectangle should be found under scratch-out",
            overlapping.any { it.strokeId == "rect1" })
    }

    // ── Empty diagram ──────────────────────────────────────────────────────────

    @Test fun eraseAllNodes_produceEmptyMermaid() {
        val diagram = DiagramModel()
        diagram.nodes["s1"] = DiagramNode("s1", StrokeType.RECTANGLE, rect(), "Only")

        diagram.nodes.remove("s1")

        assertTrue("empty diagram → empty string",
            DiagramMarkdown.buildMermaidBlock(diagram).isEmpty())
    }
}
