package com.writer.storage

import com.writer.model.ColumnData
import com.writer.model.DiagramArea
import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentProtoMapperTest {

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
}
