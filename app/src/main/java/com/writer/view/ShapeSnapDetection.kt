package com.writer.view

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.PI
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

    // ── Result types ──────────────────────────────────────────────────────────

    sealed class SnapResult {
        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : SnapResult()
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
        data class Triangle(
            val x1: Float, val y1: Float,
            val x2: Float, val y2: Float,
            val x3: Float, val y3: Float
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
                // Rounded-rectangle check: if every bounding-box corner is far from all
                // stroke points the corners are genuinely rounded — detect it before counting
                // sharp corners. This also avoids false ≥4-corner counts when a short arc
                // (< 2×window pts) is swallowed by the window and registers as a full 90° spike.
                if (hasRoundedBboxCorners(xs, ys, minX, maxX, minY, maxY, diagonal)) {
                    detectRoundedRectangle(xs, ys, minX, maxX, minY, maxY, diagonal)
                        ?.let { return it }
                }

                // Cyclic corner detection: treats the closed stroke as a ring so that a
                // corner exactly at the start/end boundary is never missed.
                val corners = findCornerIndicesCyclic(xs, ys, window)
                when {
                    corners.size == 3 -> detectTriangle(xs, ys, corners)
                                             ?.let { return it }
                    corners.size >= 4 -> detectRectangle(xs, ys, minX, maxX, minY, maxY, diagonal)
                                             ?.let { return it }
                    // 0–2 sharp corners: no bounding-box corner proximity detected above,
                    // but still check rounded-rectangle as a final fallback.
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

        return detectLine(xs, ys, lineSpacing)
    }

    // ── Corner detection ──────────────────────────────────────────────────────

    /**
     * Find corner indices: positions where the stroke direction changes sharply.
     * Uses a windowed angle-change derivative and suppresses duplicate detections
     * within the same corner region via an [inCorner] flag that tracks the current peak.
     *
     * Returns indices sorted ascending (stroke order), one per geometric corner.
     */
    private fun findCornerIndices(xs: FloatArray, ys: FloatArray, window: Int): List<Int> {
        val n = xs.size
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
    private fun findCornerIndicesCyclic(xs: FloatArray, ys: FloatArray, window: Int): List<Int> {
        // Strip the closing duplicate if present so the start/end corner is not detected twice.
        val lastDuplicatesFirst = dist(xs.last(), ys.last(), xs.first(), ys.first()) < 0.01f
        val n = if (lastDuplicatesFirst) xs.size - 1 else xs.size

        val ext = n + 2 * window
        val extXs = FloatArray(ext) { i ->
            when {
                i < window      -> xs[n - window + i]
                i < n + window  -> xs[i - window]
                else            -> xs[i - n - window]
            }
        }
        val extYs = FloatArray(ext) { i ->
            when {
                i < window      -> ys[n - window + i]
                i < n + window  -> ys[i - window]
                else            -> ys[i - n - window]
            }
        }
        // Extended loop covers extIdx ∈ [window, n+window-1] → origIdx = extIdx - window ∈ [0, n-1]
        val translated = findCornerIndices(extXs, extYs, window)
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
        if (straightFraction(xs, ys) > STRAIGHT_FRACTION_MIN) return null

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
    internal fun straightFraction(xs: FloatArray, ys: FloatArray): Float {
        val n = xs.size
        val window = maxOf(3, n / 14)
        if (n < 2 * window + 1) return 0f

        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
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
