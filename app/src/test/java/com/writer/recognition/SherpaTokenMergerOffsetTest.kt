package com.writer.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for timestamp offset handling in [SherpaTokenMerger].
 *
 * Bug: Sherpa resets its internal clock after each endpoint detection
 * (recognizer.reset(stream)), so timestamps for the second utterance
 * start from 0 again. Without an offset, all utterances' words would
 * have timestamps relative to their own segment start, not the
 * continuous audio recording.
 */
class SherpaTokenMergerOffsetTest {

    private val comp = 320L // must match SherpaTokenMerger.LATENCY_COMPENSATION_MS

    @Test
    fun `zero offset produces compensated timestamps`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" THE", " CAT"),
            floatArrayOf(1.0f, 1.5f),
            offsetSec = 0f
        )
        assertEquals(1000L - comp, result[0].startMs)
        assertEquals(1500L - comp, result[1].startMs)
    }

    @Test
    fun `offset shifts all timestamps by cumulative audio duration`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" THE", " CAT"),
            floatArrayOf(1.0f, 1.5f),
            offsetSec = 5.0f
        )
        assertEquals(6000L - comp, result[0].startMs)
        assertEquals(6500L - comp, result[1].startMs)
    }

    @Test
    fun `endMs also includes offset and compensation`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" HELLO", " WORLD"),
            floatArrayOf(1.0f, 1.5f),
            offsetSec = 10.0f
        )
        assertEquals(11500L - comp, result[0].endMs)
        assertEquals(11500L - comp, result[1].endMs)
    }

    @Test
    fun `subword merging with offset`() {
        val result = SherpaTokenMerger.mergeTokens(
            arrayOf(" RETURN", "ED"),
            floatArrayOf(1.0f, 1.3f),
            offsetSec = 8.0f
        )
        assertEquals(1, result.size)
        assertEquals("returned", result[0].text)
        assertEquals(9000L - comp, result[0].startMs)
        assertEquals(9300L - comp, result[0].endMs)
    }

    @Test
    fun `simulates multi-segment recording with two mergeTokens calls`() {
        val segment1 = SherpaTokenMerger.mergeTokens(
            arrayOf(" HELLO", " WORLD"),
            floatArrayOf(1.0f, 1.5f),
            offsetSec = 0f
        )

        val segment2 = SherpaTokenMerger.mergeTokens(
            arrayOf(" GOOD", " BYE"),
            floatArrayOf(1.0f, 1.5f),
            offsetSec = 4.0f
        )

        assertEquals(1000L - comp, segment1[0].startMs)
        assertEquals(1500L - comp, segment1[1].startMs)
        assertEquals(5000L - comp, segment2[0].startMs)
        assertEquals(5500L - comp, segment2[1].startMs)

        assertTrue("Segment 2 timestamps must be after segment 1",
            segment2[0].startMs > segment1[1].startMs)
    }
}
