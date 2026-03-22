package com.writer.view

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Post-stroke shape snapping: converts a freehand stroke to a clean geometric
 * shape when the stroke closely approximates a known shape.
 *
 * Shapes detected (in priority order for closed strokes):
 * 1. Ellipse  — closed loop with 0–1 sharp corners and low deviation from an inscribed ellipse
 * 2. Triangle — closed loop with exactly 3 sharp corners
 * 3. Rectangle — closed loop with ≥4 sharp corners and points near bounding-box edges
 * 4. Line — open stroke closely following a straight path
 *
 * Corner detection uses a windowed angle-change derivative: at each interior point,
 * the angle between the backward and forward vectors (window = max(3, N/12)) is computed.
 * Peaks above CORNER_ANGLE_DEG that are separated by below-threshold points count as one corner.
 *
 * For very short strokes (N < 2·window+1) where reliable corner counting is impossible,
 * closed shapes fall back to bounding-box rectangle detection (handles the minimal 5-point
 * test rectangle used in unit tests without risking false positives in practice).
 */
object ShapeSnapDetection {

    // ── Line constants ────────────────────────────────────────────────────────

    /** Maximum perpendicular deviation / line length for straight-line snap. */
    const val LINE_MAX_DEVIATION = 0.12f

    /** Minimum stroke length in line spacings for straight-line snap. */
    const val LINE_MIN_SPANS = 1.0f

    /**
     * Maximum ratio of path length to straight-line length for line snap.
     * Rejects curved strokes (arcs, unclosed circles) whose path winds far from
     * the start-to-end axis. A straight line = 1.0; a semicircle ≈ 1.57.
     */
    const val LINE_MAX_PATH_RATIO = 1.5f

    // ── Closed-shape constants ────────────────────────────────────────────────

    /** Max distance start→end / diagonal for a stroke to be considered "closed". */
    const val CLOSE_FRACTION = 0.20f

    /** Max mean point-to-nearest-edge distance / diagonal for rectangle snapping. */
    const val RECT_MAX_PERIM_DEV = 0.15f

    /** Max single-point distance to nearest bbox edge / diagonal for rectangle snapping.
     *  Rejects letter-like shapes (B, P, D) whose interior valleys lie far from all edges,
     *  while accepting genuine rectangles and rounded rectangles whose corners merely curve. */
    const val RECT_MAX_POINT_DEV = 0.12f

    /** Minimum length of both sides of the snapped rectangle in line spacings. */
    const val RECT_MIN_SIDE_SPANS = 0.4f

    /**
     * Maximum mean point-to-inscribed-ellipse distance / diagonal for ellipse snap.
     * A perfect circle or oval has deviation 0; a rectangle or triangle has much higher deviation.
     */
    const val ELLIPSE_MAX_DEV = 0.07f

    /**
     * Maximum windowed angle (degrees) for a point to be considered "locally straight".
     * Much lower than [CORNER_ANGLE_DEG] — this detects flat segments, not corners.
     */
    const val STRAIGHT_ANGLE_DEG = 10f

    /**
     * Minimum fraction of stroke points that are locally straight for a shape to be
     * classified as a rounded rectangle rather than an ellipse. An ellipse has curvature
     * everywhere (straightFraction ≈ 0); a rounded rectangle always has four flat sides
     * (straightFraction > 0.2 even with generous corner radii).
     */
    const val STRAIGHT_FRACTION_MIN = 0.12f

    /**
     * Minimum direction-change angle (degrees) at a point to be counted as a corner.
     * A right-angle (90°) corner is well above this threshold; gentle curves are below.
     */
    const val CORNER_ANGLE_DEG = 50f

    /**
     * Minimum distance from a bounding-box corner to the nearest stroke point,
     * expressed as a fraction of the diagonal, for the shape to be treated as having
     * rounded corners rather than sharp ones. If every bounding-box corner (TL/TR/BR/BL)
     * is farther than this threshold from all stroke points, the shape is classified as
     * a rounded rectangle before corner counting.
     *
     * A sharp rectangle has stroke points at or near each bounding-box corner (distance ≈ 0).
     * A rounded rectangle with radius r has its closest arc point at distance 0.414·r from each
     * bounding-box corner; for r ≥ ~3% of diagonal that exceeds this threshold.
     */
    const val ROUNDED_CORNER_THRESHOLD = 0.03f

    // ── Elbow constants ────────────────────────────────────────────────────────

    /** Minimum corner angle (degrees) for elbow detection. */
    const val ELBOW_MIN_ANGLE_DEG = 60f

    /** Maximum corner angle (degrees) for elbow detection. */
    const val ELBOW_MAX_ANGLE_DEG = 120f

    /** Maximum perpendicular deviation / leg length for each elbow leg to be considered straight. */
    const val ELBOW_LEG_MAX_DEVIATION = 0.15f

    // ── Arc constants ────────────────────────────────────────────────────────

    /** Minimum path ratio (path length / chord length) for arc detection. */
    const val ARC_MIN_PATH_RATIO = 1.02f

    /** Maximum path ratio for arc detection. */
    const val ARC_MAX_PATH_RATIO = 2.5f

    /** Maximum point-to-bezier deviation / chord length for arc fit quality. */
    const val ARC_MAX_FIT_DEVIATION = 0.08f

    // ── Self-loop constants ────────────────────────────────────────────────────

    /** Maximum gap (start→end distance / diagonal) for self-loop detection. */
    const val SELF_LOOP_MAX_GAP = 0.75f

    /** Maximum ellipse fit deviation / diagonal for self-loop (more lenient than ELLIPSE_MAX_DEV). */
    const val SELF_LOOP_MAX_ELLIPSE_DEV = 0.15f

    // ── Diamond constant ──────────────────────────────────────────────────────

    /**
     * Maximum distance from each detected corner to its nearest bounding-box edge midpoint,
     * as a fraction of min(width, height). If all corners satisfy this threshold, the shape
     * is classified as a diamond rather than a rectangle.
     */
    const val DIAMOND_CORNER_THRESHOLD = 0.20f

    // ── Result types ──────────────────────────────────────────────────────────

    sealed class SnapResult {
        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : SnapResult()
        data class Arrow(
            val x1: Float, val y1: Float,   // tail
            val x2: Float, val y2: Float,   // head
            val tailHead: Boolean = false,   // arrowhead at tail
            val tipHead: Boolean = true      // arrowhead at tip
        ) : SnapResult()
        data class Ellipse(val cx: Float, val cy: Float, val a: Float, val b: Float) : SnapResult()
        data class RoundedRectangle(
            val left: Float, val top: Float,
            val right: Float, val bottom: Float,
            val cornerRadius: Float
        ) : SnapResult()
        data class Rectangle(
            val left: Float, val top: Float,
            val right: Float, val bottom: Float
        ) : SnapResult()
        data class Diamond(
            val left: Float, val top: Float,
            val right: Float, val bottom: Float
        ) : SnapResult()
        data class Triangle(
            val x1: Float, val y1: Float,
            val x2: Float, val y2: Float,
            val x3: Float, val y3: Float
        ) : SnapResult()
        /** L-shaped connector: start → corner → end. */
        data class Elbow(
            val x1: Float, val y1: Float,
            val cx: Float, val cy: Float,
            val x2: Float, val y2: Float
        ) : SnapResult()
        /** Curved connector: start, quadratic bezier control point, end. */
        data class Arc(
            val x1: Float, val y1: Float,
            val cx: Float, val cy: Float,
            val x2: Float, val y2: Float
        ) : SnapResult()
        /** Smooth open curve that doesn't fit a single bezier (e.g. U-shaped self-referential arc). */
        data class Curve(val points: List<Pair<Float, Float>>) : SnapResult()
        /** Self-referential loop: elliptical arc from startAngle sweeping sweepAngle radians. */
        data class SelfLoop(
            val cx: Float, val cy: Float,
            val rx: Float, val ry: Float,
            val startAngle: Float,
            val sweepAngle: Float
        ) : SnapResult()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun detect(xs: FloatArray, ys: FloatArray, lineSpacing: Float): SnapResult? {
        if (xs.size < 2 || xs.size != ys.size) return null

        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val w = maxX - minX;  val h = maxY - minY
        val diagonal = sqrt(w * w + h * h)

        val closeDistance = dist(xs.first(), ys.first(), xs.last(), ys.last())
        val isClosed = diagonal > 0f && closeDistance < CLOSE_FRACTION * diagonal

        if (isClosed && min(w, h) >= RECT_MIN_SIDE_SPANS * lineSpacing) {
            // Try ellipse first: max-deviation test is robust because rounded
            // shapes (circles, ovals, sloppy circles with small bumps) keep all
            // points within ELLIPSE_MAX_DEV of the inscribed ellipse, while
            // shapes with true sharp corners (rectangles, triangles) have corner
            // points that exceed the threshold.
            detectEllipse(xs, ys, minX, maxX, minY, maxY, diagonal)?.let { return it }

            // Ellipse failed: use corner counting to distinguish rectangle vs triangle.
            val n = xs.size
            val window = maxOf(3, n / 12)
            if (n >= 2 * window + 1) {
                // Count corners first so we can route correctly before the rounded-corner check.
                // Cyclic corner detection: treats the closed stroke as a ring so that a
                // corner exactly at the start/end boundary is never missed.
                val corners = findCornerIndicesCyclic(xs, ys, window)

                // If every bounding-box corner is far from all stroke points, the stroke
                // never reaches the bbox corners — typical of rounded rectangles AND diamonds.
                // We check for diamond (corners near edge midpoints) before rounded rect.
                if (hasRoundedBboxCorners(xs, ys, minX, maxX, minY, maxY, diagonal)) {
                    if (corners.size >= 4) {
                        detectDiamond(xs, ys, corners, minX, maxX, minY, maxY)?.let { return it }
                    }
                    detectRoundedRectangle(xs, ys, minX, maxX, minY, maxY, diagonal)
                        ?.let { return it }
                }

                when {
                    corners.size == 3 -> detectTriangle(xs, ys, corners)
                                             ?.let { return it }
                    corners.size >= 4 -> (detectDiamond(xs, ys, corners, minX, maxX, minY, maxY)
                                             ?: detectRectangle(xs, ys, minX, maxX, minY, maxY, diagonal))
                                             ?.let { return it }
                    // 0–2 sharp corners: check rounded-rectangle as a final fallback.
                    else              -> detectRoundedRectangle(xs, ys, minX, maxX, minY, maxY, diagonal)
                                             ?.let { return it }
                }
            } else {
                // Stroke has too few points for reliable corner counting.
                // Fall back to bounding-box rectangle detection — safe because
                // a genuine circle with so few points cannot form a closed loop.
                detectRectangle(xs, ys, minX, maxX, minY, maxY, diagonal)?.let { return it }
            }
        }

        // Open strokes: try line (strictest), then elbow, then arc, self-loop, curve
        detectLine(xs, ys, lineSpacing)?.let { return it }
        detectElbow(xs, ys, lineSpacing)?.let { return it }
        detectArc(xs, ys, lineSpacing)?.let { return it }
        detectSelfLoop(xs, ys, lineSpacing)?.let { return it }
        return detectCurve(xs, ys, lineSpacing)
    }

    // ── Corner detection ──────────────────────────────────────────────────────

    /**
     * Find corner indices: positions where the stroke direction changes sharply.
     * Uses a windowed angle-change derivative and suppresses duplicate detections
     * within the same corner region via an [inCorner] flag that tracks the current peak.
     *
     * Returns indices sorted ascending (stroke order), one per geometric corner.
     */
    private fun findCornerIndices(
        xs: FloatArray, ys: FloatArray, window: Int, count: Int = xs.size
    ): List<Int> {
        val n = count
        val threshold = (CORNER_ANGLE_DEG * PI / 180).toFloat()
        val result = mutableListOf<Int>()
        var inCorner = false
        var peakAngle = 0f
        var peakIndex = -1

        for (i in window until n - window) {
            val ax = xs[i] - xs[i - window];  val ay = ys[i] - ys[i - window]
            val bx = xs[i + window] - xs[i];  val by = ys[i + window] - ys[i]
            val lenA = hypot(ax.toDouble(), ay.toDouble()).toFloat()
            val lenB = hypot(bx.toDouble(), by.toDouble()).toFloat()
            if (lenA == 0f || lenB == 0f) {
                if (inCorner) { result.add(peakIndex); inCorner = false }
                continue
            }
            val dot = ((ax * bx + ay * by) / (lenA * lenB)).coerceIn(-1f, 1f)
            val angle = acos(dot.toDouble()).toFloat()

            if (angle > threshold) {
                if (!inCorner) { inCorner = true; peakAngle = angle; peakIndex = i }
                else if (angle > peakAngle) { peakAngle = angle; peakIndex = i }
            } else {
                if (inCorner) { result.add(peakIndex); inCorner = false }
            }
        }
        if (inCorner) result.add(peakIndex)
        return result
    }

    /**
     * Cyclic variant of [findCornerIndices] for closed strokes.
     * Prepends the last [window] points and appends the first [window] points so
     * that a corner exactly at the start/end boundary is detected correctly.
     *
     * If the last point duplicates the first (the typical explicit-close convention),
     * it is trimmed before extending to avoid counting the start corner twice.
     */
    // Reusable buffers for findCornerIndicesCyclic to avoid per-call allocations.
    // ShapeSnapDetection is a singleton object, so these are effectively static.
    // NOT thread-safe: detect() must only be called from the main thread.
    private var cyclicBufXs = FloatArray(0)
    private var cyclicBufYs = FloatArray(0)

    private fun findCornerIndicesCyclic(xs: FloatArray, ys: FloatArray, window: Int): List<Int> {
        // Strip the closing duplicate if present so the start/end corner is not detected twice.
        val lastDuplicatesFirst = dist(xs.last(), ys.last(), xs.first(), ys.first()) < 0.01f
        val n = if (lastDuplicatesFirst) xs.size - 1 else xs.size

        val ext = n + 2 * window
        // Grow buffers only when needed; never shrink to avoid repeated allocation
        // across similarly-sized strokes.
        if (cyclicBufXs.size < ext) {
            cyclicBufXs = FloatArray(ext)
            cyclicBufYs = FloatArray(ext)
        }
        val extXs = cyclicBufXs
        val extYs = cyclicBufYs
        for (i in 0 until ext) {
            when {
                i < window     -> { extXs[i] = xs[n - window + i]; extYs[i] = ys[n - window + i] }
                i < n + window -> { extXs[i] = xs[i - window];     extYs[i] = ys[i - window] }
                else           -> { extXs[i] = xs[i - n - window]; extYs[i] = ys[i - n - window] }
            }
        }
        // Extended loop covers extIdx ∈ [window, n+window-1] → origIdx = extIdx - window ∈ [0, n-1]
        // Note: findCornerIndices only reads indices [0, ext), which is within our buffer.
        val translated = findCornerIndices(extXs, extYs, window, count = ext)
            .map { it - window }
            .distinct()
            .sorted()

        // Merge corners whose cyclic distance is ≤ window: they represent the same corner
        // on opposite sides of the stroke's start/end boundary.
        if (translated.size < 2) return translated
        val merged = translated.toMutableList()
        val cyclicDistFirstLast = n - merged.last() + merged.first()
        if (cyclicDistFirstLast <= window) merged.removeAt(merged.lastIndex)
        return merged
    }

    /**
     * Returns true if every bounding-box corner (TL/TR/BR/BL) is farther than
     * [ROUNDED_CORNER_THRESHOLD] × [diagonal] from all stroke points.
     * Indicates that the stroke never reaches the sharp corners of its bounding box,
     * which is the defining characteristic of a rounded rectangle (as opposed to a
     * sharp rectangle or a triangle that touches or lands on a bounding-box corner).
     */
    private fun hasRoundedBboxCorners(
        xs: FloatArray, ys: FloatArray,
        minX: Float, maxX: Float, minY: Float, maxY: Float,
        diagonal: Float
    ): Boolean {
        val threshold = ROUNDED_CORNER_THRESHOLD * diagonal
        val bboxCorners = arrayOf(
            Pair(minX, minY), Pair(maxX, minY), Pair(maxX, maxY), Pair(minX, maxY)
        )
        for ((cx, cy) in bboxCorners) {
            var minDist = Float.MAX_VALUE
            for (i in xs.indices) {
                val d = dist(xs[i], ys[i], cx, cy)
                if (d < minDist) minDist = d
            }
            if (minDist < threshold) return false  // a sharp corner is close to this bbox corner
        }
        return true
    }

    // ── Shape detectors ───────────────────────────────────────────────────────

    private fun detectEllipse(
        xs: FloatArray, ys: FloatArray,
        minX: Float, maxX: Float, minY: Float, maxY: Float,
        diagonal: Float
    ): SnapResult.Ellipse? {
        val cx = (minX + maxX) / 2f;  val cy = (minY + maxY) / 2f
        val a  = (maxX - minX) / 2f;  val b  = (maxY - minY) / 2f
        if (a == 0f || b == 0f) return null
        val a2 = a * a;  val b2 = b * b

        // First-order signed distance to ellipse: |f(P)| / |∇f(P)|  where f(P) = dx²/a² + dy²/b² − 1.
        // Exact for points on the ellipse (f=0) and accurate for points close to it.
        // Unlike radial projection (atan2 → place on ellipse), this formula is correct for
        // non-circular ellipses — atan2 projection gives non-zero error for oval points.
        //
        // We use max deviation: a rectangle with uniformly-spaced edge points has low mean
        // deviation (edge midpoints lie on the inscribed ellipse) but high MAX deviation at
        // corners.  A true ellipse has all points near the surface, so max ≈ 0.
        var maxDev = 0f
        for (i in xs.indices) {
            val dx = xs[i] - cx;  val dy = ys[i] - cy
            val f   = dx * dx / a2 + dy * dy / b2 - 1f
            val gx  = 2f * dx / a2;  val gy = 2f * dy / b2
            val gLen = sqrt(gx * gx + gy * gy)
            val d = if (gLen > 0f) abs(f) / gLen else 0f
            if (d > maxDev) maxDev = d
        }
        if (maxDev > ELLIPSE_MAX_DEV * diagonal) return null

        // Straightness check: an ellipse has non-zero curvature everywhere, so its
        // straight fraction is near 0. A rounded rectangle that passes the ellipse-fit
        // test (generous corner radii) still has four flat sides with straight fraction
        // > 0.20. Reject the ellipse classification if the stroke has too many straight
        // points — the caller will then fall through to rounded-rectangle detection.
        if (straightFraction(xs, ys, minX, maxX, minY, maxY) > STRAIGHT_FRACTION_MIN) return null

        return SnapResult.Ellipse(cx, cy, a, b)
    }

    private fun detectTriangle(
        xs: FloatArray, ys: FloatArray,
        corners: List<Int>
    ): SnapResult.Triangle? {
        if (corners.size != 3) return null
        return SnapResult.Triangle(
            xs[corners[0]], ys[corners[0]],
            xs[corners[1]], ys[corners[1]],
            xs[corners[2]], ys[corners[2]]
        )
    }

    /**
     * Returns a Diamond if all detected corners are near the four bounding-box edge midpoints
     * `(cx,minY)`, `(maxX,cy)`, `(cx,maxY)`, `(minX,cy)`, within [DIAMOND_CORNER_THRESHOLD]×min(w,h).
     */
    private fun detectDiamond(
        xs: FloatArray, ys: FloatArray,
        corners: List<Int>,
        minX: Float, maxX: Float, minY: Float, maxY: Float
    ): SnapResult.Diamond? {
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val w = maxX - minX
        val h = maxY - minY
        val threshold = DIAMOND_CORNER_THRESHOLD * min(w, h)
        val edgeMidpoints = arrayOf(
            Pair(cx, minY), Pair(maxX, cy), Pair(cx, maxY), Pair(minX, cy)
        )
        for (ci in corners) {
            val px = xs[ci]; val py = ys[ci]
            val nearestDist = edgeMidpoints.minOf { (mx, my) -> dist(px, py, mx, my) }
            if (nearestDist > threshold) return null
        }
        return SnapResult.Diamond(minX, minY, maxX, maxY)
    }

    private fun detectRectangle(
        xs: FloatArray, ys: FloatArray,
        minX: Float, maxX: Float, minY: Float, maxY: Float,
        diagonal: Float
    ): SnapResult.Rectangle? {
        if (perimeterDeviation(xs, ys, minX, maxX, minY, maxY) > RECT_MAX_PERIM_DEV * diagonal) return null
        if (maxPerimeterDeviation(xs, ys, minX, maxX, minY, maxY) > RECT_MAX_POINT_DEV * diagonal) return null
        return SnapResult.Rectangle(minX, minY, maxX, maxY)
    }

    private fun detectRoundedRectangle(
        xs: FloatArray, ys: FloatArray,
        minX: Float, maxX: Float, minY: Float, maxY: Float,
        diagonal: Float
    ): SnapResult.RoundedRectangle? {
        // Same bounding-box perimeter check: points must sit near the edges.
        // Rounded corners slightly increase mean deviation vs a sharp rectangle,
        // but the threshold is generous enough to accept them.
        if (perimeterDeviation(xs, ys, minX, maxX, minY, maxY) > RECT_MAX_PERIM_DEV * diagonal) return null
        if (maxPerimeterDeviation(xs, ys, minX, maxX, minY, maxY) > RECT_MAX_POINT_DEV * diagonal) return null
        val w = maxX - minX;  val h = maxY - minY
        val cornerRadius = min(w, h) / 4f
        return SnapResult.RoundedRectangle(minX, minY, maxX, maxY, cornerRadius)
    }

    private fun perimeterDeviation(
        xs: FloatArray, ys: FloatArray,
        minX: Float, maxX: Float, minY: Float, maxY: Float
    ): Float {
        var total = 0f
        for (i in xs.indices) {
            total += minOf(abs(xs[i] - minX), abs(xs[i] - maxX),
                           abs(ys[i] - minY), abs(ys[i] - maxY))
        }
        return total / xs.size
    }

    private fun maxPerimeterDeviation(
        xs: FloatArray, ys: FloatArray,
        minX: Float, maxX: Float, minY: Float, maxY: Float
    ): Float {
        var max = 0f
        for (i in xs.indices) {
            val d = minOf(abs(xs[i] - minX), abs(xs[i] - maxX),
                          abs(ys[i] - minY), abs(ys[i] - maxY))
            if (d > max) max = d
        }
        return max
    }

    private fun detectLine(xs: FloatArray, ys: FloatArray, lineSpacing: Float): SnapResult? {
        val x0 = xs.first(); val y0 = ys.first()
        val x1 = xs.last();  val y1 = ys.last()
        val len = dist(x0, y0, x1, y1)
        if (len < LINE_MIN_SPANS * lineSpacing) return null
        if (maxPerpendicularDeviation(xs, ys, x0, y0, x1, y1) > LINE_MAX_DEVIATION * len) return null
        if (pathLength(xs, ys) > LINE_MAX_PATH_RATIO * len) return null
        return SnapResult.Line(x0, y0, x1, y1)
    }

    /**
     * Detect an L-shaped elbow connector: exactly 1 sharp corner with angle in [60°, 120°],
     * each leg locally straight. Snaps corner to the nearest right-angle position.
     */
    private fun detectElbow(xs: FloatArray, ys: FloatArray, lineSpacing: Float): SnapResult.Elbow? {
        val n = xs.size
        if (n < 5) return null

        val x0 = xs.first(); val y0 = ys.first()
        val x1 = xs.last();  val y1 = ys.last()
        val totalLen = dist(x0, y0, x1, y1)
        if (totalLen < LINE_MIN_SPANS * lineSpacing) return null

        val window = maxOf(3, n / 12)
        if (n < 2 * window + 1) return null

        val corners = findCornerIndices(xs, ys, window)
        if (corners.size != 1) return null
        val ci = corners[0]

        // Verify corner angle is in [60°, 120°]
        val ax = xs[ci] - x0; val ay = ys[ci] - y0
        val bx = x1 - xs[ci]; val by = y1 - ys[ci]
        val lenA = hypot(ax.toDouble(), ay.toDouble()).toFloat()
        val lenB = hypot(bx.toDouble(), by.toDouble()).toFloat()
        if (lenA == 0f || lenB == 0f) return null
        val dot = ((ax * bx + ay * by) / (lenA * lenB)).coerceIn(-1f, 1f)
        val angleDeg = (acos(dot.toDouble()) * 180.0 / PI).toFloat()
        if (angleDeg < ELBOW_MIN_ANGLE_DEG || angleDeg > ELBOW_MAX_ANGLE_DEG) return null

        // Each leg must be locally straight
        val leg1Xs = FloatArray(ci + 1) { xs[it] }
        val leg1Ys = FloatArray(ci + 1) { ys[it] }
        if (maxPerpendicularDeviation(leg1Xs, leg1Ys, x0, y0, xs[ci], ys[ci]) > ELBOW_LEG_MAX_DEVIATION * lenA) return null

        val leg2Size = n - ci
        val leg2Xs = FloatArray(leg2Size) { xs[ci + it] }
        val leg2Ys = FloatArray(leg2Size) { ys[ci + it] }
        if (maxPerpendicularDeviation(leg2Xs, leg2Ys, xs[ci], ys[ci], x1, y1) > ELBOW_LEG_MAX_DEVIATION * lenB) return null

        // Snap corner to right angle: choose (x0, y1) or (x1, y0) — whichever is closer to the raw corner
        val opt1x = x0; val opt1y = y1
        val opt2x = x1; val opt2y = y0
        val d1 = dist(xs[ci], ys[ci], opt1x, opt1y)
        val d2 = dist(xs[ci], ys[ci], opt2x, opt2y)
        val (cx, cy) = if (d1 <= d2) Pair(opt1x, opt1y) else Pair(opt2x, opt2y)

        return SnapResult.Elbow(x0, y0, cx, cy, x1, y1)
    }

    /**
     * Detect a smooth arc connector via quadratic bezier fit quality.
     *
     * Uses bounding box diagonal (not chord) for minimum size and fit normalization,
     * so self-referential arcs with close endpoints are still detected. No path-ratio
     * or corner-count gates — the bezier fit alone rejects zigzags, scribbles, and
     * other non-arc shapes because they deviate far from any single quadratic curve.
     */
    private fun detectArc(xs: FloatArray, ys: FloatArray, lineSpacing: Float): SnapResult.Arc? {
        val n = xs.size
        if (n < 5) return null

        val x0 = xs.first(); val y0 = ys.first()
        val x1 = xs.last();  val y1 = ys.last()
        val chordLen = dist(x0, y0, x1, y1)

        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val w = maxX - minX; val h = maxY - minY
        val diagonal = sqrt(w * w + h * h)

        // Must be large enough (bounding box, not chord — short-chord arcs are valid)
        if (diagonal < LINE_MIN_SPANS * lineSpacing * 0.5f) return null

        // Must not be closed
        if (diagonal > 0f && chordLen < CLOSE_FRACTION * diagonal) return null

        // Find point of max perpendicular deviation from chord — this is the arc midpoint M.
        // For short-chord arcs, use distance from chord midpoint instead.
        var maxDev = 0f
        var maxIdx = n / 2
        if (chordLen > 1f) {
            val dx = x1 - x0; val dy = y1 - y0
            for (i in xs.indices) {
                val dev = abs((xs[i] - x0) * dy - (ys[i] - y0) * dx) / chordLen
                if (dev > maxDev) { maxDev = dev; maxIdx = i }
            }
        } else {
            // Near-zero chord: find the point farthest from the midpoint
            val midX = (x0 + x1) / 2f; val midY = (y0 + y1) / 2f
            for (i in xs.indices) {
                val d = dist(xs[i], ys[i], midX, midY)
                if (d > maxDev) { maxDev = d; maxIdx = i }
            }
        }

        // The arc must have meaningful curvature (not nearly straight)
        val fitRef = maxOf(chordLen, diagonal * 0.5f)
        if (maxDev < fitRef * 0.05f) return null

        // Convert midpoint M to quadratic bezier control point: C = 2*M - 0.5*P0 - 0.5*P2
        val mx = xs[maxIdx]; val my = ys[maxIdx]
        val cx = 2f * mx - 0.5f * x0 - 0.5f * x1
        val cy = 2f * my - 0.5f * y0 - 0.5f * y1

        // Validate fit: for each stroke point, find minimum distance to the bezier curve.
        // Normalize by the larger of chord and half-diagonal, so short-chord arcs
        // get a reasonable tolerance.
        val samples = 50
        var maxFitDev = 0f
        for (i in xs.indices) {
            var minD = Float.MAX_VALUE
            for (s in 0..samples) {
                val t = s.toFloat() / samples
                val omt = 1f - t
                val bx = omt * omt * x0 + 2f * omt * t * cx + t * t * x1
                val by = omt * omt * y0 + 2f * omt * t * cy + t * t * y1
                val d = dist(xs[i], ys[i], bx, by)
                if (d < minD) minD = d
            }
            if (minD > maxFitDev) maxFitDev = minD
        }
        if (maxFitDev > ARC_MAX_FIT_DEVIATION * fitRef) return null

        return SnapResult.Arc(x0, y0, cx, cy, x1, y1)
    }

    /**
     * Detect a smooth open curve that doesn't fit a single quadratic bezier
     * (e.g. U-shaped self-referential arcs). Strips dwell duplicates, checks
     * smoothness with a fixed window, and resamples to evenly-spaced points.
     */
    private fun detectCurve(xs: FloatArray, ys: FloatArray, lineSpacing: Float): SnapResult.Curve? {
        val n = xs.size
        if (n < 10) return null

        // Strip dwell duplicates (consecutive near-identical points)
        val sxs = mutableListOf(xs[0])
        val sys = mutableListOf(ys[0])
        for (i in 1 until n) {
            if (dist(xs[i], ys[i], sxs.last(), sys.last()) > 0.5f) {
                sxs.add(xs[i]); sys.add(ys[i])
            }
        }
        val sx = sxs.toFloatArray()
        val sy = sys.toFloatArray()
        val sn = sx.size
        if (sn < 10) return null

        val minX = sx.min(); val maxX = sx.max()
        val minY = sy.min(); val maxY = sy.max()
        val w = maxX - minX; val h = maxY - minY
        val diagonal = sqrt(w * w + h * h)
        if (diagonal < LINE_MIN_SPANS * lineSpacing * 0.5f) return null

        // Must not be closed
        val chordLen = dist(sx.first(), sy.first(), sx.last(), sy.last())
        if (diagonal > 0f && chordLen < CLOSE_FRACTION * diagonal) return null

        // Smooth: no SHARP corners (>120°). Uses a higher threshold than the standard
        // CORNER_ANGLE_DEG (50°) because smooth U-turns have 70-100° direction changes
        // that are valid curve features, not zigzag corners.
        val window = minOf(8, sn / 4)
        val sharpThreshold = (120f * PI / 180f).toFloat()
        if (window >= 3 && sn >= 2 * window + 1) {
            for (i in window until sn - window) {
                val ax = sx[i] - sx[i - window]; val ay = sy[i] - sy[i - window]
                val bx = sx[i + window] - sx[i]; val by = sy[i + window] - sy[i]
                val lenA = hypot(ax.toDouble(), ay.toDouble()).toFloat()
                val lenB = hypot(bx.toDouble(), by.toDouble()).toFloat()
                if (lenA == 0f || lenB == 0f) continue
                val dot = ((ax * bx + ay * by) / (lenA * lenB)).coerceIn(-1f, 1f)
                if (acos(dot.toDouble()).toFloat() > sharpThreshold) return null
            }
        }

        // Must have meaningful curvature
        val maxPerp = maxPerpendicularDeviation(sx, sy, sx.first(), sy.first(), sx.last(), sy.last())
        if (maxPerp < diagonal * 0.05f) return null

        // Resample to ~30 evenly-spaced points along the path
        val totalLen = pathLength(sx, sy)
        val numPts = 30
        val step = totalLen / numPts
        val pts = mutableListOf(Pair(sx[0], sy[0]))
        var accumulated = 0f
        var nextTarget = step
        for (i in 1 until sn) {
            val segLen = dist(sx[i - 1], sy[i - 1], sx[i], sy[i])
            accumulated += segLen
            while (accumulated >= nextTarget && pts.size < numPts) {
                val overshoot = accumulated - nextTarget
                val t = if (segLen > 0f) 1f - overshoot / segLen else 1f
                pts.add(Pair(
                    sx[i - 1] + (sx[i] - sx[i - 1]) * t,
                    sy[i - 1] + (sy[i] - sy[i - 1]) * t
                ))
                nextTarget += step
            }
        }
        pts.add(Pair(sx.last(), sy.last()))

        return SnapResult.Curve(pts)
    }

    /**
     * Detect a self-referential loop: a near-closed smooth curve that fits an elliptical arc.
     * Used for flowchart self-loop connectors (arrows from a node back to itself).
     */
    private fun detectSelfLoop(xs: FloatArray, ys: FloatArray, lineSpacing: Float): SnapResult.SelfLoop? {
        val n = xs.size
        if (n < 10) return null

        val x0 = xs.first(); val y0 = ys.first()
        val x1 = xs.last();  val y1 = ys.last()

        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val w = maxX - minX; val h = maxY - minY
        val diagonal = sqrt(w * w + h * h)

        // Must have significant extent
        if (min(w, h) < LINE_MIN_SPANS * lineSpacing * 0.5f) return null

        // Must be nearly closed
        val closeDist = dist(x0, y0, x1, y1)
        if (closeDist > SELF_LOOP_MAX_GAP * diagonal) return null

        // Must be smooth (0 corners)
        val window = maxOf(3, n / 12)
        if (n >= 2 * window + 1) {
            val corners = findCornerIndices(xs, ys, window)
            if (corners.isNotEmpty()) return null
        }

        // Fit ellipse using bounding box
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val rx = w / 2f
        val ry = h / 2f
        if (rx == 0f || ry == 0f) return null

        // Validate ellipse fit (more lenient than full ellipse detection)
        val rx2 = rx * rx; val ry2 = ry * ry
        var maxDev = 0f
        for (i in xs.indices) {
            val dx = xs[i] - cx; val dy = ys[i] - cy
            val f = dx * dx / rx2 + dy * dy / ry2 - 1f
            val gx = 2f * dx / rx2; val gy = 2f * dy / ry2
            val gLen = sqrt(gx * gx + gy * gy)
            val d = if (gLen > 0f) abs(f) / gLen else 0f
            if (d > maxDev) maxDev = d
        }
        if (maxDev > SELF_LOOP_MAX_ELLIPSE_DEV * diagonal) return null

        // Compute start/end angles on the normalized ellipse
        val startAngle = atan2((y0 - cy).toDouble() / ry, (x0 - cx).toDouble() / rx).toFloat()
        val endAngle = atan2((y1 - cy).toDouble() / ry, (x1 - cx).toDouble() / rx).toFloat()

        // Determine winding direction using cross product of chord × midpoint offset.
        // The shoelace formula doesn't work for open curves; this method checks which
        // side of the start→end chord the arc's midpoint falls on.
        // In screen coords (y-down): cross < 0 → clockwise, cross > 0 → counter-clockwise.
        val midIdx = n / 2
        val chordDx = x1 - x0; val chordDy = y1 - y0
        val midDx = xs[midIdx] - x0; val midDy = ys[midIdx] - y0
        val cross = chordDx * midDy - chordDy * midDx
        val cw = cross < 0

        var sweep = endAngle - startAngle
        if (cw) {
            if (sweep < 0) sweep += (2 * PI).toFloat()
        } else {
            if (sweep > 0) sweep -= (2 * PI).toFloat()
        }

        // Sweep must cover most of the ellipse (at least 180°)
        if (abs(sweep) < PI.toFloat()) return null

        return SnapResult.SelfLoop(cx, cy, rx, ry, startAngle, sweep)
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private fun pathLength(xs: FloatArray, ys: FloatArray): Float {
        var total = 0f
        for (i in 1 until xs.size) total += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
        return total
    }

    /**
     * Maximum perpendicular distance from any point in [xs]/[ys] to the
     * line through (x0,y0)→(x1,y1). Returns 0f if line has zero length.
     */
    internal fun maxPerpendicularDeviation(
        xs: FloatArray, ys: FloatArray,
        x0: Float, y0: Float, x1: Float, y1: Float
    ): Float {
        val dx = x1 - x0; val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        if (len == 0f) return 0f
        var maxDev = 0f
        for (i in xs.indices) {
            val dev = abs((xs[i] - x0) * dy - (ys[i] - y0) * dx) / len
            if (dev > maxDev) maxDev = dev
        }
        return maxDev
    }

    /**
     * Fraction of stroke points that are "locally straight" — where the windowed
     * direction change is well below the minimum curvature expected from an
     * ellipse inscribed in the same bounding box.
     *
     * An ellipse with semi-axes a,b has minimum curvature k_min = min(a,b)/max(a,b)²
     * at the endpoints of the long axis. The corresponding windowed angle for a
     * window spanning arc length s is approximately s × k_min. Points with angle
     * below half this expected minimum are "truly straight" — they can only come
     * from a rounded rectangle's flat sides, not from an ellipse's low-curvature zone.
     *
     * This adaptive threshold ensures elongated ovals (3:1, 4:1) are never
     * misclassified as rounded rectangles, while still detecting the flat sides
     * of rounded rectangles at any aspect ratio.
     */
    internal fun straightFraction(
        xs: FloatArray, ys: FloatArray,
        minX: Float = xs.min(), maxX: Float = xs.max(),
        minY: Float = ys.min(), maxY: Float = ys.max()
    ): Float {
        val n = xs.size
        val window = maxOf(3, n / 14)
        if (n < 2 * window + 1) return 0f

        val w = maxX - minX; val h = maxY - minY
        val a = w / 2f; val b = h / 2f
        if (a == 0f || b == 0f) return 0f

        // Approximate arc length per point, assuming roughly uniform spacing
        // around the perimeter. Ellipse perimeter ≈ PI * (3(a+b) - sqrt((3a+b)(a+3b))).
        val perim = (PI * (3 * (a + b) - sqrt((3 * a + b).toDouble() * (a + 3 * b)))).toFloat()
        val arcPerWindow = perim * window / n

        // Minimum curvature of the inscribed ellipse: k_min = min(a,b) / max(a,b)²
        val kMin = min(a, b) / (maxOf(a, b) * maxOf(a, b))

        // Expected minimum windowed angle on the ellipse = arc × curvature.
        // A point is "truly straight" if its angle is below half this — meaning
        // it has less curvature than even the flattest part of the ellipse.
        val threshold = (arcPerWindow * kMin * 0.5f)
            .coerceIn((STRAIGHT_ANGLE_DEG * PI / 180).toFloat() * 0.1f,
                       (STRAIGHT_ANGLE_DEG * PI / 180).toFloat())

        var straightCount = 0
        var measuredCount = 0
        for (i in window until n - window) {
            val ax = xs[i] - xs[i - window]; val ay = ys[i] - ys[i - window]
            val bx = xs[i + window] - xs[i]; val by = ys[i + window] - ys[i]
            val lenA = hypot(ax.toDouble(), ay.toDouble()).toFloat()
            val lenB = hypot(bx.toDouble(), by.toDouble()).toFloat()
            if (lenA == 0f || lenB == 0f) continue
            val dot = ((ax * bx + ay * by) / (lenA * lenB)).coerceIn(-1f, 1f)
            val angle = acos(dot.toDouble()).toFloat()
            measuredCount++
            if (angle < threshold) straightCount++
        }
        return if (measuredCount > 0) straightCount.toFloat() / measuredCount else 0f
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }
}
