package com.writer.view

import com.writer.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.abs

/**
 * Unit tests for [ShapeSnapDetection].
 *
 * Uses a fixed line spacing of 118 px (standard device: 63 dp × 1.875 density).
 *
 * Shape classification:
 * - Circle/oval (0 corners)  → Ellipse
 * - Triangle   (3 corners)  → Triangle
 * - Rectangle  (4 corners)  → Rectangle
 * - Straight stroke          → Line
 */
class ShapeSnapDetectionTest {

    companion object {
        private const val LS = 118f
    }

    // ── Ellipse: circles and ovals ────────────────────────────────────────────

    @Test fun perfectCircle_snapsToEllipse() {
        // 40-point circle, radius 100 px, centered at (100,100). Fully closed (point 40 == point 0).
        val n = 40
        val xs = FloatArray(n + 1) { i -> (100 + 100 * cos(2 * PI * i / n)).toFloat() }
        val ys = FloatArray(n + 1) { i -> (100 + 100 * sin(2 * PI * i / n)).toFloat() }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Circle should snap to an ellipse", result)
        assertTrue("Circle snaps to Ellipse", result is ShapeSnapDetection.SnapResult.Ellipse)
        val e = result as ShapeSnapDetection.SnapResult.Ellipse
        assertEquals(100f, e.cx, 2f)
        assertEquals(100f, e.cy, 2f)
        assertEquals(100f, e.a,  2f)
        assertEquals(100f, e.b,  2f)
    }

    @Test fun oval_snapsToEllipse() {
        // 40-point oval: 300 px wide, 150 px tall. Fully closed.
        val n = 40
        val a = 150f; val b = 75f
        val xs = FloatArray(n + 1) { i -> (a + a * cos(2 * PI * i / n)).toFloat() }
        val ys = FloatArray(n + 1) { i -> (b + b * sin(2 * PI * i / n)).toFloat() }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Oval should snap to an ellipse", result)
        assertTrue("Oval snaps to Ellipse", result is ShapeSnapDetection.SnapResult.Ellipse)
        val e = result as ShapeSnapDetection.SnapResult.Ellipse
        assertEquals(a, e.cx, 3f)
        assertEquals(b, e.cy, 3f)
        assertEquals(a, e.a,  3f)
        assertEquals(b, e.b,  3f)
    }

    @Test fun circle20Points_snapsToEllipse() {
        // 20-point circle: each arc segment = 18°. With window=3 the subtended angle
        // is 54° > CORNER_ANGLE_DEG (50°), so every point registers as a "corner".
        // This causes the circle to snap to Rectangle or Triangle instead of Ellipse.
        val n = 20
        val xs = FloatArray(n + 1) { i -> (100 + 100 * cos(2 * PI * i / n)).toFloat() }
        val ys = FloatArray(n + 1) { i -> (100 + 100 * sin(2 * PI * i / n)).toFloat() }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("20-point circle should snap to an ellipse", result)
        assertTrue("20-point circle must snap to Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun circle15Points_snapsToEllipse() {
        // 15-point circle: arc step = 24°, window=3, subtended = 72° >> 50°.
        val n = 15
        val xs = FloatArray(n + 1) { i -> (100 + 100 * cos(2 * PI * i / n)).toFloat() }
        val ys = FloatArray(n + 1) { i -> (100 + 100 * sin(2 * PI * i / n)).toFloat() }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("15-point circle should snap to an ellipse", result)
        assertTrue("15-point circle must snap to Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun bumpyCircleWithSpuriousCorners_snapsToEllipse() {
        // 20-point circle (arc step = 18°, window = 3, subtended = 54° > 50°).
        // Two points nudged inward by 15 px cause brief angle dips that reset
        // the inCorner flag, splitting the stroke into 3 distinct corner-regions:
        //   region 1: i 3..6  → corner at ~6
        //   region 2: i 8..13 → corner at ~13
        //   region 3: i 15..17 → corner at ~17
        // corners.size == 3 → current code misclassifies as Triangle.
        // Max ellipse deviation = 15 / (100*√2*2) ≈ 5.3% < ELLIPSE_MAX_DEV (7%)
        // so the shape should snap to Ellipse.
        val n = 20
        val nudgeAt = setOf(7, 14)
        val xs = FloatArray(n + 1) { i ->
            val angle = 2 * PI * i / n
            val r = if (i in nudgeAt) 85.0 else 100.0
            (100 + r * cos(angle)).toFloat()
        }
        val ys = FloatArray(n + 1) { i ->
            val angle = 2 * PI * i / n
            val r = if (i in nudgeAt) 85.0 else 100.0
            (100 + r * sin(angle)).toFloat()
        }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Bumpy circle should snap to an ellipse", result)
        assertTrue("Bumpy circle must snap to Ellipse (not Triangle), got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    /** Helper: generate an oval with optional noise and non-uniform point spacing. */
    private fun makeOval(
        cx: Float, cy: Float, a: Float, b: Float,
        n: Int = 60, noise: Float = 0f, gapFraction: Float = 0f
    ): Pair<FloatArray, FloatArray> {
        val count = n + 1
        // Non-uniform angular spacing: slight speed variation like hand-drawn
        val angles = DoubleArray(count) { i ->
            val base = 2 * PI * i / n
            // Small angular jitter for realism
            if (noise > 0 && i > 0 && i < n) base + (((i * 17) % 7) - 3) * 0.01
            else base
        }
        val xs = FloatArray(count) { i ->
            val px = (cx + a * cos(angles[i])).toFloat()
            if (noise > 0) px + ((i * 7 % 11) - 5).toFloat() * noise / 5f else px
        }
        val ys = FloatArray(count) { i ->
            val py = (cy + b * sin(angles[i])).toFloat()
            if (noise > 0) py + ((i * 13 % 11) - 5).toFloat() * noise / 5f else py
        }
        // Simulate imperfect closure: last point offset from first
        if (gapFraction > 0) {
            val gapPx = gapFraction * sqrt((2 * a) * (2 * a) + (2 * b) * (2 * b))
            xs[n] = xs[0] + gapPx * 0.3f
            ys[n] = ys[0] + gapPx * 0.7f
        }
        return Pair(xs, ys)
    }

    // ── Tall/narrow ovals at various aspect ratios ──────────────────────────

    @Test fun tallOval_2to1_perfect() {
        val (xs, ys) = makeOval(200f, 200f, 50f, 100f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("2:1 tall oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun tallOval_3to1_perfect() {
        val (xs, ys) = makeOval(200f, 300f, 40f, 120f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("3:1 tall oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun tallOval_4to1_perfect() {
        val (xs, ys) = makeOval(200f, 300f, 30f, 120f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("4:1 tall oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun tallOval_5to1_perfect() {
        // Very narrow: 48 px wide, 240 px tall — tests RECT_MIN_SIDE_SPANS gate
        val (xs, ys) = makeOval(200f, 300f, 24f, 120f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("5:1 tall oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun wideOval_3to1_perfect() {
        val (xs, ys) = makeOval(200f, 200f, 150f, 50f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("3:1 wide oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    // ── Hand-drawn ovals with noise ─────────────────────────────────────────

    @Test fun tallOval_3to1_noise5px() {
        val (xs, ys) = makeOval(200f, 300f, 40f, 120f, noise = 5f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Noisy 3:1 tall oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun tallOval_3to1_noise8px() {
        val (xs, ys) = makeOval(200f, 300f, 40f, 120f, noise = 8f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Noisy 8px 3:1 tall oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun tallOval_2to1_noise3px() {
        // 3px noise is realistic for pen input on a 100px-wide oval
        val (xs, ys) = makeOval(200f, 200f, 50f, 100f, noise = 3f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Noisy 2:1 tall oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    // ── Imperfect closure ───────────────────────────────────────────────────

    @Test fun tallOval_3to1_slightGap() {
        // Stroke doesn't perfectly close — 5% gap
        val (xs, ys) = makeOval(200f, 300f, 40f, 120f, noise = 3f, gapFraction = 0.05f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Slightly open 3:1 oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    // ── Device-realistic: 80-point strokes like real pen input ──────────────

    @Test fun tallOval_deviceRealistic_80points() {
        // Simulate real device: ~80 points, 3:1 aspect, moderate noise
        val (xs, ys) = makeOval(400f, 500f, 60f, 180f, n = 80, noise = 6f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("80pt realistic tall oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun tallOval_deviceRealistic_40points() {
        // Fast draw: fewer points, 3:1 aspect
        val (xs, ys) = makeOval(400f, 500f, 50f, 150f, n = 40, noise = 4f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("40pt realistic tall oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun tallOval_deviceRealistic_smallOnScreen() {
        // Small oval: 60 px wide, 120 px tall — near minimum size
        val (xs, ys) = makeOval(300f, 300f, 30f, 60f, n = 50, noise = 3f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Small realistic tall oval → Ellipse, got $result", result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    // ── Real device strokes from bug report (misclassified as non-ellipse) ──

    @Test fun deviceStroke_rectangle_shouldBeEllipse() {
        // Real stroke from bug report: classified as RECTANGLE, aspect ratio 4.02
        val raw = floatArrayOf(
            349.69f, 184.92f, 347.11f, 194.43f, 333.25f, 234.05f,
            324.13f, 267.73f, 312.64f, 321.81f, 307.09f, 353.90f,
            294.21f, 412.74f, 289.86f, 441.26f, 288.27f, 477.32f,
            288.27f, 505.25f, 286.69f, 530.01f, 286.88f, 548.04f,
            289.26f, 559.33f, 294.21f, 568.84f, 299.76f, 572.21f,
            305.51f, 573.20f, 311.25f, 572.80f, 315.61f, 571.22f,
            318.39f, 569.63f, 323.93f, 564.48f, 336.22f, 547.05f,
            347.51f, 527.64f, 357.81f, 503.47f, 365.14f, 474.74f,
            374.85f, 428.19f, 379.01f, 389.36f, 383.37f, 358.46f,
            383.37f, 260.20f, 381.19f, 247.32f, 377.23f, 231.48f,
            375.05f, 215.03f, 373.86f, 211.07f, 370.10f, 204.53f,
            363.36f, 196.81f, 357.81f, 192.65f, 353.85f, 191.06f,
            347.11f, 191.86f, 337.21f, 191.46f
        )
        val xs = FloatArray(raw.size / 2) { raw[it * 2] }
        val ys = FloatArray(raw.size / 2) { raw[it * 2 + 1] }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Device stroke (was RECTANGLE) → Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun deviceStroke_roundedRect_shouldBeEllipse() {
        // Real stroke from bug report: classified as ROUNDED_RECTANGLE, aspect ratio 4.12
        val raw = floatArrayOf(
            666.49f, 196.61f, 653.41f, 231.48f, 642.72f, 268.52f,
            638.16f, 287.93f, 632.02f, 320.22f, 621.71f, 382.03f,
            618.54f, 406.99f, 616.56f, 450.38f, 617.75f, 493.56f,
            618.35f, 503.27f, 620.53f, 517.73f, 623.50f, 527.64f,
            627.86f, 535.76f, 631.03f, 539.72f, 635.78f, 543.29f,
            640.14f, 545.27f, 645.69f, 546.46f, 652.03f, 545.66f,
            659.36f, 542.10f, 664.51f, 536.55f, 667.68f, 531.20f,
            669.86f, 524.66f, 681.75f, 468.01f, 695.81f, 414.72f,
            698.19f, 402.04f, 701.36f, 379.06f, 701.56f, 347.76f,
            697.99f, 319.43f, 697.20f, 297.44f, 690.46f, 234.65f,
            687.69f, 222.17f, 686.50f, 219.79f, 679.17f, 211.67f,
            672.24f, 206.71f, 668.67f, 205.13f
        )
        val xs = FloatArray(raw.size / 2) { raw[it * 2] }
        val ys = FloatArray(raw.size / 2) { raw[it * 2 + 1] }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Device stroke (was ROUNDED_RECT) → Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun deviceStroke_triangle_shouldBeEllipse() {
        // Real stroke from bug report: classified as TRIANGLE, aspect ratio 2.77
        val raw = floatArrayOf(
            562.08f, 588.45f, 557.32f, 592.22f, 554.15f, 596.18f,
            547.42f, 611.43f, 537.91f, 641.54f, 526.02f, 694.83f,
            523.64f, 715.63f, 522.85f, 745.15f, 524.04f, 765.36f,
            527.21f, 776.65f, 530.38f, 778.83f, 533.55f, 779.42f,
            539.30f, 777.84f, 545.04f, 774.67f, 564.06f, 744.56f,
            569.01f, 735.05f, 575.35f, 719.79f, 579.51f, 705.53f,
            587.44f, 658.58f, 590.01f, 651.85f, 593.18f, 637.38f,
            593.78f, 626.29f, 592.59f, 619.95f, 586.05f, 596.18f,
            583.28f, 590.63f, 578.52f, 585.68f, 570.60f, 582.91f,
            558.12f, 584.49f
        )
        val xs = FloatArray(raw.size / 2) { raw[it * 2] }
        val ys = FloatArray(raw.size / 2) { raw[it * 2 + 1] }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Device stroke (was TRIANGLE) → Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun threeQuarterArc_snapsToSelfLoop() {
        // 3/4 of a circle (270°): start at (200,100), end at (100,0).
        // Nearly closed (gap ratio ~0.50 < SELF_LOOP_MAX_GAP), smooth, fits ellipse.
        val n = 30
        val xs = FloatArray(n + 1) { i -> (100 + 100 * cos(3 * PI / 2 * i / n)).toFloat() }
        val ys = FloatArray(n + 1) { i -> (100 + 100 * sin(3 * PI / 2 * i / n)).toFloat() }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("3/4 arc should snap to SelfLoop", result)
        assertTrue("Should be SelfLoop, got $result",
            result is ShapeSnapDetection.SnapResult.SelfLoop)
    }

    // ── RoundedRectangle ──────────────────────────────────────────────────────

    @Test fun roundedRectangle_snapsToRoundedRectangle() {
        // 200×150 rounded rectangle with corner radius 37 px.
        // 15 arc points per 90° corner, 5 points per straight side.
        // N ≈ 81 → window = max(3, 81/12) = 6; arc step = 6°; detected angle = 36° < 50°.
        // So 0 sharp corners are found → should snap to RoundedRectangle, not Rectangle.
        // Max ellipse deviation at the 45° corner arc position ≈ 7.2% > 7% threshold,
        // so it must NOT snap to Ellipse either.
        val left = 0f; val top = 0f; val right = 200f; val bottom = 150f
        val r = 37f
        val arcN = 15; val sideN = 5
        val cl = left + r; val cr = right - r
        val ct = top + r;  val cb = bottom - r

        val pts = mutableListOf<Pair<Float, Float>>()
        fun arc(cx: Float, cy: Float, startDeg: Double, endDeg: Double) {
            for (i in 0 until arcN) {
                val a = Math.toRadians(startDeg + (endDeg - startDeg) * i / arcN)
                pts += Pair((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
            }
        }
        fun side(x0: Float, y0: Float, x1: Float, y1: Float) {
            for (i in 0 until sideN) {
                val t = i.toFloat() / sideN
                pts += Pair(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
        }
        // Clockwise from (cr, top): TR arc → right side → BR arc → bottom → BL arc → left → TL arc → top → close
        arc(cr, ct, -90.0,   0.0);  side(right, ct, right, cb)
        arc(cr, cb,   0.0,  90.0);  side(cr, bottom, cl, bottom)
        arc(cl, cb,  90.0, 180.0);  side(left, cb, left, ct)
        arc(cl, ct, 180.0, 270.0);  side(cl, top, cr, top)
        pts += Pair(cr, top)  // close

        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Rounded rectangle should snap", result)
        assertTrue("Rounded rectangle snaps to RoundedRectangle, got $result",
            result is ShapeSnapDetection.SnapResult.RoundedRectangle)
        val rr = result as ShapeSnapDetection.SnapResult.RoundedRectangle
        assertEquals(left,  rr.left,         3f)
        assertEquals(top,   rr.top,          3f)
        assertEquals(right, rr.right,        3f)
        assertEquals(bottom, rr.bottom,      3f)
        assertTrue("Corner radius should be positive", rr.cornerRadius > 0f)
    }

    @Test fun sharpRectangle_doesNotSnapToRoundedRectangle() {
        // A rectangle with sharp 90° corners (5 points) must snap to Rectangle,
        // not RoundedRectangle, even though it has 0 smooth corner-detections
        // in the small-N fallback path.
        val xs = floatArrayOf(0f, 200f, 200f, 0f, 0f)
        val ys = floatArrayOf(0f, 0f, 150f, 150f, 0f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("5-point sharp rect must snap to Rectangle",
            result is ShapeSnapDetection.SnapResult.Rectangle)
    }

    // ── RoundedRectangle with fewer arc points per corner ─────────────────────

    @Test fun roundedRectangleFewArcPoints_snapsToRoundedRectangle() {
        // 200×150 rounded rectangle with corner radius 30 px.
        // Only 5 arc points per 90° corner → arc step = 18°/pt.
        // N = 4*(5+5)+1 = 41 → window = max(3, 41/12) = 3.
        // At each arc midpoint the windowed angle ≈ 54° > CORNER_ANGLE_DEG (50°),
        // so currently 4 sharp corners are detected → snaps to Rectangle (BUG).
        // Expected: RoundedRectangle.
        val left = 0f; val top = 0f; val right = 200f; val bottom = 150f
        val r = 30f
        val arcN = 5; val sideN = 5
        val cl = left + r; val cr = right - r
        val ct = top + r;  val cb = bottom - r

        val pts = mutableListOf<Pair<Float, Float>>()
        fun arc(cx: Float, cy: Float, startDeg: Double, endDeg: Double) {
            for (i in 0 until arcN) {
                val a = Math.toRadians(startDeg + (endDeg - startDeg) * i / arcN)
                pts += Pair((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
            }
        }
        fun side(x0: Float, y0: Float, x1: Float, y1: Float) {
            for (i in 0 until sideN) {
                val t = i.toFloat() / sideN
                pts += Pair(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
        }
        arc(cr, ct, -90.0,   0.0);  side(right, ct, right, cb)
        arc(cr, cb,   0.0,  90.0);  side(cr, bottom, cl, bottom)
        arc(cl, cb,  90.0, 180.0);  side(left, cb, left, ct)
        arc(cl, ct, 180.0, 270.0);  side(cl, top, cr, top)
        pts += Pair(cr, top)  // close

        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Rounded rectangle (few arc pts) should snap", result)
        assertTrue("Rounded rectangle (few arc pts) snaps to RoundedRectangle, got $result",
            result is ShapeSnapDetection.SnapResult.RoundedRectangle)
    }

    // ── Rounded rectangle vs ellipse boundary ─────────────────────────────────

    /**
     * Builds a rounded rectangle with the given corner radius.
     * As radius approaches min(w,h)/2, the shape approaches an ellipse.
     */
    private fun makeRoundedRect(
        w: Float = 200f, h: Float = 150f, r: Float, arcN: Int = 12, sideN: Int = 6
    ): Pair<FloatArray, FloatArray> {
        val left = 0f; val top = 0f; val right = w; val bottom = h
        val cl = left + r; val cr = right - r
        val ct = top + r;  val cb = bottom - r
        val pts = mutableListOf<Pair<Float, Float>>()
        fun arc(cx: Float, cy: Float, startDeg: Double, endDeg: Double) {
            for (i in 0 until arcN) {
                val a = Math.toRadians(startDeg + (endDeg - startDeg) * i / arcN)
                pts += Pair((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
            }
        }
        fun side(x0: Float, y0: Float, x1: Float, y1: Float) {
            for (i in 0 until sideN) {
                val t = i.toFloat() / sideN
                pts += Pair(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
        }
        arc(cr, ct, -90.0,   0.0);  side(right, ct, right, cb)
        arc(cr, cb,   0.0,  90.0);  side(cr, bottom, cl, bottom)
        arc(cl, cb,  90.0, 180.0);  side(left, cb, left, ct)
        arc(cl, ct, 180.0, 270.0);  side(cl, top, cr, top)
        pts += pts[0] // close
        return pts.map { it.first }.toFloatArray() to pts.map { it.second }.toFloatArray()
    }

    @Test fun roundedRect_smallRadius_snapsToRoundedRectangle() {
        // r=20 on 200x150 — clearly rectangular with slight rounding.
        val (xs, ys) = makeRoundedRect(r = 20f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("r=20 should be RoundedRectangle, got $result",
            result is ShapeSnapDetection.SnapResult.RoundedRectangle)
    }

    @Test fun roundedRect_moderateRadius_snapsToRoundedRectangle() {
        // r=40 on 200x150 — noticeably rounded but still clearly a rectangle.
        val (xs, ys) = makeRoundedRect(r = 40f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("r=40 should be RoundedRectangle, got $result",
            result is ShapeSnapDetection.SnapResult.RoundedRectangle)
    }

    @Test fun roundedRect_largeRadius_snapsToRoundedRectangle() {
        // r=55 on 200x150 — very rounded (73% of min side). This is the boundary
        // case where hand-drawn rounded rects get misclassified as ellipses.
        val (xs, ys) = makeRoundedRect(r = 55f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("r=55 should be RoundedRectangle, got $result",
            result is ShapeSnapDetection.SnapResult.RoundedRectangle)
    }

    @Test fun roundedRect_nearMaxRadius_snapsToEllipse() {
        // r=75 on 200x150 — radius = min(w,h)/2, this IS an ellipse.
        val (xs, ys) = makeRoundedRect(r = 75f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("r=75 (full radius) should be Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    // ── Ovals at various aspect ratios must snap to Ellipse ────────────────────

    private fun makeOval(a: Float, b: Float, n: Int = 60): Pair<FloatArray, FloatArray> {
        val xs = FloatArray(n + 1) { i -> (a + a * cos(2 * PI * i / n)).toFloat() }
        val ys = FloatArray(n + 1) { i -> (b + b * sin(2 * PI * i / n)).toFloat() }
        return xs to ys
    }

    @Test fun oval_2to1_40pts_snapsToEllipse() {
        val (xs, ys) = makeOval(a = 150f, b = 75f, n = 40)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("2:1 oval (40 pts) should be Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun oval_2to1_60pts_snapsToEllipse() {
        val (xs, ys) = makeOval(a = 150f, b = 75f, n = 60)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("2:1 oval (60 pts) should be Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun oval_3to1_snapsToEllipse() {
        // Very elongated oval — the long sides have very low curvature.
        val (xs, ys) = makeOval(a = 225f, b = 75f, n = 60)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("3:1 oval should be Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun oval_3to1_100pts_snapsToEllipse() {
        // More points → finer sampling → long-side segments look even straighter.
        val (xs, ys) = makeOval(a = 225f, b = 75f, n = 100)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("3:1 oval (100 pts) should be Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun oval_4to1_snapsToEllipse() {
        val (xs, ys) = makeOval(a = 300f, b = 75f, n = 80)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("4:1 oval should be Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun oval_1to3_tall_snapsToEllipse() {
        // Tall narrow oval — same problem but in the vertical direction.
        val (xs, ys) = makeOval(a = 75f, b = 225f, n = 60)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("1:3 tall oval should be Ellipse, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    // ── straightFraction boundary verification ──────────────────────────────

    @Test fun straightFraction_ellipsesBelowThreshold_roundedRectsAbove() {
        val thresh = ShapeSnapDetection.STRAIGHT_FRACTION_MIN

        // Ellipses: all must be below threshold
        val shapes = listOf(
            "circle"      to makeOval(100f, 100f, 40),
            "oval 2:1"    to makeOval(150f, 75f, 40),
            "oval 3:1"    to makeOval(225f, 75f, 60),
            "oval 4:1"    to makeOval(300f, 75f, 80),
            "oval 1:3"    to makeOval(75f, 225f, 60),
            "rr75/ellipse" to makeRoundedRect(r = 75f),
        )
        val sfValues = mutableListOf<String>()
        for ((name, data) in shapes) {
            val sf = ShapeSnapDetection.straightFraction(data.first, data.second)
            sfValues += "$name=$sf"
            assertTrue("$name: SF=$sf should be < $thresh (all: ${sfValues.joinToString()})", sf < thresh)
        }

        // Rounded rects: all must be at or above threshold
        val rrs = listOf(
            "rr20" to makeRoundedRect(r = 20f),
            "rr40" to makeRoundedRect(r = 40f),
            "rr55" to makeRoundedRect(r = 55f),
        )
        val rrValues = mutableListOf<String>()
        for ((name, data) in rrs) {
            val sf = ShapeSnapDetection.straightFraction(data.first, data.second)
            rrValues += "$name=$sf"
            assertTrue("$name: SF=$sf should be >= $thresh (all: ${rrValues.joinToString()})", sf >= thresh)
        }
    }

    // ── Triangle ──────────────────────────────────────────────────────────────

    @Test fun triangleStartingAtCorner_snapsToTriangle() {
        // Equilateral triangle where the stroke starts AT corner A=(0,0).
        // Corner A is at stroke index 0, which is outside the corner-detection
        // window range [window..n-window). With window=3, i=0 is never evaluated,
        // so corner A is missed → only 2 corners detected → currently snaps to
        // RoundedRectangle (BUG). Expected: Triangle.
        val pts = mutableListOf<Pair<Float, Float>>()
        val c = listOf(Pair(0f, 0f), Pair(200f, 0f), Pair(100f, 173f), Pair(0f, 0f))
        for (side in 0 until 3) {
            val from = c[side]; val to = c[side + 1]
            for (i in 0 until 14) {
                val t = i / 14f
                pts += Pair(from.first + (to.first - from.first) * t,
                            from.second + (to.second - from.second) * t)
            }
        }
        pts += c[0]  // close

        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Triangle starting at corner should snap", result)
        assertTrue("Triangle starting at corner snaps to Triangle, got $result",
            result is ShapeSnapDetection.SnapResult.Triangle)
    }

    @Test fun equilateralTriangle_snapsToTriangle() {
        // Triangle: top=(100,0), bottom-right=(200,173), bottom-left=(0,173).
        // Start from mid-bottom (100,173) so all 3 corners are away from the
        // boundary of the corner-detection window (corners at ~i=10, 20, 30 of 41).
        val pts = mutableListOf<Pair<Float, Float>>()
        val corners = listOf(Pair(100f, 173f), Pair(200f, 173f), Pair(100f, 0f), Pair(0f, 173f))
        // Sides: mid-bottom→BR, BR→top, top→BL, BL→mid-bottom
        for (side in 0 until 3) {
            val from = corners[side]; val to = corners[side + 1]
            for (i in 0 until 10) {
                val t = i / 10f
                pts += Pair(from.first + (to.first - from.first) * t,
                            from.second + (to.second - from.second) * t)
            }
        }
        // Last segment: BL → mid-bottom (close)
        val from = corners[3]; val to = corners[0]
        for (i in 0 until 10) {
            val t = i / 10f
            pts += Pair(from.first + (to.first - from.first) * t,
                        from.second + (to.second - from.second) * t)
        }
        pts += corners[0]  // close

        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Triangle should snap", result)
        assertTrue("Triangle snaps to Triangle",
            result is ShapeSnapDetection.SnapResult.Triangle)
    }

    // ── Rectangle ─────────────────────────────────────────────────────────────

    @Test fun freehandRectangleLoop_snapsToRectangle() {
        // Simulate a hand-drawn closed rectangle (200×150 px).
        // Start from mid-top (100, 0) so all 4 corners fall within the
        // corner-detection window range (not cut off at stroke boundaries).
        val pts = mutableListOf<Pair<Float, Float>>()

        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, n: Int) {
            for (i in 0 until n) {
                val t = i.toFloat() / n
                pts += Pair(
                    x0 + (x1 - x0) * t + (i % 2) * 2f - 1f,
                    y0 + (y1 - y0) * t + (i % 3) * 1.5f
                )
            }
        }

        // Clockwise from mid-top: mid(100,0) → TR → BR → BL → TL → mid(100,0)
        edge(100f, 0f, 200f, 0f,   5)
        edge(200f, 0f, 200f, 150f, 8)
        edge(200f, 150f, 0f, 150f, 10)
        edge(0f, 150f, 0f, 0f,     8)
        edge(0f, 0f, 100f, 0f,     5)
        pts += Pair(100f, 0f)  // close

        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Freehand rectangle loop should snap", result)
        assertTrue("Freehand rectangle loop snaps to Rectangle",
            result is ShapeSnapDetection.SnapResult.Rectangle)
    }

    // ── detectLine: should snap ───────────────────────────────────────────────

    @Test fun straightHorizontalLine_snaps() {
        val xs = floatArrayOf(0f, 50f, 100f, 150f, 200f)
        val ys = floatArrayOf(100f, 100f, 100f, 100f, 100f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull(result)
        assertTrue(result is ShapeSnapDetection.SnapResult.Line)
        val line = result as ShapeSnapDetection.SnapResult.Line
        assertEquals(0f, line.x1, 0.01f)
        assertEquals(100f, line.y1, 0.01f)
        assertEquals(200f, line.x2, 0.01f)
        assertEquals(100f, line.y2, 0.01f)
    }

    @Test fun straightVerticalLine_snaps() {
        val xs = floatArrayOf(50f, 50f, 50f, 50f, 50f)
        val ys = floatArrayOf(0f, 50f, 100f, 150f, 200f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull(result)
        assertTrue(result is ShapeSnapDetection.SnapResult.Line)
    }

    @Test fun straightDiagonalLine_snaps() {
        val n = 6
        val xs = FloatArray(n) { it * 40f }
        val ys = FloatArray(n) { it * 40f }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull(result)
        assertTrue(result is ShapeSnapDetection.SnapResult.Line)
    }

    @Test fun nearlyStraitLine_smallDeviation_snaps() {
        val xs = floatArrayOf(0f, 100f, 150f, 200f, 300f)
        val ys = floatArrayOf(0f, 0f, 5f, 0f, 0f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull(result)
        assertTrue(result is ShapeSnapDetection.SnapResult.Line)
    }

    // ── detectLine: should NOT snap ──────────────────────────────────────────

    @Test fun tooShortLine_notDetected() {
        val xs = floatArrayOf(0f, 50f, 100f)
        val ys = floatArrayOf(0f, 0f, 0f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNull(result)
    }

    @Test fun curvedLine_tooMuchDeviation_notDetected() {
        val xs = floatArrayOf(0f, 100f, 200f)
        val ys = floatArrayOf(0f, 50f, 0f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNull(result)
    }

    @Test fun semicircle_doesNotSnapToLine() {
        // Semicircle: path length ≈ 314, straight-line length = 200, ratio ≈ 1.57 > 1.5.
        // Must not snap to Line (too much path ratio), but may snap to Arc.
        val n = 20
        val xs = FloatArray(n + 1) { (100f * cos(PI * it / n)).toFloat() }
        val ys = FloatArray(n + 1) { (-100f * sin(PI * it / n)).toFloat() }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Semicircle must not snap to a line, got $result",
            result !is ShapeSnapDetection.SnapResult.Line)
    }

    @Test fun closedLoop_tooSmallForLine_notDetected() {
        val xs = floatArrayOf(50f, 55f, 55f, 50f, 50f)
        val ys = floatArrayOf(50f, 50f, 55f, 55f, 50f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNull(result)
    }

    // ── Rectangle (minimal point sets, fallback path) ─────────────────────────

    @Test fun cleanRectangularLoop_snaps() {
        // 5-point rectangle — uses the small-N fallback (bounding-box detection).
        val xs = floatArrayOf(0f, 200f, 200f, 0f, 0f)
        val ys = floatArrayOf(0f, 0f, 150f, 150f, 0f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull(result)
        assertTrue(result is ShapeSnapDetection.SnapResult.Rectangle)
        val rect = result as ShapeSnapDetection.SnapResult.Rectangle
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(200f, rect.right, 0.01f)
        assertEquals(150f, rect.bottom, 0.01f)
    }

    @Test fun slightlyRoughRectangularLoop_snaps() {
        // 5-point rectangle with small jitter — small-N fallback.
        val xs = floatArrayOf(2f, 198f, 202f, 1f, -1f)
        val ys = floatArrayOf(1f, -2f, 148f, 152f, 2f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull(result)
        assertTrue(result is ShapeSnapDetection.SnapResult.Rectangle)
    }

    // ── Rectangle: should NOT snap ────────────────────────────────────────────

    @Test fun openRectangularStroke_notRect() {
        val xs = floatArrayOf(0f, 200f, 200f, 0f)
        val ys = floatArrayOf(0f, 0f, 150f, 148f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNull(result)
    }

    @Test fun tooSmallRectangle_notDetected() {
        val xs = floatArrayOf(0f, 30f, 30f, 0f, 0f)
        val ys = floatArrayOf(0f, 0f, 30f, 30f, 0f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNull(result)
    }

    @Test fun spiralLoop_notRect() {
        val pts = mutableListOf<Pair<Float, Float>>()
        val n = 30; val cx = 100f; val cy = 100f
        for (i in 0..n) {
            val angle = 4 * PI * i / n
            val r = 100f * (1f - i.toFloat() / n)
            pts.add(Pair((cx + r * cos(angle)).toFloat(), (cy + r * sin(angle)).toFloat()))
        }
        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNull(result)
    }

    // ── Diamond ───────────────────────────────────────────────────────────────

    @Test fun diamond_snapsToDialmond() {
        // 200×160 diamond: top=(100,0), right=(200,80), bottom=(100,160), left=(0,80).
        // 10 points per side, closed.
        val cx = 100f; val cy = 80f
        val vertices = listOf(
            Pair(cx, 0f), Pair(200f, cy), Pair(cx, 160f), Pair(0f, cy), Pair(cx, 0f)
        )
        val pts = mutableListOf<Pair<Float, Float>>()
        for (side in 0 until 4) {
            val from = vertices[side]; val to = vertices[side + 1]
            for (i in 0 until 10) {
                val t = i / 10f
                pts += Pair(from.first + (to.first - from.first) * t,
                            from.second + (to.second - from.second) * t)
            }
        }
        pts += vertices[0]  // close
        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Diamond should snap", result)
        assertTrue("Diamond snaps to Diamond, got $result",
            result is ShapeSnapDetection.SnapResult.Diamond)
    }

    @Test fun squareDiamond_snapsToDialmond() {
        // Square rotated 45°: diagonal = 200 px
        val pts = mutableListOf<Pair<Float, Float>>()
        val vertices = listOf(
            Pair(100f, 0f), Pair(200f, 100f), Pair(100f, 200f), Pair(0f, 100f), Pair(100f, 0f)
        )
        for (side in 0 until 4) {
            val from = vertices[side]; val to = vertices[side + 1]
            for (i in 0 until 8) {
                val t = i / 8f
                pts += Pair(from.first + (to.first - from.first) * t,
                            from.second + (to.second - from.second) * t)
            }
        }
        pts += vertices[0]
        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Square diamond should snap", result)
        assertTrue("Square diamond snaps to Diamond, got $result",
            result is ShapeSnapDetection.SnapResult.Diamond)
    }

    @Test fun axisAlignedRectangle_doesNotSnapToDiamond() {
        // Axis-aligned rectangle: corners at bbox corners, not edge midpoints → Rectangle
        val xs = FloatArray(41 + 1)
        val ys = FloatArray(41 + 1)
        val pts = mutableListOf<Pair<Float, Float>>()
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, n: Int) {
            for (i in 0 until n) {
                val t = i.toFloat() / n
                pts += Pair(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
        }
        edge(100f, 0f, 200f, 0f, 5)
        edge(200f, 0f, 200f, 150f, 8)
        edge(200f, 150f, 0f, 150f, 10)
        edge(0f, 150f, 0f, 0f, 8)
        edge(0f, 0f, 100f, 0f, 5)
        pts += Pair(100f, 0f)
        val xsArr = pts.map { it.first }.toFloatArray()
        val ysArr = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xsArr, ysArr, LS)
        assertNotNull("Rectangle should snap", result)
        assertTrue("Axis-aligned rectangle must NOT snap to Diamond, got $result",
            result is ShapeSnapDetection.SnapResult.Rectangle)
    }

    // ── Realistic pen input tests ──────────────────────────────────────────────

    private fun jitterX(i: Int) = (sin(i * 7.3) * 1.5).toFloat()
    private fun jitterY(i: Int) = (cos(i * 5.7) * 1.5).toFloat()

    @Test fun realisticRectangle_snapsToRectangle() {
        // Sharp-cornered rectangle ~200x150 px, clockwise from top-left, 41 points.
        val left = 100f; val top = 100f; val right = 300f; val bottom = 250f
        val pts = mutableListOf<Pair<Float, Float>>()
        var idx = 0

        fun addPt(x: Float, y: Float) {
            pts += Pair(x + jitterX(idx), y + jitterY(idx))
            idx++
        }

        // Top edge: 10 points
        for (i in 0 until 10) {
            addPt(left + (right - left) * i / 10f, top)
        }
        // Top-right corner
        addPt(right, top)
        // Right edge: 9 points
        for (i in 1 until 10) {
            addPt(right, top + (bottom - top) * i / 10f)
        }
        // Bottom-right corner
        addPt(right, bottom)
        // Bottom edge: 9 points
        for (i in 1 until 10) {
            addPt(right - (right - left) * i / 10f, bottom)
        }
        // Bottom-left corner
        addPt(left, bottom)
        // Left edge: 9 points
        for (i in 1 until 10) {
            addPt(left, bottom - (bottom - top) * i / 10f)
        }
        // Close (~3px from start)
        pts += Pair(pts[0].first + 1.2f, pts[0].second - 0.8f)

        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Realistic rectangle should snap", result)
        assertTrue("Realistic rectangle snaps to Rectangle, got $result",
            result is ShapeSnapDetection.SnapResult.Rectangle)
    }

    @Test fun realisticRoundedRectangle_snapsToRoundedRectangle() {
        // Rounded-corner rectangle ~200x150 px, corner radius 30px, clockwise, 55 points.
        val left = 100f; val top = 100f; val right = 300f; val bottom = 250f
        val r = 30f
        val cl = left + r; val crX = right - r
        val ct = top + r; val cb = bottom - r
        val arcN = 8
        val pts = mutableListOf<Pair<Float, Float>>()
        var idx = 0

        fun addPt(x: Float, y: Float) {
            pts += Pair(x + jitterX(idx), y + jitterY(idx))
            idx++
        }

        fun arc(cx: Float, cy: Float, startDeg: Double, endDeg: Double) {
            for (i in 0 until arcN) {
                val angle = Math.toRadians(startDeg + (endDeg - startDeg) * i / arcN)
                addPt((cx + r * cos(angle)).toFloat(), (cy + r * sin(angle)).toFloat())
            }
        }

        // Top-right arc (-90 to 0)
        arc(crX, ct, -90.0, 0.0)
        // Right edge: 6 points
        for (i in 0 until 6) addPt(right, ct + (cb - ct) * i / 6f)
        // Bottom-right arc (0 to 90)
        arc(crX, cb, 0.0, 90.0)
        // Bottom edge: 6 points
        for (i in 0 until 6) addPt(crX - (crX - cl) * i / 6f, bottom)
        // Bottom-left arc (90 to 180)
        arc(cl, cb, 90.0, 180.0)
        // Left edge: 6 points
        for (i in 0 until 6) addPt(left, cb - (cb - ct) * i / 6f)
        // Top-left arc (180 to 270)
        arc(cl, ct, 180.0, 270.0)
        // Top edge: 4 points
        for (i in 0 until 4) addPt(cl + (crX - cl) * i / 4f, top)
        // Close (~3px from start)
        pts += Pair(pts[0].first + 1.0f, pts[0].second - 0.5f)

        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Realistic rounded rectangle should snap", result)
        assertTrue("Realistic rounded rectangle snaps to RoundedRectangle, got $result",
            result is ShapeSnapDetection.SnapResult.RoundedRectangle)
    }

    // ── maxPerpendicularDeviation ─────────────────────────────────────────────

    @Test fun perfectLine_zeroDeviation() {
        val xs = floatArrayOf(0f, 50f, 100f, 150f, 200f)
        val ys = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        val dev = ShapeSnapDetection.maxPerpendicularDeviation(xs, ys, 0f, 0f, 200f, 0f)
        assertEquals(0f, dev, 0.001f)
    }

    @Test fun singlePointOffLine_correctDeviation() {
        val xs = floatArrayOf(0f, 100f, 200f)
        val ys = floatArrayOf(0f, 10f, 0f)
        val dev = ShapeSnapDetection.maxPerpendicularDeviation(xs, ys, 0f, 0f, 200f, 0f)
        assertEquals(10f, dev, 0.001f)
    }

    @Test fun zeroLengthLine_returnsZero() {
        val xs = floatArrayOf(50f, 60f)
        val ys = floatArrayOf(50f, 70f)
        val dev = ShapeSnapDetection.maxPerpendicularDeviation(xs, ys, 50f, 50f, 50f, 50f)
        assertEquals(0f, dev, 0.001f)
    }

    @Test fun diagonalLine_correctDeviation() {
        val xs = floatArrayOf(0f, 0f, 100f)
        val ys = floatArrayOf(0f, 100f, 100f)
        val dev = ShapeSnapDetection.maxPerpendicularDeviation(xs, ys, 0f, 0f, 100f, 100f)
        val expected = 50f * sqrt(2f)
        assertEquals(expected, dev, 0.01f)
    }

    // ── False-positive rejection: letter-like closed loops ────────────────────

    /**
     * Builds a "letter B"-like stroke: left spine (x=0) going down from (0,0) to (0,h),
     * then two bumps on the right side whose valley at (junctionX, h/2) is interior to
     * the bounding box, then back up the spine to close.
     *
     * Valley point deviation from nearest bbox edge = junctionX = w/3.
     * For w=90, h=200: diagonal ≈ 224, ratio ≈ 30/224 = 0.134 > RECT_MAX_POINT_DEV (0.12).
     */
    private fun makeBLikeStroke(w: Float = 90f, h: Float = 200f, pts: Int = 30): Pair<FloatArray, FloatArray> {
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        val junctionX = w / 3f
        val bumpAmp   = w - junctionX

        // Top: spine top (0,0) → junction top (junctionX, 0)
        xs.add(0f); ys.add(0f)
        xs.add(junctionX); ys.add(0f)

        // Right side: 2 bumps via raised cosine from (junctionX,0) to (junctionX,h)
        for (i in 0..pts) {
            val t = i.toFloat() / pts
            xs.add(junctionX + bumpAmp * (1 - cos(4 * PI * t)).toFloat() / 2)
            ys.add(h * t)
        }

        // Bottom: junction bottom (junctionX, h) → spine bottom (0, h)
        xs.add(0f); ys.add(h)

        // Left spine back up to (0, 0)
        for (i in pts downTo 0) {
            xs.add(0f)
            ys.add(h * i.toFloat() / pts)
        }

        return xs.toFloatArray() to ys.toFloatArray()
    }

    /**
     * Letter P: one bump on right side spanning the top half, spine on left.
     * The end of the bump (junctionX, h/2) has deviation = junctionX from nearest edge,
     * ratio = junctionX/diagonal ≈ 30/219 = 0.137 > RECT_MAX_POINT_DEV (0.12).
     */
    private fun makePLikeStroke(w: Float = 90f, h: Float = 200f, pts: Int = 30): Pair<FloatArray, FloatArray> {
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        val junctionX = w / 3f
        val bumpAmp   = w - junctionX
        val halfH     = h / 2f

        // Top edge: spine top (0,0) → junction top (junctionX, 0)
        for (i in 0..4) { xs.add(junctionX * i / 4f); ys.add(0f) }

        // Single bump from (junctionX, 0) to (junctionX, halfH)
        for (i in 1..pts) {
            val t = i.toFloat() / pts
            xs.add(junctionX + bumpAmp * (1 - cos(2 * PI * t)).toFloat() / 2)
            ys.add(halfH * t)
        }
        // Now at (junctionX, halfH) — interior point, deviation = junctionX = 30 px

        // Return to spine at mid-height: (junctionX, halfH) → (0, halfH)
        for (i in 1..4) { xs.add(junctionX * (1f - i / 4f)); ys.add(halfH) }

        // Spine down: (0, halfH) → (0, h)
        for (i in 1..pts / 2) { xs.add(0f); ys.add(halfH + halfH * i / (pts / 2f)) }

        // Spine up: (0, h) → (0, 0)  [closes the loop]
        for (i in 1..pts) { xs.add(0f); ys.add(h * (1f - i.toFloat() / pts)) }

        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test fun letterB_twoRightBumps_doesNotSnap() {
        val (xs, ys) = makeBLikeStroke()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNull("Letter B should not snap to any shape, got $result", result)
    }

    @Test fun letterP_oneRightBump_doesNotSnap() {
        val (xs, ys) = makePLikeStroke()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNull("Letter P should not snap to any shape, got $result", result)
    }

    @Test fun wavyClosedLoop_doesNotSnapToRoundedRectangle() {
        // Generic horizontally-wavy closed loop: x oscillates 0..w..0..w..0 (2 bumps),
        // y goes 0..h top-to-bottom then spine back up. Interior valley has x=w/3.
        val (xs, ys) = makeBLikeStroke(w = 120f, h = 180f, pts = 40)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue(
            "Wavy closed loop must not snap to RoundedRectangle, got $result",
            result !is ShapeSnapDetection.SnapResult.RoundedRectangle
        )
    }

    // ── Boundary positives: sloppy shapes that must still snap ────────────────

    @Test fun tallNarrowRectangle_snapsToRectangle() {
        // h/w = 3. Use a full corner-counting path (enough points).
        val pts = mutableListOf<Pair<Float, Float>>()
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, n: Int) {
            for (i in 0 until n) {
                val t = i.toFloat() / n
                pts += Pair(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
        }
        edge(50f, 0f, 100f, 0f, 5)
        edge(100f, 0f, 100f, 300f, 15)
        edge(100f, 300f, 0f, 300f, 5)
        edge(0f, 300f, 0f, 0f, 15)
        edge(0f, 0f, 50f, 0f, 5)
        pts += Pair(50f, 0f)

        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Tall narrow rectangle should snap", result)
        assertTrue("Tall narrow rectangle snaps to Rectangle, got $result",
            result is ShapeSnapDetection.SnapResult.Rectangle)
    }

    @Test fun sloppyRectangle_belowMaxPointDev_snapsToRectangle() {
        // Rectangle with moderate jitter — max single-point offset ≈ 8% of diagonal,
        // which is below RECT_MAX_POINT_DEV (12%). Should still snap.
        // 200×150 rect: diagonal ≈ 250. 8% × 250 = 20 px max jitter.
        val pts = mutableListOf<Pair<Float, Float>>()
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, n: Int) {
            for (i in 0 until n) {
                val t = i.toFloat() / n
                // Jitter perpendicular to edge direction, capped at 18 px (< 20 px limit)
                val jitter = if (i % 3 == 1) 10f else 0f
                val isHoriz = (y0 == y1)
                pts += Pair(
                    x0 + (x1 - x0) * t + if (!isHoriz) jitter else 0f,
                    y0 + (y1 - y0) * t + if (isHoriz)  jitter else 0f
                )
            }
        }
        edge(100f, 0f, 200f, 0f, 5)
        edge(200f, 0f, 200f, 150f, 8)
        edge(200f, 150f, 0f, 150f, 10)
        edge(0f, 150f, 0f, 0f, 8)
        edge(0f, 0f, 100f, 0f, 5)
        pts += Pair(100f, 0f)

        val xs = pts.map { it.first }.toFloatArray()
        val ys = pts.map { it.second }.toFloatArray()
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Sloppy rectangle (jitter < maxPointDev) should snap", result)
        assertTrue("Sloppy rectangle snaps to Rectangle, got $result",
            result is ShapeSnapDetection.SnapResult.Rectangle)
    }

    // ── Dwell-gated shape snapping ────────────────────────────────────────────
    //
    // Shape snapping requires the user to hold the pen still at the end of the
    // stroke. These tests verify the combined dwell + detection pipeline:
    // hasDwellAtEnd gates whether ShapeSnapDetection.detect is consulted.

    private val DWELL_RADIUS = 15f
    private val DWELL_MS = 300L

    /** Build StrokePoints for a rectangle with timestamps. If [dwellAtEnd], the
     *  last few points cluster at the close point for >= DWELL_MS. */
    private fun makeTimedRectangle(dwellAtEnd: Boolean): List<StrokePoint> {
        val pts = mutableListOf<StrokePoint>()
        var t = 0L
        val step = 15L // 15ms between points — fast drawing

        // Top edge: (0,0) → (200,0)
        for (i in 0..9) { pts += StrokePoint(i * 20f, 0f, 1f, t); t += step }
        // Right edge: (200,0) → (200,150)
        for (i in 1..7) { pts += StrokePoint(200f, i * 150f / 7, 1f, t); t += step }
        // Bottom edge: (200,150) → (0,150)
        for (i in 1..9) { pts += StrokePoint(200f - i * 20f, 150f, 1f, t); t += step }
        // Left edge: (0,150) → (0,0)
        for (i in 1..7) { pts += StrokePoint(0f, 150f - i * 150f / 7, 1f, t); t += step }
        // Close point
        pts += StrokePoint(1f, 1f, 1f, t); t += step

        if (dwellAtEnd) {
            // Add dwell: 5 points clustered at (1,1) spanning 350ms (> DWELL_MS)
            for (i in 1..5) {
                pts += StrokePoint(1f + i * 0.5f, 1f + i * 0.3f, 1f, t)
                t += 70L
            }
        }

        return pts
    }

    @Test fun rectangle_withEndDwell_snapsToShape() {
        val pts = makeTimedRectangle(dwellAtEnd = true)
        val last = pts.last()
        val hasDwell = ArrowDwellDetection.hasDwellAtEnd(pts, last.x, last.y, DWELL_RADIUS, DWELL_MS)
        assertTrue("Should detect end dwell", hasDwell)

        // Since dwell is present, shape detection should proceed
        val xs = FloatArray(pts.size) { pts[it].x }
        val ys = FloatArray(pts.size) { pts[it].y }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Rectangle with dwell should snap", result)
        assertTrue("Should snap to Rectangle, got $result",
            result is ShapeSnapDetection.SnapResult.Rectangle)
    }

    @Test fun rectangle_withoutEndDwell_doesNotSnap() {
        val pts = makeTimedRectangle(dwellAtEnd = false)
        val last = pts.last()
        val hasDwell = ArrowDwellDetection.hasDwellAtEnd(pts, last.x, last.y, DWELL_RADIUS, DWELL_MS)
        assertFalse("Should NOT detect end dwell", hasDwell)

        // The shape IS a valid rectangle geometrically...
        val xs = FloatArray(pts.size) { pts[it].x }
        val ys = FloatArray(pts.size) { pts[it].y }
        val shapeResult = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Shape IS a rectangle geometrically", shapeResult)

        // ...but the dwell gate blocks snapping
        val gatedResult = if (hasDwell) shapeResult else null
        assertNull("Without dwell, shape snapping should not activate", gatedResult)
    }

    // ── Elbow detection ─────────────────────────────────────────────────────

    /** Build an L-shaped stroke from (x0,y0) → corner → (x1,y1) with n points per leg. */
    private fun makeElbow(
        x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float,
        pointsPerLeg: Int = 15
    ): Pair<FloatArray, FloatArray> {
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        for (i in 0 until pointsPerLeg) {
            val t = i.toFloat() / pointsPerLeg
            xs += x0 + (cx - x0) * t
            ys += y0 + (cy - y0) * t
        }
        for (i in 0..pointsPerLeg) {
            val t = i.toFloat() / pointsPerLeg
            xs += cx + (x1 - cx) * t
            ys += cy + (y1 - cy) * t
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test fun rightAngleElbow_horizontal_then_vertical_snaps() {
        // L-shape: (0,0) → (200,0) → (200,200)
        val (xs, ys) = makeElbow(0f, 0f, 200f, 0f, 200f, 200f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("L-shaped stroke should snap to Elbow", result)
        assertTrue("Should be Elbow, got $result", result is ShapeSnapDetection.SnapResult.Elbow)
        val elbow = result as ShapeSnapDetection.SnapResult.Elbow
        assertEquals(0f, elbow.x1, 1f)
        assertEquals(0f, elbow.y1, 1f)
        assertEquals(200f, elbow.x2, 1f)
        assertEquals(200f, elbow.y2, 1f)
    }

    @Test fun rightAngleElbow_vertical_then_horizontal_snaps() {
        // L-shape: (0,0) → (0,200) → (200,200)
        val (xs, ys) = makeElbow(0f, 0f, 0f, 200f, 200f, 200f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Vertical-then-horizontal L should snap to Elbow", result)
        assertTrue("Should be Elbow, got $result", result is ShapeSnapDetection.SnapResult.Elbow)
    }

    @Test fun elbowWithAcuteAngle_doesNotSnap() {
        // Angle < 60° — too sharp for an elbow
        // V-shape: (0,0) → (100,200) → (50,0)
        val (xs, ys) = makeElbow(0f, 0f, 100f, 200f, 50f, 0f)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Acute angle should not snap to Elbow, got $result",
            result !is ShapeSnapDetection.SnapResult.Elbow)
    }

    @Test fun straightLine_doesNotSnapToElbow() {
        val xs = FloatArray(20) { it * 15f }
        val ys = FloatArray(20) { 100f }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Straight line must not snap to Elbow, got $result",
            result !is ShapeSnapDetection.SnapResult.Elbow)
    }

    // ── Arc detection ──────────────────────────────────────────────────────

    /** Build a circular arc stroke from startAngle to endAngle (radians) with given center and radius. */
    private fun makeArcStroke(
        cx: Float, cy: Float, radius: Float,
        startAngle: Double, endAngle: Double,
        n: Int = 30
    ): Pair<FloatArray, FloatArray> {
        val xs = FloatArray(n + 1) { i ->
            val angle = startAngle + (endAngle - startAngle) * i / n
            (cx + radius * cos(angle)).toFloat()
        }
        val ys = FloatArray(n + 1) { i ->
            val angle = startAngle + (endAngle - startAngle) * i / n
            (cy + radius * sin(angle)).toFloat()
        }
        return xs to ys
    }

    @Test fun semicircularArc_snapsToArc() {
        // Half circle: 180° arc, radius 100
        val (xs, ys) = makeArcStroke(100f, 100f, 100f, 0.0, PI, 30)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Semicircular arc should snap", result)
        assertTrue("Semicircle should snap to Arc, got $result",
            result is ShapeSnapDetection.SnapResult.Arc)
    }

    @Test fun quarterCircleArc_snapsToArc() {
        // Quarter circle: 90° arc, radius 150
        val (xs, ys) = makeArcStroke(0f, 0f, 150f, 0.0, PI / 2, 25)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Quarter-circle arc should snap", result)
        assertTrue("Quarter-circle should snap to Arc, got $result",
            result is ShapeSnapDetection.SnapResult.Arc)
    }

    @Test fun shallowArc_snapsToArc() {
        // Shallow arc: 60° sweep, radius 200
        val (xs, ys) = makeArcStroke(0f, 0f, 200f, -PI / 6, PI / 6, 25)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("Shallow arc should snap", result)
        assertTrue("Shallow arc should snap to Arc, got $result",
            result is ShapeSnapDetection.SnapResult.Arc)
    }

    @Test fun closedLoop_doesNotSnapToArc() {
        // Full circle — closed, should not snap to arc
        val n = 40
        val xs = FloatArray(n + 1) { i -> (100 + 100 * cos(2 * PI * i / n)).toFloat() }
        val ys = FloatArray(n + 1) { i -> (100 + 100 * sin(2 * PI * i / n)).toFloat() }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Closed loop must not snap to Arc, got $result",
            result !is ShapeSnapDetection.SnapResult.Arc)
    }

    @Test fun straightLine_doesNotSnapToArc() {
        val xs = FloatArray(20) { it * 15f }
        val ys = FloatArray(20) { 100f }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Straight line must not snap to Arc, got $result",
            result !is ShapeSnapDetection.SnapResult.Arc)
    }

    @Test fun selfReferentialArc_shortChord_snapsToArc() {
        // Arc from one side of a circle to another — chord is short (80px)
        // but the arc extends 120px outward.
        val (xs2, ys2) = makeArcStroke(0f, 0f, 100f, -PI / 8, PI / 8, 25)
        // chord ≈ 76.5px < LS (118px), but diagonal ≈ 100px > 59px
        val result = ShapeSnapDetection.detect(xs2, ys2, LS)
        assertNotNull("Short-chord self-referential arc should snap", result)
        assertTrue("Should be Arc, got $result",
            result is ShapeSnapDetection.SnapResult.Arc)
    }

    @Test fun selfReferentialArc_realPenFixture_snapsToArc() {
        // Downsampled real pen data: U-shaped arc from one side of a circle to another.
        // Start ≈ (651, 2629), loops right to (721, 2653), then left-down to (656, 2567),
        // back up to (656, 2584). Chord ≈ 43px, diagonal ≈ 111px.
        // Originally 553 points; downsampled every ~18th point for the moving portion.
        val xs = floatArrayOf(
            651.0f, 652.4f, 653.6f, 655.0f, 657.4f, 660.5f, 664.7f,
            668.7f, 673.2f, 677.8f, 682.3f, 686.5f, 690.3f, 695.4f,
            700.6f, 705.5f, 710.5f, 714.4f, 717.8f, 720.0f, 721.2f,
            721.2f, 720.4f, 717.2f, 713.1f, 709.9f, 706.3f, 702.4f,
            698.4f, 694.4f, 690.9f, 686.9f, 684.5f, 680.4f, 676.2f,
            672.0f, 668.1f, 665.7f, 660.3f, 656.2f
        )
        val ys = floatArrayOf(
            2629.0f, 2635.2f, 2638.8f, 2641.3f, 2643.3f, 2646.5f, 2649.2f,
            2650.8f, 2652.0f, 2653.0f, 2653.6f, 2653.6f, 2652.6f, 2649.2f,
            2646.1f, 2642.9f, 2639.3f, 2635.4f, 2630.6f, 2625.9f, 2620.5f,
            2614.0f, 2608.0f, 2601.7f, 2596.2f, 2591.0f, 2585.3f, 2580.5f,
            2575.8f, 2572.0f, 2569.2f, 2567.0f, 2566.8f, 2567.4f, 2568.8f,
            2572.0f, 2575.4f, 2576.7f, 2580.7f, 2584.1f
        )
        val result = ShapeSnapDetection.detect(xs, ys, 77f)
        assertNotNull("Real pen self-referential arc should snap, got null", result)
        assertTrue("Should be Arc or Curve, got $result",
            result is ShapeSnapDetection.SnapResult.Arc ||
            result is ShapeSnapDetection.SnapResult.Curve)
    }

    @Test fun zigzag_doesNotSnapToArc() {
        // Zigzag: has corners, should not snap to arc
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        for (i in 0..30) {
            xs += i * 10f
            ys += if (i % 2 == 0) 0f else 50f
        }
        val result = ShapeSnapDetection.detect(xs.toFloatArray(), ys.toFloatArray(), LS)
        assertTrue("Zigzag must not snap to Arc, got $result",
            result !is ShapeSnapDetection.SnapResult.Arc)
    }

    // ── Self-loop detection ──────────────────────────────────────────────────

    @Test fun nearCompleteCircle_snapsToSelfLoop() {
        // 300° arc (5/6 of a circle), radius 100
        val n = 40
        val sweepRad = 300.0 * PI / 180.0
        val xs = FloatArray(n + 1) { i -> (100 + 100 * cos(sweepRad * i / n)).toFloat() }
        val ys = FloatArray(n + 1) { i -> (100 + 100 * sin(sweepRad * i / n)).toFloat() }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("300° arc should snap to SelfLoop", result)
        assertTrue("Should be SelfLoop, got $result",
            result is ShapeSnapDetection.SnapResult.SelfLoop)
    }

    @Test fun nearCompleteOval_snapsToSelfLoop() {
        // 300° oval arc, rx=150, ry=80
        val n = 40
        val sweepRad = 300.0 * PI / 180.0
        val xs = FloatArray(n + 1) { i -> (150 + 150 * cos(sweepRad * i / n)).toFloat() }
        val ys = FloatArray(n + 1) { i -> (80 + 80 * sin(sweepRad * i / n)).toFloat() }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertNotNull("300° oval should snap to SelfLoop", result)
        assertTrue("Should be SelfLoop, got $result",
            result is ShapeSnapDetection.SnapResult.SelfLoop)
    }

    @Test fun fullClosedCircle_doesNotSnapToSelfLoop() {
        // Full closed circle should snap to Ellipse, not SelfLoop
        val n = 40
        val xs = FloatArray(n + 1) { i -> (100 + 100 * cos(2 * PI * i / n)).toFloat() }
        val ys = FloatArray(n + 1) { i -> (100 + 100 * sin(2 * PI * i / n)).toFloat() }
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Full circle should be Ellipse not SelfLoop, got $result",
            result is ShapeSnapDetection.SnapResult.Ellipse)
    }

    @Test fun halfCircle_doesNotSnapToSelfLoop() {
        // 180° arc — gap too large for self-loop (ratio > 0.75)
        val (xs, ys) = makeArcStroke(100f, 100f, 100f, 0.0, PI, 30)
        val result = ShapeSnapDetection.detect(xs, ys, LS)
        assertTrue("Half circle should not be SelfLoop, got $result",
            result !is ShapeSnapDetection.SnapResult.SelfLoop)
    }

    @Test fun zigzagNearClosed_doesNotSnapToSelfLoop() {
        // Near-closed zigzag: has corners, should not be SelfLoop
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        for (i in 0..30) {
            xs += 100f + 80f * cos(2 * PI * i / 30).toFloat() + if (i % 2 == 0) 20f else -20f
            ys += 100f + 80f * sin(2 * PI * i / 30).toFloat()
        }
        val result = ShapeSnapDetection.detect(xs.toFloatArray(), ys.toFloatArray(), LS)
        assertTrue("Zigzag near-closed loop must not snap to SelfLoop, got $result",
            result !is ShapeSnapDetection.SnapResult.SelfLoop)
    }
}
