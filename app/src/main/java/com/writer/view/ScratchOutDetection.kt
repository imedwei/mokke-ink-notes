package com.writer.view

import kotlin.math.abs

/**
 * Pure geometry functions for scratch-out (scribble-to-erase) gesture detection.
 *
 * A scratch-out is a rapid back-and-forth horizontal stroke. Detected post-stroke
 * (after pen-up) by counting X-direction reversals. Natural writing strokes have
 * at most one reversal; a deliberate scratch-out has two or more.
 *
 * ## Threshold rationale (standard device, LS = 118 px)
 *
 * | Constant            | Value | Standard device (118 px LS)  |
 * |---------------------|-------|------------------------------|
 * | MIN_REVERSALS       | 2     | ≥ 2 direction changes        |
 * | MIN_X_TRAVEL_SPANS  | 1.5   | ≥ 177 px total x-travel      |
 * | MAX_Y_DRIFT         | 0.4   | y-range < 40% of x-travel    |
 */
object ScratchOutDetection {

    /** Minimum number of X-direction reversals to qualify as a scratch-out. */
    const val MIN_REVERSALS = 2

    /** Minimum total X-travel (sum of all |dx|) in line spacings. */
    const val MIN_X_TRAVEL_SPANS = 1.5f

    /**
     * Maximum vertical bounding-box height as a fraction of total X-travel.
     * Keeps the scratch roughly horizontal.
     */
    const val MAX_Y_DRIFT = 0.4f

    /**
     * Maximum net horizontal advance as a fraction of total X-travel.
     * A scratch-out goes back and forth over the same region (advance ≈ 0);
     * cursive writing progresses steadily forward (advance ≈ word width).
     * If `|lastX − firstX| / totalXTravel ≥ MAX_ADVANCE_RATIO`, the stroke
     * is progressive writing, not a scratch-out.
     */
    const val MAX_ADVANCE_RATIO = 0.4f

    /**
     * Detect a scratch-out gesture.
     *
     * @param xs           x-coordinates of all stroke points
     * @param yRange       vertical bounding-box height: maxY − minY
     * @param lineSpacing  line spacing in pixels
     * @param isClosedLoop true if the stroke is a closed loop (start ≈ end in 2D space).
     *                     A closed loop is never a scratch-out — it is a shape drawn around
     *                     existing content.  Callers should compute this from full (x,y)
     *                     data before calling detect().
     * @return true if the stroke is a scratch-out gesture
     */
    fun detect(xs: FloatArray, yRange: Float, lineSpacing: Float, isClosedLoop: Boolean = false): Boolean {
        if (isClosedLoop) return false
        if (xs.size < 4) return false

        var reversals = 0
        var totalXTravel = 0f
        var prevDir = 0  // -1 = leftward, +1 = rightward

        for (i in 1 until xs.size) {
            val dx = xs[i] - xs[i - 1]
            if (dx == 0f) continue
            val dir = if (dx > 0f) 1 else -1
            totalXTravel += abs(dx)
            if (prevDir != 0 && dir != prevDir) reversals++
            prevDir = dir
        }

        if (reversals < MIN_REVERSALS) return false
        if (totalXTravel < MIN_X_TRAVEL_SPANS * lineSpacing) return false
        if (yRange >= totalXTravel * MAX_Y_DRIFT) return false
        // Progressive-advance guard: cursive writing advances left-to-right while
        // a scratch-out covers the same ground repeatedly.
        val netAdvance = abs(xs.last() - xs.first())
        if (netAdvance >= totalXTravel * MAX_ADVANCE_RATIO) return false
        return true
    }
}
