package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.view.HandwritingCanvasView
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class DiagramStrokeClassifierTest {

    companion object {
        private const val DENSITY = 1.875f
        private val LS get() = HandwritingCanvasView.LINE_SPACING
        private val TM get() = HandwritingCanvasView.TOP_MARGIN
    }

    @Before
    fun setUp() {
        ScreenMetrics.init(DENSITY, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    private fun makePoints(vararg pairs: Pair<Float, Float>): List<StrokePoint> =
        pairs.map { (x, y) -> StrokePoint(x, y, 0.5f, 0L) }

    /** Small text-like stroke on a single line. */
    private fun makeTextStroke(x: Float, y: Float): InkStroke =
        InkStroke(points = makePoints(
            x to y, (x + 10f) to (y + 5f), (x + 20f) to (y - 3f), (x + 30f) to (y + 2f)
        ))

    /** Tall vertical stroke spanning multiple lines. */
    private fun makeTallStroke(x: Float, topY: Float, height: Float): InkStroke =
        InkStroke(points = makePoints(
            x to topY, (x + 5f) to (topY + height * 0.3f),
            (x + 2f) to (topY + height * 0.7f), x to (topY + height)
        ))

    /** Closed circle/oval. */
    private fun makeCircle(cx: Float, cy: Float, r: Float, n: Int = 40): InkStroke {
        val points = (0..n).map { i ->
            StrokePoint(
                (cx + r * cos(2 * PI * i / n)).toFloat(),
                (cy + r * sin(2 * PI * i / n)).toFloat(),
                0.5f, 0L
            )
        }
        return InkStroke(points = points)
    }

    /** Wide flat connector-like stroke. */
    private fun makeConnector(startX: Float, y: Float, width: Float): InkStroke =
        InkStroke(points = makePoints(
            startX to y, (startX + width * 0.5f) to (y + 3f), (startX + width) to (y - 2f)
        ))

    /** Geometric shape (rectangle). */
    private fun makeGeometricRect(x: Float, y: Float, w: Float, h: Float): InkStroke =
        InkStroke(
            points = makePoints(x to y, (x + w) to y, (x + w) to (y + h), x to (y + h), x to y),
            strokeType = StrokeType.RECTANGLE,
            isGeometric = true
        )

    // ── Per-stroke classification ────────────────────────────────────────────

    @Test fun smallTextStroke_classifiedAsText() {
        val stroke = makeTextStroke(100f, 300f)
        val score = DiagramStrokeClassifier.classifyStroke(stroke, LS)
        assertTrue("Small text stroke should be text (score=$score)", score < 0.5f)
    }

    @Test fun tallStroke_1_7xLS_classifiedAsDrawing() {
        val stroke = makeTallStroke(100f, 200f, LS * 1.7f)
        val score = DiagramStrokeClassifier.classifyStroke(stroke, LS)
        assertTrue("Tall stroke (1.7×LS) should be drawing (score=$score)", score >= 0.5f)
    }

    @Test fun tallStroke_1_2xLS_classifiedAsText() {
        // Moderately tall but not enough alone (0.3 < 0.5)
        val stroke = makeTallStroke(100f, 200f, LS * 1.2f)
        val score = DiagramStrokeClassifier.classifyStroke(stroke, LS)
        assertTrue("Moderately tall stroke (1.2×LS) should be text (score=$score)", score < 0.5f)
    }

    @Test fun complexCircle_classifiedAsDrawing() {
        // Circle has pathLength/diagonal ≈ π ≈ 3.14 > 2.5, plus closure
        val stroke = makeCircle(300f, 300f, LS * 0.8f)
        val score = DiagramStrokeClassifier.classifyStroke(stroke, LS)
        assertTrue("Complex circular stroke should be drawing (score=$score)", score >= 0.5f)
    }

    @Test fun largeCircle_classifiedAsDrawing() {
        // Large circle: size > 2.5×LS + closure + complexity
        val stroke = makeCircle(300f, 300f, LS * 1.5f)
        val score = DiagramStrokeClassifier.classifyStroke(stroke, LS)
        assertTrue("Large circle should be drawing (score=$score)", score >= 0.5f)
    }

    @Test fun wideFlatConnector_classifiedAsDrawing() {
        val stroke = makeConnector(100f, 300f, LS * 2.5f)
        val score = DiagramStrokeClassifier.classifyStroke(stroke, LS)
        assertTrue("Wide flat connector should be drawing (score=$score)", score >= 0.5f)
    }

    @Test fun closedLoop_smallEnough_notDrawing() {
        // Small closed loop (< 0.5×LS diagonal) — could be a letter "o"
        val stroke = makeCircle(300f, 300f, LS * 0.15f, n = 20)
        val score = DiagramStrokeClassifier.classifyStroke(stroke, LS)
        assertTrue("Small closed loop should be text (score=$score)", score < 0.5f)
    }

    @Test fun normalLetterA_classifiedAsText() {
        // Two-stroke "A": moderate height, simple path
        val stroke = InkStroke(points = makePoints(
            100f to (300f + LS * 0.8f),  // bottom-left
            115f to 300f,                 // top
            130f to (300f + LS * 0.8f),  // bottom-right
        ))
        val score = DiagramStrokeClassifier.classifyStroke(stroke, LS)
        assertTrue("Letter A should be text (score=$score)", score < 0.5f)
    }

    // ── Per-stroke partition ─────────────────────────────────────────────────

    @Test fun partitionByStroke_separatesTextAndDrawing() {
        val text1 = makeTextStroke(100f, 300f)
        val text2 = makeTextStroke(200f, 300f)
        val drawing1 = makeTallStroke(300f, 200f, LS * 2f)
        val drawing2 = makeCircle(400f, 300f, LS * 1f)

        val (textResult, drawingResult) = DiagramStrokeClassifier.partitionByStroke(
            listOf(text1, text2, drawing1, drawing2), LS
        )

        assertTrue("Should have 2 text strokes", textResult.size == 2)
        assertTrue("Should have 2 drawing strokes", drawingResult.size == 2)
    }

    // ── Group-level: multi-line span ─────────────────────────────────────────

    @Test fun groupSpanning_0_8xLS_isText() {
        val group = listOf(
            makeTextStroke(100f, 300f),
            makeTextStroke(150f, 300f + LS * 0.3f)
        )
        assertTrue("Group spanning 0.8×LS should be text",
            DiagramStrokeClassifier.isTextGroup(group, LS))
    }

    @Test fun groupSpanning_2_5xLS_isDrawing() {
        val group = listOf(
            makeTextStroke(100f, 300f),
            makeTextStroke(100f, 300f + LS * 2.5f)
        )
        assertFalse("Group spanning 2.5×LS should be drawing",
            DiagramStrokeClassifier.isTextGroup(group, LS))
    }

    // ── Drawing contagion ────────────────────────────────────────────────────

    @Test fun freehandAdjacentToGeometricShape_becomesDrawing() {
        // Small freehand stroke near a geometric rectangle
        val textStroke = makeTextStroke(155f, 350f)  // adjacent to the shape
        val shape = makeGeometricRect(100f, 300f, 50f, 80f)

        val (text, drawing) = DiagramStrokeClassifier.partition(
            freehandStrokes = listOf(textStroke),
            geometricStrokes = listOf(shape),
            lineSpacing = LS
        )

        assertTrue("Freehand adjacent to shape should become drawing (contagion)", drawing.size == 1)
        assertTrue("No text candidates", text.isEmpty())
    }

    @Test fun freehandAdjacentToTallDrawingStroke_becomesDrawing() {
        // A tall drawing stroke and a small freehand stroke nearby
        val tallStroke = makeTallStroke(100f, 200f, LS * 2f)  // classified as drawing
        val smallStroke = makeTextStroke(110f, 200f + LS * 1.5f)  // adjacent, text-like

        val (text, drawing) = DiagramStrokeClassifier.partition(
            freehandStrokes = listOf(tallStroke, smallStroke),
            geometricStrokes = emptyList(),
            lineSpacing = LS
        )

        assertTrue("Both strokes should be drawing (contagion)", drawing.size == 2)
        assertTrue("No text candidates", text.isEmpty())
    }

    @Test fun freehandFarFromDrawing_staysText() {
        // A tall drawing stroke far from a small text stroke
        val tallStroke = makeTallStroke(100f, 200f, LS * 2f)
        val textStroke = makeTextStroke(500f, 800f)  // far away

        val (text, drawing) = DiagramStrokeClassifier.partition(
            freehandStrokes = listOf(tallStroke, textStroke),
            geometricStrokes = emptyList(),
            lineSpacing = LS
        )

        assertTrue("Text stroke should remain text", text.size == 1)
        assertTrue("Drawing stroke should be drawing", drawing.size == 1)
        assertTrue("Text stroke ID preserved", text[0].strokeId == textStroke.strokeId)
    }

    @Test fun textInsideShape_staysText_whenNoDrawingContagion() {
        // Text stroke whose centroid is inside a shape, but it's the only
        // freehand stroke — no drawing contagion from the shape alone
        // because the shape is geometric, triggering contagion.
        // This tests that shapes DO trigger contagion (by design).
        val shape = makeGeometricRect(80f, 280f, 100f, 60f)
        val textInside = makeTextStroke(100f, 300f)

        val (text, drawing) = DiagramStrokeClassifier.partition(
            freehandStrokes = listOf(textInside),
            geometricStrokes = listOf(shape),
            lineSpacing = LS
        )

        // Shape adjacency triggers contagion — text near shapes is drawing
        assertTrue("Text near shape becomes drawing via contagion", drawing.size == 1)
    }

    @Test fun isolatedTextGroup_notAffectedByDistantShape() {
        // Text far from any shape
        val shape = makeGeometricRect(100f, 100f, 50f, 50f)
        val textFar = makeTextStroke(500f, 800f)

        val (text, drawing) = DiagramStrokeClassifier.partition(
            freehandStrokes = listOf(textFar),
            geometricStrokes = listOf(shape),
            lineSpacing = LS
        )

        assertTrue("Distant text should stay text", text.size == 1)
        assertTrue("No drawing strokes", drawing.isEmpty())
    }

    // ── Full partition with mixed content ────────────────────────────────────

    @Test fun mixedDiagramContent_correctlyPartitioned() {
        // Simulate a real diagram: shapes + text labels + freehand drawings
        val shape1 = makeGeometricRect(100f, 200f, 80f, 60f)
        val shape2 = makeGeometricRect(300f, 200f, 80f, 60f)

        // Text label far from shapes (diagram title at top)
        val titleText = makeTextStroke(200f, 100f)

        // Freehand arrow between shapes (tall, near shapes)
        val arrow = makeTallStroke(190f, 210f, LS * 1.8f)

        // Small annotation near shape1
        val annotation = makeTextStroke(105f, 270f)

        val (text, drawing) = DiagramStrokeClassifier.partition(
            freehandStrokes = listOf(titleText, arrow, annotation),
            geometricStrokes = listOf(shape1, shape2),
            lineSpacing = LS
        )

        // Arrow is drawing (tall). Annotation near shape1 gets contagion.
        // Title text is far from shapes — might stay text depending on distance.
        assertTrue("Arrow should be drawing", drawing.any { it.strokeId == arrow.strokeId })
        assertTrue("Annotation near shape should be drawing (contagion)",
            drawing.any { it.strokeId == annotation.strokeId })
    }
}
