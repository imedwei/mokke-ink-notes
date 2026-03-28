package com.writer.storage

import com.writer.model.StrokeType
import com.writer.model.proto.DocumentProto
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
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

    @Before
    fun setUp() {
        // Init ScreenMetrics for v2 golden file tests (same device used during generation)
        ScreenMetrics.init(1.875f, smallestWidthDp = 674, widthPixels = 1264, heightPixels = 1680)
    }

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

    // ── Protobuf v2 (.inkup) — coordinate_system = 1 ─────────────────────

    @Test
    fun protoV2_loadsWithCoordinateSystem() {
        val bytes = loadResource("document_v2.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)

        // coordinate_system field is present and set to 1 (normalized line-units)
        assertEquals(1, proto.coordinate_system!!)

        // Structure matches v1 (same builder base)
        assertNotNull(proto.main)
        assertEquals(3, proto.main!!.strokes.size)
        assertEquals("proto-stroke-1", proto.main!!.strokes[0].stroke_id)

        assertNotNull(proto.cue)
        assertEquals(1, proto.cue!!.strokes.size)
        assertEquals("cue-proto-1", proto.cue!!.strokes[0].stroke_id)

        assertEquals(7, proto.highest_line_index)
        assertEquals(5, proto.current_line_index)
        assertTrue(proto.user_renamed!!)
    }

    @Test
    fun protoV2_toDomain_denormalizesCoordinates() {
        val bytes = loadResource("document_v2.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        val data = proto.toDomain()

        // The v2 golden file was generated from v1 data (absolute coords 10f, 20f)
        // normalized then denormalized with the same Go 7 line spacing (77px).
        // Values should match the original v1 data within float precision.
        assertEquals(10f, data.main.strokes[0].points[0].x, 0.5f)
        assertEquals(20f, data.main.strokes[0].points[0].y, 0.5f)
        assertEquals(75.5f, data.scrollOffsetY, 0.5f)

        // Stroke IDs, types, and non-coordinate fields are unchanged
        assertEquals("proto-stroke-1", data.main.strokes[0].strokeId)
        assertEquals(StrokeType.FREEHAND, data.main.strokes[0].strokeType)
        assertEquals(7, data.highestLineIndex)
        assertEquals(5, data.currentLineIndex)
        assertTrue(data.userRenamed)
    }

    @Test
    fun protoV1_hasNoCoordinateSystem() {
        val bytes = loadResource("document_v1.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        // v1 files have no coordinate_system field — Wire returns null (absent)
        assertEquals(null, proto.coordinate_system)
    }

}
