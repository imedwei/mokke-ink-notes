package com.writer.ui.writing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [InlineTextRegion] — the per-line HWR text region attached to main/cue columns.
 */
class InlineTextRegionTest {

    @Test
    fun defaultState_isUnconsolidatedNoCandidates() {
        val region = InlineTextRegion(lineIndex = 0, text = "hello")

        assertFalse("newly built regions are not consolidated by default", region.consolidated)
        assertFalse("newly built regions are not in the user-triggered un-consolidated state", region.unConsolidated)
        assertTrue("no synthetic strokes until consolidation", region.syntheticStrokes.isEmpty())
        assertTrue("no alternative candidates by default", region.candidates.isEmpty())
        assertFalse("low-confidence flag defaults off with no candidates", region.lowConfidence)
    }

    @Test
    fun consolidated_clearsUnconsolidatedFlag() {
        // The two flags are exclusive: consolidated and unConsolidated cannot both be true.
        // unConsolidated is the user-explicit "revert to raw strokes" state and only applies
        // when the line would otherwise be consolidated.
        val region = InlineTextRegion(
            lineIndex = 3,
            text = "foo",
            consolidated = true,
            unConsolidated = false,
        )
        assertTrue(region.consolidated)
        assertFalse(region.unConsolidated)
    }

    @Test
    fun lowConfidence_setWhenAnyCandidateBelowThreshold() {
        val low = InlineTextRegion(
            lineIndex = 1,
            text = "hello",
            candidates = listOf(RecognitionCandidate("hello", 0.95f), RecognitionCandidate("hallo", 0.40f)),
            lowConfidence = true,
        )
        assertTrue("explicit lowConfidence flag persisted", low.lowConfidence)

        val high = InlineTextRegion(
            lineIndex = 1,
            text = "hello",
            candidates = listOf(RecognitionCandidate("hello", 0.95f)),
            lowConfidence = false,
        )
        assertFalse("no lowConfidence flag when none set", high.lowConfidence)
    }

    @Test
    fun equality_matchesDataClassContract() {
        val a = InlineTextRegion(lineIndex = 2, text = "abc")
        val b = InlineTextRegion(lineIndex = 2, text = "abc")
        val c = InlineTextRegion(lineIndex = 2, text = "abc", consolidated = true)

        assertEquals("same fields → equal", a, b)
        assertEquals("same fields → same hashCode", a.hashCode(), b.hashCode())
        assertNotEquals("different consolidated flag → not equal", a, c)
    }
}
