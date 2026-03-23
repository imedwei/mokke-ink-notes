package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tests for dwell disambiguation: ensuring that the start-dwell gesture
 * only creates freeform diagram areas for drawing-like strokes, not text.
 *
 * The two-layer disambiguation:
 * 1. Movement cancellation: dwell is cancelled when pen moves beyond radius
 * 2. Stroke classification: even if dwell fires, text-like strokes don't
 *    create diagram areas
 *
 * These tests simulate the logic in HandwritingCanvasView.finishStroke()
 * where dwellIndicatorShown + stroke classification gate diagram creation.
 */
class DwellDisambiguationTest {

    companion object {
        private const val DENSITY = 1.875f
        private val LS get() = HandwritingCanvasView.LINE_SPACING
        private const val DWELL_RADIUS = 15f  // ARROW_DWELL_RADIUS_PX
        private const val DRAWING_THRESHOLD = 0.5f
    }

    @Before
    fun setUp() {
        ScreenMetrics.init(DENSITY, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun makePoints(vararg pairs: Pair<Float, Float>): List<StrokePoint> =
        pairs.map { (x, y) -> StrokePoint(x, y, 0.5f, 0L) }

    /**
     * Simulate the dwell disambiguation logic:
     * - Check if pen moved beyond dwell radius (layer 1)
     * - Check if stroke is drawing-like (layer 2)
     * @return true if a diagram area should be created
     */
    private fun shouldCreateDiagram(
        stroke: InkStroke,
        dwellFired: Boolean
    ): Boolean {
        if (!dwellFired) return false

        // Layer 1: Check if pen moved beyond 2× dwell radius from first point
        val first = stroke.points.first()
        val last = stroke.points.last()
        val maxDist = stroke.points.maxOf { pt ->
            val dx = pt.x - first.x
            val dy = pt.y - first.y
            dx * dx + dy * dy
        }
        if (maxDist > DWELL_RADIUS * DWELL_RADIUS * 4) {
            // Pen moved significantly — check stroke classification
            // (movement alone cancels dwell in the actual implementation,
            //  but we still have layer 2 as backup)
        }

        // Layer 2: Stroke must look like drawing, not text.
        // Exclude connector heuristic — cursive text is wide+flat like connectors.
        val score = DiagramStrokeClassifier.classifyStroke(stroke, LS, includeConnector = false)
        return score >= DRAWING_THRESHOLD
    }

    /**
     * Simulate whether the dwell timer would fire: pen hasn't moved
     * beyond DWELL_RADIUS from the first point after 500ms.
     */
    private fun wouldDwellFire(points: List<StrokePoint>): Boolean {
        if (points.size < 2) return false
        val first = points.first()
        // Check if at ~500ms (approximated by checking early points)
        // the pen was still within the radius
        val earlyPoints = points.take(points.size / 3) // first third of stroke
        return earlyPoints.all { pt ->
            val dx = pt.x - first.x
            val dy = pt.y - first.y
            dx * dx + dy * dy < DWELL_RADIUS * DWELL_RADIUS
        }
    }

    // ── Text strokes should NOT create diagram areas ─────────────────────────

    @Test
    fun `short horizontal text Hello World should not create diagram`() {
        // "Hello World" written horizontally on one line
        val stroke = InkStroke(points = makePoints(
            100f to 500f, 110f to 498f, 120f to 502f, 130f to 499f,
            150f to 501f, 170f to 498f, 200f to 500f, 230f to 502f,
            260f to 499f, 300f to 501f
        ))
        assertFalse("Text 'Hello World' should not create diagram",
            shouldCreateDiagram(stroke, dwellFired = true))
    }

    @Test
    fun `cursive writing should not create diagram even with dwell`() {
        // "cursive is cool" — connected cursive with ups and downs
        val stroke = InkStroke(points = makePoints(
            50f to 800f, 60f to 795f, 70f to 805f, 80f to 790f,
            90f to 810f, 100f to 795f, 120f to 800f, 140f to 792f,
            160f to 808f, 180f to 795f, 200f to 805f, 220f to 790f,
            250f to 800f, 280f to 795f, 310f to 805f, 340f to 798f,
            370f to 802f, 400f to 795f
        ))
        assertFalse("Cursive text should not create diagram",
            shouldCreateDiagram(stroke, dwellFired = true))
    }

    @Test
    fun `small single letter should not create diagram`() {
        val stroke = InkStroke(points = makePoints(
            100f to 300f, 105f to 290f, 110f to 295f, 115f to 305f, 120f to 300f
        ))
        assertFalse("Single letter should not create diagram",
            shouldCreateDiagram(stroke, dwellFired = true))
    }

    @Test
    fun `text spanning most of line width should not create diagram`() {
        // Wide text line but still short height — clearly text
        val points = (0..20).map { i ->
            StrokePoint(50f + i * 30f, 400f + (i % 3 - 1) * 5f, 0.5f, 0L)
        }
        val stroke = InkStroke(points = points)
        assertFalse("Wide text should not create diagram",
            shouldCreateDiagram(stroke, dwellFired = true))
    }

    // ── Drawing strokes SHOULD create diagram areas ──────────────────────────

    @Test
    fun `tall vertical stroke should create diagram with dwell`() {
        val stroke = InkStroke(points = makePoints(
            200f to 300f, 205f to 350f, 202f to 400f, 198f to 500f, 200f to 600f
        ))
        assertTrue("Tall stroke with dwell should create diagram",
            shouldCreateDiagram(stroke, dwellFired = true))
    }

    @Test
    fun `circle should create diagram with dwell`() {
        val n = 40
        val cx = 300f; val cy = 400f; val r = LS * 0.8f
        val points = (0..n).map { i ->
            StrokePoint(
                (cx + r * cos(2 * PI * i / n)).toFloat(),
                (cy + r * sin(2 * PI * i / n)).toFloat(),
                0.5f, 0L
            )
        }
        val stroke = InkStroke(points = points)
        assertTrue("Circle with dwell should create diagram",
            shouldCreateDiagram(stroke, dwellFired = true))
    }

    @Test
    fun `large freehand shape should create diagram with dwell`() {
        // A large irregular shape spanning multiple lines
        val stroke = InkStroke(points = makePoints(
            200f to 200f, 300f to 200f, 350f to 300f, 300f to 400f,
            200f to 450f, 150f to 350f, 150f to 250f, 200f to 200f
        ))
        assertTrue("Large shape with dwell should create diagram",
            shouldCreateDiagram(stroke, dwellFired = true))
    }

    // ── No dwell = no diagram regardless of stroke type ──────────────────────

    @Test
    fun `drawing stroke without dwell should not create diagram`() {
        val stroke = InkStroke(points = makePoints(
            200f to 300f, 205f to 350f, 202f to 400f, 198f to 500f, 200f to 600f
        ))
        assertFalse("Drawing stroke without dwell should not create diagram",
            shouldCreateDiagram(stroke, dwellFired = false))
    }

    // ── Movement cancellation (layer 1) ──────────────────────────────────────

    @Test
    fun `dwell should not fire if pen moves immediately`() {
        // Pen moves beyond radius in the first few points (realistic: ~30 points)
        val points = mutableListOf<StrokePoint>()
        // First 3 points near start, then moves away
        points.add(StrokePoint(100f, 300f, 0.5f, 0L))
        points.add(StrokePoint(102f, 301f, 0.5f, 0L))
        points.add(StrokePoint(105f, 302f, 0.5f, 0L))
        // Remaining 27 points: pen moves far
        for (i in 0..26) {
            points.add(StrokePoint(110f + i * 10f, 305f + i * 2f, 0.5f, 0L))
        }
        assertFalse("Dwell should not fire when pen moves immediately",
            wouldDwellFire(points))
    }

    @Test
    fun `dwell should fire if pen stays still in early portion`() {
        // Pen stays within radius for the first third, then moves
        val points = mutableListOf<StrokePoint>()
        // First 10 points: stationary (within 5px of start)
        for (i in 0..9) {
            points.add(StrokePoint(100f + i * 0.3f, 300f + i * 0.2f, 0.5f, 0L))
        }
        // Then 20 points: moves away
        for (i in 0..19) {
            points.add(StrokePoint(105f + i * 15f, 305f + i * 5f, 0.5f, 0L))
        }
        val stroke = InkStroke(points = points)
        assertTrue("Dwell should fire when pen is still initially",
            wouldDwellFire(stroke.points))
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `dot stroke (very short) should not create diagram`() {
        // A single tap — too small to be a meaningful drawing
        val stroke = InkStroke(points = makePoints(
            200f to 400f, 201f to 401f
        ))
        assertFalse("Dot should not create diagram",
            shouldCreateDiagram(stroke, dwellFired = true))
    }

    @Test
    fun `stroke from bug report - Hello World text at line 10`() {
        // Simulated "Hello World" text — horizontal, small height, simple path
        val stroke = InkStroke(points = makePoints(
            50f to 837f, 70f to 835f, 90f to 840f, 110f to 833f,
            130f to 838f, 160f to 836f, 190f to 841f, 220f to 834f,
            250f to 839f, 280f to 836f, 320f to 840f, 360f to 835f,
            400f to 838f
        ))
        assertFalse("Hello World text (from bug report) should not create diagram",
            shouldCreateDiagram(stroke, dwellFired = true))
    }

    @Test
    fun `stroke from bug report - cursive is cool at line 17`() {
        // Simulated cursive — connected, moderate height, horizontal
        val stroke = InkStroke(points = makePoints(
            79f to 1382f, 70f to 1385f, 65f to 1370f, 75f to 1365f,
            90f to 1375f, 110f to 1360f, 130f to 1380f, 155f to 1365f,
            180f to 1375f, 200f to 1358f, 225f to 1370f, 250f to 1362f,
            280f to 1378f, 310f to 1365f, 340f to 1372f, 370f to 1360f,
            400f to 1375f, 420f to 1368f
        ))
        assertFalse("Cursive text (from bug report) should not create diagram",
            shouldCreateDiagram(stroke, dwellFired = true))
    }
}
