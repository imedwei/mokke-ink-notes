package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DiagramTextGrouping]: 2D spatial grouping and Y-row splitting
 * of freehand strokes in diagram areas.
 */
class DiagramTextGroupingTest {

    companion object {
        private const val DENSITY = 1.875f
        private const val GAP = 118f // ~1 line spacing at standard density
    }

    @Before
    fun setUp() {
        ScreenMetrics.init(DENSITY, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun stroke(vararg pairs: Pair<Float, Float>): InkStroke =
        InkStroke(points = pairs.map { (x, y) -> StrokePoint(x, y, 0.5f, 0L) })

    // ── groupByProximity ──────────────────────────────────────────────────────

    @Test
    fun `single stroke forms one group`() {
        val s = stroke(100f to 100f, 150f to 130f)
        val groups = DiagramTextGrouping.groupByProximity(listOf(s), GAP)
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].size)
    }

    @Test
    fun `empty list returns empty`() {
        val groups = DiagramTextGrouping.groupByProximity(emptyList(), GAP)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `two strokes close in X and Y are grouped together`() {
        // Two strokes of letter "A" — overlapping bounding boxes
        val s1 = stroke(100f to 100f, 130f to 150f) // left leg
        val s2 = stroke(130f to 100f, 160f to 150f) // right leg
        val groups = DiagramTextGrouping.groupByProximity(listOf(s1, s2), GAP)
        assertEquals("Close strokes should form 1 group", 1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun `two strokes far apart in X form separate groups`() {
        // "A" in left shape, "B" in right shape — far apart horizontally
        val sA = stroke(100f to 200f, 150f to 250f)
        val sB = stroke(500f to 200f, 550f to 250f)
        val groups = DiagramTextGrouping.groupByProximity(listOf(sA, sB), GAP)
        assertEquals("Far-apart strokes should form 2 groups", 2, groups.size)
    }

    @Test
    fun `two strokes close in X but far in Y form separate groups`() {
        // "A" at top, "C" at bottom — same X column but different shapes vertically
        val sA = stroke(200f to 100f, 250f to 150f)
        val sC = stroke(210f to 400f, 260f to 450f)
        val groups = DiagramTextGrouping.groupByProximity(listOf(sA, sC), GAP)
        assertEquals("Vertically separated strokes should form 2 groups", 2, groups.size)
    }

    @Test
    fun `strokes exactly at gap distance are grouped`() {
        val s1 = stroke(100f to 100f, 150f to 120f)
        // s2 starts exactly GAP px to the right of s1's right edge
        val s2 = stroke(150f + GAP to 100f, 200f + GAP to 120f)
        val groups = DiagramTextGrouping.groupByProximity(listOf(s1, s2), GAP)
        assertEquals("Strokes at exactly gap distance should group", 1, groups.size)
    }

    @Test
    fun `strokes just beyond gap distance are separate`() {
        val s1 = stroke(100f to 100f, 150f to 120f)
        val s2 = stroke(150f + GAP + 1f to 100f, 200f + GAP + 1f to 120f)
        val groups = DiagramTextGrouping.groupByProximity(listOf(s1, s2), GAP)
        assertEquals("Strokes beyond gap should be separate", 2, groups.size)
    }

    @Test
    fun `chain of close strokes forms one group`() {
        // Three strokes in a chain: s1 close to s2, s2 close to s3, but s1 far from s3
        val s1 = stroke(100f to 100f, 150f to 120f)
        val s2 = stroke(200f to 100f, 250f to 120f) // within GAP of s1
        val s3 = stroke(300f to 100f, 350f to 120f) // within GAP of s2, >GAP from s1
        val groups = DiagramTextGrouping.groupByProximity(listOf(s1, s2, s3), GAP)
        assertEquals("Transitive proximity should form 1 group", 1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test
    fun `three separate groups at different positions`() {
        // A (top-left), B (top-right), C (bottom-center) — all far apart
        val sA = stroke(100f to 100f, 140f to 140f)
        val sB = stroke(500f to 100f, 540f to 140f)
        val sC = stroke(300f to 500f, 340f to 540f)
        val groups = DiagramTextGrouping.groupByProximity(listOf(sA, sB, sC), GAP)
        assertEquals("Three isolated strokes should form 3 groups", 3, groups.size)
    }

    // ── splitIntoRows ─────────────────────────────────────────────────────────

    @Test
    fun `single stroke returns one row`() {
        val s = stroke(100f to 100f, 150f to 130f)
        val rows = DiagramTextGrouping.splitIntoRows(listOf(s), GAP)
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].size)
    }

    @Test
    fun `empty group returns empty`() {
        val rows = DiagramTextGrouping.splitIntoRows(emptyList(), GAP)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `tall letter A spanning two lines stays as one row`() {
        // Peak stroke at y=100-130, legs stroke at y=120-200
        // These overlap vertically — no gap
        val peak = stroke(140f to 100f, 160f to 130f)
        val legs = stroke(120f to 120f, 180f to 200f)
        val rows = DiagramTextGrouping.splitIntoRows(listOf(peak, legs), GAP)
        assertEquals("Overlapping strokes should stay in 1 row", 1, rows.size)
        assertEquals(2, rows[0].size)
    }

    @Test
    fun `Side and Text on separate lines split into two rows`() {
        // "Side" at y=100-150, "Text" at y=300-350 — gap of 150 > GAP
        val side1 = stroke(500f to 100f, 550f to 130f)
        val side2 = stroke(550f to 100f, 600f to 140f)
        val text1 = stroke(500f to 300f, 560f to 340f)
        val text2 = stroke(560f to 300f, 620f to 350f)
        val rows = DiagramTextGrouping.splitIntoRows(listOf(side1, side2, text1, text2), GAP)
        assertEquals("Stacked text blocks should split into 2 rows", 2, rows.size)
        assertEquals(2, rows[0].size) // "Side"
        assertEquals(2, rows[1].size) // "Text"
    }

    @Test
    fun `strokes with gap exactly at threshold stay together`() {
        val s1 = stroke(100f to 100f, 150f to 120f) // maxY = 120
        // s2's minY is exactly GAP away from s1's maxY
        val s2 = stroke(100f to 120f + GAP, 150f to 140f + GAP)
        val rows = DiagramTextGrouping.splitIntoRows(listOf(s1, s2), GAP)
        assertEquals("Gap exactly at threshold should stay as 1 row", 1, rows.size)
    }

    @Test
    fun `strokes with gap just beyond threshold split`() {
        val s1 = stroke(100f to 100f, 150f to 120f)
        val s2 = stroke(100f to 120f + GAP + 1f, 150f to 140f + GAP + 1f)
        val rows = DiagramTextGrouping.splitIntoRows(listOf(s1, s2), GAP)
        assertEquals("Gap beyond threshold should split into 2 rows", 2, rows.size)
    }

    @Test
    fun `rows are sorted left-to-right within each row`() {
        // Two strokes in same row, added in reverse X order
        val right = stroke(300f to 100f, 350f to 130f)
        val left = stroke(100f to 100f, 150f to 130f)
        val rows = DiagramTextGrouping.splitIntoRows(listOf(right, left), GAP)
        assertEquals(1, rows.size)
        // First stroke in row should be the leftmost
        assertTrue(
            "Row should be sorted left-to-right",
            rows[0][0].points.minOf { it.x } < rows[0][1].points.minOf { it.x }
        )
    }

    @Test
    fun `three stacked lines split into three rows`() {
        val line1 = stroke(100f to 100f, 200f to 130f)
        val line2 = stroke(100f to 300f, 200f to 330f)
        val line3 = stroke(100f to 500f, 200f to 530f)
        val rows = DiagramTextGrouping.splitIntoRows(listOf(line1, line2, line3), GAP)
        assertEquals("Three stacked lines should form 3 rows", 3, rows.size)
    }

    // ── splitIntoRowsAdaptive ─────────────────────────────────────────────────

    @Test
    fun `adaptive - tall A spanning two lines stays as one row`() {
        // Strokes of "A": peak at y=100-130, left leg y=110-200, right leg y=110-200
        // All overlap vertically — centroids are close
        val peak = stroke(140f to 100f, 160f to 130f)   // height=30, centroid=115
        val leftLeg = stroke(120f to 110f, 140f to 200f) // height=90, centroid=155
        val rightLeg = stroke(160f to 110f, 180f to 200f) // height=90, centroid=155
        // Median height = 90. Centroid gap: 155-115=40, threshold=90*0.8=72. 40<72 → same row
        val rows = DiagramTextGrouping.splitIntoRowsAdaptive(listOf(peak, leftLeg, rightLeg), GAP)
        assertEquals("Tall A strokes should stay as 1 row", 1, rows.size)
        assertEquals(3, rows[0].size)
    }

    @Test
    fun `adaptive - Side and Text on adjacent lines split into two rows`() {
        // "Side" strokes: each ~30px tall, centroids around y=115
        val s1 = stroke(500f to 100f, 520f to 130f) // height=30, centroid=115
        val s2 = stroke(520f to 100f, 540f to 130f) // height=30, centroid=115
        val s3 = stroke(540f to 105f, 560f to 135f) // height=30, centroid=120
        // "Text" strokes: each ~30px tall, centroids around y=195
        val t1 = stroke(500f to 180f, 520f to 210f) // height=30, centroid=195
        val t2 = stroke(520f to 180f, 540f to 210f) // height=30, centroid=195
        val t3 = stroke(540f to 185f, 560f to 215f) // height=30, centroid=200
        // Median height = 30. Centroid gap: 195-120=75, threshold=30*0.8=24. 75>24 → split
        val rows = DiagramTextGrouping.splitIntoRowsAdaptive(
            listOf(s1, s2, s3, t1, t2, t3), GAP
        )
        assertEquals("Side/Text should split into 2 rows", 2, rows.size)
        assertEquals(3, rows[0].size)
        assertEquals(3, rows[1].size)
    }

    @Test
    fun `adaptive - falls back for fewer than 3 strokes`() {
        val s1 = stroke(100f to 100f, 150f to 130f)
        val s2 = stroke(100f to 300f, 150f to 330f)
        // Only 2 strokes — falls back to splitIntoRows with fallbackGap
        val rows = DiagramTextGrouping.splitIntoRowsAdaptive(listOf(s1, s2), GAP)
        assertEquals("Fallback should split on large gap", 2, rows.size)
    }

    @Test
    fun `adaptive - single stroke returns one row`() {
        val s = stroke(100f to 100f, 150f to 130f)
        val rows = DiagramTextGrouping.splitIntoRowsAdaptive(listOf(s), GAP)
        assertEquals(1, rows.size)
    }

    @Test
    fun `adaptive - mixed large and small strokes uses median height`() {
        // 3 small strokes (height=30) + 1 tall stroke (height=100)
        // Median of [30, 30, 30, 100] = 30 (index 2 of sorted 4)
        val small1 = stroke(100f to 100f, 130f to 130f) // h=30, cy=115
        val small2 = stroke(140f to 100f, 170f to 130f) // h=30, cy=115
        val tall = stroke(200f to 80f, 230f to 180f)    // h=100, cy=130
        val small3 = stroke(100f to 250f, 130f to 280f) // h=30, cy=265
        // Median height=30, centroid gap threshold=24
        // Centroids: 115, 115, 130, 265
        // Gap 115→115=0, 115→130=15 (<24), 130→265=135 (>24) → split
        val rows = DiagramTextGrouping.splitIntoRowsAdaptive(
            listOf(small1, small2, tall, small3), GAP
        )
        assertEquals("Should split into 2 rows at the large gap", 2, rows.size)
        assertEquals("First row has 3 strokes", 3, rows[0].size)
        assertEquals("Second row has 1 stroke", 1, rows[1].size)
    }

    @Test
    fun `adaptive - three stacked text lines split correctly`() {
        // Three lines of text, each ~30px tall, spaced 80px apart
        val line1a = stroke(100f to 100f, 130f to 130f)
        val line1b = stroke(140f to 100f, 170f to 130f)
        val line1c = stroke(180f to 100f, 210f to 130f)
        val line2a = stroke(100f to 210f, 130f to 240f)
        val line2b = stroke(140f to 210f, 170f to 240f)
        val line2c = stroke(180f to 210f, 210f to 240f)
        val line3a = stroke(100f to 320f, 130f to 350f)
        val line3b = stroke(140f to 320f, 170f to 350f)
        val line3c = stroke(180f to 320f, 210f to 350f)
        // Median height=30, centroid gap threshold=24
        // Line 1 centroids ~115, Line 2 centroids ~225, Line 3 centroids ~335
        // Gaps: 225-115=110 (>24), 335-225=110 (>24) → 3 rows
        val rows = DiagramTextGrouping.splitIntoRowsAdaptive(
            listOf(line1a, line1b, line1c, line2a, line2b, line2c, line3a, line3b, line3c), GAP
        )
        assertEquals("Three stacked text lines should form 3 rows", 3, rows.size)
    }
}
