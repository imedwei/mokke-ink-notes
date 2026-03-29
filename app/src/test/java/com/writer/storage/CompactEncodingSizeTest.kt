package com.writer.storage

import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.proto.ColumnDataProto
import com.writer.model.proto.DocumentProto
import com.writer.model.proto.InkStrokeProto
import com.writer.model.proto.NumericRunProto
import com.writer.model.proto.StrokePointProto
import com.writer.model.proto.StrokeTypeProto
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates example documents in v2 (per-point) and v3 (compact run) encoding
 * to demonstrate the size reduction. Not a correctness test — just a size report.
 */
class CompactEncodingSizeTest {

    @Before
    fun setUp() {
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

    // ── Example 1: Single handwriting line (~50 points) ────────────────────

    @Test
    fun example_singleHandwritingLine() {
        val stroke = syntheticHandwritingStroke(
            startX = 0.5f, startY = 1.0f, pointCount = 50, seed = 42
        )
        val (v2Size, v3Size) = compareSizes("Single handwriting line (50 pts)", listOf(stroke))
        assertTrue("v3 should be smaller than v2", v3Size < v2Size)
    }

    // ── Example 2: One paragraph of handwriting (~10 lines, ~500 points) ──

    @Test
    fun example_oneHandwrittenParagraph() {
        val strokes = (0 until 10).map { line ->
            syntheticHandwritingStroke(
                startX = 0.5f, startY = 1.0f + line, pointCount = 50, seed = line
            )
        }
        val (v2Size, v3Size) = compareSizes("One paragraph (10 strokes, 500 pts)", strokes)
        assertTrue("v3 should be smaller than v2", v3Size < v2Size)
    }

    // ── Example 3: Full page (~30 lines + 5 geometric shapes) ─────────────

    @Test
    fun example_fullPageWithDiagrams() {
        val freehand = (0 until 30).map { line ->
            syntheticHandwritingStroke(
                startX = 0.5f, startY = 1.0f + line, pointCount = 60, seed = line
            )
        }
        val shapes = listOf(
            geometricRectangle(x = 2f, y = 10f, w = 4f, h = 2f),
            geometricEllipse(cx = 8f, cy = 12f, rx = 1.5f, ry = 1f),
            geometricLine(x1 = 6f, y1 = 10f, x2 = 6f, y2 = 12f),
            geometricRectangle(x = 2f, y = 14f, w = 3f, h = 1.5f),
            geometricLine(x1 = 5f, y1 = 14.75f, x2 = 8f, y2 = 12f),
        )
        val (v2Size, v3Size) = compareSizes("Full page (30 lines + 5 shapes)", freehand + shapes)
        assertTrue("v3 should be smaller than v2", v3Size < v2Size)
    }

    // ── Example 4: Heavy note-taking session (~100 strokes) ───────────────

    @Test
    fun example_heavyNoteTakingSession() {
        val strokes = (0 until 100).map { i ->
            syntheticHandwritingStroke(
                startX = 0.5f, startY = 1.0f + i * 0.5f,
                pointCount = Random(i).nextInt(30, 80), seed = i
            )
        }
        val (v2Size, v3Size) = compareSizes("Heavy session (100 strokes, ~5500 pts)", strokes)
        assertTrue("v3 should be smaller than v2", v3Size < v2Size)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Compare v2 (per-point StrokePointProto) vs v3 (NumericRunProto) wire sizes.
     * Returns (v2Bytes, v3Bytes) and prints a summary.
     */
    private fun compareSizes(label: String, strokes: List<InkStroke>): Pair<Int, Int> {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin

        // V2: per-point sub-messages (current format)
        val v2Strokes = strokes.map { stroke ->
            InkStrokeProto(
                stroke_id = stroke.strokeId,
                stroke_width = stroke.strokeWidth,
                points = stroke.points.map { pt ->
                    StrokePointProto(
                        x = pt.x / ls,
                        y = (pt.y - tm) / ls,
                        pressure = pt.pressure,
                        timestamp = pt.timestamp
                    )
                },
                stroke_type = stroke.strokeType.toProto(),
                is_geometric = stroke.isGeometric
            )
        }
        val v2Doc = DocumentProto(
            main = ColumnDataProto(strokes = v2Strokes),
            coordinate_system = 1
        )
        val v2Bytes = v2Doc.encode().size

        // V3: compact column-oriented encoding
        val v3Strokes = strokes.map { stroke ->
            val xs = FloatArray(stroke.points.size) { stroke.points[it].x / ls }
            val ys = FloatArray(stroke.points.size) { (stroke.points[it].y - tm) / ls }
            val pressures = FloatArray(stroke.points.size) { stroke.points[it].pressure }
            val timestamps = LongArray(stroke.points.size) { stroke.points[it].timestamp }

            val hasTimestamps = !NumericRunEncoder.allZeroTimestamps(timestamps)
            val baseTs = if (hasTimestamps) timestamps[0] else 0L
            InkStrokeProto(
                stroke_id = stroke.strokeId,
                stroke_width = stroke.strokeWidth,
                stroke_type = stroke.strokeType.toProto(),
                is_geometric = stroke.isGeometric,
                x_run = NumericRunEncoder.encodeCoordinates(xs),
                y_run = NumericRunEncoder.encodeCoordinates(ys),
                pressure_run = if (NumericRunEncoder.allDefaultPressure(pressures)) null
                    else NumericRunEncoder.encodePressure(pressures),
                time_run = if (hasTimestamps) NumericRunEncoder.encodeTimestamps(timestamps, baseTs) else null,
                stroke_timestamp = if (hasTimestamps) baseTs else null
            )
        }
        val v3Doc = DocumentProto(
            main = ColumnDataProto(strokes = v3Strokes),
            coordinate_system = 1
        )
        val v3Bytes = v3Doc.encode().size

        val totalPoints = strokes.sumOf { it.points.size }
        val ratio = v2Bytes.toFloat() / v3Bytes
        println(
            """
            |── $label ──
            |  Points: $totalPoints
            |  v2 (per-point): ${formatBytes(v2Bytes)} (${v2Bytes / totalPoints} bytes/pt)
            |  v3 (compact):   ${formatBytes(v3Bytes)} (${v3Bytes / totalPoints} bytes/pt)
            |  Ratio: %.1fx smaller
            """.trimMargin().format(ratio)
        )
        return v2Bytes to v3Bytes
    }

    private fun formatBytes(bytes: Int): String = when {
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }

    /** Generate a synthetic freehand stroke that looks like handwriting (wavy line). */
    private fun syntheticHandwritingStroke(
        startX: Float, startY: Float, pointCount: Int, seed: Int
    ): InkStroke {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        val rng = Random(seed)
        val points = (0 until pointCount).map { i ->
            val t = i.toFloat() / pointCount
            StrokePoint(
                x = (startX + t * 8f) * ls,  // ~8 line-units wide
                y = tm + (startY + 0.1f * sin(t * 20f + rng.nextFloat()) + rng.nextFloat() * 0.05f) * ls,
                pressure = 0.3f + rng.nextFloat() * 0.7f,
                timestamp = 1000L + i * 5L  // 5ms intervals (~200Hz)
            )
        }
        return InkStroke(points = points, strokeType = StrokeType.FREEHAND)
    }

    /** Generate a geometric rectangle (4 corner points). */
    private fun geometricRectangle(x: Float, y: Float, w: Float, h: Float): InkStroke {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        val points = listOf(
            StrokePoint(x * ls, tm + y * ls, 1f, 0L),
            StrokePoint((x + w) * ls, tm + y * ls, 1f, 0L),
            StrokePoint((x + w) * ls, tm + (y + h) * ls, 1f, 0L),
            StrokePoint(x * ls, tm + (y + h) * ls, 1f, 0L),
            StrokePoint(x * ls, tm + y * ls, 1f, 0L),
        )
        return InkStroke(points = points, strokeType = StrokeType.RECTANGLE, isGeometric = true)
    }

    /** Generate a geometric ellipse (~20 points). */
    private fun geometricEllipse(cx: Float, cy: Float, rx: Float, ry: Float): InkStroke {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        val n = 20
        val points = (0..n).map { i ->
            val angle = 2.0 * Math.PI * i / n
            StrokePoint(
                x = (cx + rx * kotlin.math.cos(angle).toFloat()) * ls,
                y = tm + (cy + ry * kotlin.math.sin(angle).toFloat()) * ls,
                pressure = 1f,
                timestamp = 0L
            )
        }
        return InkStroke(points = points, strokeType = StrokeType.ELLIPSE, isGeometric = true)
    }

    /** Generate a geometric line (2 points). */
    private fun geometricLine(x1: Float, y1: Float, x2: Float, y2: Float): InkStroke {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        return InkStroke(
            points = listOf(
                StrokePoint(x1 * ls, tm + y1 * ls, 1f, 0L),
                StrokePoint(x2 * ls, tm + y2 * ls, 1f, 0L),
            ),
            strokeType = StrokeType.LINE,
            isGeometric = true
        )
    }
}
