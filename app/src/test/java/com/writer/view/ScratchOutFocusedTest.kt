package com.writer.view

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ScratchOutDetection.isFocusedScratchOut].
 *
 * Tests the coverage-based scratch-out validation: a scratch-out must
 * intersect existing strokes AND have most of its path near them.
 */
class ScratchOutFocusedTest {

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun stroke(vararg pairs: Pair<Float, Float>) = InkStroke(
        points = pairs.map { (x, y) -> StrokePoint(x, y, 0.5f, 0L) }
    )

    private fun points(vararg pairs: Pair<Float, Float>) =
        pairs.map { (x, y) -> StrokePoint(x, y, 0.5f, 0L) }

    /** Create a zigzag scratch-out centered at (cx, cy) with given width. */
    private fun zigzagAt(cx: Float, cy: Float, width: Float = 100f, segments: Int = 6): List<StrokePoint> {
        val pts = mutableListOf<StrokePoint>()
        val halfW = width / 2f
        for (i in 0..segments) {
            val x = cx - halfW + width * i / segments
            val y = cy + if (i % 2 == 0) 5f else -5f
            pts.add(StrokePoint(x, y, 0.5f, i.toLong()))
        }
        return pts
    }

    // ── Small targets (dots, taps) ────────────────────────────────────────────

    @Test
    fun `scratch-out over small dot erases it`() {
        // A 2-point dot at (200, 300)
        val dot = stroke(200f to 300f, 201f to 301f)
        // Zigzag crossing over the dot
        val scratch = zigzagAt(200f, 300f, width = 80f)

        assertTrue(
            "Scratch-out should erase small dot (< 5 points, intersection sufficient)",
            ScratchOutDetection.isFocusedScratchOut(scratch, listOf(dot))
        )
    }

    @Test
    fun `scratch-out over single-point tap erases it`() {
        val tap = stroke(150f to 250f, 150f to 250f)
        val scratch = zigzagAt(150f, 250f, width = 60f)

        assertTrue(
            "Scratch-out should erase single-point tap",
            ScratchOutDetection.isFocusedScratchOut(scratch, listOf(tap))
        )
    }

    @Test
    fun `scratch-out over 3-point dwell mark erases it`() {
        val dwell = stroke(300f to 400f, 301f to 401f, 300f to 400f)
        val scratch = zigzagAt(300f, 400f, width = 50f)

        assertTrue(
            "Scratch-out should erase 3-point dwell mark",
            ScratchOutDetection.isFocusedScratchOut(scratch, listOf(dwell))
        )
    }

    @Test
    fun `scratch-out NOT over dot does not erase it`() {
        val dot = stroke(200f to 300f, 201f to 301f)
        // Zigzag far from the dot
        val scratch = zigzagAt(500f, 300f, width = 80f)

        assertFalse(
            "Scratch-out far from dot should not erase it",
            ScratchOutDetection.isFocusedScratchOut(scratch, listOf(dot))
        )
    }

    @Test
    fun `scratch-out over multiple small dots erases them`() {
        val dot1 = stroke(100f to 300f, 101f to 301f)
        val dot2 = stroke(150f to 300f, 151f to 301f)
        val scratch = zigzagAt(125f, 300f, width = 120f)

        assertTrue(
            "Scratch-out should erase multiple small dots in its path",
            ScratchOutDetection.isFocusedScratchOut(scratch, listOf(dot1, dot2))
        )
    }

    // ── Normal strokes (coverage check applies) ───────────────────────────────

    @Test
    fun `scratch-out focused on text erases it`() {
        // A word-sized stroke from (100,300) to (300,310)
        val word = stroke(
            100f to 300f, 130f to 305f, 160f to 298f,
            190f to 303f, 220f to 300f, 250f to 308f, 280f to 302f
        )
        // Zigzag directly over the word
        val scratch = zigzagAt(190f, 303f, width = 200f, segments = 10)

        assertTrue(
            "Scratch-out focused on a word should erase it",
            ScratchOutDetection.isFocusedScratchOut(scratch, listOf(word))
        )
    }

    @Test
    fun `cursive grazing descender does not erase`() {
        // "disappear" descender at y=280-320, x=100-150
        val descender = stroke(
            100f to 280f, 110f to 290f, 120f to 300f,
            130f to 310f, 140f to 320f, 145f to 315f, 150f to 305f
        )
        // "helped" written mostly below at y=340-380, only briefly crossing at x=100
        // Most of the stroke is NOT near the descender
        val helpedPoints = mutableListOf<StrokePoint>()
        // Brief crossing at top
        helpedPoints.add(StrokePoint(100f, 310f, 0.5f, 0L))
        helpedPoints.add(StrokePoint(110f, 320f, 0.5f, 1L))
        // Rest of the word far below
        for (i in 2..20) {
            helpedPoints.add(StrokePoint(100f + i * 15f, 360f + (i % 3) * 5f, 0.5f, i.toLong()))
        }

        assertFalse(
            "Cursive text that briefly crosses a descender should NOT trigger scratch-out",
            ScratchOutDetection.isFocusedScratchOut(helpedPoints, listOf(descender))
        )
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `empty scratch points returns false`() {
        val word = stroke(100f to 300f, 200f to 300f)
        assertFalse(ScratchOutDetection.isFocusedScratchOut(emptyList(), listOf(word)))
    }

    @Test
    fun `empty existing strokes returns false`() {
        val scratch = zigzagAt(200f, 300f)
        assertFalse(ScratchOutDetection.isFocusedScratchOut(scratch, emptyList()))
    }

    @Test
    fun `scratch-out with no intersection returns false`() {
        // Stroke at y=100, scratch-out at y=500 — no intersection possible
        val word = stroke(100f to 100f, 200f to 100f, 300f to 100f)
        val scratch = zigzagAt(200f, 500f, width = 200f)

        assertFalse(
            "Non-intersecting scratch-out should return false",
            ScratchOutDetection.isFocusedScratchOut(scratch, listOf(word))
        )
    }

    // ── Bounding box pre-filter ───────────────────────────────────────────────

    @Test
    fun `distant strokes are filtered by bounding box precheck`() {
        // Many strokes far away — should be filtered before intersection check
        val distantStrokes = (0..20).map { i ->
            stroke(1000f + i * 50f to 1000f, 1010f + i * 50f to 1010f)
        }
        // One nearby stroke
        val nearby = stroke(200f to 300f, 250f to 300f, 300f to 300f)
        val all = distantStrokes + nearby
        val scratch = zigzagAt(250f, 300f, width = 150f)

        // Should still work (and fast — distant strokes filtered by bbox)
        assertTrue(
            "Should find and erase the nearby stroke despite many distant ones",
            ScratchOutDetection.isFocusedScratchOut(scratch, all)
        )
    }
}
