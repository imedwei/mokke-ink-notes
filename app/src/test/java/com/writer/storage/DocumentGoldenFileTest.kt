package com.writer.storage

import com.writer.model.StrokeType
import com.writer.model.proto.DocumentProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden file tests: verify that every historical document format can be loaded.
 *
 * RULES:
 * - Never modify or delete existing golden files.
 * - When the proto schema changes, add a new golden file.
 * - These tests are the permanent backward-compatibility contract.
 */
class DocumentGoldenFileTest {

    private fun loadResource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("golden/$name")!!.readBytes()

    // ── Legacy flat JSON (no "main" wrapper) ─────────────────────────────

    @Test
    fun legacyFlatJson_loadsAllData() {
        val json = String(loadResource("document_legacy_flat.json"))
        val data = DocumentStorage.deserializeFromJson(json)

        // Top-level fields
        assertEquals(42.5f, data.scrollOffsetY, 0.1f)
        assertEquals(3, data.highestLineIndex)
        assertEquals(2, data.currentLineIndex)

        // Strokes
        assertEquals(2, data.main.strokes.size)
        assertEquals("stroke-1", data.main.strokes[0].strokeId)
        assertEquals(3f, data.main.strokes[0].strokeWidth, 0.001f)
        assertEquals(2, data.main.strokes[0].points.size)
        assertEquals(10f, data.main.strokes[0].points[0].x, 0.001f)
        assertEquals(StrokeType.FREEHAND, data.main.strokes[0].strokeType)

        // Text cache
        assertEquals("hello", data.main.lineTextCache[0])
        assertEquals("world", data.main.lineTextCache[1])

        // Hidden lines
        assertEquals(setOf(0), data.main.everHiddenLines)

        // Diagram areas
        assertEquals(1, data.main.diagramAreas.size)
        assertEquals("area-1", data.main.diagramAreas[0].id)
        assertEquals(5, data.main.diagramAreas[0].startLineIndex)
        assertEquals(3, data.main.diagramAreas[0].heightInLines)

        // Empty cue (not present in legacy)
        assertEquals(0, data.cue.strokes.size)
    }

    // ── Legacy main-wrapper JSON (with "main"/"cue", no formatVersion) ──

    @Test
    fun legacyMainWrapperJson_loadsAllData() {
        val json = String(loadResource("document_legacy_main.json"))
        val data = DocumentStorage.deserializeFromJson(json)

        // Top-level fields
        assertEquals(99f, data.scrollOffsetY, 0.1f)
        assertEquals(10, data.highestLineIndex)
        assertEquals(8, data.currentLineIndex)
        assertTrue(data.userRenamed)

        // Main strokes with types
        assertEquals(3, data.main.strokes.size)
        assertEquals(StrokeType.FREEHAND, data.main.strokes[0].strokeType)
        assertFalse(data.main.strokes[0].isGeometric)
        assertEquals(StrokeType.RECTANGLE, data.main.strokes[1].strokeType)
        assertTrue(data.main.strokes[1].isGeometric)
        assertEquals(StrokeType.ARROW_HEAD, data.main.strokes[2].strokeType)
        assertTrue(data.main.strokes[2].isGeometric)

        // Main text cache and hidden lines
        assertEquals("main text", data.main.lineTextCache[0])
        assertEquals("more text", data.main.lineTextCache[2])
        assertEquals(setOf(0, 1), data.main.everHiddenLines)

        // Main diagram areas
        assertEquals(1, data.main.diagramAreas.size)
        assertEquals("diag-1", data.main.diagramAreas[0].id)

        // Cue column
        assertEquals(1, data.cue.strokes.size)
        assertEquals("cue-stroke-1", data.cue.strokes[0].strokeId)
        assertEquals("cue note", data.cue.lineTextCache[0])
    }

    // ── Protobuf v1 (.inkup) ─────────────────────────────────────────────

    @Test
    fun protoV1_loadsAllData() {
        val bytes = loadResource("document_v1.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        val data = proto.toDomain()

        // Top-level fields
        assertEquals(75.5f, data.scrollOffsetY, 0.1f)
        assertEquals(7, data.highestLineIndex)
        assertEquals(5, data.currentLineIndex)
        assertTrue(data.userRenamed)

        // Main strokes
        assertEquals(3, data.main.strokes.size)
        assertEquals("proto-stroke-1", data.main.strokes[0].strokeId)
        assertEquals(StrokeType.FREEHAND, data.main.strokes[0].strokeType)
        assertFalse(data.main.strokes[0].isGeometric)

        assertEquals("proto-stroke-2", data.main.strokes[1].strokeId)
        assertEquals(StrokeType.ELLIPSE, data.main.strokes[1].strokeType)
        assertTrue(data.main.strokes[1].isGeometric)

        assertEquals("proto-stroke-3", data.main.strokes[2].strokeId)
        assertEquals(StrokeType.ELBOW_ARROW_HEAD, data.main.strokes[2].strokeType)
        assertTrue(data.main.strokes[2].isGeometric)

        // Points preserved
        assertEquals(2, data.main.strokes[0].points.size)
        assertEquals(10f, data.main.strokes[0].points[0].x, 0.001f)
        assertEquals(20f, data.main.strokes[0].points[0].y, 0.001f)

        // Main text cache
        assertEquals("proto hello", data.main.lineTextCache[0])
        assertEquals("proto world", data.main.lineTextCache[1])

        // Main hidden lines
        assertEquals(setOf(0, 2), data.main.everHiddenLines)

        // Main diagram areas
        assertEquals(1, data.main.diagramAreas.size)
        assertEquals("proto-area", data.main.diagramAreas[0].id)
        assertEquals(3, data.main.diagramAreas[0].startLineIndex)
        assertEquals(4, data.main.diagramAreas[0].heightInLines)

        // Cue column
        assertEquals(1, data.cue.strokes.size)
        assertEquals("cue-proto-1", data.cue.strokes[0].strokeId)
        assertEquals("cue entry", data.cue.lineTextCache[0])
    }

}
