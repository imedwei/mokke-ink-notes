package com.writer.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LineDragDetection.isSnappableLine].
 *
 * Verifies that straight vertical strokes that would snap to a Line shape
 * are not consumed as line-drag gestures.
 */
class LineDragSnapGuardTest {

    companion object {
        private const val LS = 118f  // standard line spacing (63 dp × 1.875 density)
    }

    @Test fun straightVerticalLine_isSnappable_returnsTrue() {
        // 5-point perfectly vertical stroke spanning 200 px (> 1 × LS = 118 px)
        val xs = floatArrayOf(50f, 50f, 50f, 50f, 50f)
        val ys = floatArrayOf(0f, 50f, 100f, 150f, 200f)
        val result = LineDragDetection.isSnappableLine(xs, ys, LS)
        assertTrue("Straight vertical line should be snappable (returns true)", result)
    }

    @Test fun wobblyVerticalStroke_isNotSnappable_returnsFalse() {
        // Stroke with large x variation — won't pass line deviation check
        val xs = floatArrayOf(0f, 50f, 100f, 50f, 0f)
        val ys = floatArrayOf(0f, 50f, 100f, 150f, 200f)
        val result = LineDragDetection.isSnappableLine(xs, ys, LS)
        assertFalse("Wobbly vertical stroke is not snappable (too much x deviation)", result)
    }

    @Test fun shortVerticalStroke_belowMinSpans_isNotSnappable_returnsFalse() {
        // Short stroke below LINE_MIN_SPANS (only 50 px, LS = 118 px)
        val xs = floatArrayOf(50f, 50f, 50f)
        val ys = floatArrayOf(0f, 25f, 50f)
        val result = LineDragDetection.isSnappableLine(xs, ys, LS)
        assertFalse("Short stroke below min spans should not be snappable", result)
    }
}
