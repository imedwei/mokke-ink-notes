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
    const val MIN_X_TRAVEL_SPANS = 0.5f

    /**
     * Reversal density threshold: reversals per line-spacing of X-span.
     * A tight scribble (many reversals packed into a small area) is unmistakably
     * intentional erasing. When density exceeds this, the travel requirement
     * is halved so tight scribbles are accepted sooner.
     */
    const val TIGHT_DENSITY_THRESHOLD = 3.0f

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
     * Maximum path-length / diagonal ratio for a stroke to be considered a closed loop.
     * A shape outline traces its perimeter once (ratio ~3-4).
     * A zigzag scratch-out covers the same ground repeatedly (ratio >> 4).
     * If `pathLength / diagonal ≥ PATH_RATIO_THRESHOLD`, the stroke is a scratch-out
     * zigzag even if start ≈ end, and should NOT be classified as a closed loop.
     */
    const val PATH_RATIO_THRESHOLD = 4.5f

    /**
     * Determine whether a stroke is a true closed loop (shape drawn around content)
     * vs a compact scratch-out (start ≈ end but zigzags back and forth).
     *
     * @param closeDist  distance between first and last points
     * @param diagonal   bounding-box diagonal of the stroke
     * @param pathLength total path length of the stroke
     * @return true if the stroke is a genuine closed loop (not a scratch-out zigzag)
     */
    fun isClosedLoop(closeDist: Float, diagonal: Float, pathLength: Float): Boolean {
        if (diagonal <= 0f) return false
        if (closeDist >= ShapeSnapDetection.CLOSE_FRACTION * diagonal) return false
        // High path/diagonal ratio → zigzag covering same ground, not a shape outline
        return pathLength / diagonal < PATH_RATIO_THRESHOLD
    }

    /**
     * Detect a scratch-out gesture along either axis.
     *
     * Analyses reversals along both X and Y independently, then uses the axis
     * with more reversals as the oscillation axis. The perpendicular axis is
     * used for drift and advance checks. This allows scratch-outs in any
     * direction — horizontal scribbles over vertical arrows and vice versa.
     *
     * @param xs           x-coordinates of all stroke points
     * @param ys           y-coordinates of all stroke points (may be empty for
     *                     backward compatibility — falls back to X-only analysis)
     * @param lineSpacing  line spacing in pixels
     * @param isClosedLoop true if the stroke is a closed loop (start ≈ end in 2D space).
     *                     A closed loop is never a scratch-out — it is a shape drawn around
     *                     existing content.  Callers should compute this from full (x,y)
     *                     data before calling detect().
     * @return true if the stroke is a scratch-out gesture
     */
    fun detect(
        xs: FloatArray, ys: FloatArray = floatArrayOf(),
        lineSpacing: Float, isClosedLoop: Boolean = false
    ): Boolean {
        if (isClosedLoop) return false
        if (xs.size < 4) return false

        val xStats = axisStats(xs)
        val yStats = if (ys.size == xs.size) axisStats(ys) else null

        // Pick the dominant oscillation axis: more reversals, or more travel if tied
        val osc: AxisStats   // oscillation axis (the back-and-forth direction)
        val cross: Float     // perpendicular axis range (drift)
        val yDominant = yStats != null && (yStats.reversals > xStats.reversals
            || (yStats.reversals == xStats.reversals && yStats.totalTravel > xStats.totalTravel))
        if (yDominant) {
            osc = yStats
            cross = xStats.span
        } else {
            osc = xStats
            cross = yStats?.span ?: (xs.max() - xs.min())  // fallback: yRange not available
        }

        if (osc.reversals < MIN_REVERSALS) return false

        // Tight scribble: many reversals packed into a small span.
        // Halve the travel requirement when reversal density is high.
        val spanInSpans = (osc.span / lineSpacing).coerceAtLeast(0.01f)
        val density = osc.reversals / spanInSpans
        val travelThreshold = if (density >= TIGHT_DENSITY_THRESHOLD)
            MIN_X_TRAVEL_SPANS * lineSpacing * 0.5f
        else
            MIN_X_TRAVEL_SPANS * lineSpacing
        if (osc.totalTravel < travelThreshold) return false
        if (cross >= osc.totalTravel * MAX_Y_DRIFT) return false
        // Progressive-advance guard: cursive writing advances along the oscillation
        // axis while a scratch-out covers the same ground repeatedly.
        if (osc.netAdvance >= osc.totalTravel * MAX_ADVANCE_RATIO) return false
        return true
    }

    /** @suppress backward compat: old signature with yRange instead of ys array */
    fun detect(xs: FloatArray, yRange: Float, lineSpacing: Float, isClosedLoop: Boolean = false): Boolean {
        // Delegate to the full version. Without ys, only X-axis reversals are checked
        // and yRange is used as the cross-axis drift.
        if (isClosedLoop) return false
        if (xs.size < 4) return false

        val xStats = axisStats(xs)
        if (xStats.reversals < MIN_REVERSALS) return false

        val spanInSpans = (xStats.span / lineSpacing).coerceAtLeast(0.01f)
        val density = xStats.reversals / spanInSpans
        val travelThreshold = if (density >= TIGHT_DENSITY_THRESHOLD)
            MIN_X_TRAVEL_SPANS * lineSpacing * 0.5f
        else
            MIN_X_TRAVEL_SPANS * lineSpacing
        if (xStats.totalTravel < travelThreshold) return false
        if (yRange >= xStats.totalTravel * MAX_Y_DRIFT) return false
        if (xStats.netAdvance >= xStats.totalTravel * MAX_ADVANCE_RATIO) return false
        return true
    }

    /** Reversal / travel statistics for a single axis. */
    internal data class AxisStats(
        val reversals: Int,
        val totalTravel: Float,
        val span: Float,
        val netAdvance: Float
    )

    /** Compute reversal count, total travel, span, and net advance for one axis. */
    internal fun axisStats(vals: FloatArray): AxisStats {
        var reversals = 0
        var totalTravel = 0f
        var prevDir = 0
        for (i in 1 until vals.size) {
            val d = vals[i] - vals[i - 1]
            if (d == 0f) continue
            val dir = if (d > 0f) 1 else -1
            totalTravel += abs(d)
            if (prevDir != 0 && dir != prevDir) reversals++
            prevDir = dir
        }
        val span = vals.max() - vals.min()
        val netAdvance = abs(vals.last() - vals.first())
        return AxisStats(reversals, totalTravel, span, netAdvance)
    }

    /**
     * Test whether any line segment between consecutive stroke points intersects
     * the axis-aligned rectangle [left, top, right, bottom].
     *
     * This catches geometric strokes (arrows/lines with only 2 points) where the
     * visual line passes through a region even though neither endpoint is inside it.
     * Uses Cohen–Sutherland-style segment clipping.
     */
    fun strokeIntersectsRect(
        points: List<com.writer.model.StrokePoint>,
        left: Float, top: Float, right: Float, bottom: Float
    ): Boolean {
        for (i in 0 until points.size - 1) {
            if (segmentIntersectsRect(
                    points[i].x, points[i].y,
                    points[i + 1].x, points[i + 1].y,
                    left, top, right, bottom
                )) return true
        }
        return false
    }

    /**
     * Cohen–Sutherland line segment vs AABB intersection test.
     * Returns true if the segment (x1,y1)→(x2,y2) intersects or is inside the rect.
     */
    internal fun segmentIntersectsRect(
        x1: Float, y1: Float, x2: Float, y2: Float,
        left: Float, top: Float, right: Float, bottom: Float
    ): Boolean {
        var ax = x1; var ay = y1; var bx = x2; var by = y2

        fun outcode(x: Float, y: Float): Int {
            var code = 0
            if (x < left) code = code or 1
            if (x > right) code = code or 2
            if (y < top) code = code or 4
            if (y > bottom) code = code or 8
            return code
        }

        var codeA = outcode(ax, ay)
        var codeB = outcode(bx, by)

        while (true) {
            if (codeA or codeB == 0) return true          // both inside
            if (codeA and codeB != 0) return false         // both on same outside side
            // Pick the point outside the rect
            val codeOut = if (codeA != 0) codeA else codeB
            val x: Float; val y: Float
            when {
                codeOut and 8 != 0 -> { // below bottom
                    x = ax + (bx - ax) * (bottom - ay) / (by - ay); y = bottom
                }
                codeOut and 4 != 0 -> { // above top
                    x = ax + (bx - ax) * (top - ay) / (by - ay); y = top
                }
                codeOut and 2 != 0 -> { // right of right
                    y = ay + (by - ay) * (right - ax) / (bx - ax); x = right
                }
                else -> { // left of left
                    y = ay + (by - ay) * (left - ax) / (bx - ax); x = left
                }
            }
            if (codeOut == codeA) { ax = x; ay = y; codeA = outcode(ax, ay) }
            else { bx = x; by = y; codeB = outcode(bx, by) }
        }
    }
}
