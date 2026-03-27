package com.writer.view

/**
 * Layered palm-rejection filter for finger touches.
 *
 * Pure logic — no Android view dependency. Takes primitive parameters, returns
 * accept/reject decisions. Unit-testable on JVM.
 *
 * Filter layers (cheapest first):
 * 1. Concurrent stylus suppression — pen is down
 * 2. Pen cooldown — pen lifted within [penCooldownMs]
 * 3. Touch size threshold — contact area too large (palm)
 * 4. Multi-touch rejection — pointerCount > 1
 * 5. Stationary contact timeout — finger hasn't moved past slop (canvas only)
 */
class TouchFilter(
    private val palmSizeThresholdDp: Float = 40f,
    private val penCooldownMs: Long = 500L,
    private val stationarySlopDp: Float = 8f,
    private val stationaryTimeoutMs: Long = 200L,
) {

    enum class Decision { ACCEPT, REJECT }

    // --- Pen state, set by the view ---

    /** True when the stylus is in hover range (proximity, not contact).
     *  Rejects all finger events while set — Apple Pencil-style proximity suppression. */
    @Volatile var penHovering: Boolean = false

    @Volatile var penActive: Boolean = false

    /** Timestamp (uptimeMillis) when pen last lifted. */
    @Volatile var penUpTimestamp: Long = 0L

    // --- Per-touch tracking for stationary check ---

    private var fingerDownX: Float = 0f
    private var fingerDownY: Float = 0f
    private var fingerDownTime: Long = 0L
    private var fingerMovedPastSlop: Boolean = false

    /**
     * Evaluate whether a finger ACTION_DOWN should be accepted.
     *
     * @param pointerCount  number of active pointers
     * @param touchMinorDp  minor axis of touch area in dp
     * @param eventTime     uptimeMillis of the event
     * @param x             screen x of the touch
     * @param y             screen y of the touch
     */
    fun evaluateDown(
        pointerCount: Int,
        touchMinorDp: Float,
        eventTime: Long,
        x: Float,
        y: Float,
    ): Decision {
        // Layer 0: pen is in hover range (proximity suppression)
        if (penHovering) return Decision.REJECT

        // Layer 1: pen is currently down
        if (penActive) return Decision.REJECT

        // Layer 2: pen lifted recently (500ms cooldown)
        if (penUpTimestamp > 0L && (eventTime - penUpTimestamp) < penCooldownMs) {
            return Decision.REJECT
        }

        // Layer 3: contact area too large
        if (touchMinorDp > palmSizeThresholdDp) return Decision.REJECT

        // Layer 4: multi-touch
        if (pointerCount > 1) return Decision.REJECT

        // Passed all down-time checks — start tracking for stationary timeout
        fingerDownX = x
        fingerDownY = y
        fingerDownTime = eventTime
        fingerMovedPastSlop = false

        return Decision.ACCEPT
    }

    /**
     * Evaluate whether a finger ACTION_MOVE should be accepted.
     * Must be called on every move after a successful [evaluateDown].
     *
     * @param pointerCount  number of active pointers
     * @param touchMinorDp  minor axis of touch area in dp
     * @param eventTime     uptimeMillis of the event
     * @param x             screen x
     * @param y             screen y
     * @param checkStationary  true for canvas (reject resting palm), false for text view
     */
    fun evaluateMove(
        pointerCount: Int,
        touchMinorDp: Float,
        eventTime: Long,
        x: Float,
        y: Float,
        checkStationary: Boolean,
    ): Decision {
        // Layer 0: pen entered hover range mid-gesture
        if (penHovering) return Decision.REJECT

        // Layer 1: pen went down mid-gesture
        if (penActive) return Decision.REJECT

        // Layer 3: contact area grew (palm settling)
        if (touchMinorDp > palmSizeThresholdDp) return Decision.REJECT

        // Layer 4: second finger appeared
        if (pointerCount > 1) return Decision.REJECT

        // Layer 5: stationary contact timeout (canvas only)
        if (checkStationary && !fingerMovedPastSlop) {
            val dx = x - fingerDownX
            val dy = y - fingerDownY
            val slopPx = stationarySlopDp * ScreenMetrics.density
            if (dx * dx + dy * dy > slopPx * slopPx) {
                fingerMovedPastSlop = true
            } else if ((eventTime - fingerDownTime) > stationaryTimeoutMs) {
                return Decision.REJECT
            }
        }

        return Decision.ACCEPT
    }

    /**
     * Check whether enough movement has happened for a scroll gesture.
     * Call after [evaluateMove] returns ACCEPT.
     */
    fun hasMovedPastSlop(): Boolean = fingerMovedPastSlop
}
