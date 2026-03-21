package com.writer.storage

import com.writer.model.DocumentData
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [DocumentStorage] JSON serialization.
 * Verifies that strokeType and isGeometric survive save/load.
 */
class DocumentStorageSerializationTest {

    private fun makeData(strokes: List<InkStroke>) = DocumentData(
        strokes = strokes,
        scrollOffsetY = 0f,
        lineTextCache = emptyMap(),
        everHiddenLines = emptySet(),
        highestLineIndex = 0,
        currentLineIndex = 0
    )

    private fun roundTrip(strokes: List<InkStroke>): List<InkStroke> {
        val json = DocumentStorage.serializeToJson(makeData(strokes))
        val loaded = DocumentStorage.deserializeFromJson(json.toString())
        return loaded.strokes
    }

    private fun samplePoints() = listOf(
        StrokePoint(0f, 0f, 1f, 0L),
        StrokePoint(100f, 100f, 1f, 100L)
    )

    // ── strokeType persistence ──────────────────────────────────────────────

    @Test fun arrowHead_survivesRoundTrip() {
        val strokes = roundTrip(listOf(
            InkStroke(points = samplePoints(), strokeType = StrokeType.ARROW_HEAD, isGeometric = true)
        ))
        assertEquals(StrokeType.ARROW_HEAD, strokes[0].strokeType)
    }

    @Test fun arrowTail_survivesRoundTrip() {
        val strokes = roundTrip(listOf(
            InkStroke(points = samplePoints(), strokeType = StrokeType.ARROW_TAIL, isGeometric = true)
        ))
        assertEquals(StrokeType.ARROW_TAIL, strokes[0].strokeType)
    }

    @Test fun arrowBoth_survivesRoundTrip() {
        val strokes = roundTrip(listOf(
            InkStroke(points = samplePoints(), strokeType = StrokeType.ARROW_BOTH, isGeometric = true)
        ))
        assertEquals(StrokeType.ARROW_BOTH, strokes[0].strokeType)
    }

    @Test fun line_survivesRoundTrip() {
        val strokes = roundTrip(listOf(
            InkStroke(points = samplePoints(), strokeType = StrokeType.LINE, isGeometric = true)
        ))
        assertEquals(StrokeType.LINE, strokes[0].strokeType)
    }

    @Test fun rectangle_survivesRoundTrip() {
        val strokes = roundTrip(listOf(
            InkStroke(points = samplePoints(), strokeType = StrokeType.RECTANGLE, isGeometric = true)
        ))
        assertEquals(StrokeType.RECTANGLE, strokes[0].strokeType)
    }

    @Test fun ellipse_survivesRoundTrip() {
        val strokes = roundTrip(listOf(
            InkStroke(points = samplePoints(), strokeType = StrokeType.ELLIPSE, isGeometric = true)
        ))
        assertEquals(StrokeType.ELLIPSE, strokes[0].strokeType)
    }

    @Test fun freehand_survivesRoundTrip() {
        val strokes = roundTrip(listOf(
            InkStroke(points = samplePoints(), strokeType = StrokeType.FREEHAND)
        ))
        assertEquals(StrokeType.FREEHAND, strokes[0].strokeType)
    }

    // ── isGeometric persistence ─────────────────────────────────────────────

    @Test fun isGeometricTrue_survivesRoundTrip() {
        val strokes = roundTrip(listOf(
            InkStroke(points = samplePoints(), isGeometric = true, strokeType = StrokeType.RECTANGLE)
        ))
        assertTrue("isGeometric should be true", strokes[0].isGeometric)
    }

    @Test fun isGeometricFalse_survivesRoundTrip() {
        val strokes = roundTrip(listOf(
            InkStroke(points = samplePoints(), isGeometric = false)
        ))
        assertFalse("isGeometric should be false", strokes[0].isGeometric)
    }

    // ── Backward compatibility ──────────────────────────────────────────────

    @Test fun oldJsonWithoutStrokeType_defaultsToFreehand() {
        // Simulate a document saved before strokeType was added
        val json = """
        {
            "scrollOffsetY": 0,
            "highestLineIndex": 0,
            "currentLineIndex": 0,
            "lineTextCache": {},
            "everHiddenLines": [],
            "strokes": [{
                "strokeId": "s1",
                "strokeWidth": 3.0,
                "points": [
                    {"x": 0, "y": 0, "pressure": 1, "timestamp": 0},
                    {"x": 100, "y": 100, "pressure": 1, "timestamp": 100}
                ]
            }],
            "diagramAreas": []
        }
        """.trimIndent()

        val loaded = DocumentStorage.deserializeFromJson(json)
        assertEquals(StrokeType.FREEHAND, loaded.strokes[0].strokeType)
        assertFalse(loaded.strokes[0].isGeometric)
    }

    @Test fun unknownStrokeType_defaultsToFreehand() {
        val json = """
        {
            "scrollOffsetY": 0,
            "highestLineIndex": 0,
            "currentLineIndex": 0,
            "lineTextCache": {},
            "everHiddenLines": [],
            "strokes": [{
                "strokeId": "s1",
                "strokeWidth": 3.0,
                "strokeType": "FUTURE_TYPE",
                "points": [
                    {"x": 0, "y": 0, "pressure": 1, "timestamp": 0}
                ]
            }],
            "diagramAreas": []
        }
        """.trimIndent()

        val loaded = DocumentStorage.deserializeFromJson(json)
        assertEquals(StrokeType.FREEHAND, loaded.strokes[0].strokeType)
    }

    // ── Multiple strokes with mixed types ───────────────────────────────────

    @Test fun mixedStrokeTypes_allSurviveRoundTrip() {
        val original = listOf(
            InkStroke(points = samplePoints(), strokeType = StrokeType.FREEHAND),
            InkStroke(points = samplePoints(), strokeType = StrokeType.ARROW_HEAD, isGeometric = true),
            InkStroke(points = samplePoints(), strokeType = StrokeType.RECTANGLE, isGeometric = true),
            InkStroke(points = samplePoints(), strokeType = StrokeType.ELLIPSE, isGeometric = true),
        )

        val loaded = roundTrip(original)
        assertEquals(4, loaded.size)
        assertEquals(StrokeType.FREEHAND, loaded[0].strokeType)
        assertFalse(loaded[0].isGeometric)
        assertEquals(StrokeType.ARROW_HEAD, loaded[1].strokeType)
        assertTrue(loaded[1].isGeometric)
        assertEquals(StrokeType.RECTANGLE, loaded[2].strokeType)
        assertTrue(loaded[2].isGeometric)
        assertEquals(StrokeType.ELLIPSE, loaded[3].strokeType)
        assertTrue(loaded[3].isGeometric)
    }
}
