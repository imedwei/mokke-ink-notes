package com.writer.view

import com.writer.view.GutterTouchGuard.Decision.ACCEPT
import com.writer.view.GutterTouchGuard.Decision.REJECT
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [GutterTouchGuard] — palm rejection on gutter buttons.
 *
 * Verifies that accidental palm contact, pen-concurrent taps, and
 * long holds are rejected while deliberate fingertip taps pass through.
 */
class GutterTouchGuardTest {

    private var penBusy = false
    private val guard = GutterTouchGuard(
        palmThresholdPx = 60f,   // ~30dp at 2x density
        maxTapMs = 400L,
        isPenBusy = { penBusy }
    )

    // --- ACTION_DOWN tests ---

    @Test
    fun `fingertip tap accepted on down`() {
        assertEquals(ACCEPT, guard.evaluateDown(touchMajorPx = 20f))
    }

    @Test
    fun `palm touch rejected on down`() {
        assertEquals(REJECT, guard.evaluateDown(touchMajorPx = 80f))
    }

    @Test
    fun `borderline touch at threshold rejected on down`() {
        assertEquals(REJECT, guard.evaluateDown(touchMajorPx = 60.1f))
    }

    @Test
    fun `touch at exactly threshold accepted on down`() {
        assertEquals(ACCEPT, guard.evaluateDown(touchMajorPx = 60f))
    }

    @Test
    fun `fingertip rejected when pen is active on down`() {
        penBusy = true
        assertEquals(REJECT, guard.evaluateDown(touchMajorPx = 20f))
    }

    // --- ACTION_UP tests ---

    @Test
    fun `quick tap accepted on up`() {
        assertEquals(ACCEPT, guard.evaluateUp(downTime = 1000L, eventTime = 1100L))
    }

    @Test
    fun `long hold rejected on up`() {
        assertEquals(REJECT, guard.evaluateUp(downTime = 1000L, eventTime = 1500L))
    }

    @Test
    fun `hold at exactly threshold accepted on up`() {
        assertEquals(ACCEPT, guard.evaluateUp(downTime = 1000L, eventTime = 1400L))
    }

    @Test
    fun `hold just over threshold rejected on up`() {
        assertEquals(REJECT, guard.evaluateUp(downTime = 1000L, eventTime = 1401L))
    }

    @Test
    fun `quick tap rejected when pen becomes active on up`() {
        penBusy = true
        assertEquals(REJECT, guard.evaluateUp(downTime = 1000L, eventTime = 1100L))
    }

    // --- Combined scenarios ---

    @Test
    fun `palm lands before pen - rejected on down`() {
        // Palm rests on button, no pen yet — large touch area rejects
        assertEquals(REJECT, guard.evaluateDown(touchMajorPx = 80f))
    }

    @Test
    fun `finger tap during active pen stroke - rejected on down`() {
        penBusy = true
        assertEquals(REJECT, guard.evaluateDown(touchMajorPx = 20f))
    }

    @Test
    fun `palm rests then lifts after long hold - rejected on up`() {
        // Down passed (small touch), but held too long before release
        assertEquals(ACCEPT, guard.evaluateDown(touchMajorPx = 20f))
        assertEquals(REJECT, guard.evaluateUp(downTime = 1000L, eventTime = 2000L))
    }

    @Test
    fun `pen becomes active between down and up - rejected on up`() {
        assertEquals(ACCEPT, guard.evaluateDown(touchMajorPx = 20f))
        penBusy = true  // pen touches down while finger is held
        assertEquals(REJECT, guard.evaluateUp(downTime = 1000L, eventTime = 1100L))
    }
}
