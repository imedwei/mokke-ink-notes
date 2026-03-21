package com.writer.view

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TouchFilter].
 *
 * Uses the plain-float [ScreenMetrics.init] overload so no Android framework
 * dependency is needed — these run on the JVM with plain JUnit.
 */
class TouchFilterTest {

    companion object {
        private const val DENSITY = 1.875f // 300 PPI Boox devices
    }

    private lateinit var filter: TouchFilter

    @Before
    fun setUp() {
        ScreenMetrics.init(DENSITY, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
        filter = TouchFilter(
            palmSizeThresholdDp = 40f,
            penCooldownMs = 150L,
            stationarySlopDp = 8f,
            stationaryTimeoutMs = 200L,
        )
    }

    // ── Layer 1: Pen active ────────────────────────────────────────────────

    @Test
    fun rejects_finger_when_pen_is_down() {
        filter.penActive = true
        val result = filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        assertEquals(TouchFilter.Decision.REJECT, result)
    }

    @Test
    fun accepts_finger_when_pen_is_not_down() {
        filter.penActive = false
        val result = filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        assertEquals(TouchFilter.Decision.ACCEPT, result)
    }

    // ── Layer 2: Pen cooldown ──────────────────────────────────────────────

    @Test
    fun rejects_finger_during_pen_cooldown() {
        filter.penUpTimestamp = 900L
        val result = filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        assertEquals(TouchFilter.Decision.REJECT, result)
    }

    @Test
    fun accepts_finger_after_pen_cooldown() {
        filter.penUpTimestamp = 800L
        val result = filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        assertEquals(TouchFilter.Decision.ACCEPT, result)
    }

    @Test
    fun accepts_finger_exactly_at_cooldown_boundary() {
        filter.penUpTimestamp = 850L
        val result = filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        assertEquals(TouchFilter.Decision.ACCEPT, result)
    }

    // ── Layer 3: Palm size ─────────────────────────────────────────────────

    @Test
    fun rejects_large_touch_area() {
        val result = filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 50f, eventTime = 1000L, x = 100f, y = 100f
        )
        assertEquals(TouchFilter.Decision.REJECT, result)
    }

    @Test
    fun accepts_small_touch_area() {
        val result = filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 15f, eventTime = 1000L, x = 100f, y = 100f
        )
        assertEquals(TouchFilter.Decision.ACCEPT, result)
    }

    @Test
    fun rejects_touch_at_exact_threshold() {
        val result = filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 40.1f, eventTime = 1000L, x = 100f, y = 100f
        )
        assertEquals(TouchFilter.Decision.REJECT, result)
    }

    // ── Layer 4: Multi-touch ───────────────────────────────────────────────

    @Test
    fun rejects_multi_touch() {
        val result = filter.evaluateDown(
            pointerCount = 2, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        assertEquals(TouchFilter.Decision.REJECT, result)
    }

    // ── Layer 5: Stationary timeout (canvas) ───────────────────────────────

    @Test
    fun rejects_stationary_finger_on_canvas_after_timeout() {
        filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        // Finger hasn't moved, 250ms later
        val result = filter.evaluateMove(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1250L,
            x = 100f, y = 100f, checkStationary = true
        )
        assertEquals(TouchFilter.Decision.REJECT, result)
    }

    @Test
    fun accepts_stationary_finger_on_text_view() {
        filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        // Finger hasn't moved, 250ms later — but checkStationary is false
        val result = filter.evaluateMove(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1250L,
            x = 100f, y = 100f, checkStationary = false
        )
        assertEquals(TouchFilter.Decision.ACCEPT, result)
    }

    @Test
    fun accepts_moving_finger_on_canvas_before_timeout() {
        filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        // Finger hasn't moved much yet, within timeout
        val result = filter.evaluateMove(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1100L,
            x = 101f, y = 101f, checkStationary = true
        )
        assertEquals(TouchFilter.Decision.ACCEPT, result)
    }

    @Test
    fun accepts_finger_that_moves_past_slop_on_canvas() {
        filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        // Move well past slop (8dp * 1.875 = 15px, move 50px)
        val result = filter.evaluateMove(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1300L,
            x = 100f, y = 150f, checkStationary = true
        )
        assertEquals(TouchFilter.Decision.ACCEPT, result)
    }

    // ── Move-time checks ───────────────────────────────────────────────────

    @Test
    fun rejects_move_when_pen_goes_down() {
        filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        filter.penActive = true
        val result = filter.evaluateMove(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1050L,
            x = 100f, y = 150f, checkStationary = false
        )
        assertEquals(TouchFilter.Decision.REJECT, result)
    }

    @Test
    fun rejects_move_when_touch_grows() {
        filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        val result = filter.evaluateMove(
            pointerCount = 1, touchMinorDp = 50f, eventTime = 1050L,
            x = 100f, y = 150f, checkStationary = false
        )
        assertEquals(TouchFilter.Decision.REJECT, result)
    }

    @Test
    fun rejects_move_when_second_finger_appears() {
        filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        val result = filter.evaluateMove(
            pointerCount = 2, touchMinorDp = 10f, eventTime = 1050L,
            x = 100f, y = 150f, checkStationary = false
        )
        assertEquals(TouchFilter.Decision.REJECT, result)
    }

    // ── hasMovedPastSlop ───────────────────────────────────────────────────

    @Test
    fun hasMovedPastSlop_false_initially() {
        filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        assertEquals(false, filter.hasMovedPastSlop())
    }

    @Test
    fun hasMovedPastSlop_true_after_large_move() {
        filter.evaluateDown(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 100f, y = 100f
        )
        filter.evaluateMove(
            pointerCount = 1, touchMinorDp = 10f, eventTime = 1050L,
            x = 100f, y = 150f, checkStationary = true
        )
        assertEquals(true, filter.hasMovedPastSlop())
    }

    // ── Scroll acceptance (full sequence) ──────────────────────────────────

    @Test
    fun full_scroll_sequence_accepted() {
        // Down
        assertEquals(
            TouchFilter.Decision.ACCEPT,
            filter.evaluateDown(
                pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 400f, y = 500f
            )
        )
        // Small move (within slop)
        assertEquals(
            TouchFilter.Decision.ACCEPT,
            filter.evaluateMove(
                pointerCount = 1, touchMinorDp = 10f, eventTime = 1020L,
                x = 400f, y = 505f, checkStationary = true
            )
        )
        assertEquals(false, filter.hasMovedPastSlop())

        // Larger move (past slop)
        assertEquals(
            TouchFilter.Decision.ACCEPT,
            filter.evaluateMove(
                pointerCount = 1, touchMinorDp = 10f, eventTime = 1040L,
                x = 400f, y = 550f, checkStationary = true
            )
        )
        assertEquals(true, filter.hasMovedPastSlop())
    }

    // ── Tap acceptance on text view ────────────────────────────────────────

    @Test
    fun tap_accepted_on_text_view() {
        assertEquals(
            TouchFilter.Decision.ACCEPT,
            filter.evaluateDown(
                pointerCount = 1, touchMinorDp = 10f, eventTime = 1000L, x = 200f, y = 300f
            )
        )
        // Stationary for 300ms — but text view doesn't check stationary
        assertEquals(
            TouchFilter.Decision.ACCEPT,
            filter.evaluateMove(
                pointerCount = 1, touchMinorDp = 10f, eventTime = 1300L,
                x = 200f, y = 300f, checkStationary = false
            )
        )
    }
}
