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

    // ── Protobuf v3 (.inkup) — compact column-oriented encoding ──────────

    @Test
    fun protoV3_loadsAllData() {
        val bytes = loadResource("document_v3.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        val data = proto.toDomain()

        // Top-level fields
        assertEquals(75.5f, data.scrollOffsetY, 0.5f)
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

        // Points preserved (within quantization tolerance)
        assertEquals(2, data.main.strokes[0].points.size)
        assertEquals(10f, data.main.strokes[0].points[0].x, 1f)
        assertEquals(20f, data.main.strokes[0].points[0].y, 1f)

        // Text cache, hidden lines, diagram areas
        assertEquals("proto hello", data.main.lineTextCache[0])
        assertEquals("proto world", data.main.lineTextCache[1])
        assertEquals(setOf(0, 2), data.main.everHiddenLines)
        assertEquals(1, data.main.diagramAreas.size)
        assertEquals("proto-area", data.main.diagramAreas[0].id)

        // Cue column
        assertEquals(1, data.cue.strokes.size)
        assertEquals("cue-proto-1", data.cue.strokes[0].strokeId)
        assertEquals("cue entry", data.cue.lineTextCache[0])
    }

    @Test
    fun protoV3_usesCompactRuns() {
        val bytes = loadResource("document_v3.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        val stroke = proto.main!!.strokes[0]

        // v3 strokes use runs, not per-point sub-messages
        assertNotNull(stroke.x_run)
        assertNotNull(stroke.y_run)
        assertTrue("points should be empty in v3", stroke.points.isEmpty())
    }

    // ── Protobuf v4 (.inkup) — stroke_timestamp for lossless time precision ─

    @Test
    fun protoV4_loadsAllData() {
        val bytes = loadResource("document_v4.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        val data = proto.toDomain()

        // Same structure as v3
        assertEquals(75.5f, data.scrollOffsetY, 0.5f)
        assertEquals(3, data.main.strokes.size)
        assertEquals("proto-stroke-1", data.main.strokes[0].strokeId)
        assertEquals(2, data.main.strokes[0].points.size)
        assertEquals(10f, data.main.strokes[0].points[0].x, 1f)
        assertEquals(20f, data.main.strokes[0].points[0].y, 1f)

        // Timestamps preserved losslessly
        assertEquals(1000L, data.main.strokes[0].points[0].timestamp)
        assertEquals(2000L, data.main.strokes[0].points[1].timestamp)
    }

    @Test
    fun protoV4_hasStrokeTimestamp() {
        val bytes = loadResource("document_v4.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        val stroke = proto.main!!.strokes[0]

        assertNotNull(stroke.x_run)
        assertNotNull(stroke.time_run)
        assertEquals(1000L, stroke.stroke_timestamp)
    }

    // ── Protobuf v5 (.inkup) — TextBlocks and AudioRecordings ─────────────

    @Test
    fun protoV5_loadsAllData() {
        val bytes = loadResource("document_v5.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        val data = proto.toDomain()

        // Existing v1 data still present
        assertEquals(75.5f, data.scrollOffsetY, 0.5f)
        assertEquals(3, data.main.strokes.size)
        assertEquals("proto-stroke-1", data.main.strokes[0].strokeId)
        assertEquals("proto hello", data.main.lineTextCache[0])
        assertEquals(1, data.main.diagramAreas.size)
        assertTrue(data.userRenamed)

        // TextBlocks — v5 files had them in main; Phase 3a migration moves them into
        // the transcript column on load with anchorTarget = MAIN stamped.
        assertEquals("main.textBlocks cleared after v5→v6 migration", 0, data.main.textBlocks.size)
        assertEquals(2, data.transcript.textBlocks.size)
        with(data.transcript.textBlocks[0]) {
            assertEquals("proto-text-block-1", id)
            assertEquals(10, startLineIndex)
            assertEquals(2, heightInLines)
            assertEquals("transcribed lecture text", text)
            assertEquals("rec-001.opus", audioFile)
            assertEquals(5000L, audioStartMs)
            assertEquals(15000L, audioEndMs)
            assertEquals(com.writer.model.AnchorTarget.MAIN, anchorTarget)
            assertEquals(com.writer.model.AnchorMode.AUTO, anchorMode)
        }
        with(data.transcript.textBlocks[1]) {
            assertEquals("proto-text-block-2", id)
            assertEquals(15, startLineIndex)
            assertEquals(1, heightInLines)
            assertEquals("quick voice memo", text)
            assertEquals("", audioFile)
        }

        // AudioRecordings
        assertEquals(1, data.audioRecordings.size)
        with(data.audioRecordings[0]) {
            assertEquals("rec-001.opus", audioFile)
            assertEquals(1700000000000L, startTimeMs)
            assertEquals(60000L, durationMs)
        }
    }

    @Test
    fun protoV5_hasTextBlocksInProto() {
        val bytes = loadResource("document_v5.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        assertEquals(2, proto.main!!.text_blocks.size)
        assertEquals("proto-text-block-1", proto.main!!.text_blocks[0].id)
    }

    @Test
    fun protoV5_hasAudioRecordingsInProto() {
        val bytes = loadResource("document_v5.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        assertEquals(1, proto.audio_recordings.size)
        assertEquals("rec-001.opus", proto.audio_recordings[0].audio_file)
    }

    @Test
    fun protoV1_hasNoCoordinateSystem() {
        val bytes = loadResource("document_v1.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        // v1 files have no coordinate_system field — Wire returns null (absent)
        assertEquals(null, proto.coordinate_system)
    }

    // ── Protobuf v6 (.inkup) — transcript column + anchor metadata ────────

    @Test
    fun protoV6_loadsAllData() {
        val bytes = loadResource("document_v6.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        val data = proto.toDomain()

        // Existing data still loads
        assertEquals(3, data.main.strokes.size)
        assertTrue(data.userRenamed)

        // Transcript column present with text blocks carrying anchors
        assertTrue("v6 must carry transcript textBlocks", data.transcript.textBlocks.isNotEmpty())

        // Every transcript block has explicit anchor fields (not defaults).
        with(data.transcript.textBlocks[0]) {
            assertNotNull(anchorMode)
            assertNotNull(anchorTarget)
        }
    }

    @Test
    fun protoV6_hasTranscriptInProto() {
        val bytes = loadResource("document_v6.inkup")
        val proto = DocumentProto.ADAPTER.decode(bytes)
        assertNotNull("v6 must serialize transcript field", proto.transcript)
        assertTrue(proto.transcript!!.text_blocks.isNotEmpty())
    }

    @Test
    fun protoV6_priorGoldensStillRoundTrip() {
        // Every historical version must continue to load under the current code.
        // This guards against accidentally breaking legacy compatibility while
        // adding v6-specific migration logic.
        for (v in listOf("v1", "v2", "v3", "v4", "v5")) {
            val bytes = loadResource("document_$v.inkup")
            val proto = DocumentProto.ADAPTER.decode(bytes)
            val data = proto.toDomain()
            // Smoke assertion — decoding to domain must not throw
            assertNotNull("$v domain decode", data)
        }
    }
}
