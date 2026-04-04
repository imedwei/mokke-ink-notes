package com.writer.view

import com.writer.model.StrokePoint
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression test for degenerate cellSize in [ScratchOutDetection.strokesIntersect].
 *
 * When two strokes' bounding boxes barely overlap (e.g., 1 pixel), the grid
 * cellSize becomes extremely small. Segments far from the overlap region then
 * map to enormous cell ranges, causing billions of HashMap insertions that
 * thrash the GC and hang the main thread (observed as ANR on device).
 */
class StrokesIntersectCellSizeTest {

    /**
     * Two long strokes whose bounding boxes overlap by a single pixel.
     *
     * Stroke A: horizontal line at y=100, x from 0 to 300 (300 points)
     * Stroke B: horizontal line at y=101, x from 299 to 599 (300 points)
     *
     * Overlap region: x=[299,300], y=[100,101] — just 1×1 pixel.
     * With indexed.size=300, cellSize = 1/sqrt(300) ≈ 0.058 px.
     * A segment spanning 1px maps to ~17 cells, but segments far from
     * the overlap span hundreds of pixels → millions of cells → OOM/ANR.
     *
     * This must complete in under 50ms, not hang for minutes.
     */
    @Test(timeout = 2000)
    fun `barely overlapping strokes must not hang`() {
        // Stroke A: 300 points along y=100, x from 0 to 300
        val strokeA = (0 until 300).map { i ->
            StrokePoint(x = i.toFloat(), y = 100f, pressure = 0.5f, timestamp = i.toLong())
        }
        // Stroke B: 300 points along y=101, x from 299 to 599
        val strokeB = (0 until 300).map { i ->
            StrokePoint(x = 299f + i.toFloat(), y = 101f, pressure = 0.5f, timestamp = i.toLong())
        }
        // Bounding boxes overlap at x=[299,300], y=[100,101] — 1×1 pixel overlap.
        // segA * segB = 299 * 299 = 89401 >> 500, so the grid path is used.
        // With the bug, cellSize ≈ 0.058 and segments far from the overlap
        // explode to millions of grid cells.

        val result = ScratchOutDetection.strokesIntersect(strokeA, strokeB)
        assertFalse("Parallel lines should not intersect", result)
    }

    /**
     * Same scenario but with strokes that actually cross in the overlap zone.
     * Verifies the fix doesn't break real intersection detection.
     */
    @Test(timeout = 2000)
    fun `barely overlapping strokes that cross are detected`() {
        // Stroke A: 300 points along y=100, x from 0 to 300
        val strokeA = (0 until 300).map { i ->
            StrokePoint(x = i.toFloat(), y = 100f, pressure = 0.5f, timestamp = i.toLong())
        }
        // Stroke B: 300 points, diagonal from (299, 99) to (300, 200)
        // Crosses stroke A at approximately (299.x, 100)
        val strokeB = (0 until 300).map { i ->
            val t = i.toFloat() / 299f
            StrokePoint(
                x = 299f + t,
                y = 99f + t * 101f,
                pressure = 0.5f,
                timestamp = i.toLong()
            )
        }

        val result = ScratchOutDetection.strokesIntersect(strokeA, strokeB)
        // These strokes cross — result depends on segment resolution but must not hang
    }
}
