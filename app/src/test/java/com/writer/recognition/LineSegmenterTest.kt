package com.writer.recognition

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LineSegmenter] line assignment using Y-centroid.
 *
 * Uses standard Boox device metrics: density=1.875, line spacing=77px.
 * Lines are numbered from 0 at TOP_MARGIN.
 */
class LineSegmenterTest {

    companion object {
        private const val DENSITY = 1.875f
    }

    private lateinit var segmenter: LineSegmenter
    private var LS = 0f
    private var TM = 0f

    @Before
    fun setUp() {
        ScreenMetrics.init(DENSITY, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        segmenter = LineSegmenter()
        LS = ScreenMetrics.lineSpacing
        TM = ScreenMetrics.topMargin
    }

    /** Create a stroke from (x,y) pairs. */
    private fun stroke(vararg pairs: Pair<Float, Float>): InkStroke =
        InkStroke(points = pairs.map { (x, y) -> StrokePoint(x, y, 0.5f, 0L) })

    /** Y coordinate at the center of a given line. */
    private fun lineCenter(line: Int) = TM + line * LS + LS * 0.5f

    /** Y coordinate at the top of a given line. */
    private fun lineTop(line: Int) = TM + line * LS

    // ── Basic assignment ──────────────────────────────────────────────────────

    @Test
    fun `stroke centered on line 0 assigns to line 0`() {
        val s = stroke(100f to lineCenter(0), 200f to lineCenter(0))
        assertEquals(0, segmenter.getStrokeLineIndex(s))
    }

    @Test
    fun `stroke centered on line 5 assigns to line 5`() {
        val s = stroke(100f to lineCenter(5), 200f to lineCenter(5))
        assertEquals(5, segmenter.getStrokeLineIndex(s))
    }

    // ── Centroid-based assignment ──────────────────────────────────────────────

    @Test
    fun `stroke starting above line but centered on line assigns correctly`() {
        // Stroke starts slightly above line 3 but body is on line 3
        val startY = lineTop(3) - LS * 0.1f  // above line 3
        val endY = lineCenter(3) + LS * 0.3f  // well into line 3
        // Centroid = (startY + endY) / 2, which is in line 3
        val s = stroke(100f to startY, 200f to endY)
        assertEquals("Centroid is in line 3", 3, segmenter.getStrokeLineIndex(s))
    }

    @Test
    fun `descender stroke stays on its line`() {
        // "g" or "y": body on line 4, descender extends into line 5
        val bodyTop = lineCenter(4) - LS * 0.3f
        val descenderBottom = lineTop(5) + LS * 0.3f
        // Centroid = midpoint of bodyTop and descenderBottom
        val s = stroke(100f to bodyTop, 100f to descenderBottom)
        assertEquals("Descender should stay on line 4", 4, segmenter.getStrokeLineIndex(s))
    }

    @Test
    fun `ascender stroke stays on its line`() {
        // "t" crossbar or "l": starts high, body on line 4
        val ascenderTop = lineTop(4) - LS * 0.2f
        val bodyBottom = lineCenter(4) + LS * 0.2f
        val s = stroke(100f to ascenderTop, 100f to bodyBottom)
        assertEquals("Ascender should stay on line 4", 4, segmenter.getStrokeLineIndex(s))
    }

    // ── Cursive / overlapping words on same line ──────────────────────────────

    @Test
    fun `two words at slightly different heights assign to same line`() {
        // "helped" starts slightly lower, "problematic" starts slightly higher
        // but both are on the same visual line
        val line = 5
        val helpedStart = lineCenter(line) + LS * 0.1f  // slightly below center
        val helpedEnd = lineCenter(line) + LS * 0.3f
        val helped = stroke(100f to helpedStart, 200f to helpedEnd)

        val probStart = lineCenter(line) - LS * 0.15f  // slightly above center
        val probEnd = lineCenter(line) + LS * 0.2f
        val problematic = stroke(300f to probStart, 500f to probEnd)

        assertEquals(
            "Both words should be on the same line",
            segmenter.getStrokeLineIndex(helped),
            segmenter.getStrokeLineIndex(problematic)
        )
    }

    @Test
    fun `cursive stroke crossing line boundary assigns by centroid`() {
        // Cursive "p" starts with upstroke from line 6, body on line 5
        // First point is on line 6, but centroid is on line 5
        val firstPointY = lineCenter(6) - LS * 0.1f  // in line 6
        val bodyTopY = lineCenter(5) - LS * 0.2f      // in line 5
        val bodyBottomY = lineCenter(5) + LS * 0.3f    // in line 5
        val s = stroke(
            100f to firstPointY,  // starts in line 6
            110f to bodyTopY,      // moves up to line 5
            120f to bodyBottomY    // body in line 5
        )
        // Centroid = (min(firstPointY, bodyTopY) + max(firstPointY, bodyBottomY)) / 2
        // = (bodyTopY + bodyBottomY) / 2 ≈ center of line 5
        assertEquals("Centroid-based assignment should pick line 5", 5, segmenter.getStrokeLineIndex(s))
    }

    @Test
    fun `first-point-based would misassign but centroid assigns correctly`() {
        // Stroke that starts on line 4 but has most of its body on line 3
        // (e.g. an upstroke starting from a previous word's descender)
        val startY = lineCenter(4)            // first point: line 4
        val bodyMid = lineCenter(3)           // body: line 3
        val bodyTop = lineTop(3) + LS * 0.1f  // body top: line 3
        val s = stroke(
            100f to startY,    // starts in line 4
            110f to bodyMid,   // body in line 3
            120f to bodyTop,   // body in line 3
            130f to bodyMid    // body in line 3
        )
        // minY = bodyTop (line 3), maxY = startY (line 4)
        // centroid = (bodyTop + startY) / 2 — should be in line 3
        assertEquals("Centroid should place this on line 3", 3, segmenter.getStrokeLineIndex(s))
    }

    // ── groupByLine with overlapping strokes ──────────────────────────────────

    @Test
    fun `groupByLine puts overlapping words on same line`() {
        val line = 5
        // Two words at slightly different Y positions but same visual line
        val word1 = stroke(100f to (lineCenter(line) + 5f), 200f to (lineCenter(line) + 10f))
        val word2 = stroke(300f to (lineCenter(line) - 5f), 400f to (lineCenter(line) + 5f))

        val groups = segmenter.groupByLine(listOf(word1, word2))
        assertEquals("Both strokes should be in 1 line group", 1, groups.size)
        assertEquals("That group should have 2 strokes", 2, groups.values.first().size)
    }

    @Test
    fun `groupByLine separates words on different lines`() {
        val word1 = stroke(100f to lineCenter(3), 200f to lineCenter(3))
        val word2 = stroke(100f to lineCenter(5), 200f to lineCenter(5))

        val groups = segmenter.groupByLine(listOf(word1, word2))
        assertEquals("Strokes on different lines should form 2 groups", 2, groups.size)
    }

    @Test
    fun `groupByLine with descender keeps stroke on original line`() {
        // "g" on line 3: body in line 3, descender reaches line 4
        val g = stroke(
            100f to (lineCenter(3) - LS * 0.2f),
            110f to (lineCenter(3) + LS * 0.1f),
            105f to (lineTop(4) + LS * 0.2f)  // descender
        )
        // "a" on line 4: entirely on line 4
        val a = stroke(100f to lineCenter(4), 150f to lineCenter(4))

        val groups = segmenter.groupByLine(listOf(g, a))
        assertEquals("g and a should be on different lines", 2, groups.size)
        assertTrue("g should be on line 3", groups.containsKey(3))
        assertTrue("a should be on line 4", groups.containsKey(4))
    }
}
