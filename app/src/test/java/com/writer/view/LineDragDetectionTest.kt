package com.writer.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LineDragDetection.detect].
 *
 * Uses a fixed line spacing of 118 px (63 dp × 1.875 density — standard devices).
 * All strokes are described as (firstY, lastY, xRange); the bounding box xRange
 * represents the maximum horizontal spread across all points.
 *
 * Geometry recap:
 *   - Detected when: |yDelta| > MIN_SPANS (1.0) × lineSpacing
 *                AND  xRange  < |yDelta|  × MAX_DRIFT (0.3)
 *   - Shift = round(yDelta / lineSpacing)  — positive down, negative up
 */
class LineDragDetectionTest {

    companion object {
        private const val LS = 118f  // standard line spacing in px (63 dp × 1.875)
    }

    // ── Detection: strokes that SHOULD fire ───────────────────────────────────

    @Test fun downwardStroke_twoAndHalfLines_detectsShiftThree() {
        // 2.5 line spacings down — well above the MIN_SPANS = 2.0 threshold, narrow xRange
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 2.5f, xRange = 5f, lineSpacing = LS
        )
        assertEquals("2.5 lines down → shift +3 (round-half-up)", 3, result)
    }

    @Test fun downwardStroke_twoPointOneLine_detectsShiftTwo() {
        // 2.1× LS is just over the MIN_SPANS = 2.0 threshold → detected, shift = 2
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 2.1f, xRange = 10f, lineSpacing = LS
        )
        assertEquals("2.1 lines down → shift +2", 2, result)
    }

    @Test fun downwardStroke_threeLines_detectsShiftThree() {
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 3f, xRange = 15f, lineSpacing = LS
        )
        assertEquals("3 lines down → shift +3", 3, result)
    }

    @Test fun upwardStroke_twoPointOneLine_detectsNegativeShift() {
        // 2.1× LS upward — just over MIN_SPANS = 2.0 → detected, shift = -2
        val result = LineDragDetection.detect(
            firstY = LS * 5f, lastY = LS * 5f - LS * 2.1f, xRange = 8f, lineSpacing = LS
        )
        assertEquals("2.1 lines up → shift -2", -2, result)
    }

    @Test fun upwardStroke_twoAndHalfLines_detectsNegativeShift() {
        // 2.5 line spacings up — above MIN_SPANS = 2.0; roundToInt(-2.5) → -2 (round-half-up)
        val result = LineDragDetection.detect(
            firstY = LS * 5f, lastY = LS * 2.5f, xRange = 10f, lineSpacing = LS
        )
        assertEquals("2.5 lines up → shift -2 (round-half-up)", -2, result)
    }

    @Test fun justOverNewThreshold_detectsShiftTwo() {
        // Stroke just barely over the MIN_SPANS = 2.0 threshold
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 2.05f, xRange = 5f, lineSpacing = LS
        )
        assertEquals("Stroke just over 2× LS → detected, shift = 2", 2, result)
    }

    // ── Rejection: strokes that should NOT fire ───────────────────────────────

    @Test fun tooShort_exactlyTwoLineSpacings_notDetected() {
        // absYDelta == 2 × lineSpacing — must be STRICTLY greater than MIN_SPANS * lineSpacing
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 2f, xRange = 5f, lineSpacing = LS
        )
        assertNull("Stroke == 2× LS is exactly at threshold, not over it", result)
    }

    @Test fun tooShort_oneAndHalfLines_notDetected() {
        // 1.5× LS is under the new MIN_SPANS = 2.0 threshold
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 1.5f, xRange = 5f, lineSpacing = LS
        )
        assertNull("Stroke of 1.5× LS is below MIN_SPANS = 2.0, not a line-drag", result)
    }

    @Test fun tooShort_halfLine_notDetected() {
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 0.5f, xRange = 5f, lineSpacing = LS
        )
        assertNull("Short stroke (0.5× LS) not a line-drag", result)
    }

    @Test fun tooWobbly_highXRange_notDetected() {
        // absYDelta = 2× LS, but xRange ≥ absYDelta × 0.3 → too wobbly
        val yDelta = LS * 2f
        val xRange = yDelta * LineDragDetection.MAX_DRIFT  // exactly at limit
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = yDelta, xRange = xRange, lineSpacing = LS
        )
        assertNull("xRange at the drift limit is not a line-drag", result)
    }

    @Test fun diagonal_notDetected() {
        // 45-degree diagonal: xRange ≈ yDelta → way too much drift
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 2f, xRange = LS * 2f, lineSpacing = LS
        )
        assertNull("Diagonal stroke not a line-drag", result)
    }

    @Test fun horizontalStroke_notDetected() {
        // Wide horizontal stroke — yDelta is tiny
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 0.1f, xRange = LS * 3f, lineSpacing = LS
        )
        assertNull("Horizontal stroke not a line-drag", result)
    }

    @Test fun zeroLength_notDetected() {
        val result = LineDragDetection.detect(
            firstY = 100f, lastY = 100f, xRange = 0f, lineSpacing = LS
        )
        assertNull("Zero-length stroke not a line-drag", result)
    }

    // ── Rounding behaviour ────────────────────────────────────────────────────

    @Test fun shiftRoundsToNearestLine_roundUp() {
        // 2.7 line spacings → rounds to 3
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 2.7f, xRange = 5f, lineSpacing = LS
        )
        assertEquals("2.7× LS rounds to shift 3", 3, result)
    }

    @Test fun shiftRoundsToNearestLine_roundDown() {
        // 2.3 line spacings → rounds to 2
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 2.3f, xRange = 5f, lineSpacing = LS
        )
        assertEquals("2.3× LS rounds to shift 2", 2, result)
    }

    // ── False positive: letter-height stroke is mistaken for a line-drag (Bug #6) ──
    //
    // MIN_SPANS = 1.0 means any stroke taller than one line spacing qualifies.
    // A tall handwritten letter (capital, ascender, digit '1') can easily span
    // 1.1 × LINE_SPACING.  Combined with the narrow xRange of a single letter
    // the stroke passes both checks and is silently consumed as a line-drag gesture.
    // The user sees ink appear then vanish — nothing is added to the document.
    //
    // Fix: raise MIN_SPANS to 2.0 so a gesture must span two full line spacings
    // (≈ 15 mm on a 300-PPI device) — a deliberate gesture, never an accidental letter.

    @Test fun letterHeightStroke_notLineDrag() {
        // A stroke 1.1 × LINE_SPACING tall, narrow like a capital letter.
        // Currently FAILS: detect() returns 1 (false positive).
        // After fix (MIN_SPANS = 2.0): 1.1 × LS = 130 < 2.0 × LS = 236 → returns null.
        assertNull(
            "A letter-height stroke (1.1× LS) must not trigger line-drag",
            LineDragDetection.detect(firstY = 0f, lastY = LS * 1.1f, xRange = LS * 0.2f, lineSpacing = LS)
        )
    }

    // ── Gutter-zone guard (Bug #6) ────────────────────────────────────────────
    //
    // Line-drag is only valid when the stroke is written near the right margin
    // (within one gutter-width of the gutter boundary).  Strokes in the main
    // writing area must not be consumed, even if they happen to be tall and narrow
    // (e.g. ascender letters, capital 'I', digit '1').
    //
    // Canvas geometry (Tab X C example):
    //   canvasWidth = 3200 px,  gutterWidth = 129 px
    //   drag zone   = [canvasWidth − 2·gutterWidth, canvasWidth − gutterWidth]
    //               = [2942, 3071]

    private val CW = 3200f   // typical canvas width  (px)
    private val GW = 129f    // typical gutter width  (px)

    @Test fun strokeInDragZone_isAllowed() {
        // strokeMinX = 2950 is inside [2942, 3071] → zone check passes
        assertTrue(
            "Stroke min-X inside drag zone should be allowed",
            LineDragDetection.isInDragZone(strokeMinX = 2950f, canvasWidth = CW, gutterWidth = GW)
        )
    }

    @Test fun strokeAtZoneLeftEdge_isAllowed() {
        // strokeMinX = canvasWidth − 2·gutterWidth = 2942 → exactly at the left boundary → allowed
        assertTrue(
            "Stroke at left edge of drag zone should be allowed",
            LineDragDetection.isInDragZone(strokeMinX = CW - GW * 2, canvasWidth = CW, gutterWidth = GW)
        )
    }

    @Test fun strokeJustOutsideDragZone_isRejected() {
        // strokeMinX = 2941 is 1 px left of the drag zone → NOT allowed
        assertFalse(
            "Stroke just left of drag zone should be rejected",
            LineDragDetection.isInDragZone(strokeMinX = CW - GW * 2 - 1f, canvasWidth = CW, gutterWidth = GW)
        )
    }

    @Test fun strokeFarFromGutter_isRejected() {
        // strokeMinX = 500 is deep in the writing area → NOT a line-drag zone
        assertFalse(
            "Stroke in the middle of the writing area should not be line-drag zone",
            LineDragDetection.isInDragZone(strokeMinX = 500f, canvasWidth = CW, gutterWidth = GW)
        )
    }
}
