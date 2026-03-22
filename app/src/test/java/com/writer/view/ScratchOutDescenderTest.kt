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
 * Regression test for false scratch-out detection when cursive text on one line
 * has descenders that overlap with strokes on the next line.
 *
 * Uses a bug report fixture captured from a real session where "helped" strokes
 * on line 6 falsely triggered scratch-out and erased "disappear" strokes on line 4.
 *
 * The fixture records every raw stroke before processing, plus the processing
 * decisions. Stroke 26 and 29 were falsely classified as SCRATCH_OUT.
 */
class ScratchOutDescenderTest {

    private lateinit var segmenter: LineSegmenter
    private lateinit var rawStrokes: List<List<StrokePoint>>
    private lateinit var events: List<Triple<Int, String, String>> // (strokeIndex, type, detail)
    private var lineSpacing = 0f

    @Before
    fun setUp() {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/bug-report-scratch-out.json")
            ?: throw IllegalStateException("Fixture not found")
        val json = JSONObject(stream.reader().readText())

        lineSpacing = json.getJSONObject("device").getDouble("lineSpacing").toFloat()
        val density = json.getJSONObject("device").getDouble("density").toFloat()
        ScreenMetrics.init(density, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        segmenter = LineSegmenter()

        // Load raw strokes
        val strokesArr = json.getJSONArray("recentStrokes")
        rawStrokes = (0 until strokesArr.length()).map { i ->
            val strokeObj = strokesArr.getJSONObject(i)
            val pointsArr = strokeObj.getJSONArray("points")
            (0 until pointsArr.length()).map { j ->
                val pt = pointsArr.getJSONObject(j)
                StrokePoint(
                    pt.getDouble("x").toFloat(),
                    pt.getDouble("y").toFloat(),
                    pt.getDouble("pressure").toFloat(),
                    pt.getLong("timestamp")
                )
            }
        }

        // Load processing events
        val eventsArr = json.getJSONArray("processingEvents")
        events = (0 until eventsArr.length()).map { i ->
            val ev = eventsArr.getJSONObject(i)
            Triple(ev.getInt("strokeIndex"), ev.getString("type"), ev.getString("detail"))
        }
    }

    @Test
    fun `fixture loads with strokes and events`() {
        assertTrue("Should have strokes", rawStrokes.isNotEmpty())
        assertTrue("Should have events", events.isNotEmpty())
    }

    @Test
    fun `fixture contains the false scratch-out events`() {
        val scratchOuts = events.filter { it.second == "SCRATCH_OUT" }
        assertTrue(
            "Bug report should contain SCRATCH_OUT events (the bug we're testing)",
            scratchOuts.isNotEmpty()
        )
    }

    @Test
    fun `false scratch-out strokes are detected by ScratchOutDetection`() {
        // Verify that the strokes flagged as SCRATCH_OUT in the bug report
        // actually pass ScratchOutDetection.detect() — confirming the detector
        // is the source of the false positive
        val scratchOutIndices = events.filter { it.second == "SCRATCH_OUT" }.map { it.first }

        for (idx in scratchOutIndices) {
            if (idx >= rawStrokes.size) continue
            val points = rawStrokes[idx]
            val xs = FloatArray(points.size) { points[it].x }
            val yRange = points.maxOf { it.y } - points.minOf { it.y }
            val diagonal = hypot(
                points.maxOf { it.x } - points.minOf { it.x },
                yRange
            )
            val first = points.first(); val last = points.last()
            val closeDist = hypot(last.x - first.x, last.y - first.y)
            val isClosedLoop = diagonal > 0f && closeDist < ShapeSnapDetection.CLOSE_FRACTION * diagonal

            val detected = ScratchOutDetection.detect(xs, yRange, lineSpacing, isClosedLoop)
            // This documents the current (buggy) behavior:
            // These strokes ARE detected as scratch-outs even though they're normal text
            assertTrue(
                "Stroke $idx should be detected as scratch-out (confirming the bug exists)",
                detected
            )
        }
    }

    /**
     * Core regression test: replay strokes through the full pipeline.
     * No stroke that was ADDED to the document on one line should be
     * erased by a stroke on a different line.
     */
    @Test
    fun `replay should not erase strokes from different line`() {
        val completedStrokes = mutableListOf<InkStroke>()

        for ((idx, points) in rawStrokes.withIndex()) {
            if (points.size < 2) continue

            val xs = FloatArray(points.size) { points[it].x }
            val yRange = points.maxOf { it.y } - points.minOf { it.y }
            val diagonal = hypot(
                points.maxOf { it.x } - points.minOf { it.x },
                yRange
            )
            val first = points.first(); val last = points.last()
            val closeDist = hypot(last.x - first.x, last.y - first.y)
            val isClosedLoop = diagonal > 0f && closeDist < ShapeSnapDetection.CLOSE_FRACTION * diagonal

            val isScratchOut = ScratchOutDetection.detect(xs, yRange, lineSpacing, isClosedLoop)

            if (isScratchOut && completedStrokes.isNotEmpty()) {
                // Check what would be erased using stroke intersection
                val scratchStroke = InkStroke(points = points)
                val scratchLine = segmenter.getStrokeLineIndex(scratchStroke)

                val wouldErase = completedStrokes.filter { existing ->
                    ScratchOutDetection.strokesIntersect(points, existing.points)
                }

                // The bug: strokes from a different line are being erased
                for (erased in wouldErase) {
                    val erasedLine = segmenter.getStrokeLineIndex(erased)
                    assertFalse(
                        "Stroke $idx (line $scratchLine) should NOT erase stroke on line $erasedLine. " +
                            "This is the cross-line scratch-out bug.",
                        erasedLine != scratchLine
                    )
                }
            }

            // Add stroke to completed (simulating normal add)
            completedStrokes.add(InkStroke(points = points))
        }
    }

    @Test
    fun `strokes span multiple lines`() {
        val lines = rawStrokes.filter { it.size >= 2 }.map { points ->
            segmenter.getStrokeLineIndex(InkStroke(points = points))
        }.toSet()
        assertTrue("Should span at least 2 lines, got $lines", lines.size >= 2)
    }

    /**
     * The bug report recorded SCRATCH_OUT events at strokes 26 and 29.
     * With the current intersection guard, these strokes should NOT trigger
     * cross-line erasure in a replay. This is a regression test — if the
     * guard is removed, the replay test above would start failing.
     */
    @Test
    fun `scratch-out strokes from bug report do not cause cross-line erasure with intersection guard`() {
        val completedStrokes = mutableListOf<InkStroke>()
        val scratchOutIndices = events.filter { it.second == "SCRATCH_OUT" }.map { it.first }.toSet()
        var crossLineErasureAttempted = false

        for ((idx, points) in rawStrokes.withIndex()) {
            if (points.size < 2) continue

            if (idx in scratchOutIndices) {
                // This stroke was flagged as scratch-out in the original bug
                val scratchLine = segmenter.getStrokeLineIndex(InkStroke(points = points))

                // With intersection guard: check what would be erased
                val wouldErase = completedStrokes.filter { existing ->
                    ScratchOutDetection.strokesIntersect(points, existing.points)
                }
                for (erased in wouldErase) {
                    val erasedLine = segmenter.getStrokeLineIndex(erased)
                    if (erasedLine != scratchLine) {
                        crossLineErasureAttempted = true
                    }
                }
            }

            completedStrokes.add(InkStroke(points = points))
        }

        assertFalse(
            "Intersection guard should prevent cross-line erasure for the reported scratch-out strokes",
            crossLineErasureAttempted
        )
    }

    @Test
    fun `descenders from line 4 overlap with line 6 Y range`() {
        val line4Strokes = rawStrokes.filter { points ->
            points.size >= 2 && segmenter.getStrokeLineIndex(InkStroke(points = points)) == 4
        }
        val line6Strokes = rawStrokes.filter { points ->
            points.size >= 2 && segmenter.getStrokeLineIndex(InkStroke(points = points)) == 6
        }
        if (line4Strokes.isEmpty() || line6Strokes.isEmpty()) return

        val line4MaxY = line4Strokes.maxOf { points -> points.maxOf { it.y } }
        val line6MinY = line6Strokes.minOf { points -> points.minOf { it.y } }

        assertTrue(
            "Line 4 descenders ($line4MaxY) should overlap line 6 region ($line6MinY)",
            line4MaxY > line6MinY
        )
    }
}
