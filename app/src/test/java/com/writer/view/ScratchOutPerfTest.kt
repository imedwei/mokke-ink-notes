package com.writer.view

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.minX
import com.writer.model.maxX
import com.writer.model.minY
import com.writer.model.maxY
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Performance tests for [ScratchOutDetection.isFocusedScratchOut].
 *
 * Exercises large scratch-out strokes (500+ points) against documents
 * with many candidate strokes, matching the real-world scenario that
 * caused perceptible latency on e-ink devices.
 */
class ScratchOutPerfTest {

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    /** Create a zigzag scratch-out with [pointCount] points spanning [width] px. */
    private fun makeScratchPoints(
        startX: Float, centerY: Float, width: Float, pointCount: Int
    ): List<StrokePoint> {
        val pts = mutableListOf<StrokePoint>()
        val amplitude = 10f
        var x = startX
        var direction = 1f
        val stepX = width * 2f / pointCount  // zigzag covers width multiple times
        for (i in 0 until pointCount) {
            val y = centerY + if (i % 2 == 0) amplitude else -amplitude
            pts.add(StrokePoint(x, y, 0.5f, i.toLong()))
            x += direction * stepX
            if (x > startX + width) { direction = -1f; x = startX + width }
            if (x < startX) { direction = 1f; x = startX }
        }
        return pts
    }

    /** Create a text stroke (vertical letter-like) at given position. */
    private fun makeTextStroke(x: Float, y: Float, height: Float, pointCount: Int = 80): InkStroke {
        val pts = (0 until pointCount).map { i ->
            val t = i.toFloat() / (pointCount - 1)
            StrokePoint(
                x = x + (i % 3) * 2f,  // slight horizontal wobble
                y = y + height * t,
                pressure = 0.5f,
                timestamp = i.toLong()
            )
        }
        return InkStroke(points = pts)
    }

    @Test
    fun `focused scratch-out 500 pts over 10 strokes under 10ms`() {
        val strokes = (0 until 10).map { i ->
            makeTextStroke(x = 50f + i * 20f, y = 100f, height = 50f)
        }
        val scratch = makeScratchPoints(startX = 40f, centerY = 125f, width = 220f, pointCount = 500)

        val t0 = System.nanoTime()
        val result = ScratchOutDetection.isFocusedScratchOut(scratch, strokes)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue("Should detect focused scratch-out", result)
        assertTrue(
            "isFocusedScratchOut took ${elapsedMs}ms, budget is 10ms (500 pts, 10 strokes)",
            elapsedMs < 10.0
        )
    }

    @Test
    fun `focused scratch-out 1000 pts over 20 strokes under 15ms`() {
        val strokes = (0 until 20).map { i ->
            makeTextStroke(x = 30f + i * 15f, y = 100f, height = 50f, pointCount = 100)
        }
        val scratch = makeScratchPoints(startX = 20f, centerY = 125f, width = 320f, pointCount = 1000)

        val t0 = System.nanoTime()
        val result = ScratchOutDetection.isFocusedScratchOut(scratch, strokes)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue("Should detect focused scratch-out", result)
        assertTrue(
            "isFocusedScratchOut took ${elapsedMs}ms, budget is 15ms (1000 pts, 20 strokes)",
            elapsedMs < 15.0
        )
    }

    @Test
    fun `unfocused scratch-out 500 pts away from 10 strokes under 5ms`() {
        // Strokes at y=100, scratch at y=300 — no overlap
        val strokes = (0 until 10).map { i ->
            makeTextStroke(x = 50f + i * 20f, y = 100f, height = 50f)
        }
        val scratch = makeScratchPoints(startX = 40f, centerY = 300f, width = 220f, pointCount = 500)

        val t0 = System.nanoTime()
        val result = ScratchOutDetection.isFocusedScratchOut(scratch, strokes)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue("Should reject unfocused scratch-out", !result)
        assertTrue(
            "Unfocused rejection took ${elapsedMs}ms, budget is 5ms (bbox filter should be instant)",
            elapsedMs < 5.0
        )
    }

    @Test
    fun `large document 500 strokes scratch-out under 20ms`() {
        // Simulate a large document: 500 strokes spread across many lines
        val strokes = (0 until 500).map { i ->
            val line = i / 10
            val col = i % 10
            makeTextStroke(x = 30f + col * 30f, y = 80f * line + 100f, height = 40f, pointCount = 50)
        }
        // Scratch-out targets one line (line 5, y ≈ 500)
        val scratch = makeScratchPoints(startX = 20f, centerY = 500f, width = 300f, pointCount = 500)

        val t0 = System.nanoTime()
        ScratchOutDetection.isFocusedScratchOut(scratch, strokes)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue(
            "Large doc scratch-out took ${elapsedMs}ms, budget is 20ms (500 strokes, bbox pre-filter)",
            elapsedMs < 20.0
        )
    }

    // --- strokesIntersect erase-path benchmarks ---
    // These cover the WritingCoordinator.onScratchOut path where strokesIntersect
    // is called per-stroke to identify which strokes to erase.

    @Test
    fun `strokesIntersect 700 pts vs non-overlapping stroke under 1ms`() {
        // Scratch at y=100, stroke at y=500 — bbox early-exit should make this instant
        val scratch = makeScratchPoints(startX = 50f, centerY = 100f, width = 300f, pointCount = 700)
        val distant = makeTextStroke(x = 100f, y = 500f, height = 50f, pointCount = 80)

        val t0 = System.nanoTime()
        val result = ScratchOutDetection.strokesIntersect(scratch, distant.points)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue("Should not intersect", !result)
        assertTrue(
            "Non-overlapping strokesIntersect took ${elapsedMs}ms, budget is 1ms (bbox exit)",
            elapsedMs < 1.0
        )
    }

    @Test
    fun `strokesIntersect 700 pts vs overlapping stroke under 5ms`() {
        // Scratch and stroke overlap — must do full segment intersection
        val scratch = makeScratchPoints(startX = 50f, centerY = 125f, width = 200f, pointCount = 700)
        val overlapping = makeTextStroke(x = 100f, y = 100f, height = 50f, pointCount = 80)

        val t0 = System.nanoTime()
        ScratchOutDetection.strokesIntersect(scratch, overlapping.points)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue(
            "Overlapping strokesIntersect took ${elapsedMs}ms, budget is 5ms (700x80 segments)",
            elapsedMs < 5.0
        )
    }

    @Test
    fun `erase identification 700 pts over 200 strokes under 20ms`() {
        // Simulates the WritingCoordinator.onScratchOut loop: check strokesIntersect
        // for every active stroke. Most strokes are far away (bbox skip), a few overlap.
        val strokes = (0 until 200).map { i ->
            val line = i / 10
            val col = i % 10
            makeTextStroke(x = 30f + col * 30f, y = 80f * line + 100f, height = 40f, pointCount = 50)
        }
        val scratch = makeScratchPoints(startX = 20f, centerY = 500f, width = 300f, pointCount = 700)
        val scratchMinX = scratch.minOf { it.x }
        val scratchMaxX = scratch.maxOf { it.x }
        val scratchMinY = scratch.minOf { it.y }
        val scratchMaxY = scratch.maxOf { it.y }
        val radius = ScreenMetrics.dp(ScratchOutDetection.COVERAGE_RADIUS_DP)

        val t0 = System.nanoTime()
        val erased = strokes.filter { stroke ->
            val sMinX = stroke.minX; val sMaxX = stroke.maxX
            val sMinY = stroke.minY; val sMaxY = stroke.maxY
            if (sMaxX < scratchMinX - radius || sMinX > scratchMaxX + radius ||
                sMaxY < scratchMinY - radius || sMinY > scratchMaxY + radius) return@filter false
            ScratchOutDetection.strokesIntersect(scratch, stroke.points)
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue(
            "Erase identification took ${elapsedMs}ms, budget is 20ms (200 strokes, bbox pre-filter + intersection)",
            elapsedMs < 20.0
        )
    }
}
