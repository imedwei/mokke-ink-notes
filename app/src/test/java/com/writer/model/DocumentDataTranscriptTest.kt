package com.writer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3a contract: DocumentData gains a third ColumnData slot (transcript) and
 * TextBlock gains anchor metadata (target, line index, mode).
 */
class DocumentDataTranscriptTest {

    @Test
    fun transcriptColumnDefault_isEmpty() {
        val doc = DocumentData(main = ColumnData())

        // The transcript column is a full ColumnData (strokes + textBlocks carrier).
        assertNotNull("DocumentData.transcript must exist", doc.transcript)
        assertTrue("default transcript has no strokes", doc.transcript.strokes.isEmpty())
        assertTrue("default transcript has no textBlocks", doc.transcript.textBlocks.isEmpty())
        assertTrue("default transcript has no diagram areas", doc.transcript.diagramAreas.isEmpty())
        assertTrue("default transcript has no lineTextCache", doc.transcript.lineTextCache.isEmpty())
    }

    @Test
    fun textBlockDefaultAnchorMode_isAuto() {
        val block = TextBlock(startLineIndex = 5, heightInLines = 1)
        assertEquals(AnchorMode.AUTO, block.anchorMode)
    }

    @Test
    fun textBlockDefaultAnchorTarget_isMain() {
        val block = TextBlock(startLineIndex = 5, heightInLines = 1)
        assertEquals(AnchorTarget.MAIN, block.anchorTarget)
    }

    @Test
    fun textBlockDefaultAnchorLineIndex_isNegativeOne() {
        // -1 means "unset / use startLineIndex as fallback" per Phase 4 anchor-computer rules.
        // A freshly constructed block has no explicit anchor; migration will stamp one on.
        val block = TextBlock(startLineIndex = 5, heightInLines = 1)
        assertEquals(-1, block.anchorLineIndex)
    }

    @Test
    fun anchorTarget_hasMainAndCue() {
        // Sanity: the enum is exhaustive for the two stroke columns only.
        val values = AnchorTarget.entries.toSet()
        assertEquals(setOf(AnchorTarget.MAIN, AnchorTarget.CUE), values)
    }

    @Test
    fun anchorMode_hasAutoAndManual() {
        val values = AnchorMode.entries.toSet()
        assertEquals(setOf(AnchorMode.AUTO, AnchorMode.MANUAL), values)
    }
}
