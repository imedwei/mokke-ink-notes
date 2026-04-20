package com.writer.storage

import com.writer.model.AnchorMode
import com.writer.model.AnchorTarget
import com.writer.model.ColumnData
import com.writer.model.DocumentData
import com.writer.model.TextBlock
import com.writer.model.proto.DocumentProto
import com.writer.view.ScreenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 3a: transcript column round-trip + legacy-main/cue-TextBlock migration.
 *
 * Per the redesign plan, the proto → domain mapper must:
 *  1. Round-trip a populated `transcript` column (strokes + textBlocks).
 *  2. Round-trip per-TextBlock anchor metadata (target/lineIndex/mode).
 *  3. On load, migrate any TextBlocks that exist in main/cue into transcript,
 *     stamping anchorTarget by column of origin and anchorLineIndex by
 *     startLineIndex, with anchorMode = AUTO. The source columns' textBlocks
 *     lists are left empty in the returned domain object so the next save
 *     writes them as empty.
 */
class DocumentProtoMapperTranscriptTest {

    @Before
    fun setUp() {
        ScreenMetrics.init(
            1.875f,
            smallestWidthDp = DocumentProtoMapperTest.SW_GO_7,
            widthPixels = DocumentProtoMapperTest.W_GO_7,
            heightPixels = DocumentProtoMapperTest.H_GO_7,
        )
    }

    private fun roundTrip(data: DocumentData): DocumentData = data.toProto().toDomain()

    // ── Round-trip ───────────────────────────────────────────────────────

    @Test
    fun transcriptField_roundTrips_withTextBlocksAndStrokes() {
        val data = DocumentData(
            main = ColumnData(),
            transcript = ColumnData(
                textBlocks = listOf(
                    TextBlock(
                        id = "tb-x",
                        startLineIndex = 7,
                        heightInLines = 2,
                        text = "spoken text",
                        audioFile = "rec-a.opus",
                        audioStartMs = 1000L,
                        audioEndMs = 5000L,
                        anchorTarget = AnchorTarget.CUE,
                        anchorLineIndex = 4,
                        anchorMode = AnchorMode.MANUAL,
                    )
                )
            )
        )

        val result = roundTrip(data)

        assertEquals(1, result.transcript.textBlocks.size)
        with(result.transcript.textBlocks[0]) {
            assertEquals("tb-x", id)
            assertEquals(7, startLineIndex)
            assertEquals("spoken text", text)
            assertEquals(AnchorTarget.CUE, anchorTarget)
            assertEquals(4, anchorLineIndex)
            assertEquals(AnchorMode.MANUAL, anchorMode)
        }
    }

    @Test
    fun textBlockAnchor_roundTrips_allFields() {
        val block = TextBlock(
            id = "tb-anchor",
            startLineIndex = 3,
            heightInLines = 1,
            text = "hello",
            anchorTarget = AnchorTarget.CUE,
            anchorLineIndex = 9,
            anchorMode = AnchorMode.MANUAL,
        )
        val data = DocumentData(
            main = ColumnData(),
            transcript = ColumnData(textBlocks = listOf(block))
        )
        val result = roundTrip(data).transcript.textBlocks[0]
        assertEquals(block.anchorTarget, result.anchorTarget)
        assertEquals(block.anchorLineIndex, result.anchorLineIndex)
        assertEquals(block.anchorMode, result.anchorMode)
    }

    // ── Migration: legacy main/cue → transcript ──────────────────────────

    /** Build a proto directly with text_blocks in main (bypassing the current
     *  domain→proto path which will eventually stop writing them there). */
    private fun legacyProtoWithMainTextBlocks(blocks: List<TextBlock>): DocumentProto =
        DocumentData(
            main = ColumnData(textBlocks = blocks)
        ).toProto()

    private fun legacyProtoWithCueTextBlocks(blocks: List<TextBlock>): DocumentProto =
        DocumentData(
            main = ColumnData(),
            cue = ColumnData(textBlocks = blocks),
        ).toProto()

    @Test
    fun legacyDocument_textBlocksInMain_migrateToTranscript_anchorTargetMain() {
        val proto = legacyProtoWithMainTextBlocks(
            listOf(TextBlock(id = "m1", startLineIndex = 2, heightInLines = 1, text = "from main"))
        )
        val data = proto.toDomain()

        assertEquals("migrated into transcript", 1, data.transcript.textBlocks.size)
        assertEquals("main has been cleared", 0, data.main.textBlocks.size)
        assertEquals(AnchorTarget.MAIN, data.transcript.textBlocks[0].anchorTarget)
        assertEquals("from main", data.transcript.textBlocks[0].text)
    }

    @Test
    fun legacyDocument_textBlocksInCue_migrateToTranscript_anchorTargetCue() {
        val proto = legacyProtoWithCueTextBlocks(
            listOf(TextBlock(id = "c1", startLineIndex = 3, heightInLines = 1, text = "from cue"))
        )
        val data = proto.toDomain()

        assertEquals("migrated into transcript", 1, data.transcript.textBlocks.size)
        assertEquals("cue has been cleared", 0, data.cue.textBlocks.size)
        assertEquals(AnchorTarget.CUE, data.transcript.textBlocks[0].anchorTarget)
    }

    @Test
    fun legacyDocument_textBlocksInBoth_migrateAll_preservingTargets() {
        val proto = DocumentData(
            main = ColumnData(textBlocks = listOf(
                TextBlock(id = "m1", startLineIndex = 1, heightInLines = 1, text = "m-one"),
                TextBlock(id = "m2", startLineIndex = 4, heightInLines = 1, text = "m-two"),
            )),
            cue = ColumnData(textBlocks = listOf(
                TextBlock(id = "c1", startLineIndex = 2, heightInLines = 1, text = "c-one"),
            )),
        ).toProto()
        val data = proto.toDomain()

        assertEquals(3, data.transcript.textBlocks.size)
        assertTrue(data.main.textBlocks.isEmpty())
        assertTrue(data.cue.textBlocks.isEmpty())

        val byId = data.transcript.textBlocks.associateBy { it.id }
        assertEquals(AnchorTarget.MAIN, byId.getValue("m1").anchorTarget)
        assertEquals(AnchorTarget.MAIN, byId.getValue("m2").anchorTarget)
        assertEquals(AnchorTarget.CUE, byId.getValue("c1").anchorTarget)
    }

    @Test
    fun legacyMigration_setsAnchorLineIndex_fromOriginalStartLineIndex() {
        val proto = legacyProtoWithMainTextBlocks(
            listOf(
                TextBlock(id = "m1", startLineIndex = 2, heightInLines = 1, text = "a"),
                TextBlock(id = "m2", startLineIndex = 17, heightInLines = 3, text = "b"),
            )
        )
        val data = proto.toDomain()
        val byId = data.transcript.textBlocks.associateBy { it.id }
        assertEquals(2, byId.getValue("m1").anchorLineIndex)
        assertEquals(17, byId.getValue("m2").anchorLineIndex)
    }

    @Test
    fun legacyMigration_setsAnchorMode_AUTO() {
        val proto = legacyProtoWithMainTextBlocks(
            listOf(TextBlock(id = "m1", startLineIndex = 1, heightInLines = 1, text = "a"))
        )
        val data = proto.toDomain()
        assertEquals(AnchorMode.AUTO, data.transcript.textBlocks[0].anchorMode)
    }

    @Test
    fun legacyMigration_clearsSourceColumnTextBlocks_onSecondSave() {
        val proto = legacyProtoWithMainTextBlocks(
            listOf(TextBlock(id = "m1", startLineIndex = 1, heightInLines = 1, text = "a"))
        )
        // First save after migration: the domain object has main.textBlocks empty
        // so the serialized proto will also have main.text_blocks empty.
        val savedAgain = proto.toDomain().toProto()
        assertEquals(0, savedAgain.main!!.text_blocks.size)
        assertEquals(1, savedAgain.transcript!!.text_blocks.size)
    }

    @Test
    fun existingTranscript_notReMigrated() {
        // Document already has a transcript with one block and main/cue are clean.
        // Loading it must not duplicate the block.
        val data = DocumentData(
            main = ColumnData(),
            transcript = ColumnData(textBlocks = listOf(
                TextBlock(id = "t1", startLineIndex = 0, heightInLines = 1, text = "orig", anchorMode = AnchorMode.MANUAL)
            )),
        )
        val result = roundTrip(data)
        assertEquals(1, result.transcript.textBlocks.size)
        assertEquals("orig", result.transcript.textBlocks[0].text)
        // Manual mode preserved — migration didn't stomp it
        assertEquals(AnchorMode.MANUAL, result.transcript.textBlocks[0].anchorMode)
    }

    @Test
    fun mixedLegacyAndNew_preservesBoth() {
        // Partial migration state (e.g. an older client saved after a newer one):
        // transcript already has content AND main also has legacy blocks. On load,
        // both sources of blocks end up in transcript and main is cleared.
        val proto = DocumentData(
            main = ColumnData(textBlocks = listOf(
                TextBlock(id = "legacy-m", startLineIndex = 3, heightInLines = 1, text = "legacy")
            )),
            transcript = ColumnData(textBlocks = listOf(
                TextBlock(id = "existing-t", startLineIndex = 7, heightInLines = 1, text = "existing")
            )),
        ).toProto()

        val data = proto.toDomain()
        val ids = data.transcript.textBlocks.map { it.id }.toSet()
        assertEquals(setOf("legacy-m", "existing-t"), ids)
        assertTrue("main is cleared", data.main.textBlocks.isEmpty())
    }
}
