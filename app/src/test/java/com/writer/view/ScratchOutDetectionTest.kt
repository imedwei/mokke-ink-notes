package com.writer.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ScratchOutDetection].
 *
 * Uses a fixed line spacing of 118 px (standard device: 63 dp × 1.875 density).
 *
 * Geometry recap:
 *   MIN_REVERSALS = 2         → stroke must change X direction at least twice
 *   MIN_X_TRAVEL_SPANS = 1.5  → total |dx| ≥ 177 px at 118 px LS
 *   MAX_Y_DRIFT = 0.4         → yRange < 40% of total x-travel
 */
class ScratchOutDetectionTest {

    companion object {
        private const val LS = 118f
        private val X_THRESH get() = ScratchOutDetection.MIN_X_TRAVEL_SPANS * LS  // ≈ 177 px
    }

    // ── Strokes that SHOULD qualify ──────────────────────────────────────────

    @Test fun wideZigzag_detects() {
        // 4 segments: right → left → right → left — clearly a scratch-out
        val xs = zigzag(startX = 0f, segmentWidth = 60f, segments = 4)
        assertTrue(ScratchOutDetection.detect(xs, yRange = 5f, lineSpacing = LS))
    }

    @Test fun minimalZigzag_exactlyTwoReversals_detects() {
        // 3 segments → 2 reversals; total travel just above threshold
        val segW = X_THRESH / 2f + 2f
        val xs = zigzag(startX = 0f, segmentWidth = segW, segments = 3)
        assertTrue(ScratchOutDetection.detect(xs, yRange = 2f, lineSpacing = LS))
    }

    @Test fun manyReversals_detects() {
        val xs = zigzag(startX = 0f, segmentWidth = 50f, segments = 6)
        assertTrue(ScratchOutDetection.detect(xs, yRange = 3f, lineSpacing = LS))
    }

    // ── Strokes that should NOT qualify ──────────────────────────────────────

    @Test fun singleDirection_noReversal_notDetected() {
        val xs = floatArrayOf(0f, 50f, 100f, 200f, 300f)
        assertFalse(ScratchOutDetection.detect(xs, yRange = 5f, lineSpacing = LS))
    }

    @Test fun oneReversal_notDetected() {
        // Right then left — only 1 reversal (U-shape), not a scratch-out
        val xs = floatArrayOf(0f, 100f, 200f, 100f, 0f)
        assertFalse(ScratchOutDetection.detect(xs, yRange = 5f, lineSpacing = LS))
    }

    @Test fun twoReversals_tooNarrow_notDetected() {
        // Zigzag with 2 reversals but total travel below threshold
        val xs = zigzag(startX = 0f, segmentWidth = 5f, segments = 3)
        assertFalse(ScratchOutDetection.detect(xs, yRange = 1f, lineSpacing = LS))
    }

    @Test fun twoReversals_tooWobbly_notDetected() {
        // Wide zigzag but excessive vertical displacement
        val xs = zigzag(startX = 0f, segmentWidth = 100f, segments = 4)
        val totalXTravel = 100f * 4  // each segment is 100 px
        // yRange = 200% of total x-travel → way over MAX_Y_DRIFT (0.4)
        assertFalse(ScratchOutDetection.detect(xs, yRange = totalXTravel * 2f, lineSpacing = LS))
    }

    @Test fun tooFewPoints_notDetected() {
        // Less than 4 points — cannot reliably detect reversals
        assertFalse(ScratchOutDetection.detect(floatArrayOf(0f, 50f, 100f), yRange = 5f, lineSpacing = LS))
    }

    @Test fun emptyArray_notDetected() {
        assertFalse(ScratchOutDetection.detect(floatArrayOf(), yRange = 0f, lineSpacing = LS))
    }

    // ── Boundary ─────────────────────────────────────────────────────────────

    @Test fun exactlyAtYDriftLimit_notDetected() {
        // yRange == totalXTravel * MAX_Y_DRIFT — must be strictly less than
        val xs = zigzag(startX = 0f, segmentWidth = 60f, segments = 4)
        // total travel = 4 × 60 = 240 px; drift limit = 240 × 0.4 = 96 px
        assertFalse(ScratchOutDetection.detect(xs, yRange = 240f * ScratchOutDetection.MAX_Y_DRIFT, lineSpacing = LS))
    }

    @Test fun justBelowYDriftLimit_detects() {
        val xs = zigzag(startX = 0f, segmentWidth = 60f, segments = 4)
        val totalXTravel = 60f * 4
        assertFalse(ScratchOutDetection.detect(xs, yRange = totalXTravel * ScratchOutDetection.MAX_Y_DRIFT, lineSpacing = LS))
        // one less px → should detect
        assertTrue(ScratchOutDetection.detect(xs, yRange = totalXTravel * ScratchOutDetection.MAX_Y_DRIFT - 1f, lineSpacing = LS))
    }

    // ── Connected cursive false positives ─────────────────────────────────────
    //
    // A connected cursive word is a single stroke that advances left-to-right,
    // with small X-direction reversals at letter transitions (e.g. at the join
    // between 'e' and 'l' in "hello").  These reversals, combined with a mostly-
    // horizontal profile, satisfy all three scratch-out checks:
    //
    //   - Reversals ≥ 2 (letter transitions in a 4+ letter word)
    //   - Total X-travel ≥ 1.5× LS (a word is easily > 177 px wide)
    //   - Y-range < 0.4 × X-travel (writing stays within one line)
    //
    // The key difference: a scratch-out goes BACK AND FORTH over the same region
    // (net X-advance ≈ 0), while cursive PROGRESSES forward (net advance ≈ word
    // width).
    //
    // Fix: add a progressive-advance guard.  If |lastX − firstX| ≥ totalXTravel ×
    // MAX_ADVANCE_RATIO (0.4), the stroke is advancing forward and is NOT a
    // scratch-out.

    @Test fun cursiveWord_progressiveAdvance_notScratchOut() {
        // Simulated cursive "hello": advances right with small leftward dips at
        // letter joins.  Total travel ≈ 300 px, net advance = 240 px,
        // advance ratio = 240/300 = 0.80 — clearly progressive writing.
        val xs = floatArrayOf(0f, 50f, 40f, 100f, 90f, 160f, 150f, 210f, 200f, 240f)
        // 4 reversals, total travel = 50+10+60+10+70+10+60+10+40 = 320
        // net advance = 240, ratio = 0.75
        assertFalse(
            "Cursive word with progressive advance must NOT be scratch-out",
            ScratchOutDetection.detect(xs, yRange = 40f, lineSpacing = LS)
        )
    }

    @Test fun cursiveWordShort_threeLetters_notScratchOut() {
        // Simulated "the": 3 letters, 2 reversals at joins
        // right 80, left 15, right 80, left 15, right 60 → advances to 190
        val xs = floatArrayOf(0f, 80f, 65f, 145f, 130f, 190f)
        // total travel = 80+15+80+15+60 = 250, net = 190, ratio = 0.76
        assertFalse(
            "Short cursive word must NOT be scratch-out",
            ScratchOutDetection.detect(xs, yRange = 35f, lineSpacing = LS)
        )
    }

    @Test fun cursiveLongWord_manyReversals_notScratchOut() {
        // Simulated "minimum": many up-down strokes, 6+ reversals, but steady advance
        val xs = floatArrayOf(
            0f, 40f, 30f, 70f, 60f, 100f, 90f, 130f, 120f, 160f, 150f, 200f, 190f, 240f
        )
        // 6 reversals, progressive advance 0→240
        assertFalse(
            "Long cursive word with many reversals must NOT be scratch-out",
            ScratchOutDetection.detect(xs, yRange = 45f, lineSpacing = LS)
        )
    }

    @Test fun scratchOutStaysInPlace_stillDetected() {
        // True scratch-out: rapid back and forth over same region, ends near start
        // right 100, left 100, right 100, left 100 → net advance = 0
        val xs = floatArrayOf(0f, 100f, 0f, 100f, 0f)
        // total travel = 400, net = 0, ratio = 0.0
        assertTrue(
            "Scratch-out that stays in place should still be detected",
            ScratchOutDetection.detect(xs, yRange = 10f, lineSpacing = LS)
        )
    }

    @Test fun scratchOutSmallDrift_stillDetected() {
        // Scratch-out that drifts slightly rightward: net advance is small vs total travel
        // right 100, left 80, right 100, left 80 → net = 40, total = 360
        // advance ratio = 40/360 = 0.11 — well below 0.3 → still scratch-out
        val xs = floatArrayOf(0f, 100f, 20f, 120f, 40f)
        assertTrue(
            "Scratch-out with small rightward drift should still be detected",
            ScratchOutDetection.detect(xs, yRange = 10f, lineSpacing = LS)
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Build a zigzag x-coordinate array.
     * [segments] equal-width segments alternating right/left.
     * Result has [segments + 1] points.
     */
    private fun zigzag(startX: Float, segmentWidth: Float, segments: Int): FloatArray {
        val pts = FloatArray(segments + 1)
        pts[0] = startX
        for (i in 1..segments) {
            val dir = if (i % 2 == 1) 1f else -1f
            pts[i] = pts[i - 1] + dir * segmentWidth
        }
        return pts
    }
}
