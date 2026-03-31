package com.writer.storage

import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.proto.ColumnDataProto
import com.writer.model.proto.DocumentProto
import com.writer.model.proto.InkStrokeProto
import com.writer.model.proto.StrokePointProto
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for v3 compact encoding integration in DocumentProtoMapper.
 * Written before implementation — these should fail until the mapper is updated.
 */
class CompactEncodingMapperTest {

    companion object {
        const val DENSITY = 1.875f
        const val SW_GO_7 = 674
        const val W_GO_7 = 1264
        const val H_GO_7 = 1680
        const val SW_TAB_X_C = 1280
        const val W_TAB_X_C = 3200
        const val H_TAB_X_C = 2400
    }

    @Before
    fun setUp() {
        ScreenMetrics.init(DENSITY, smallestWidthDp = SW_GO_7, widthPixels = W_GO_7, heightPixels = H_GO_7)
    }

    private fun roundTrip(data: DocumentData): DocumentData =
        data.toProto().toDomain()

    // ── v3 output format ───────────────────────────────────────────────────

    @Test
    fun toProto_usesCompactRuns() {
        val stroke = InkStroke(
            points = listOf(
                StrokePoint(77f, 113f, 0.5f, 1000L),
                StrokePoint(154f, 190f, 0.8f, 1005L)
            )
        )
        val proto = DocumentData(main = ColumnData(strokes = listOf(stroke))).toProto()
        val s = proto.main!!.strokes[0]

        // v3: runs present, points empty
        assertNotNull("x_run should be present", s.x_run)
        assertNotNull("y_run should be present", s.y_run)
        assertTrue("points should be empty when runs are present", s.points.isEmpty())
    }

    @Test
    fun toProto_omitsPressureRun_whenAllDefault() {
        val stroke = InkStroke(
            points = listOf(
                StrokePoint(77f, 113f, 1f, 1000L),
                StrokePoint(154f, 190f, 1f, 1005L)
            )
        )
        val proto = DocumentData(main = ColumnData(strokes = listOf(stroke))).toProto()
        val s = proto.main!!.strokes[0]
        assertNull("pressure_run should be omitted when all pressures are 1.0", s.pressure_run)
    }

    @Test
    fun toProto_includesPressureRun_whenVarying() {
        val stroke = InkStroke(
            points = listOf(
                StrokePoint(77f, 113f, 0.5f, 1000L),
                StrokePoint(154f, 190f, 0.8f, 1005L)
            )
        )
        val proto = DocumentData(main = ColumnData(strokes = listOf(stroke))).toProto()
        assertNotNull("pressure_run should be present", proto.main!!.strokes[0].pressure_run)
    }

    @Test
    fun toProto_omitsTimeRun_whenAllZero() {
        val stroke = InkStroke(
            points = listOf(
                StrokePoint(77f, 113f, 1f, 0L),
                StrokePoint(154f, 190f, 1f, 0L)
            )
        )
        val proto = DocumentData(main = ColumnData(strokes = listOf(stroke))).toProto()
        assertNull("time_run should be omitted when all timestamps are 0", proto.main!!.strokes[0].time_run)
    }

    @Test
    fun toProto_includesTimeRun_whenPresent() {
        val stroke = InkStroke(
            points = listOf(
                StrokePoint(77f, 113f, 1f, 1000L),
                StrokePoint(154f, 190f, 1f, 1005L)
            )
        )
        val proto = DocumentData(main = ColumnData(strokes = listOf(stroke))).toProto()
        assertNotNull("time_run should be present", proto.main!!.strokes[0].time_run)
    }

    // ── Round-trip correctness ─────────────────────────────────────────────

    @Test
    fun compactEncoding_roundTrip_preservesCoordinates() {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        val points = listOf(
            StrokePoint(ls * 2f, tm + ls * 3f, 0.5f, 1000L),
            StrokePoint(ls * 2.5f, tm + ls * 3.1f, 0.7f, 1005L),
            StrokePoint(ls * 3f, tm + ls * 3.2f, 0.9f, 1010L)
        )
        val data = DocumentData(main = ColumnData(strokes = listOf(InkStroke(points = points))))
        val result = roundTrip(data)

        val pts = result.main.strokes[0].points
        assertEquals(3, pts.size)
        // 0.01 line-unit quantization → 0.01 * 77px ≈ 0.77px tolerance
        for (i in points.indices) {
            assertEquals("x[$i]", points[i].x, pts[i].x, 1f)
            assertEquals("y[$i]", points[i].y, pts[i].y, 1f)
            assertEquals("pressure[$i]", points[i].pressure, pts[i].pressure, 0.02f)
            assertEquals("timestamp[$i]", points[i].timestamp, pts[i].timestamp)
        }
    }

    @Test
    fun compactEncoding_roundTrip_preservesStrokeMetadata() {
        val stroke = InkStroke(
            strokeId = "test-id",
            points = listOf(StrokePoint(77f, 113f, 0.5f, 1000L)),
            strokeWidth = 5.5f,
            strokeType = StrokeType.ARROW_HEAD,
            isGeometric = true
        )
        val result = roundTrip(DocumentData(main = ColumnData(strokes = listOf(stroke))))
        val s = result.main.strokes[0]
        assertEquals("test-id", s.strokeId)
        assertEquals(5.5f, s.strokeWidth, 0.001f)
        assertEquals(StrokeType.ARROW_HEAD, s.strokeType)
        assertTrue(s.isGeometric)
    }

    @Test
    fun compactEncoding_roundTrip_allStrokeTypes() {
        // SYNTHETIC is ephemeral (display-only), maps to FREEHAND in proto — skip it
        for (type in StrokeType.entries.filter { it != StrokeType.SYNTHETIC }) {
            val stroke = InkStroke(
                strokeId = "type-$type",
                points = listOf(
                    StrokePoint(77f, 113f, 0.5f, 1000L),
                    StrokePoint(154f, 190f, 0.8f, 2000L)
                ),
                strokeType = type,
                isGeometric = type != StrokeType.FREEHAND
            )
            val result = roundTrip(DocumentData(main = ColumnData(strokes = listOf(stroke))))
            assertEquals("StrokeType $type failed", type, result.main.strokes[0].strokeType)
        }
    }

    @Test
    fun compactEncoding_roundTrip_defaultPressure() {
        val points = listOf(
            StrokePoint(77f, 113f, 1f, 0L),
            StrokePoint(154f, 190f, 1f, 0L)
        )
        val result = roundTrip(DocumentData(main = ColumnData(strokes = listOf(InkStroke(points = points)))))
        val pts = result.main.strokes[0].points
        assertEquals(1f, pts[0].pressure, 0.001f)
        assertEquals(1f, pts[1].pressure, 0.001f)
    }

    @Test
    fun compactEncoding_roundTrip_zeroTimestamps() {
        val points = listOf(
            StrokePoint(77f, 113f, 0.5f, 0L),
            StrokePoint(154f, 190f, 0.8f, 0L)
        )
        val result = roundTrip(DocumentData(main = ColumnData(strokes = listOf(InkStroke(points = points)))))
        val pts = result.main.strokes[0].points
        assertEquals(0L, pts[0].timestamp)
        assertEquals(0L, pts[1].timestamp)
    }

    @Test
    fun emptyStroke_survivesRoundTrip() {
        val stroke = InkStroke(strokeId = "empty", points = emptyList(), strokeType = StrokeType.RECTANGLE, isGeometric = true)
        val result = roundTrip(DocumentData(main = ColumnData(strokes = listOf(stroke))))
        assertEquals("empty", result.main.strokes[0].strokeId)
        assertEquals(0, result.main.strokes[0].points.size)
        assertEquals(StrokeType.RECTANGLE, result.main.strokes[0].strokeType)
        assertTrue(result.main.strokes[0].isGeometric)
    }

    @Test
    fun compactEncoding_crossDevice_scalesToNewLineSpacing() {
        // Save on Go 7
        ScreenMetrics.init(DENSITY, smallestWidthDp = SW_GO_7, widthPixels = W_GO_7, heightPixels = H_GO_7)
        val go7Ls = ScreenMetrics.lineSpacing
        val go7Tm = ScreenMetrics.topMargin
        val points = listOf(StrokePoint(go7Ls * 2f, go7Tm + go7Ls * 3f, 0.5f, 1000L))
        val proto = DocumentData(main = ColumnData(strokes = listOf(InkStroke(points = points)))).toProto()

        // Load on Tab X C
        ScreenMetrics.init(DENSITY, smallestWidthDp = SW_TAB_X_C, widthPixels = W_TAB_X_C, heightPixels = H_TAB_X_C)
        val tabLs = ScreenMetrics.lineSpacing
        val tabTm = ScreenMetrics.topMargin
        val result = proto.toDomain()

        assertEquals(tabLs * 2f, result.main.strokes[0].points[0].x, 1f)
        assertEquals(tabTm + tabLs * 3f, result.main.strokes[0].points[0].y, 1f)
    }

    // ── Backward compatibility: reading v2 per-point protos ────────────────

    @Test
    fun v2PerPointProto_stillLoadsCorrectly() {
        // Manually build a v2-style proto with per-point encoding (no runs)
        val proto = DocumentProto(
            main = ColumnDataProto(
                strokes = listOf(
                    InkStrokeProto(
                        stroke_id = "legacy-v2",
                        stroke_width = 3f,
                        points = listOf(
                            StrokePointProto(x = 2.0f, y = 3.0f, pressure = 0.5f, timestamp = 1000L)
                        )
                    )
                )
            ),
            coordinate_system = 1
        )
        val result = proto.toDomain()
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        assertEquals(2.0f * ls, result.main.strokes[0].points[0].x, 0.5f)
        assertEquals(tm + 3.0f * ls, result.main.strokes[0].points[0].y, 0.5f)
        assertEquals(0.5f, result.main.strokes[0].points[0].pressure, 0.001f)
    }

    // ── Binary round-trip (encode → decode bytes → domain) ─────────────────

    @Test
    fun compactEncoding_binaryRoundTrip() {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        val stroke = InkStroke(
            points = listOf(
                StrokePoint(ls * 1f, tm + ls * 2f, 0.6f, 5000L),
                StrokePoint(ls * 1.5f, tm + ls * 2.5f, 0.8f, 5005L),
                StrokePoint(ls * 2f, tm + ls * 3f, 0.7f, 5010L)
            ),
            strokeType = StrokeType.FREEHAND
        )
        val data = DocumentData(
            main = ColumnData(strokes = listOf(stroke)),
            scrollOffsetY = ls * 4f
        )
        val bytes = data.toProto().encode()
        val decoded = DocumentProto.ADAPTER.decode(bytes).toDomain()

        assertEquals(1, decoded.main.strokes.size)
        assertEquals(3, decoded.main.strokes[0].points.size)
        assertEquals(ls * 1f, decoded.main.strokes[0].points[0].x, 1f)
        assertEquals(ls * 4f, decoded.scrollOffsetY, 0.5f)
    }

    // ── Multiple strokes ───────────────────────────────────────────────────

    @Test
    fun compactEncoding_multipleStrokes_roundTrip() {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        val strokes = listOf(
            InkStroke(
                strokeId = "s1",
                points = listOf(
                    StrokePoint(ls, tm + ls, 0.5f, 100L),
                    StrokePoint(ls * 2, tm + ls, 0.6f, 105L)
                )
            ),
            InkStroke(
                strokeId = "s2",
                points = listOf(
                    StrokePoint(ls * 5, tm + ls * 5, 1f, 0L),
                    StrokePoint(ls * 6, tm + ls * 6, 1f, 0L)
                ),
                strokeType = StrokeType.LINE,
                isGeometric = true
            )
        )
        val result = roundTrip(DocumentData(main = ColumnData(strokes = strokes)))
        assertEquals(2, result.main.strokes.size)
        assertEquals("s1", result.main.strokes[0].strokeId)
        assertEquals("s2", result.main.strokes[1].strokeId)
        assertEquals(StrokeType.LINE, result.main.strokes[1].strokeType)
        assertTrue(result.main.strokes[1].isGeometric)
    }

    // ── Cue column also uses compact encoding ──────────────────────────────

    @Test
    fun compactEncoding_cueColumn_roundTrip() {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        val cue = ColumnData(
            strokes = listOf(
                InkStroke(
                    points = listOf(
                        StrokePoint(ls, tm + ls, 0.5f, 100L),
                        StrokePoint(ls * 2, tm + ls * 2, 0.7f, 105L)
                    )
                )
            ),
            lineTextCache = mapOf(0 to "cue text")
        )
        val data = DocumentData(
            main = ColumnData(strokes = listOf(InkStroke(
                points = listOf(StrokePoint(ls, tm + ls, 1f, 0L))
            ))),
            cue = cue
        )
        val result = roundTrip(data)
        assertEquals(1, result.cue.strokes.size)
        assertEquals(2, result.cue.strokes[0].points.size)
        assertEquals("cue text", result.cue.lineTextCache[0])
    }
}
