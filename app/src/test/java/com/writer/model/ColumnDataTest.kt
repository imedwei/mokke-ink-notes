package com.writer.model

import com.writer.storage.DocumentStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ColumnData] extraction and [DocumentData] composition.
 * Verifies the two-level hierarchy: DocumentData holds two ColumnData
 * instances (main + cue) plus shared fields.
 */
class ColumnDataTest {

    private fun samplePoints() = listOf(
        StrokePoint(0f, 0f, 1f, 0L),
        StrokePoint(100f, 100f, 1f, 100L)
    )

    private fun sampleStrokes() = listOf(
        InkStroke(strokeId = "s1", points = samplePoints()),
        InkStroke(strokeId = "s2", points = samplePoints(), strokeType = StrokeType.RECTANGLE, isGeometric = true)
    )

    private fun sampleDiagramAreas() = listOf(
        DiagramArea(id = "d1", startLineIndex = 2, heightInLines = 3)
    )

    // ── ColumnData construction ──────────────────────────────────────────

    @Test fun columnData_defaultsToEmpty() {
        val col = ColumnData()
        assertTrue(col.strokes.isEmpty())
        assertTrue(col.lineTextCache.isEmpty())
        assertTrue(col.everHiddenLines.isEmpty())
        assertTrue(col.diagramAreas.isEmpty())
    }

    @Test fun columnData_holdsStrokesAndCache() {
        val col = ColumnData(
            strokes = sampleStrokes(),
            lineTextCache = mapOf(0 to "Hello", 1 to "World"),
            everHiddenLines = setOf(0),
            diagramAreas = sampleDiagramAreas()
        )
        assertEquals(2, col.strokes.size)
        assertEquals("Hello", col.lineTextCache[0])
        assertEquals("World", col.lineTextCache[1])
        assertTrue(col.everHiddenLines.contains(0))
        assertEquals(1, col.diagramAreas.size)
        assertEquals("d1", col.diagramAreas[0].id)
    }

    // ── DocumentData composition ─────────────────────────────────────────

    @Test fun documentData_composesMainAndCue() {
        val main = ColumnData(strokes = sampleStrokes())
        val cue = ColumnData(strokes = listOf(InkStroke(strokeId = "cue1", points = samplePoints())))

        val doc = DocumentData(
            main = main,
            cue = cue,
            scrollOffsetY = 42.5f,
            highestLineIndex = 10,
            currentLineIndex = 5,
            userRenamed = true
        )

        assertEquals(2, doc.main.strokes.size)
        assertEquals(1, doc.cue.strokes.size)
        assertEquals("cue1", doc.cue.strokes[0].strokeId)
        assertEquals(42.5f, doc.scrollOffsetY, 0.001f)
        assertEquals(10, doc.highestLineIndex)
        assertEquals(5, doc.currentLineIndex)
        assertTrue(doc.userRenamed)
    }

    @Test fun documentData_cueDefaultsToEmpty() {
        val doc = DocumentData(
            main = ColumnData(strokes = sampleStrokes()),
            scrollOffsetY = 0f,
            highestLineIndex = 0,
            currentLineIndex = 0
        )
        assertTrue(doc.cue.strokes.isEmpty())
        assertTrue(doc.cue.lineTextCache.isEmpty())
        assertTrue(doc.cue.diagramAreas.isEmpty())
    }

    // ── Serialization round-trip ─────────────────────────────────────────

    @Test fun roundTrip_mainOnlyDocument() {
        val original = DocumentData(
            main = ColumnData(
                strokes = sampleStrokes(),
                lineTextCache = mapOf(0 to "Hello", 1 to "World"),
                everHiddenLines = setOf(0, 1),
                diagramAreas = sampleDiagramAreas()
            ),
            scrollOffsetY = 123.5f,
            highestLineIndex = 5,
            currentLineIndex = 3,
            userRenamed = true
        )

        val json = DocumentStorage.serializeToJson(original)
        val loaded = DocumentStorage.deserializeFromJson(json.toString())

        assertEquals(original.main.strokes.size, loaded.main.strokes.size)
        assertEquals("s1", loaded.main.strokes[0].strokeId)
        assertEquals("s2", loaded.main.strokes[1].strokeId)
        assertEquals(StrokeType.RECTANGLE, loaded.main.strokes[1].strokeType)
        assertTrue(loaded.main.strokes[1].isGeometric)
        assertEquals("Hello", loaded.main.lineTextCache[0])
        assertEquals("World", loaded.main.lineTextCache[1])
        assertTrue(loaded.main.everHiddenLines.contains(0))
        assertTrue(loaded.main.everHiddenLines.contains(1))
        assertEquals(1, loaded.main.diagramAreas.size)
        assertEquals("d1", loaded.main.diagramAreas[0].id)
        assertEquals(123.5f, loaded.scrollOffsetY, 0.001f)
        assertEquals(5, loaded.highestLineIndex)
        assertEquals(3, loaded.currentLineIndex)
        assertTrue(loaded.userRenamed)
        assertTrue(loaded.cue.strokes.isEmpty())
    }

    @Test fun roundTrip_mainAndCueDocument() {
        val original = DocumentData(
            main = ColumnData(
                strokes = listOf(InkStroke(strokeId = "main1", points = samplePoints())),
                lineTextCache = mapOf(0 to "Main text"),
                everHiddenLines = setOf(0)
            ),
            cue = ColumnData(
                strokes = listOf(InkStroke(strokeId = "cue1", points = samplePoints())),
                lineTextCache = mapOf(0 to "KEY CONCEPT"),
                everHiddenLines = setOf(),
                diagramAreas = listOf(DiagramArea(id = "cd1", startLineIndex = 1, heightInLines = 2))
            ),
            scrollOffsetY = 50f,
            highestLineIndex = 3,
            currentLineIndex = 2
        )

        val json = DocumentStorage.serializeToJson(original)
        val loaded = DocumentStorage.deserializeFromJson(json.toString())

        assertEquals(1, loaded.main.strokes.size)
        assertEquals("main1", loaded.main.strokes[0].strokeId)
        assertEquals("Main text", loaded.main.lineTextCache[0])

        assertEquals(1, loaded.cue.strokes.size)
        assertEquals("cue1", loaded.cue.strokes[0].strokeId)
        assertEquals("KEY CONCEPT", loaded.cue.lineTextCache[0])
        assertEquals(1, loaded.cue.diagramAreas.size)
        assertEquals("cd1", loaded.cue.diagramAreas[0].id)
    }

    // ── Backward compatibility ───────────────────────────────────────────

    @Test fun legacyJson_loadsIntoMainColumn() {
        // Simulate a document saved before the ColumnData refactor (flat structure)
        val legacyJson = """
        {
            "scrollOffsetY": 42.5,
            "highestLineIndex": 10,
            "currentLineIndex": 5,
            "userRenamed": true,
            "lineTextCache": {"0": "Hello", "1": "World"},
            "everHiddenLines": [0, 1],
            "strokes": [{
                "strokeId": "s1",
                "strokeWidth": 3.0,
                "points": [
                    {"x": 0, "y": 0, "pressure": 1, "timestamp": 0},
                    {"x": 100, "y": 100, "pressure": 1, "timestamp": 100}
                ]
            }],
            "diagramAreas": [{
                "id": "d1",
                "startLineIndex": 2,
                "heightInLines": 3
            }]
        }
        """.trimIndent()

        val loaded = DocumentStorage.deserializeFromJson(legacyJson)

        // Legacy data should load into main column
        assertEquals(1, loaded.main.strokes.size)
        assertEquals("s1", loaded.main.strokes[0].strokeId)
        assertEquals("Hello", loaded.main.lineTextCache[0])
        assertEquals("World", loaded.main.lineTextCache[1])
        assertTrue(loaded.main.everHiddenLines.contains(0))
        assertEquals(1, loaded.main.diagramAreas.size)
        assertEquals("d1", loaded.main.diagramAreas[0].id)

        // Shared fields
        assertEquals(42.5f, loaded.scrollOffsetY, 0.001f)
        assertEquals(10, loaded.highestLineIndex)
        assertEquals(5, loaded.currentLineIndex)
        assertTrue(loaded.userRenamed)

        // Cue column should be empty
        assertTrue(loaded.cue.strokes.isEmpty())
        assertTrue(loaded.cue.lineTextCache.isEmpty())
        assertTrue(loaded.cue.diagramAreas.isEmpty())
    }

    @Test fun legacyJson_emptyCueColumn() {
        val legacyJson = """
        {
            "scrollOffsetY": 0,
            "highestLineIndex": 0,
            "currentLineIndex": 0,
            "lineTextCache": {},
            "everHiddenLines": [],
            "strokes": [],
            "diagramAreas": []
        }
        """.trimIndent()

        val loaded = DocumentStorage.deserializeFromJson(legacyJson)
        assertTrue(loaded.main.strokes.isEmpty())
        assertTrue(loaded.cue.strokes.isEmpty())
    }
}
