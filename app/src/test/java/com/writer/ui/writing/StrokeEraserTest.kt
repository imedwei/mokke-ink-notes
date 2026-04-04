package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.view.ScreenMetrics
import com.writer.view.ScratchOutDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [StrokeEraser] — scratch-out overlap detection and
 * connected-word expansion.
 *
 * Previously this logic lived inline in WritingCoordinator.onScratchOut
 * and was tested only via duplicated helpers in ScratchOutFocusedTest
 * and DiagramEraseTest.
 */
class StrokeEraserTest {

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun pt(x: Float, y: Float, t: Long = 0L) = StrokePoint(x, y, 0.5f, t)

    private fun stroke(vararg pts: Pair<Float, Float>, type: StrokeType = StrokeType.FREEHAND, id: String = ""): InkStroke {
        val points = pts.map { (x, y) -> pt(x, y) }
        return InkStroke(points = points, strokeType = type, strokeId = id.ifEmpty { java.util.UUID.randomUUID().toString() })
    }

    private fun timedStroke(
        vararg pts: Pair<Float, Float>,
        startMs: Long, endMs: Long,
        id: String = "",
        lineY: Float = pts.first().second
    ): InkStroke {
        val points = pts.mapIndexed { i, (x, y) ->
            val t = startMs + (endMs - startMs) * i / maxOf(1, pts.size - 1)
            pt(x, y, t)
        }
        return InkStroke(points = points, strokeId = id.ifEmpty { java.util.UUID.randomUUID().toString() })
    }

    /** Zigzag scratch points centered at (cx, cy). */
    private fun zigzag(cx: Float, cy: Float, width: Float = 60f, count: Int = 20): List<StrokePoint> {
        return (0 until count).map { i ->
            val x = cx - width / 2 + (width * i / count)
            val y = cy + if (i % 2 == 0) 5f else -5f
            pt(x, y)
        }
    }

    // --- findOverlappingStrokes ---

    @Test
    fun `small stroke found by proximity within radius`() {
        val dot = stroke(300f to 400f, 302f to 401f)
        val scratch = zigzag(300f, 400f)
        val radius = ScreenMetrics.dp(ScratchOutDetection.COVERAGE_RADIUS_DP)
        val result = StrokeEraser.findOverlappingStrokes(
            scratch, listOf(dot),
            270f, 370f, 330f, 430f, radius
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `small stroke outside radius not found`() {
        val dot = stroke(300f to 400f, 302f to 401f)
        val scratch = zigzag(500f, 500f)  // far away
        val radius = ScreenMetrics.dp(ScratchOutDetection.COVERAGE_RADIUS_DP)
        val result = StrokeEraser.findOverlappingStrokes(
            scratch, listOf(dot),
            470f, 470f, 530f, 530f, radius
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `normal stroke found by segment intersection`() {
        // Horizontal stroke from (100, 200) to (400, 200) — 7 points (>= 5)
        val horiz = stroke(
            100f to 200f, 150f to 200f, 200f to 200f,
            250f to 200f, 300f to 200f, 350f to 200f, 400f to 200f
        )
        // Diagonal scratch from (200, 170) to (300, 230) — clearly crosses y=200
        val scratch = (0 until 10).map { i -> pt(200f + i * 11f, 170f + i * 7f) }
        val radius = ScreenMetrics.dp(ScratchOutDetection.COVERAGE_RADIUS_DP)
        val result = StrokeEraser.findOverlappingStrokes(
            scratch, listOf(horiz),
            210f, 170f, 270f, 230f, radius
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `connector stroke found by rect intersection`() {
        val arrow = InkStroke(
            points = listOf(pt(100f, 300f), pt(500f, 300f)),
            isGeometric = true,
            strokeType = StrokeType.ARROW_HEAD
        )
        val scratch = zigzag(300f, 300f)
        val radius = ScreenMetrics.dp(ScratchOutDetection.COVERAGE_RADIUS_DP)
        val result = StrokeEraser.findOverlappingStrokes(
            scratch, listOf(arrow),
            250f, 280f, 350f, 320f, radius
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `bounding box pre-filter skips distant strokes`() {
        val distant = stroke(
            1000f to 1000f, 1050f to 1000f, 1050f to 1050f,
            1000f to 1050f, 1000f to 1000f, 1025f to 1025f
        )
        val scratch = zigzag(200f, 200f)
        val radius = ScreenMetrics.dp(ScratchOutDetection.COVERAGE_RADIUS_DP)
        val result = StrokeEraser.findOverlappingStrokes(
            scratch, listOf(distant),
            170f, 170f, 230f, 230f, radius
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty strokes returns empty`() {
        val scratch = zigzag(200f, 200f)
        val radius = ScreenMetrics.dp(ScratchOutDetection.COVERAGE_RADIUS_DP)
        val result = StrokeEraser.findOverlappingStrokes(
            scratch, emptyList(),
            170f, 170f, 230f, 230f, radius
        )
        assertTrue(result.isEmpty())
    }

    // --- expandToConnectedWord ---

    /** Simple line index: strokes at y < 100 are line 0, 100..200 line 1, etc. */
    private fun lineOf(stroke: InkStroke): Int = (stroke.points.first().y / 100f).toInt()

    @Test
    fun `direct hits only, no expansion`() {
        val hit = timedStroke(50f to 50f, 100f to 50f, startMs = 1000, endMs = 2000, id = "hit")
        val result = StrokeEraser.expandToConnectedWord(
            directHits = listOf(hit),
            allStrokes = listOf(hit),
            scratchLeft = 40f, scratchTop = 40f, scratchRight = 110f, scratchBottom = 60f,
            lineSpacing = 100f,
            getStrokeLineIndex = ::lineOf
        )
        assertEquals(1, result.size)
        assertEquals("hit", result[0].strokeId)
    }

    @Test
    fun `expands to temporally connected stroke on same line`() {
        val hit = timedStroke(50f to 50f, 100f to 50f, startMs = 1000, endMs = 2000, id = "hit")
        val neighbor = timedStroke(110f to 50f, 160f to 50f, startMs = 2500, endMs = 3500, id = "neighbor")
        val result = StrokeEraser.expandToConnectedWord(
            directHits = listOf(hit),
            allStrokes = listOf(hit, neighbor),
            scratchLeft = 40f, scratchTop = 0f, scratchRight = 170f, scratchBottom = 99f,
            lineSpacing = 100f,
            getStrokeLineIndex = ::lineOf
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.strokeId == "neighbor" })
    }

    @Test
    fun `does NOT expand to stroke on different line`() {
        val hit = timedStroke(50f to 50f, 100f to 50f, startMs = 1000, endMs = 2000, id = "hit")
        // Different line (y=150, lineOf=1 vs lineOf=0)
        val other = timedStroke(50f to 150f, 100f to 150f, startMs = 2500, endMs = 3500, id = "other")
        val result = StrokeEraser.expandToConnectedWord(
            directHits = listOf(hit),
            allStrokes = listOf(hit, other),
            scratchLeft = 40f, scratchTop = 0f, scratchRight = 170f, scratchBottom = 199f,
            lineSpacing = 100f,
            getStrokeLineIndex = ::lineOf
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `does NOT expand to stroke outside scratch bbox plus margin`() {
        val hit = timedStroke(50f to 50f, 100f to 50f, startMs = 1000, endMs = 2000, id = "hit")
        // Same line but x is far outside scratch bbox + margin
        val far = timedStroke(500f to 50f, 550f to 50f, startMs = 2500, endMs = 3500, id = "far")
        val result = StrokeEraser.expandToConnectedWord(
            directHits = listOf(hit),
            allStrokes = listOf(hit, far),
            scratchLeft = 40f, scratchTop = 0f, scratchRight = 110f, scratchBottom = 99f,
            lineSpacing = 100f,
            getStrokeLineIndex = ::lineOf
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `does NOT expand to stroke with large time gap`() {
        val hit = timedStroke(50f to 50f, 100f to 50f, startMs = 1000, endMs = 2000, id = "hit")
        // Same line, within bbox, but written 5 seconds later (> 2000ms gap)
        val late = timedStroke(110f to 50f, 160f to 50f, startMs = 7000, endMs = 8000, id = "late")
        val result = StrokeEraser.expandToConnectedWord(
            directHits = listOf(hit),
            allStrokes = listOf(hit, late),
            scratchLeft = 40f, scratchTop = 0f, scratchRight = 170f, scratchBottom = 99f,
            lineSpacing = 100f,
            getStrokeLineIndex = ::lineOf
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `empty direct hits returns empty`() {
        val result = StrokeEraser.expandToConnectedWord(
            directHits = emptyList(),
            allStrokes = listOf(timedStroke(50f to 50f, startMs = 0, endMs = 0)),
            scratchLeft = 0f, scratchTop = 0f, scratchRight = 100f, scratchBottom = 100f,
            lineSpacing = 100f,
            getStrokeLineIndex = ::lineOf
        )
        assertTrue(result.isEmpty())
    }
}
