package com.writer.view

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for scratch-out overlap detection in diagram areas.
 *
 * WritingCoordinator.onScratchOut finds overlapping strokes by checking if
 * any stored stroke point falls inside the scratch-out bounding box.
 * For geometric arrows (2 endpoints only), segment intersection is also checked.
 */
class DiagramEraseTest {

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
        val arrow = InkStroke(
            strokeId = "arrow1",
            points = listOf(
                StrokePoint(100f, 300f, 0.5f, 0L),
                StrokePoint(500f, 300f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.ARROW_HEAD
        )

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
        val arrow = InkStroke(
            strokeId = "arrow2",
            points = listOf(
                StrokePoint(100f, 100f, 0.5f, 0L),
                StrokePoint(500f, 500f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.ARROW_HEAD
        )

        val overlapping = findOverlappingStrokes(
            listOf(arrow),
            left = 270f, top = 270f, right = 330f, bottom = 330f
        )

        assertTrue(
            "Scratch-out over diagonal arrow midpoint should find the arrow",
            overlapping.any { it.strokeId == "arrow2" }
        )
    }

    @Test fun scratchOut_overLineMidpoint_findsGeometricLine() {
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
        val arrow = InkStroke(
            strokeId = "snappedArrow",
            points = listOf(
                StrokePoint(300f, 200f, 0.5f, 0L),
                StrokePoint(600f, 200f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.ARROW_HEAD
        )

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
        val arrow = InkStroke(
            strokeId = "arrow3",
            points = listOf(
                StrokePoint(100f, 300f, 0.5f, 0L),
                StrokePoint(500f, 300f, 0.5f, 0L)
            ),
            isGeometric = true,
            strokeType = StrokeType.ARROW_HEAD
        )

        val overlapping = findOverlappingStrokes(
            listOf(arrow),
            left = 100f, top = 500f, right = 500f, bottom = 520f
        )

        assertFalse(
            "Scratch-out far from arrow should not find it",
            overlapping.any { it.strokeId == "arrow3" }
        )
    }

    @Test fun scratchOutGesture_overRectangleStroke_detectsAndFindsOverlap() {
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

        val scratchXs = floatArrayOf(90f, 310f, 90f, 310f, 90f)
        val scratchYRange = 25f
        val lineSpacing = 118f

        assertTrue("Zigzag should be detected as scratch-out",
            ScratchOutDetection.detect(scratchXs, scratchYRange, lineSpacing))

        val left = 90f; val top = 90f; val right = 310f; val bottom = 115f
        val overlapping = findOverlappingStrokes(listOf(rectangle), left, top, right, bottom)
        assertTrue("Rectangle should be found under scratch-out",
            overlapping.any { it.strokeId == "rect1" })
    }
}
