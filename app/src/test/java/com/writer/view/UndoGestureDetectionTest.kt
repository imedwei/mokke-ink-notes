package com.writer.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UndoGestureDetection].
 *
 * Uses a fixed line spacing of 118 px (63 dp × 1.875 density — standard devices).
 * Tests reference [UndoGestureDetection] constants directly so they remain valid
 * when threshold values are tuned.
 *
 * ## Box-drawing geometry at 300 PPI (1.875 density)
 *
 *   Line spacing = 118 px ≈ 10 mm
 *   HORIZONTAL_MIN_SPANS = 1.5  →  trigger threshold ≈ 177 px ≈ 15 mm
 *   VERTICAL_ACTIVATION_SPANS = 1.0  →  activation threshold ≈ 118 px ≈ 10 mm
 *
 *   Natural horizontal stroke drift before pen-lift: < 5 mm (< 59 px).
 *   A deliberate undo stroke goes horizontal then curves > 10 mm vertically.
 */
class UndoGestureDetectionTest {

    companion object {
        private const val LS   = 118f  // line spacing in px (63 dp × 1.875)
        // ~11 dp × 1.875 density, same as ScreenMetrics.dp(11f) on standard devices
        private const val STEP = 21f

        private val H_THRESH get() = UndoGestureDetection.HORIZONTAL_MIN_SPANS * LS
        private val V_THRESH get() = UndoGestureDetection.VERTICAL_ACTIVATION_SPANS * LS
    }

    // ── isHorizontalTrigger — strokes that SHOULD qualify ────────────────────

    @Test fun wideFlat_qualifiesAsTrigger() {
        assertTrue(UndoGestureDetection.isHorizontalTrigger(
            xRange = H_THRESH * 2f, yRange = LS * 0.1f, lineSpacing = LS
        ))
    }

    @Test fun justAboveHorizontalThreshold_qualifiesAsTrigger() {
        assertTrue(UndoGestureDetection.isHorizontalTrigger(
            xRange = H_THRESH + 1f, yRange = LS * 0.05f, lineSpacing = LS
        ))
    }

    // ── isHorizontalTrigger — strokes that should NOT qualify ─────────────────

    @Test fun belowHorizontalThreshold_doesNotTrigger() {
        assertFalse(UndoGestureDetection.isHorizontalTrigger(
            xRange = H_THRESH - 1f, yRange = LS * 0.05f, lineSpacing = LS
        ))
    }

    @Test fun exactlyAtHorizontalThreshold_doesNotTrigger() {
        assertFalse(UndoGestureDetection.isHorizontalTrigger(
            xRange = H_THRESH, yRange = LS * 0.05f, lineSpacing = LS
        ))
    }

    @Test fun wideButWobbly_doesNotTrigger() {
        val xRange = H_THRESH * 2f
        val yRange = xRange * UndoGestureDetection.HORIZONTAL_MAX_DRIFT  // at limit
        assertFalse(UndoGestureDetection.isHorizontalTrigger(xRange, yRange, LS))
    }

    @Test fun diagonal_doesNotTrigger() {
        assertFalse(UndoGestureDetection.isHorizontalTrigger(
            xRange = H_THRESH * 2f, yRange = H_THRESH * 2f, lineSpacing = LS
        ))
    }

    // ── isVerticalActivation — movements that SHOULD activate scrub ──────────

    @Test fun largeVerticalDown_activates() {
        assertTrue(UndoGestureDetection.isVerticalActivation(V_THRESH * 2f, LS))
    }

    @Test fun largeVerticalUp_activates() {
        assertTrue(UndoGestureDetection.isVerticalActivation(-V_THRESH * 2f, LS))
    }

    @Test fun justAboveVerticalThreshold_activates() {
        assertTrue(UndoGestureDetection.isVerticalActivation(V_THRESH + 1f, LS))
    }

    // ── isVerticalActivation — small dips that should NOT activate ────────────

    @Test fun smallDip_doesNotActivate() {
        // Natural pen drift well below the activation threshold
        assertFalse(UndoGestureDetection.isVerticalActivation(V_THRESH * 0.4f, LS))
    }

    @Test fun exactlyAtVerticalThreshold_doesNotActivate() {
        assertFalse(UndoGestureDetection.isVerticalActivation(V_THRESH, LS))
    }

    @Test fun zeroDip_doesNotActivate() {
        assertFalse(UndoGestureDetection.isVerticalActivation(0f, LS))
    }

    // ── detect() — post-stroke L-shape ───────────────────────────────────────

    @Test fun lShape_downward_detectsPositiveOffset() {
        val result = UndoGestureDetection.detect(
            firstY = 0f, lastY = V_THRESH * 2f, xRange = H_THRESH * 2f,
            lineSpacing = LS, stepSize = STEP
        )
        assertNotNull(result)
        assertTrue("Downward L-shape → positive scrub offset (redo dir)", result!! > 0)
    }

    @Test fun lShape_upward_detectsNegativeOffset() {
        val result = UndoGestureDetection.detect(
            firstY = LS * 5f, lastY = LS * 5f - V_THRESH * 2f, xRange = H_THRESH * 2f,
            lineSpacing = LS, stepSize = STEP
        )
        assertNotNull(result)
        assertTrue("Upward L-shape → negative scrub offset (undo dir)", result!! < 0)
    }

    @Test fun lShape_offsetProportionalToVerticalTravel() {
        val yDelta = V_THRESH * 3f
        val result = UndoGestureDetection.detect(
            firstY = 0f, lastY = yDelta, xRange = H_THRESH * 2f,
            lineSpacing = LS, stepSize = STEP
        )
        val expected = (yDelta / STEP).toInt()
        assertEquals("Scrub offset = yDelta / stepSize truncated", expected, result)
    }

    // ── detect() — strokes that must NOT fire ─────────────────────────────────

    @Test fun pureHorizontal_naturalDrift_notDetected() {
        // A horizontal stroke with tiny net vertical displacement (natural hand drift)
        val result = UndoGestureDetection.detect(
            firstY = 200f, lastY = 210f,   // only 10 px drift — well under V_THRESH
            xRange = H_THRESH * 2f,
            lineSpacing = LS, stepSize = STEP
        )
        assertNull("Horizontal stroke with tiny drift is not an undo gesture", result)
    }

    @Test fun pureVertical_notDetected() {
        val result = UndoGestureDetection.detect(
            firstY = 0f, lastY = V_THRESH * 3f, xRange = LS * 0.3f,
            lineSpacing = LS, stepSize = STEP
        )
        assertNull("Pure vertical stroke not an undo gesture", result)
    }

    @Test fun tooNarrow_notDetected() {
        val result = UndoGestureDetection.detect(
            firstY = 0f, lastY = V_THRESH * 2f, xRange = H_THRESH - 1f,
            lineSpacing = LS, stepSize = STEP
        )
        assertNull("Stroke narrower than horizontal threshold not an undo gesture", result)
    }

    @Test fun notEnoughVertical_notDetected() {
        val result = UndoGestureDetection.detect(
            firstY = 0f, lastY = V_THRESH - 1f, xRange = H_THRESH * 2f,
            lineSpacing = LS, stepSize = STEP
        )
        assertNull("Stroke with insufficient vertical displacement not an undo gesture", result)
    }
}
