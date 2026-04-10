package com.writer.view

/**
 * Palm-rejection guard for gutter buttons.
 *
 * Pure logic — no Android view dependency. Takes primitive touch parameters
 * and returns accept/reject decisions. Unit-testable on JVM.
 *
 * Rejection layers:
 * 1. Touch contact area too large (palm)
 * 2. Pen is currently active (concurrent stylus suppression)
 * 3. Hold duration exceeds tap threshold (accidental long press)
 */
class GutterTouchGuard(
    val palmThresholdPx: Float,
    private val maxTapMs: Long,
    private val isPenBusy: () -> Boolean,
) {
    enum class Decision { ACCEPT, REJECT }

    /** Evaluate an ACTION_DOWN event. */
    fun evaluateDown(touchMajorPx: Float): Decision {
        if (touchMajorPx > palmThresholdPx) return Decision.REJECT
        if (isPenBusy()) return Decision.REJECT
        return Decision.ACCEPT
    }

    /** Evaluate an ACTION_UP event. */
    fun evaluateUp(downTime: Long, eventTime: Long): Decision {
        if (eventTime - downTime > maxTapMs) return Decision.REJECT
        if (isPenBusy()) return Decision.REJECT
        return Decision.ACCEPT
    }
}
