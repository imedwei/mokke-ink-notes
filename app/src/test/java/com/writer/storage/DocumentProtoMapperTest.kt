package com.writer.storage

import com.writer.model.ColumnData
import com.writer.model.DiagramArea
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.model.proto.DocumentProto
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DocumentProtoMapperTest {

    companion object {
        const val DENSITY = 1.875f
        // Go 7 (small screen): 41dp * 1.875 = 77px line spacing
        const val SW_GO_7 = 674
        const val W_GO_7 = 1264
        const val H_GO_7 = 1680
        // Tab X C (large screen): 50dp * 1.875 = 94px line spacing
        const val SW_TAB_X_C = 1280
        const val W_TAB_X_C = 3200
        const val H_TAB_X_C = 2400
    }

    @Before
    fun setUp() {
        ScreenMetrics.init(DENSITY, smallestWidthDp = SW_GO_7, widthPixels = W_GO_7, heightPixels = H_GO_7)
    }

    private fun samplePoints() = listOf(
        StrokePoint(10f, 20f, 0.5f, 1000L),
        StrokePoint(30f, 40f, 0.8f, 2000L)
    )

    private fun roundTrip(data: DocumentData): DocumentData =
        data.toProto().toDomain()

    // ── Basic round-trip ─────────────────────────────────────────────────

    @Test
    fun emptyDocument_survivesRoundTrip() {
        val data = DocumentData(main = ColumnData())
        val result = roundTrip(data)
        assertEquals(0, result.main.strokes.size)
        assertEquals(0, result.main.lineTextCache.size)
        assertEquals(0, result.main.everHiddenLines.size)
        assertEquals(0, result.main.diagramAreas.size)
    }

    @Test
    fun topLevelFields_surviveRoundTrip() {
        val data = DocumentData(
            main = ColumnData(),
            scrollOffsetY = 123.5f,
            highestLineIndex = 42,
            currentLineIndex = 7,
            userRenamed = true
        )
        val result = roundTrip(data)
        assertEquals(123.5f, result.scrollOffsetY, 0.001f)
        assertEquals(42, result.highestLineIndex)
        assertEquals(7, result.currentLineIndex)
        assertTrue(result.userRenamed)
    }

    // ── Stroke types ─────────────────────────────────────────────────────

    @Test
    fun allStrokeTypes_surviveRoundTrip() {
        for (type in StrokeType.entries) {
            val stroke = InkStroke(
                strokeId = "id-$type",
                points = samplePoints(),
                strokeType = type,
                isGeometric = type != StrokeType.FREEHAND
            )
            val data = DocumentData(main = ColumnData(strokes = listOf(stroke)))
            val result = roundTrip(data)
            assertEquals("StrokeType $type failed", type, result.main.strokes[0].strokeType)
        }
    }

    @Test
    fun isGeometric_survivesRoundTrip() {
        val strokes = listOf(
            InkStroke(points = samplePoints(), isGeometric = true, strokeType = StrokeType.RECTANGLE),
            InkStroke(points = samplePoints(), isGeometric = false)
        )
        val result = roundTrip(DocumentData(main = ColumnData(strokes = strokes)))
        assertTrue(result.main.strokes[0].isGeometric)
        assertFalse(result.main.strokes[1].isGeometric)
    }

    // ── Points ───────────────────────────────────────────────────────────

    @Test
    fun strokePoints_surviveRoundTrip() {
        val stroke = InkStroke(points = samplePoints(), strokeWidth = 5.5f)
        val result = roundTrip(DocumentData(main = ColumnData(strokes = listOf(stroke))))
        val pts = result.main.strokes[0].points
        assertEquals(2, pts.size)
        assertEquals(10f, pts[0].x, 0.001f)
        assertEquals(20f, pts[0].y, 0.001f)
        assertEquals(0.5f, pts[0].pressure, 0.001f)
        assertEquals(1000L, pts[0].timestamp)
        assertEquals(5.5f, result.main.strokes[0].strokeWidth, 0.001f)
    }

    // ── Column data ──────────────────────────────────────────────────────

    @Test
    fun lineTextCache_survivesRoundTrip() {
        val cache = mapOf(0 to "hello", 3 to "world")
        val result = roundTrip(DocumentData(main = ColumnData(lineTextCache = cache)))
        assertEquals(cache, result.main.lineTextCache)
    }

    @Test
    fun everHiddenLines_survivesRoundTrip() {
        val hidden = setOf(0, 1, 5)
        val result = roundTrip(DocumentData(main = ColumnData(everHiddenLines = hidden)))
        assertEquals(hidden, result.main.everHiddenLines)
    }

    @Test
    fun diagramAreas_surviveRoundTrip() {
        val areas = listOf(
            DiagramArea(id = "area-1", startLineIndex = 3, heightInLines = 5),
            DiagramArea(id = "area-2", startLineIndex = 10, heightInLines = 2)
        )
        val result = roundTrip(DocumentData(main = ColumnData(diagramAreas = areas)))
        assertEquals(2, result.main.diagramAreas.size)
        assertEquals("area-1", result.main.diagramAreas[0].id)
        assertEquals(3, result.main.diagramAreas[0].startLineIndex)
        assertEquals(5, result.main.diagramAreas[0].heightInLines)
    }

    // ── Cue column ───────────────────────────────────────────────────────

    @Test
    fun emptyCue_survivesRoundTrip() {
        val data = DocumentData(main = ColumnData(strokes = listOf(InkStroke(points = samplePoints()))))
        val result = roundTrip(data)
        assertEquals(0, result.cue.strokes.size)
    }

    @Test
    fun populatedCue_survivesRoundTrip() {
        val cue = ColumnData(
            strokes = listOf(InkStroke(points = samplePoints())),
            lineTextCache = mapOf(0 to "cue text"),
            everHiddenLines = setOf(0),
            diagramAreas = listOf(DiagramArea(id = "cue-area", startLineIndex = 0, heightInLines = 3))
        )
        val data = DocumentData(
            main = ColumnData(strokes = listOf(InkStroke(points = samplePoints()))),
            cue = cue
        )
        val result = roundTrip(data)
        assertEquals(1, result.cue.strokes.size)
        assertEquals("cue text", result.cue.lineTextCache[0])
        assertEquals(setOf(0), result.cue.everHiddenLines)
        assertEquals("cue-area", result.cue.diagramAreas[0].id)
    }

    // ── Full document ────────────────────────────────────────────────────

    @Test
    fun fullDocument_survivesRoundTrip() {
        val data = DocumentData(
            main = ColumnData(
                strokes = listOf(
                    InkStroke(points = samplePoints(), strokeType = StrokeType.FREEHAND),
                    InkStroke(points = samplePoints(), strokeType = StrokeType.ARROW_HEAD, isGeometric = true),
                    InkStroke(points = samplePoints(), strokeType = StrokeType.RECTANGLE, isGeometric = true)
                ),
                lineTextCache = mapOf(0 to "hello", 1 to "world"),
                everHiddenLines = setOf(0),
                diagramAreas = listOf(DiagramArea(id = "d1", startLineIndex = 5, heightInLines = 4))
            ),
            cue = ColumnData(
                strokes = listOf(InkStroke(points = samplePoints())),
                lineTextCache = mapOf(0 to "cue note")
            ),
            scrollOffsetY = 99.9f,
            highestLineIndex = 10,
            currentLineIndex = 8,
            userRenamed = true
        )
        val result = roundTrip(data)

        assertEquals(3, result.main.strokes.size)
        assertEquals(StrokeType.FREEHAND, result.main.strokes[0].strokeType)
        assertEquals(StrokeType.ARROW_HEAD, result.main.strokes[1].strokeType)
        assertEquals(StrokeType.RECTANGLE, result.main.strokes[2].strokeType)
        assertEquals("hello", result.main.lineTextCache[0])
        assertEquals(setOf(0), result.main.everHiddenLines)
        assertEquals(1, result.main.diagramAreas.size)

        assertEquals(1, result.cue.strokes.size)
        assertEquals("cue note", result.cue.lineTextCache[0])

        assertEquals(99.9f, result.scrollOffsetY, 0.1f)
        assertEquals(10, result.highestLineIndex)
        assertEquals(8, result.currentLineIndex)
        assertTrue(result.userRenamed)
    }

    // ── Binary round-trip ────────────────────────────────────────────────

    @Test
    fun binaryEncodeDecode_survivesRoundTrip() {
        val data = DocumentData(
            main = ColumnData(
                strokes = listOf(
                    InkStroke(points = samplePoints(), strokeType = StrokeType.ELLIPSE, isGeometric = true)
                ),
                lineTextCache = mapOf(0 to "test")
            ),
            scrollOffsetY = 50f
        )
        val proto = data.toProto()
        val bytes = proto.encode()
        val decoded = com.writer.model.proto.DocumentProto.ADAPTER.decode(bytes)
        val result = decoded.toDomain()

        assertEquals(1, result.main.strokes.size)
        assertEquals(StrokeType.ELLIPSE, result.main.strokes[0].strokeType)
        assertEquals("test", result.main.lineTextCache[0])
        assertEquals(50f, result.scrollOffsetY, 0.001f)
    }

    // ── Coordinate normalization ──────────────────────────────────────────

    @Test
    fun toProto_setsCoordinateSystem1() {
        val data = DocumentData(main = ColumnData(strokes = listOf(InkStroke(points = samplePoints()))))
        val proto = data.toProto()
        assertEquals(1, proto.coordinate_system)
    }

    @Test
    fun toProto_normalizesStrokeCoordinates() {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        val points = listOf(StrokePoint(ls * 2f, tm + ls * 3f, 0.5f, 1000L))
        val data = DocumentData(main = ColumnData(strokes = listOf(InkStroke(points = points))))
        val proto = data.toProto()
        val pt = proto.main!!.strokes[0].points[0]
        assertEquals("x should be normalized to 2.0 line-units", 2.0f, pt.x!!, 0.01f)
        assertEquals("y should be normalized to 3.0 line-units", 3.0f, pt.y!!, 0.01f)
    }

    @Test
    fun toProto_normalizesScrollOffset() {
        val ls = ScreenMetrics.lineSpacing
        val data = DocumentData(main = ColumnData(), scrollOffsetY = ls * 5f)
        val proto = data.toProto()
        assertEquals(5.0f, proto.scroll_offset_y!!, 0.01f)
    }

    @Test
    fun toProto_doesNotNormalizeStrokeWidth() {
        val stroke = InkStroke(points = samplePoints(), strokeWidth = 4.875f)
        val data = DocumentData(main = ColumnData(strokes = listOf(stroke)))
        val proto = data.toProto()
        assertEquals(4.875f, proto.main!!.strokes[0].stroke_width!!, 0.001f)
    }

    @Test
    fun sameDevice_roundTrip_preservesCoordinates() {
        val ls = ScreenMetrics.lineSpacing
        val tm = ScreenMetrics.topMargin
        val points = listOf(
            StrokePoint(100f, tm + ls * 4.5f, 0.7f, 5000L),
            StrokePoint(200f, tm + ls * 5.2f, 0.9f, 6000L)
        )
        val data = DocumentData(
            main = ColumnData(strokes = listOf(InkStroke(points = points))),
            scrollOffsetY = ls * 3f
        )
        val result = roundTrip(data)
        assertEquals(100f, result.main.strokes[0].points[0].x, 0.1f)
        assertEquals(tm + ls * 4.5f, result.main.strokes[0].points[0].y, 0.1f)
        assertEquals(ls * 3f, result.scrollOffsetY, 0.1f)
    }

    @Test
    fun crossDevice_roundTrip_scalesToNewLineSpacing() {
        // Save on Go 7 (77px line spacing)
        ScreenMetrics.init(DENSITY, smallestWidthDp = SW_GO_7, widthPixels = W_GO_7, heightPixels = H_GO_7)
        val go7Ls = ScreenMetrics.lineSpacing
        val go7Tm = ScreenMetrics.topMargin
        val points = listOf(StrokePoint(go7Ls * 2f, go7Tm + go7Ls * 3f, 0.5f, 1000L))
        val data = DocumentData(
            main = ColumnData(strokes = listOf(InkStroke(points = points))),
            scrollOffsetY = go7Ls * 5f
        )
        val proto = data.toProto()

        // Load on Tab X C (94px line spacing)
        ScreenMetrics.init(DENSITY, smallestWidthDp = SW_TAB_X_C, widthPixels = W_TAB_X_C, heightPixels = H_TAB_X_C)
        val tabLs = ScreenMetrics.lineSpacing
        val tabTm = ScreenMetrics.topMargin
        val result = proto.toDomain()

        // Coordinates should be at the same line-unit positions but in Tab X C pixels
        assertEquals(tabLs * 2f, result.main.strokes[0].points[0].x, 0.5f)
        assertEquals(tabTm + tabLs * 3f, result.main.strokes[0].points[0].y, 0.5f)
        assertEquals(tabLs * 5f, result.scrollOffsetY, 0.5f)
    }

    @Test
    fun legacyDocument_loadsWithoutConversion() {
        // Build a proto with coordinate_system = 0 (legacy) and raw absolute coordinates
        // Build a legacy proto with raw absolute coordinates (coordinate_system = 0)
        val manualProto = DocumentProto(
            main = com.writer.model.proto.ColumnDataProto(
                strokes = listOf(
                    com.writer.model.proto.InkStrokeProto(
                        stroke_id = "legacy",
                        points = listOf(
                            com.writer.model.proto.StrokePointProto(x = 100f, y = 500f, pressure = 0.5f, timestamp = 1000L)
                        )
                    )
                )
            ),
            scroll_offset_y = 250f,
            coordinate_system = 0
        )
        val result = manualProto.toDomain()
        // Legacy coordinates should pass through unchanged
        assertEquals(100f, result.main.strokes[0].points[0].x, 0.001f)
        assertEquals(500f, result.main.strokes[0].points[0].y, 0.001f)
        assertEquals(250f, result.scrollOffsetY, 0.001f)
    }

    @Test
    fun legacyDocument_nullCoordinateSystem_loadsWithoutConversion() {
        val manualProto = DocumentProto(
            main = com.writer.model.proto.ColumnDataProto(
                strokes = listOf(
                    com.writer.model.proto.InkStrokeProto(
                        stroke_id = "old",
                        points = listOf(
                            com.writer.model.proto.StrokePointProto(x = 77f, y = 300f, pressure = 1f, timestamp = 0L)
                        )
                    )
                )
            ),
            scroll_offset_y = 100f
            // coordinate_system not set — null
        )
        val result = manualProto.toDomain()
        assertEquals(77f, result.main.strokes[0].points[0].x, 0.001f)
        assertEquals(300f, result.main.strokes[0].points[0].y, 0.001f)
        assertEquals(100f, result.scrollOffsetY, 0.001f)
    }
}
