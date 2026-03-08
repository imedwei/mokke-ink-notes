package com.writer.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun downwardStroke_oneAndHalfLines_detectsShiftOne() {
        // 1.5 line spacings down, narrow xRange
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 1.5f, xRange = 5f, lineSpacing = LS
        )
        assertEquals("1.5 lines down → shift +2 (rounds to nearest)", 2, result)
    }

    @Test fun downwardStroke_twoLines_detectsShiftTwo() {
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 2f, xRange = 10f, lineSpacing = LS
        )
        assertEquals("2 lines down → shift +2", 2, result)
    }

    @Test fun downwardStroke_threeLines_detectsShiftThree() {
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 3f, xRange = 15f, lineSpacing = LS
        )
        assertEquals("3 lines down → shift +3", 3, result)
    }

    @Test fun upwardStroke_twoLines_detectsNegativeShift() {
        val result = LineDragDetection.detect(
            firstY = LS * 5f, lastY = LS * 3f, xRange = 8f, lineSpacing = LS
        )
        assertEquals("2 lines up → shift -2", -2, result)
    }

    @Test fun upwardStroke_oneAndHalfLines_detectsNegativeShift() {
        // roundToInt() rounds ties toward +∞: -1.5 → -1 (not -2)
        val result = LineDragDetection.detect(
            firstY = LS * 4f, lastY = LS * 2.5f, xRange = 10f, lineSpacing = LS
        )
        assertEquals("1.5 lines up → shift -1 (round-half-up)", -1, result)
    }

    @Test fun justOverThreshold_detectsShiftOne() {
        // Stroke just barely over the 1-line threshold
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 1.05f, xRange = 5f, lineSpacing = LS
        )
        assertEquals("Stroke > 1× LS → detected, shift = 1", 1, result)
    }

    // ── Rejection: strokes that should NOT fire ───────────────────────────────

    @Test fun tooShort_exactlyOneLineSpacing_notDetected() {
        // absYDelta == lineSpacing — must be strictly greater than threshold
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS, xRange = 5f, lineSpacing = LS
        )
        assertNull("Stroke == 1× LS is not over threshold", result)
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
        // 1.7 line spacings → rounds to 2
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 1.7f, xRange = 5f, lineSpacing = LS
        )
        assertEquals("1.7× LS rounds to shift 2", 2, result)
    }

    @Test fun shiftRoundsToNearestLine_roundDown() {
        // 1.3 line spacings → rounds to 1
        val result = LineDragDetection.detect(
            firstY = 0f, lastY = LS * 1.3f, xRange = 5f, lineSpacing = LS
        )
        assertEquals("1.3× LS rounds to shift 1", 1, result)
    }
}
