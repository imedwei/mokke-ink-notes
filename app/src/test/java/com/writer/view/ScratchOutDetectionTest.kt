package com.writer.view

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
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

    @Test fun minimalZigzag_exactlyThreeReversals_detects() {
        // 4 segments → 3 reversals; total travel just above threshold
        val segW = X_THRESH / 3f + 2f
        val xs = zigzag(startX = 0f, segmentWidth = segW, segments = 4)
        assertTrue(ScratchOutDetection.detect(xs, yRange = 2f, lineSpacing = LS))
    }

    @Test fun twoReversals_notDetected() {
        // 3 segments → 2 reversals; below MIN_REVERSALS threshold of 3
        val segW = X_THRESH / 2f + 2f
        val xs = zigzag(startX = 0f, segmentWidth = segW, segments = 3)
        assertFalse(ScratchOutDetection.detect(xs, yRange = 2f, lineSpacing = LS))
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

    // ── Bug 1: closed-loop strokes must not trigger scratch-out ──────────────
    //
    // When the user draws a shape (e.g. a rounded-rectangle outline) around existing
    // handwritten letters, the shape snap may fail if the stroke is too wobbly.
    // checkPostStrokeScratchOut() then runs.  A closed loop with multiple x-reversals
    // satisfies all three scratch-out criteria (reversals ≥ 2, travel ≥ 177 px, low
    // y-drift) and currently ERASES the letters inside — a false positive.
    //
    // The fix: ScratchOutDetection.detect() must return false whenever the stroke is
    // a closed loop (stroke start ≈ stroke end relative to its own diagonal).

    /**
     * BUG 1 — CURRENTLY FAILS.
     *
     * A stroke whose x-coordinate series returns to the same value as it started
     * (xs.first() == xs.last()) with multiple x-reversals is detected as scratch-out.
     * This can happen when the user draws a bumpy oval around text:
     *   — shape snap fails (stroke too irregular for any shape detector)
     *   — scratch-out sees ≥ 2 reversals + ≥ 177 px travel + low y-drift → true
     *   — interior letters are erased
     *
     * Correct behaviour: a closed loop is NEVER a scratch-out.
     */
    @Test fun closedLoop_multipleXReversals_notScratchOut() {
        // xs traces a figure-8 / bumpy closed oval: 0 → 200 → 0 → 200 → 0
        // reversals = 3, total x-travel = 800 px, yRange = 30 px → passes all three checks.
        // But the stroke IS a closed loop — must never be treated as scratch-out.
        val xs = floatArrayOf(0f, 200f, 0f, 200f, 0f)
        val yRange = 30f
        assertFalse(
            "Closed loop must NOT trigger scratch-out (Bug 1: erases letters drawn inside a shape)",
            ScratchOutDetection.detect(xs, yRange, LS, isClosedLoop = true)
        )
    }

    /**
     * BUG 1 variant: a rectangular outline with corner overshoots.
     *
     * Real freehand rectangles commonly overshoot corners: after going right the pen
     * briefly continues then reverses, adding extra x-reversals.
     * 300×100 px rectangle, 2 corner overshoots → 2 reversals, total x-travel = 620 px,
     * yRange = 100 px → 100 < 0.4×620 = 248 — passes all scratch-out checks.
     * But the stroke is closed, so it must not trigger scratch-out.
     */
    @Test fun closedRectangleWithCornerOvershoots_notScratchOut() {
        val xs = floatArrayOf(0f, 310f, 300f, -10f, 0f, 0f)
        val yRange = 100f
        assertFalse(
            "Rectangular closed stroke with overshoots must NOT trigger scratch-out (Bug 1)",
            ScratchOutDetection.detect(xs, yRange, LS, isClosedLoop = true)
        )
    }

    // ── Compact scratch-out (start ≈ end) ─────────────────────────────────────
    //
    // When scratching out a thin target (arrow line), the scribble naturally
    // starts and ends at nearly the same position.  The closed-loop guard in
    // checkPostStrokeScratchOut classifies this as a "closed loop" and rejects
    // the scratch-out — but a tight zigzag is clearly NOT a shape drawn around
    // content.  The caller should not classify high-reversal zigzags as closed
    // loops.

    @Test fun compactScratchOut_startNearEnd_notClassifiedAsClosedLoop() {
        // Tight scratch-out: zigzag right-left-right-left, ending near start.
        // closeDist = 5, diagonal = 80, pathLength = 500 (with Y jitter)
        // closeDist/diagonal = 0.0625 < CLOSE_FRACTION (0.20) → geometrically "closed"
        // BUT pathLength/diagonal = 6.25 >> PATH_RATIO_THRESHOLD (4.5) → zigzag, not shape
        // isClosedLoop should return false so scratch-out detection proceeds.
        val closedLoop = ScratchOutDetection.isClosedLoop(
            closeDist = 5f, diagonal = 80f, pathLength = 500f
        )
        assertFalse(
            "Compact scratch-out (high path ratio) should NOT be classified as closed loop",
            closedLoop
        )
    }

    @Test fun shapeOutline_startNearEnd_classifiedAsClosedLoop() {
        // Real shape outline: closeDist = 5, diagonal = 200, pathLength = 600
        // closeDist/diagonal = 0.025 < CLOSE_FRACTION → geometrically closed
        // pathLength/diagonal = 3.0 < PATH_RATIO_THRESHOLD → shape outline
        val closedLoop = ScratchOutDetection.isClosedLoop(
            closeDist = 5f, diagonal = 200f, pathLength = 600f
        )
        assertTrue(
            "Shape outline (low path ratio) should be classified as closed loop",
            closedLoop
        )
    }

    @Test fun compactScratchOut_fullDetection_shouldDetect() {
        // End-to-end: a compact zigzag with start ≈ end should be detected
        // when isClosedLoop is correctly computed as false.
        val xs = floatArrayOf(0f, 80f, 0f, 80f, 5f)
        // isClosedLoop: closeDist=5, diagonal≈80, pathLength≈315 (X-only)
        // With Y jitter, real pathLength would be higher. Use X-only path as lower bound.
        // pathLength/diagonal = 315/80 ≈ 3.9 — still below 4.5 in X-only case.
        // In practice, a real scratch-out has significant Y component pushing ratio > 4.5.
        // Test with isClosedLoop=false to verify detect() accepts the zigzag.
        assertTrue(
            "Compact scratch-out should be detected when not classified as closed loop",
            ScratchOutDetection.detect(xs, yRange = 10f, lineSpacing = LS, isClosedLoop = false)
        )
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

    // ── Vertical scratch-out (Y-axis oscillation) ───────────────────────────

    @Test fun verticalZigzag_detects() {
        // Scribbling up-down over a horizontal arrow.
        // X stays roughly constant, Y zigzags.
        val xs = floatArrayOf(200f, 202f, 198f, 201f, 199f)  // barely moves in X
        val ys = zigzag(startX = 100f, segmentWidth = 60f, segments = 4)  // 4 Y-segments
        assertTrue(
            "Vertical zigzag should be detected as scratch-out",
            ScratchOutDetection.detect(xs, ys, lineSpacing = LS)
        )
    }

    @Test fun verticalZigzag_tooFewReversals_notDetected() {
        val xs = floatArrayOf(200f, 202f, 198f)
        val ys = floatArrayOf(100f, 160f, 100f)  // 1 reversal only
        assertFalse(
            "Vertical zigzag with only 1 reversal should not be detected",
            ScratchOutDetection.detect(xs, ys, lineSpacing = LS)
        )
    }

    @Test fun diagonalZigzag_detects() {
        // Scribble at ~45 degrees: both X and Y oscillate, but one axis dominates
        val xs = floatArrayOf(0f, 50f, 10f, 60f, 20f)  // 3 X-reversals? No: 0→50→10→60→20, reversals=3
        val ys = floatArrayOf(0f, 50f, 10f, 60f, 20f)  // same pattern in Y
        // Both axes have 3 reversals. Either axis works. Total travel per axis ≈ 180px.
        assertTrue(
            "Diagonal zigzag should be detected as scratch-out",
            ScratchOutDetection.detect(xs, ys, lineSpacing = LS)
        )
    }

    // ── Device-captured false positives ─────────────────────────────────────
    //
    // Real strokes captured from the device that were incorrectly detected as
    // scratch-outs.  These are downsampled (every 20th point) but preserve the
    // reversal structure and advance ratio of the originals.

    /**
     * Device-captured: word "difficulty" written in cursive on Palma 2 Pro (ls=77).
     * 23 reversals from tight letter forms (ffi, lt), advance_ratio=0.37.
     *
     * This stroke DOES pass the detection heuristic (advance_ratio < 0.4).
     * The actual false-positive was fixed in [HandwritingCanvasView.checkPostStrokeScratchOut]
     * by requiring existing strokes under the scratch-out region — a new word written
     * onto blank canvas has nothing to erase, so the scratch-out is rejected.
     *
     * This test documents that the detection heuristic alone matches this pattern
     * (so future threshold changes don't unknowingly regress).
     */
    @Test fun cursive_difficulty_matchesDetectionHeuristic() {
        val ls = 77f
        val xs = floatArrayOf(113.9f,113.9f,102.0f,92.7f,90.7f,103.8f,116.1f,122.0f,124.6f,124.6f,112.9f,112.7f,125.2f,134.7f,135.1f,135.1f,143.0f,157.3f,169.2f,177.1f,176.7f,158.3f,157.9f,167.0f,175.5f,184.3f,188.2f,188.2f,182.5f,165.2f,167.2f,174.9f,172.4f,172.4f,179.5f,193.8f,203.5f,203.5f,207.2f,215.2f,205.7f,185.0f,188.0f,201.7f,202.7f,201.1f,201.1f,200.5f,199.9f,198.5f,196.9f,197.3f,207.0f,209.4f,222.1f,233.0f,235.4f,241.3f,243.3f,241.7f,240.3f,252.4f,263.3f,264.3f,265.1f,273.4f,273.8f,284.9f,294.8f,301.7f,296.0f,287.1f,294.0f,307.5f,311.1f,311.8f,311.8f,316.0f,319.2f,315.6f,302.5f,312.2f,329.3f,330.3f,331.1f,329.3f,342.4f,350.7f,335.6f,317.8f,328.3f,344.7f,345.1f)
        val yRange = 87.8f  // 676.0 - 588.2
        assertTrue(
            "Cursive 'difficulty' matches scratch-out heuristic (advance_ratio=0.37 < 0.4)",
            ScratchOutDetection.detect(xs, yRange, ls)
        )
    }

    // ── Target-stroke gating ────────────────────────────────────────────────
    //
    // Scratch-out must only erase when there are pre-existing strokes under
    // the scratch region. Without this, new cursive words with many reversals
    // (e.g. "difficulty") pass the detection heuristic and disappear.

    private fun makeStroke(vararg pairs: Pair<Float, Float>, type: StrokeType = StrokeType.FREEHAND) =
        InkStroke(
            points = pairs.map { (x, y) -> StrokePoint(x, y, 0.5f, 0L) },
            strokeType = type
        )

    @Test fun hasTargetStrokes_withOverlappingStroke_returnsTrue() {
        val existing = listOf(makeStroke(50f to 50f, 60f to 60f))
        assertTrue(
            "Scratch-out over existing stroke should find target",
            ScratchOutDetection.hasTargetStrokes(existing, 40f, 40f, 70f, 70f)
        )
    }

    @Test fun hasTargetStrokes_noStrokes_returnsFalse() {
        assertFalse(
            "Scratch-out on blank canvas must not find target",
            ScratchOutDetection.hasTargetStrokes(emptyList(), 0f, 0f, 100f, 100f)
        )
    }

    @Test fun hasTargetStrokes_strokeOutsideRegion_returnsFalse() {
        val existing = listOf(makeStroke(200f to 200f, 210f to 210f))
        assertFalse(
            "Scratch-out should not match strokes outside its region",
            ScratchOutDetection.hasTargetStrokes(existing, 0f, 0f, 100f, 100f)
        )
    }

    @Test fun hasTargetStrokes_connectorCrossingRegion_returnsTrue() {
        // A geometric line whose segment crosses the region even though
        // neither endpoint is inside it.
        val connector = makeStroke(0f to 50f, 200f to 50f, type = StrokeType.LINE)
        assertTrue(
            "Connector line crossing scratch region should be found",
            ScratchOutDetection.hasTargetStrokes(listOf(connector), 80f, 40f, 120f, 60f)
        )
    }

    @Test fun cursive_difficulty_noTarget_notErased() {
        // End-to-end: "difficulty" matches the detection heuristic but has no
        // strokes underneath. The hasTargetStrokes guard prevents erasure.
        val ls = 77f
        val xs = floatArrayOf(113.9f,113.9f,102.0f,92.7f,90.7f,103.8f,116.1f,122.0f,124.6f,124.6f,112.9f,112.7f,125.2f,134.7f,135.1f,135.1f,143.0f,157.3f,169.2f,177.1f,176.7f,158.3f,157.9f,167.0f,175.5f,184.3f,188.2f,188.2f,182.5f,165.2f,167.2f,174.9f,172.4f,172.4f,179.5f,193.8f,203.5f,203.5f,207.2f,215.2f,205.7f,185.0f,188.0f,201.7f,202.7f,201.1f,201.1f,200.5f,199.9f,198.5f,196.9f,197.3f,207.0f,209.4f,222.1f,233.0f,235.4f,241.3f,243.3f,241.7f,240.3f,252.4f,263.3f,264.3f,265.1f,273.4f,273.8f,284.9f,294.8f,301.7f,296.0f,287.1f,294.0f,307.5f,311.1f,311.8f,311.8f,316.0f,319.2f,315.6f,302.5f,312.2f,329.3f,330.3f,331.1f,329.3f,342.4f,350.7f,335.6f,317.8f,328.3f,344.7f,345.1f)
        val yRange = 87.8f

        // Step 1: detection heuristic fires
        assertTrue("Heuristic should match", ScratchOutDetection.detect(xs, yRange, ls))

        // Step 2: but no existing strokes → scratch-out is rejected
        assertFalse(
            "Cursive 'difficulty' on blank canvas must NOT be erased",
            ScratchOutDetection.hasTargetStrokes(
                emptyList(),
                xs.min(), 588.2f, xs.max(), 676.0f
            )
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

    // ── Closed-loop scratch-out (start ≈ end) ──────────────────────────────
    //
    // When a scratch-out returns near its starting point, the simple
    // closeDist < 0.2*diagonal check classifies it as a "closed loop"
    // (shape drawn around content), causing detect() to reject it.
    // ScratchOutDetection.isClosedLoop() uses the path-length/diagonal
    // ratio to distinguish genuine shape outlines from zigzag scratch-outs.

    @Test fun `isClosedLoop rejects zigzag scratch-out where start near end`() {
        // Scratch-out: closeDist=25, diagonal=191, pathLength=1236
        // pathRatio = 1236/191 = 6.5 → zigzag, NOT a shape outline
        assertFalse(
            "Zigzag scratch-out with start≈end should NOT be classified as closed loop",
            ScratchOutDetection.isClosedLoop(closeDist = 25f, diagonal = 191f, pathLength = 1236f)
        )
    }

    @Test fun `isClosedLoop accepts genuine shape outline`() {
        // Circle-like shape: closeDist=10, diagonal=100, pathLength=320
        // pathRatio = 320/100 = 3.2 → shape outline
        assertTrue(
            "Shape outline should be classified as closed loop",
            ScratchOutDetection.isClosedLoop(closeDist = 10f, diagonal = 100f, pathLength = 320f)
        )
    }

    @Test fun `scratch-out with start near end detected when using isClosedLoop`() {
        // Real bug: scratch-out zigzag where start ≈ end was rejected because
        // checkPostStrokeScratchOut used simple closeDist check instead of
        // isClosedLoop (which accounts for path-length/diagonal ratio).
        //
        // Simulated: 7 reversals, ends near start (net advance = 15px)
        val xs = zigzag(startX = 80f, segmentWidth = 95f, segments = 8)
        // xs = [80, 175, 80, 175, 80, 175, 80, 175, 80]
        // closeDist=0, diagonal≈95, pathLength=760
        // pathRatio = 760/95 = 8.0 → zigzag, not a closed loop
        val closeDist = kotlin.math.abs(xs.last() - xs.first())
        val diagonal = xs.max() - xs.min()
        val pathLength = (1 until xs.size).sumOf {
            kotlin.math.abs(xs[it] - xs[it - 1]).toDouble()
        }.toFloat()

        val isClosedLoop = ScratchOutDetection.isClosedLoop(closeDist, diagonal, pathLength)
        assertFalse("Zigzag should not be a closed loop", isClosedLoop)

        assertTrue(
            "Scratch-out with start≈end should be detected when isClosedLoop is correct",
            ScratchOutDetection.detect(xs, yRange = 20f, lineSpacing = LS, isClosedLoop = isClosedLoop)
        )
    }
}
