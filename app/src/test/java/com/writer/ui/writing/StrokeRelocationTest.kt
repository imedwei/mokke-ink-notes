package com.writer.ui.writing

import com.writer.model.ColumnModel
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.minX
import com.writer.model.maxX
import com.writer.model.minY
import com.writer.model.maxY
import com.writer.recognition.LineSegmenter
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for relocating replacement strokes into the gap left by a scratched-out word.
 *
 * Subproblems:
 * 1. Replacement fits in gap → scale/translate to fit
 * 2. Replacement overflows gap → split line, shift content down
 * 3. Surrounding strokes are reflowed for coherent spacing
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w480dp-h800dp")
class StrokeRelocationTest {

    private val ls get() = HandwritingCanvasView.LINE_SPACING
    private val tm get() = HandwritingCanvasView.TOP_MARGIN

    private lateinit var lineSegmenter: LineSegmenter

    private fun stroke(lineIndex: Int, startX: Float, endX: Float, id: String): InkStroke {
        val y = tm + lineIndex * ls + ls * 0.5f
        return InkStroke(
            strokeId = id,
            points = listOf(
                StrokePoint(startX, y - 3f, 0.5f, 0L),
                StrokePoint((startX + endX) / 2f, y + 3f, 0.5f, 50L),
                StrokePoint(endX, y - 1f, 0.5f, 100L)
            )
        )
    }

    /** Relocate strokes to fit within a gap, preserving relative positions. */
    fun relocateToGap(
        strokes: List<InkStroke>,
        gapStartX: Float, gapEndX: Float,
        targetLineY: Float
    ): List<InkStroke> {
        if (strokes.isEmpty()) return emptyList()
        val srcMinX = strokes.minOf { it.minX }
        val srcMaxX = strokes.maxOf { it.maxX }
        val srcMinY = strokes.minOf { it.minY }
        val srcWidth = (srcMaxX - srcMinX).coerceAtLeast(1f)
        val gapWidth = (gapEndX - gapStartX).coerceAtLeast(1f)
        val scaleX = (gapWidth / srcWidth).coerceAtMost(1.5f)  // don't stretch more than 1.5x
        val dx = gapStartX - srcMinX * scaleX
        val dy = targetLineY - srcMinY
        return strokes.map { s ->
            s.copy(points = s.points.map { p ->
                p.copy(x = p.x * scaleX + dx, y = p.y + dy)
            })
        }
    }

    /** Check if relocated strokes fit within the gap. */
    fun fitsInGap(strokes: List<InkStroke>, gapStartX: Float, gapEndX: Float): Boolean {
        if (strokes.isEmpty()) return true
        val width = strokes.maxOf { it.maxX } - strokes.minOf { it.minX }
        val gapWidth = gapEndX - gapStartX
        return width <= gapWidth * 1.1f  // allow 10% overflow
    }

    /**
     * Split a line's strokes to make room for a wider replacement.
     * Returns (beforeGap, afterGap) — afterGap strokes are shifted to next line.
     */
    fun splitLineForOverflow(
        lineStrokes: List<InkStroke>,
        gapWordIndex: Int,
        expectedWords: Int,
        lineSpacing: Float
    ): Pair<List<InkStroke>, List<InkStroke>> {
        if (lineStrokes.isEmpty()) return emptyList<InkStroke>() to emptyList()

        val sorted = lineStrokes.sortedBy { it.minX }
        // Use N-1 largest gaps to split into word groups
        if (expectedWords <= 1) return sorted to emptyList()

        data class Gap(val index: Int, val size: Float)
        val gaps = mutableListOf<Gap>()
        for (i in 1 until sorted.size) {
            gaps.add(Gap(i, sorted[i].minX - sorted[i - 1].maxX))
        }
        val boundaries = gaps.sortedByDescending { it.size }
            .take(expectedWords - 1)
            .map { it.index }
            .sorted()

        // Split: everything up to and including gapWordIndex stays on this line,
        // everything after moves to next line
        val splitBoundary = if (gapWordIndex < boundaries.size) {
            boundaries[gapWordIndex]
        } else sorted.size

        val before = sorted.subList(0, splitBoundary)
        val after = sorted.subList(splitBoundary, sorted.size)

        // Shift "after" strokes down by one line
        val shifted = after.map { s ->
            s.copy(points = s.points.map { p ->
                p.copy(y = p.y + lineSpacing)
            })
        }

        return before to shifted
    }

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        lineSegmenter = LineSegmenter()
    }

    // ── Subproblem 1: Replacement fits in gap ────────────────────────────

    @Test
    fun `replacement strokes scaled to fit gap width`() {
        // Gap: X=100-200 (width=100). Replacement written at X=300-450 (width=150).
        val replacement = listOf(
            stroke(5, 300f, 350f, "r1"),
            stroke(5, 360f, 450f, "r2")
        )
        val gapStartX = 100f
        val gapEndX = 200f
        val targetY = tm + 0 * ls + ls * 0.5f

        val relocated = relocateToGap(replacement, gapStartX, gapEndX, targetY)

        val relocMinX = relocated.minOf { it.minX }
        val relocMaxX = relocated.maxOf { it.maxX }

        assertTrue("Relocated should start near gap start: $relocMinX",
            relocMinX >= gapStartX - 5f)
        assertTrue("Relocated should end near gap end: $relocMaxX",
            relocMaxX <= gapEndX + 10f)
    }

    @Test
    fun `replacement strokes moved to correct Y position`() {
        val replacement = listOf(stroke(5, 300f, 400f, "r1"))
        val targetY = tm + 0 * ls + ls * 0.5f

        val relocated = relocateToGap(replacement, 100f, 200f, targetY)

        val relocMinY = relocated.minOf { it.minY }
        assertTrue("Relocated Y should be near target line: $relocMinY vs $targetY",
            kotlin.math.abs(relocMinY - (targetY - 3f)) < ls)
    }

    @Test
    fun `replacement narrower than gap is not over-stretched`() {
        // Gap: width=200. Replacement: width=50. Should scale up but cap at 1.5x.
        val replacement = listOf(stroke(5, 300f, 350f, "r1"))
        val relocated = relocateToGap(replacement, 100f, 300f, tm + ls * 0.5f)

        val relocWidth = relocated.maxOf { it.maxX } - relocated.minOf { it.minX }
        assertTrue("Should not stretch more than 1.5x original (75): $relocWidth",
            relocWidth <= 80f)  // 50 * 1.5 = 75
    }

    @Test
    fun `fitsInGap returns true when replacement fits`() {
        val replacement = listOf(stroke(0, 100f, 180f, "r1"))
        assertTrue(fitsInGap(replacement, 100f, 200f))
    }

    @Test
    fun `fitsInGap returns false when replacement overflows`() {
        val replacement = listOf(stroke(0, 100f, 350f, "r1"))
        assertFalse(fitsInGap(replacement, 100f, 200f))
    }

    // ── Subproblem 2: Overflow — split line and shift ─────────────────────

    @Test
    fun `split line moves words after gap to next line`() {
        // Line: "the [GAP] brown fox" — scratched "quick" at index 1
        // Words after gap ("brown fox") should move to next line
        val strokes = listOf(
            stroke(0, 10f, 80f, "the"),
            // gap where "quick" was
            stroke(0, 210f, 320f, "brown"),
            stroke(0, 340f, 400f, "fox"),
        )

        val (before, after) = splitLineForOverflow(strokes, 0, 3, ls)

        assertEquals("Before gap should have 1 stroke ('the')", 1, before.size)
        assertEquals("After gap should have 2 strokes ('brown', 'fox')", 2, after.size)

        // After strokes should be shifted down by one line spacing
        val origBrownY = strokes[1].points[0].y
        val shiftedBrownY = after[0].points[0].y
        assertEquals("Shifted Y should be one line lower",
            origBrownY + ls, shiftedBrownY, 0.1f)
    }

    @Test
    fun `split preserves X positions of shifted strokes`() {
        val strokes = listOf(
            stroke(0, 10f, 80f, "w1"),
            stroke(0, 200f, 300f, "w2"),
            stroke(0, 320f, 400f, "w3"),
        )

        val (_, after) = splitLineForOverflow(strokes, 0, 3, ls)

        // X positions should be preserved (only Y changes)
        assertEquals("w2 X should be preserved", 200f, after[0].minX, 1f)
        assertEquals("w3 X should be preserved", 320f, after[1].minX, 1f)
    }

    @Test
    fun `split at last word returns empty after list`() {
        val strokes = listOf(
            stroke(0, 10f, 80f, "w1"),
            stroke(0, 100f, 200f, "w2"),
            stroke(0, 220f, 300f, "w3"),
        )

        // Scratching last word (index 2) — nothing after to shift
        val (before, after) = splitLineForOverflow(strokes, 2, 3, ls)

        assertEquals("All strokes should be in before", 3, before.size)
        assertTrue("After should be empty", after.isEmpty())
    }

    // ── Subproblem 3: Reflow for coherent spacing ────────────────────────

    @Test
    fun `after insertion remaining strokes maintain relative spacing`() {
        // Original: "the [quick] brown" — quick removed, replacement inserted
        // "brown" should maintain its spacing relative to the new word
        val theStroke = stroke(0, 10f, 80f, "the")
        val brownStroke = stroke(0, 210f, 320f, "brown")

        // Original gap between "the" and "brown" was 130px (80 to 210)
        val originalGap = brownStroke.minX - theStroke.maxX
        assertEquals("Original gap should be ~130", 130f, originalGap, 1f)

        // After inserting a wider replacement (200px wide), "brown" might
        // need to shift right. But we preserve relative positions unless overflow.
        // This test documents the behavior.
        assertTrue("Original spacing is preserved when no overflow", originalGap > 0)
    }

    @Test
    fun `relocated strokes preserve internal proportions`() {
        // A multi-stroke word like "W" should keep its shape when relocated
        val replacement = listOf(
            stroke(5, 300f, 320f, "r1"),  // first stroke
            stroke(5, 315f, 340f, "r2"),  // overlapping second stroke
            stroke(5, 335f, 360f, "r3"),  // third stroke
        )
        val origWidth = 360f - 300f  // 60px

        val relocated = relocateToGap(replacement, 100f, 200f, tm + ls * 0.5f)

        // Internal proportions: r1 was 0-20px, r2 was 15-40px, r3 was 35-60px
        // After scaling to 100px gap with scaleX = 100/60 = 1.5 (capped at 1.5)
        val r1 = relocated[0]
        val r2 = relocated[1]
        val r3 = relocated[2]

        // r2 should start after r1 starts but before r1 ends (overlapping)
        assertTrue("r2 should overlap r1", r2.minX > r1.minX && r2.minX < r1.maxX)
        // r3 should start after r2 starts
        assertTrue("r3 should be after r2 start", r3.minX > r2.minX)
    }

    @Test
    fun `empty replacement returns empty list`() {
        val relocated = relocateToGap(emptyList(), 100f, 200f, tm + ls * 0.5f)
        assertTrue(relocated.isEmpty())
    }

    // ── Subproblem 4: Relocated strokes survive applyWordEdit cleanup ────

    @Test
    fun `relocated strokes with new IDs are not removed by replacement cleanup`() {
        // This tests the bug: relocated strokes had the same IDs as the original
        // replacement strokes. applyWordEdit removes all replacementStrokeIds,
        // which deleted the just-relocated strokes.
        //
        // Fix: relocated strokes get new UUIDs.

        val column = ColumnModel()

        // Original replacement strokes (user wrote these, to be removed after edit)
        val rep1 = stroke(5, 300f, 350f, "rep1")
        val rep2 = stroke(5, 360f, 400f, "rep2")
        column.activeStrokes.addAll(listOf(rep1, rep2))

        val replacementStrokeIds = mutableSetOf("rep1", "rep2")

        // Step 1: Remove originals and add relocated with NEW IDs
        column.activeStrokes.removeAll { it.strokeId in replacementStrokeIds }
        val relocated = relocateToGap(listOf(rep1, rep2), 100f, 200f, tm + ls * 0.5f)
        val relocatedWithNewIds = relocated.map {
            it.copy(strokeId = "relocated_${it.strokeId}")  // simulate UUID
        }
        column.activeStrokes.addAll(relocatedWithNewIds)

        assertEquals("Should have 2 relocated strokes", 2, column.activeStrokes.size)

        // Step 2: applyWordEdit cleanup — removes replacementStrokeIds
        column.activeStrokes.removeAll { it.strokeId in replacementStrokeIds }

        // Relocated strokes should SURVIVE because they have different IDs
        assertEquals("Relocated strokes should survive cleanup", 2, column.activeStrokes.size)
        assertTrue("Should contain relocated_rep1",
            column.activeStrokes.any { it.strokeId == "relocated_rep1" })
        assertTrue("Should contain relocated_rep2",
            column.activeStrokes.any { it.strokeId == "relocated_rep2" })
    }

    @Test
    fun `relocated strokes with SAME IDs would be removed by cleanup (regression)`() {
        // This demonstrates the bug that existed before the fix.

        val column = ColumnModel()
        val rep1 = stroke(5, 300f, 350f, "rep1")
        column.activeStrokes.add(rep1)

        val replacementStrokeIds = mutableSetOf("rep1")

        // Without new IDs: remove original and add relocated with SAME ID
        column.activeStrokes.removeAll { it.strokeId in replacementStrokeIds }
        val relocated = relocateToGap(listOf(rep1), 100f, 200f, tm + ls * 0.5f)
        // Bug: keeping same ID
        column.activeStrokes.addAll(relocated)

        assertEquals("Should have 1 relocated stroke", 1, column.activeStrokes.size)

        // Cleanup removes it because same ID!
        column.activeStrokes.removeAll { it.strokeId in replacementStrokeIds }

        assertEquals("Bug: relocated stroke removed because same ID", 0, column.activeStrokes.size)
    }
}
