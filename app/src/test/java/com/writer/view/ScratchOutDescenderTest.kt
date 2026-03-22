package com.writer.view

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.hypot

/**
 * Tests that scratch-out detection does not falsely erase strokes from adjacent
 * lines when descenders from the line above overlap with the line below.
 *
 * Uses a raw stroke fixture captured from a real handwriting session:
 * "disappear disappeared" on line 1, "helped problematic" on line 2.
 *
 * The fixture records every stroke BEFORE any processing (scratch-out, shape snap),
 * so we can replay the exact sequence through the detection pipeline.
 */
class ScratchOutDescenderTest {

    private lateinit var segmenter: LineSegmenter
    private lateinit var rawStrokes: List<InkStroke>
    private var lineSpacing = 0f
    private var topMargin = 0f

    @Before
    fun setUp() {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/descender_test.json")
            ?: throw IllegalStateException("Fixture not found")
        val json = JSONObject(stream.reader().readText())

        lineSpacing = json.getDouble("lineSpacing").toFloat()
        topMargin = json.getDouble("topMargin").toFloat()

        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        segmenter = LineSegmenter()

        val strokesArr = json.getJSONArray("strokes")
        rawStrokes = (0 until strokesArr.length()).map { i ->
            val strokeObj = strokesArr.getJSONObject(i)
            val pointsArr = strokeObj.getJSONArray("points")
            val points = (0 until pointsArr.length()).map { j ->
                val pt = pointsArr.getJSONObject(j)
                StrokePoint(
                    pt.getDouble("x").toFloat(),
                    pt.getDouble("y").toFloat(),
                    pt.getDouble("pressure").toFloat(),
                    pt.getLong("timestamp")
                )
            }
            InkStroke(points = points)
        }
    }

    @Test
    fun `fixture loads with expected stroke count`() {
        assertTrue("Should have multiple strokes", rawStrokes.size >= 4)
    }

    @Test
    fun `strokes span at least 2 line indices`() {
        val lines = rawStrokes.map { segmenter.getStrokeLineIndex(it) }.toSet()
        assertTrue("Strokes should span at least 2 lines, got $lines", lines.size >= 2)
    }

    /**
     * Core test: replay each stroke through the scratch-out pipeline exactly as
     * HandwritingCanvasView.checkPostStrokeScratchOut does. No stroke should be
     * falsely detected as a scratch-out when there are no pre-existing strokes
     * on the same line yet, and later strokes should not erase earlier ones from
     * a different line.
     */
    @Test
    fun `no stroke is falsely detected as scratch-out`() {
        val completedStrokes = mutableListOf<InkStroke>()

        for ((i, stroke) in rawStrokes.withIndex()) {
            val points = stroke.points
            val xs = FloatArray(points.size) { points[it].x }
            val ys = FloatArray(points.size) { points[it].y }
            val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
            val yRange = maxY - minY
            val diagonal = hypot(maxX - minX, maxY - minY)
            val first = points.first(); val last = points.last()
            val closeDist = hypot(last.x - first.x, last.y - first.y)
            val isClosedLoop = diagonal > 0f && closeDist < ShapeSnapDetection.CLOSE_FRACTION * diagonal

            val detected = ScratchOutDetection.detect(xs, yRange, lineSpacing, isClosedLoop)

            if (detected) {
                // Scratch-out was detected — check if it would erase existing strokes.
                // It should NOT find targets from a different line.
                val hasSameLineTarget = completedStrokes.any { existing ->
                    ScratchOutDetection.strokesIntersect(points, existing.points)
                }

                assertFalse(
                    "Stroke #$i was detected as scratch-out AND intersects existing strokes. " +
                        "This is the false-positive bug — cursive text on line 2 erases line 1 descenders. " +
                        "Stroke bounds: x=[$minX,$maxX] y=[$minY,$maxY]",
                    hasSameLineTarget
                )
            }

            // Add stroke to completed list (simulates normal stroke completion)
            completedStrokes.add(stroke)
        }
    }

    /**
     * Test that the centroid-distance guard correctly separates lines.
     * Even if bounding boxes overlap due to descenders, the centroids
     * should be far enough apart.
     */
    @Test
    fun `line centroids are separated enough to prevent cross-line erasure`() {
        val groups = segmenter.groupByLine(rawStrokes)
        val sortedLines = groups.keys.sorted()
        assertTrue("Need at least 2 lines", sortedLines.size >= 2)

        for (i in 0 until sortedLines.size - 1) {
            val line1Strokes = groups[sortedLines[i]]!!
            val line2Strokes = groups[sortedLines[i + 1]]!!

            val line1MaxCentroid = line1Strokes.maxOf { s ->
                (s.points.minOf { it.y } + s.points.maxOf { it.y }) / 2f
            }
            val line2MinCentroid = line2Strokes.minOf { s ->
                (s.points.minOf { it.y } + s.points.maxOf { it.y }) / 2f
            }

            val gap = line2MinCentroid - line1MaxCentroid
            assertTrue(
                "Centroid gap between line ${sortedLines[i]} and ${sortedLines[i + 1]} " +
                    "= $gap should be positive (line 1 max=$line1MaxCentroid, line 2 min=$line2MinCentroid)",
                gap > 0
            )
        }
    }

    /**
     * Verify that descenders from line 1 do overlap with line 2's Y range,
     * confirming this is a valid test case for the bug.
     */
    @Test
    fun `descenders from line 1 overlap with line 2 Y range`() {
        val groups = segmenter.groupByLine(rawStrokes)
        val sortedLines = groups.keys.sorted()
        if (sortedLines.size < 2) return

        val line1Strokes = groups[sortedLines[0]]!!
        val line2Strokes = groups[sortedLines[1]]!!

        val line1MaxY = line1Strokes.maxOf { s -> s.points.maxOf { it.y } }
        val line2MinY = line2Strokes.minOf { s -> s.points.minOf { it.y } }

        // Descenders should extend below line 2's top
        assertTrue(
            "Line 1 descender maxY ($line1MaxY) should overlap line 2 minY ($line2MinY)",
            line1MaxY > line2MinY
        )
    }
}
